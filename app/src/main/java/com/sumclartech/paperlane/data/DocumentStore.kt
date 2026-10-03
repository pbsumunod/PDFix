package com.sumclartech.paperlane.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

data class DocumentRecord(
    val id: String,
    val title: String,
    val filePath: String,
    val modifiedAt: Long,
    val pageCount: Int,
    val revisionId: String,
    val trashed: Boolean = false,
)

data class PdfRevision(val id: String, val createdAt: Long, val pageCount: Int)

data class UserProfile(
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val company: String = "",
)

class DocumentStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Local, synchronous persistence. Invoke from an IO dispatcher.
 *
 * Each saved revision is immutable. A single AtomicFile metadata write publishes a revision;
 * until that write succeeds, the previous revision is still the active document. The untouched
 * import is kept separately as source.pdf. All instances for the same root share a process lock.
 * Input files belong to the caller and are never changed or removed.
 */
class DocumentStore internal constructor(
    context: Context,
    private val storeRoot: File,
    private val beforeMetadataCommit: (() -> Unit)? = null,
    private val beforePermanentDelete: (() -> Unit)? = null,
    private val validateCompatibility: ((File) -> Unit)? = null,
) {
    constructor(context: Context) : this(context, File(context.filesDir, "paperlane"))
    constructor(context: Context, validateCompatibility: (File) -> Unit) : this(
        context, File(context.filesDir, "paperlane"), validateCompatibility = validateCompatibility,
    )

    private val appContext = context.applicationContext
    private val lock: Any
    private val documents: File
    private val staging: File
    private val exports: File
    private val shares: File
    private val tombstones: File

    init {
        val privateRoot = appContext.filesDir.canonicalFile
        require(storeRoot.canonicalPath.startsWith(privateRoot.path + File.separator)) {
            "Document storage must be inside the app's private files directory."
        }
        lock = synchronized(locks) { locks.getOrPut(storeRoot.canonicalPath) { Any() } }
        documents = File(storeRoot, "documents")
        staging = File(storeRoot, "staging")
        exports = File(appContext.cacheDir, "paperlane_exports")
        shares = File(appContext.cacheDir, "shared")
        tombstones = File(storeRoot, "pending_deletions")
        synchronized(lock) {
            ensureDirectory(documents)
            ensureDirectory(staging)
            ensureDirectory(exports)
            ensureDirectory(shares)
            ensureDirectory(tombstones)
            // A durable deletion marker hides a document before content removal begins. Resume
            // incomplete removals after process death; never infer deletion from a corrupt PDF.
            tombstones.listFiles().orEmpty().mapNotNull {
                it.name.removeSuffix(".bak").removeSuffix(".json").takeIf(ID_PATTERN::matches)
            }.distinct().forEach { id ->
                if (atomicExists(tombstoneFile(id))) runCatching { finishPermanentDelete(id) }
            }
            // Staging files are never published and can only survive an interrupted import.
            staging.listFiles()?.forEach { deleteOwned(it, staging) }
            allStates().forEach { state -> pruneRevisions(state.id, state.revisions.map { it.id }.toSet()) }
        }
    }

    fun listDocuments(): List<DocumentRecord> = synchronized(lock) {
        allStates().filterNot { it.trashed }.map { record(it) }.sortedByDescending { it.modifiedAt }
    }

    fun listTrash(): List<DocumentRecord> = synchronized(lock) {
        allStates().filter { it.trashed }.map { record(it) }.sortedByDescending { it.modifiedAt }
    }

    fun getDocument(id: String): DocumentRecord = synchronized(lock) { record(readState(id)) }

    fun importPdf(uri: Uri): DocumentRecord = synchronized(lock) {
        val title = displayName(uri)
        val input = try {
            appContext.contentResolver.openInputStream(uri)
                ?: throw DocumentStoreException("This file could not be opened. Try choosing it again.")
        } catch (e: Exception) {
            throw friendly("This file could not be opened. Try choosing it again.", e)
        }
        input.use { createFromStream(it, title) }
    }

    fun createFromFile(file: File, title: String): DocumentRecord = synchronized(lock) {
        try {
            file.inputStream().use { createFromStream(it, title) }
        } catch (e: Exception) {
            throw friendly("The PDF could not be imported. Check the file and available storage.", e)
        }
    }

    fun rename(id: String, title: String): DocumentRecord = synchronized(lock) {
        val old = readState(id)
        val updated = old.copy(title = cleanTitle(title), modifiedAt = System.currentTimeMillis())
        saveState(updated)
        record(updated)
    }

    /** Recoverable removal; no PDF content is deleted. */
    fun remove(id: String): Unit = synchronized(lock) {
        val old = readState(id)
        saveState(old.copy(trashed = true, modifiedAt = System.currentTimeMillis()))
    }

    fun restore(id: String): DocumentRecord = synchronized(lock) {
        val old = readState(id)
        val updated = old.copy(trashed = false, modifiedAt = System.currentTimeMillis())
        saveState(updated)
        record(updated)
    }

    /**
     * Irreversibly removes only a document already placed in Trash, including its original,
     * revisions and bookmark. The UI must obtain explicit confirmation before invoking this.
     * Previously exported copies are separate and are not removed.
     */
    fun deletePermanently(id: String): Unit = synchronized(lock) {
        validId(id)
        val marker = tombstoneFile(id)
        if (!atomicExists(marker)) {
            val state = readState(id)
            if (!state.trashed) throw DocumentStoreException("Move this document to Trash before deleting it permanently.")
            writeJson(marker, JSONObject().put("id", id).put("deletedAt", System.currentTimeMillis()))
        }
        try {
            beforePermanentDelete?.invoke()
            finishPermanentDelete(id)
        } catch (e: Exception) {
            throw friendly("Permanent deletion is pending. Restart the app to finish removing its local files.", e)
        }
    }

    /** Validates and publishes an engine-produced PDF without modifying the input or old revision. */
    fun commitRevision(id: String, generatedFile: File): DocumentRecord = synchronized(lock) {
        val old = readState(id)
        if (old.trashed) throw DocumentStoreException("Restore this document before editing it.")
        val revisionId = UUID.randomUUID().toString()
        val revisionDirectory = File(documentDirectory(id), "revisions")
        val temporary = File(revisionDirectory, "$revisionId.part")
        val published = File(revisionDirectory, "$revisionId.pdf")
        var metadataPublished = false
        try {
            generatedFile.inputStream().use { copyLimited(it, temporary) }
            val pageCount = validatePdf(temporary)
            atomicRename(temporary, published)
            val now = System.currentTimeMillis()
            val activeIndex = old.revisions.indexOfFirst { it.id == old.currentRevision }
            val retained = old.revisions.take(activeIndex + 1)
                .plus(PdfRevision(revisionId, now, pageCount)).takeLast(MAX_REVISIONS)
            val updated = old.copy(currentRevision = revisionId, revisions = retained, modifiedAt = now)
            beforeMetadataCommit?.invoke()
            saveState(updated)
            metadataPublished = true
            // Cleanup is best effort and is never allowed to turn a successful commit into failure.
            pruneRevisions(id, retained.map { it.id }.toSet())
            record(updated)
        } catch (e: Exception) {
            temporary.delete()
            if (!metadataPublished) {
                // If metadata was durably published before an fsync error, preserve its revision.
                val activeOnDisk = runCatching { readState(id).currentRevision }.getOrNull()
                if (activeOnDisk != revisionId) published.delete()
            }
            throw friendly("Changes could not be saved. Your previous PDF is still recoverable.", e)
        }
    }

    /** Newest first, including the active version. Up to ten editing versions are retained. */
    fun history(id: String): List<PdfRevision> = synchronized(lock) { readState(id).revisions.reversed() }

    fun undo(id: String): DocumentRecord = synchronized(lock) {
        val state = readState(id)
        val currentIndex = state.revisions.indexOfFirst { it.id == state.currentRevision }
        if (currentIndex <= 0) throw DocumentStoreException("There is no earlier saved version.")
        restoreRevision(id, state.revisions[currentIndex - 1].id)
    }

    fun restoreRevision(id: String, revisionId: String): DocumentRecord = synchronized(lock) {
        val old = readState(id)
        val revision = old.revisions.firstOrNull { it.id == revisionId }
            ?: throw DocumentStoreException("That saved version is no longer available.")
        validatePdf(revisionFile(id, revision.id))
        val updated = old.copy(currentRevision = revision.id, modifiedAt = System.currentTimeMillis())
        saveState(updated)
        record(updated)
    }

    /** Restores the untouched import as a new editing revision. */
    fun restoreOriginal(id: String): DocumentRecord = synchronized(lock) {
        commitRevision(id, File(documentDirectory(id), "source.pdf"))
    }

    /** Stable, app-private cache copy for FileProvider sharing; later edits cannot change it. */
    fun exportSnapshot(id: String): File = synchronized(lock) {
        val state = readState(id)
        val exportDirectory = File(File(exports, validId(id)), UUID.randomUUID().toString())
        ensureDirectory(exportDirectory)
        val temporary = File(exportDirectory, "export.part")
        val destination = File(exportDirectory, cleanTitle(state.title))
        try {
            revisionFile(id, state.currentRevision).inputStream().use { copyLimited(it, temporary) }
            atomicRename(temporary, destination)
            destination
        } catch (e: Exception) {
            deleteOwned(exportDirectory, exports)
            throw friendly("The sharing copy could not be created. Check available storage.", e)
        }
    }

    fun saveProfile(profile: UserProfile): Unit = synchronized(lock) {
        val json = JSONObject().put("name", profile.name.take(500))
            .put("email", profile.email.take(500)).put("phone", profile.phone.take(100))
            .put("address", profile.address.take(2000)).put("company", profile.company.take(500))
        writeJson(File(storeRoot, "profile.json"), json)
    }

    fun loadProfile(): UserProfile = synchronized(lock) {
        val file = File(storeRoot, "profile.json")
        if (!atomicExists(file)) return@synchronized UserProfile()
        val json = readJson(file)
        UserProfile(json.optString("name"), json.optString("email"), json.optString("phone"),
            json.optString("address"), json.optString("company"))
    }

    /** Last reading position, zero based and clamped to the active document. */
    fun saveBookmark(id: String, pageIndex: Int): Unit = synchronized(lock) {
        val state = readState(id)
        val pages = activeRevision(state).pageCount
        writeJson(File(documentDirectory(id), "bookmark.json"), JSONObject()
            .put("pageIndex", pageIndex.coerceIn(0, pages - 1)).put("updatedAt", System.currentTimeMillis()))
    }

    fun loadBookmark(id: String): Int = synchronized(lock) {
        val state = readState(id)
        val file = File(documentDirectory(id), "bookmark.json")
        if (!atomicExists(file)) return@synchronized 0
        readJson(file).optInt("pageIndex", 0).coerceIn(0, activeRevision(state).pageCount - 1)
    }

    private fun createFromStream(input: InputStream, title: String): DocumentRecord {
        val id = UUID.randomUUID().toString()
        val temporaryDirectory = File(staging, id)
        val finalDirectory = documentDirectory(id)
        ensureDirectory(temporaryDirectory)
        var moved = false
        try {
            val source = File(temporaryDirectory, "source.pdf")
            copyLimited(input, source)
            val pages = validatePdf(source)
            val revisionId = UUID.randomUUID().toString()
            val revisionsDirectory = File(temporaryDirectory, "revisions")
            ensureDirectory(revisionsDirectory)
            source.inputStream().use { copyLimited(it, File(revisionsDirectory, "$revisionId.pdf")) }
            val now = System.currentTimeMillis()
            val state = State(id, cleanTitle(title), now, revisionId,
                listOf(PdfRevision(revisionId, now, pages)), false)
            writeJson(File(temporaryDirectory, "state.json"), state.toJson())
            atomicRename(temporaryDirectory, finalDirectory)
            moved = true
            return record(state)
        } catch (e: Exception) {
            if (!moved && !finalDirectory.exists()) deleteOwned(temporaryDirectory, staging)
            throw friendly("The PDF could not be imported. Check the file and available storage.", e)
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && it.moveToFirst()) name = it.getString(index)
            }
        }
        return cleanTitle(name ?: uri.lastPathSegment ?: "Document.pdf")
    }

    private fun copyLimited(input: InputStream, destination: File) {
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > MAX_PDF_BYTES) throw DocumentStoreException("Choose a PDF of 80 MiB or less.")
                output.write(buffer, 0, count)
            }
            output.flush()
            output.fd.sync()
        }
    }

    private fun validatePdf(file: File): Int {
        if (!file.isFile || file.length() < 8 || file.length() > MAX_PDF_BYTES) {
            throw DocumentStoreException("Choose a valid PDF of 80 MiB or less.")
        }
        val header = file.inputStream().use { input ->
            val bytes = ByteArray(1024)
            val length = input.read(bytes)
            String(bytes, 0, length.coerceAtLeast(0), Charsets.ISO_8859_1)
        }
        if (!header.contains("%PDF-")) throw DocumentStoreException("This file is not a valid PDF.")
        try {
            val pageCount = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val pages = renderer.pageCount
                    if (pages < 1) throw DocumentStoreException("This PDF has no readable pages.")
                    if (pages > MAX_PAGES) throw DocumentStoreException("PDFs with up to 250 pages are supported.")
                    renderer.openPage(0).use { }
                    if (pages > 1) renderer.openPage(pages - 1).use { }
                    pages
                }
            }
            validateCompatibility?.invoke(file)
            return pageCount
        } catch (e: SecurityException) {
            throw DocumentStoreException("This PDF is password protected. Import an unlocked copy.", e)
        } catch (e: Exception) {
            throw friendly("This PDF cannot be opened. It may be damaged or password protected.", e)
        }
    }

    private fun readState(id: String): State {
        if (atomicExists(tombstoneFile(id))) throw DocumentStoreException("This document has been permanently deleted.")
        val file = File(documentDirectory(id), "state.json")
        if (!atomicExists(file)) throw DocumentStoreException("This document could not be found.")
        try {
            val json = readJson(file)
            val revisionJson = json.getJSONArray("revisions")
            val revisions = (0 until revisionJson.length()).map { index ->
                val value = revisionJson.getJSONObject(index)
                val revisionId = validId(value.getString("id"))
                PdfRevision(revisionId, value.getLong("createdAt"), value.getInt("pageCount"))
            }
            val state = State(id, cleanTitle(json.getString("title")), json.getLong("modifiedAt"),
                validId(json.getString("currentRevision")), revisions, json.optBoolean("trashed", false))
            if (activeRevision(state).pageCount < 1 || !revisionFile(id, state.currentRevision).isFile) {
                throw DocumentStoreException("This document's saved version could not be found.")
            }
            return state
        } catch (e: Exception) {
            throw friendly("This document's saved information could not be read.", e)
        }
    }

    private fun allStates(): List<State> = documents.listFiles().orEmpty()
        .filter { it.isDirectory && ID_PATTERN.matches(it.name) }
        .mapNotNull { runCatching { readState(it.name) }.getOrNull() }

    private fun record(state: State) = DocumentRecord(state.id, state.title,
        revisionFile(state.id, state.currentRevision).absolutePath, state.modifiedAt,
        activeRevision(state).pageCount, state.currentRevision, state.trashed)

    private fun activeRevision(state: State): PdfRevision = state.revisions.firstOrNull { it.id == state.currentRevision }
        ?: throw DocumentStoreException("The active saved version could not be found.")

    private fun saveState(state: State) = writeJson(File(documentDirectory(state.id), "state.json"), state.toJson())

    private fun writeJson(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(value.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
            output = null
            syncDirectory(file.parentFile!!)
        } catch (e: Exception) {
            if (output != null) atomic.failWrite(output)
            throw DocumentStoreException("Changes could not be saved. Check available storage.", e)
        }
    }

    private fun readJson(file: File): JSONObject = AtomicFile(file).openRead().bufferedReader().use { JSONObject(it.readText()) }

    private fun atomicExists(file: File) = file.exists() || File(file.path + ".bak").exists()

    private fun documentDirectory(id: String) = File(documents, validId(id))

    private fun tombstoneFile(id: String) = File(tombstones, "${validId(id)}.json")

    private fun finishPermanentDelete(id: String) {
        val directory = documentDirectory(id)
        if (directory.exists() && !deleteOwned(directory, documents)) {
            throw IOException("Some private document files could not be removed")
        }
        val cachedSnapshots = File(exports, validId(id))
        if (cachedSnapshots.exists() && !deleteOwned(cachedSnapshots, exports)) {
            throw IOException("Some private sharing copies could not be removed")
        }
        val preparedShares = File(shares, validId(id))
        if (preparedShares.exists() && !deleteOwned(preparedShares, shares)) {
            throw IOException("Some private sharing copies could not be removed")
        }
        syncDirectory(documents)
        syncDirectory(exports)
        syncDirectory(shares)
        AtomicFile(tombstoneFile(id)).delete()
        syncDirectory(tombstones)
    }

    private fun revisionFile(id: String, revisionId: String) = File(File(documentDirectory(id), "revisions"), "${validId(revisionId)}.pdf")

    private fun validId(id: String): String {
        if (!ID_PATTERN.matches(id)) throw DocumentStoreException("This document identifier is invalid.")
        return id
    }

    private fun pruneRevisions(id: String, retained: Set<String>) {
        runCatching {
            val directory = File(documentDirectory(id), "revisions")
            directory.listFiles()?.filter { it.extension == "part" || (it.extension == "pdf" && it.nameWithoutExtension !in retained) }
                ?.forEach { deleteOwned(it, directory) }
        }
    }

    private fun atomicRename(source: File, destination: File) {
        if (destination.exists()) throw DocumentStoreException("A saved file already exists at this location.")
        Os.rename(source.absolutePath, destination.absolutePath)
        syncDirectory(destination.parentFile!!)
        if (source.parentFile != destination.parentFile) syncDirectory(source.parentFile!!)
    }

    private fun syncDirectory(directory: File) {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw DocumentStoreException("App storage is unavailable.")
    }

    private fun deleteOwned(file: File, parent: File): Boolean {
        val allowed = parent.canonicalPath + File.separator
        check(file.canonicalPath.startsWith(allowed)) { "Refusing to delete outside app storage." }
        return file.deleteRecursively()
    }

    private fun cleanTitle(input: String): String {
        val base = input.replace('\\', '/').substringAfterLast('/')
            .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "").trim().trim('.').take(120)
            .ifBlank { "Document" }
        return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
    }

    private fun friendly(message: String, cause: Exception): DocumentStoreException =
        if (cause is DocumentStoreException) cause else DocumentStoreException(message, cause)

    private data class State(val id: String, val title: String, val modifiedAt: Long,
        val currentRevision: String, val revisions: List<PdfRevision>, val trashed: Boolean) {
        fun toJson(): JSONObject = JSONObject().put("schemaVersion", 1).put("id", id).put("title", title)
            .put("modifiedAt", modifiedAt).put("currentRevision", currentRevision).put("trashed", trashed)
            .put("revisions", JSONArray().apply {
                revisions.forEach { put(JSONObject().put("id", it.id).put("createdAt", it.createdAt).put("pageCount", it.pageCount)) }
            })
    }

    companion object {
        const val MAX_PDF_BYTES = 80L * 1024L * 1024L
        const val MAX_PAGES = 250
        const val MAX_REVISIONS = 10
        private val ID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val locks = mutableMapOf<String, Any>()
    }
}
