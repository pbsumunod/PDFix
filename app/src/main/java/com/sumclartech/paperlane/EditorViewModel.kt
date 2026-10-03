package com.sumclartech.paperlane

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sumclartech.paperlane.billing.BillingManager
import com.sumclartech.paperlane.data.DocumentRecord
import com.sumclartech.paperlane.data.DocumentStore
import com.sumclartech.paperlane.data.DocumentStoreException
import com.sumclartech.paperlane.data.FormDraft
import com.sumclartech.paperlane.data.FormDraftStore
import com.sumclartech.paperlane.data.carryForwardFormAnswers
import com.sumclartech.paperlane.data.UserProfile
import com.sumclartech.paperlane.pdf.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class EditorTool { MOVE, EDIT_TEXT, TEXT, PEN, HIGHLIGHT, SIGN, REDACT, CROP, CORRECT }
enum class Panel { NONE, PAGES, FORM, TEXT, EDIT_TEXT, SIGNATURE, EXPORT, SEARCH, READ, HISTORY, UNLOCK, PROFILE, PRIVACY, TRASH, RENAME, REDACT_CONFIRM, CROP_CONFIRM, CORRECT, LICENSES }
data class PreparedExport(val file: File, val targetMet: Boolean = true, val imageOnly: Boolean = false, val previewFile: File = file)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    val engine = PdfEngine(application)
    val store = DocumentStore(application) { file ->
        if (engine.inspect(file).encrypted) throw DocumentStoreException("This PDF is password protected. Import an unlocked copy.")
    }
    val billing = BillingManager(application)
    var documents by mutableStateOf<List<DocumentRecord>>(emptyList()); private set
    var current by mutableStateOf<DocumentRecord?>(null); private set
    var info by mutableStateOf<PdfInfo?>(null); private set
    var page by mutableStateOf(0); private set
    var tab by mutableStateOf(0)
    var panel by mutableStateOf(Panel.NONE)
    var tool by mutableStateOf(EditorTool.MOVE)
    var busy by mutableStateOf(false); private set
    var busyLabel by mutableStateOf(""); private set
    var notice by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var searchHits by mutableStateOf<List<SearchHit>>(emptyList()); private set
    var readText by mutableStateOf(""); private set
    var export by mutableStateOf<PreparedExport?>(null); private set
    var profile by mutableStateOf(UserProfile()); private set
    var trash by mutableStateOf<List<DocumentRecord>>(emptyList()); private set
    var textToPlace by mutableStateOf("")
    var editableTextRuns by mutableStateOf<List<EditableTextRun>>(emptyList()); private set
    var selectedTextRun by mutableStateOf<EditableTextRun?>(null); private set
    var existingTextError by mutableStateOf<String?>(null)
    private var textRunRevision: String? = null
    val formDraft = mutableStateMapOf<String, String>()
    var formDraftRevisionId by mutableStateOf<String?>(null)
    val formDraftQuestion = mutableIntStateOf(0)
    private val formDraftStore = FormDraftStore(application)
    private val formDraftMutex = Mutex()
    private var formDraftSaveJob: Job? = null
    private var formDraftGeneration = 0L
    var textSize by mutableStateOf(.028f)
    var inkColor by mutableStateOf(0xFF263C34.toInt())
    var signature by mutableStateOf<List<List<NormalizedPoint>>>(emptyList())
    var selection by mutableStateOf<NormalizedRect?>(null)
    private val workDir = File(application.cacheDir, "work").apply { mkdirs() }
    private val shareDir = File(application.cacheDir, "shared").apply { mkdirs() }
    private val pendingOperations = ArrayDeque<() -> Unit>()

    init { billing.connect(); refreshLibrary() }

    private fun operation(label: String, edit: Boolean = false, queue: Boolean = false, onFailure: ((String) -> Unit)? = null, block: suspend () -> Unit) {
        if (busy) { if (queue) pendingOperations.addLast { operation(label, edit, queue, onFailure, block) }; return }
        if (edit && !billing.canEditNow()) { panel = Panel.UNLOCK; return }
        busy = true
        busyLabel = label
        viewModelScope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                val message = error.message?.take(240) ?: "That didn't work. Your original file is safe."
                if (onFailure != null) onFailure(message) else notice = message
            }
            finally { busy = false; busyLabel = ""; if (pendingOperations.isNotEmpty()) pendingOperations.removeFirst().invoke() }
        }
    }

    private suspend fun reload() {
        documents = withContext(Dispatchers.IO) { store.listDocuments() }
    }
    fun refreshLibrary() = operation("Opening your desk") {
        reload()
        profile = withContext(Dispatchers.IO) { store.loadProfile() }
    }
    private suspend fun show(record: DocumentRecord) {
        flushFormDraftAndAwait()
        val detail = withContext(Dispatchers.IO) { engine.inspect(File(record.filePath)) }
        val bookmark = withContext(Dispatchers.IO) { store.loadBookmark(record.id) }
        loadFormDraft(record, detail.formFields)
        current = record
        info = detail
        page = bookmark.coerceIn(0, detail.pageCount - 1)
        tool = EditorTool.MOVE
        clearTextSelection()
        export = null
        selection = null
        panel = Panel.NONE
        query = ""; searchHits = emptyList(); readText = ""
    }
    fun open(record: DocumentRecord) = operation("Opening PDF") { show(record) }
    fun closeDocument() {
        if (busy) return
        flushFormDraft()
        current = null; info = null; panel = Panel.NONE; tool = EditorTool.MOVE; selection = null
        clearTextSelection()
        refreshLibrary()
    }
    fun goToPage(index: Int) {
        val record = current ?: return
        page = index.coerceIn(0, (info?.pageCount ?: 1) - 1)
        selection = null
        clearTextSelection()
        if (tool == EditorTool.EDIT_TEXT) tool = EditorTool.MOVE
        val target = page
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(store) { if (current?.id == record.id && page == target) runCatching { store.saveBookmark(record.id, target) } }
        }
    }
    fun importPdf(uri: Uri, thenForm: Boolean = false) = operation("Making a safe local copy", queue = true) {
        val record = withContext(Dispatchers.IO) { store.importPdf(uri) }
        show(record); reload()
        if (thenForm) panel = Panel.FORM
    }
    fun demo() = create("Welcome to PDFix", "Creating your practice PDF") { engine.demo(it) }
    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        create("Scan ${java.time.LocalDate.now()}", "Preparing ${uris.size} page${if (uris.size == 1) "" else "s"}") { engine.imagesToPdf(uris, it) }
    }
    private fun create(title: String, label: String, block: (File) -> Unit) = operation(label, edit = true, queue = true) {
        val record = withContext(Dispatchers.IO) {
            val out = File.createTempFile("new-", ".pdf", workDir)
            try { block(out); store.createFromFile(out, title) } finally { out.delete() }
        }
        show(record); reload()
    }
    fun merge(uris: List<Uri>) {
        if (uris.size < 2) { notice = "Choose at least two PDFs to merge."; return }
        operation("Combining PDFs", edit = true, queue = true) {
            val result = withContext(Dispatchers.IO) {
                val imports = uris.map { store.importPdf(it) }
                val out = File.createTempFile("merge-", ".pdf", workDir)
                try { engine.merge(imports.map { File(it.filePath) }, out); store.createFromFile(out, "Combined document") }
                finally { out.delete() }
            }
            show(result); reload(); notice = "Combined in selection order. Use Pages to rearrange."
        }
    }
    private fun mutate(label: String, closePanel: Boolean = false, onFailure: ((String) -> Unit)? = null, block: suspend (File, File) -> Unit) {
        val record = current ?: return
        val oldPage = page
        val previousFields = info?.formFields.orEmpty()
        val pendingAnswers = if (formDraftRevisionId == "${record.id}:${record.revisionId}") formDraft.toMap() else emptyMap()
        val pendingQuestion = formDraftQuestion.intValue
        operation(label, edit = true, onFailure = onFailure) {
            flushFormDraftAndAwait()
            val updated = withContext(Dispatchers.IO) {
                val out = File.createTempFile("edit-", ".pdf", workDir)
                try { block(File(record.filePath), out); store.commitRevision(record.id, out) }
                finally { out.delete() }
            }
            current = updated
            info = withContext(Dispatchers.IO) { engine.inspect(File(updated.filePath)) }
            loadFormDraft(updated, info!!.formFields)
            val carriedAnswers = carryForwardFormAnswers(previousFields, info!!.formFields, pendingAnswers)
            if (carriedAnswers.isNotEmpty()) {
                formDraft.putAll(carriedAnswers)
                formDraftQuestion.intValue = pendingQuestion.coerceIn(0, (formDraft.size - 1).coerceAtLeast(0))
                flushFormDraftAndAwait()
            }
            page = oldPage.coerceIn(0, info!!.pageCount - 1)
            export = null; selection = null; query = ""; searchHits = emptyList(); readText = ""
            clearTextSelection()
            if (tool == EditorTool.EDIT_TEXT) tool = EditorTool.MOVE
            reload()
            if (closePanel) panel = Panel.NONE
        }
    }
    fun addAnnotation(annotation: PageAnnotation) {
        val target = page
        mutate("Saving your edit") { source, out -> engine.annotate(source, out, target, listOf(annotation)) }
    }
    private fun clearTextSelection() {
        editableTextRuns = emptyList(); selectedTextRun = null; textRunRevision = null; existingTextError = null
    }
    fun beginTextEditing() {
        val record = current ?: return
        val target = page
        operation("Finding existing text", edit = true) {
            val runs = withContext(Dispatchers.IO) { engine.editableText(File(record.filePath), target) }
            if (current?.revisionId != record.revisionId || page != target) return@operation
            editableTextRuns = runs
            selectedTextRun = null
            textRunRevision = "${record.id}:${record.revisionId}:$target"
            selection = null; tool = EditorTool.EDIT_TEXT
            if (runs.isEmpty()) notice = "No editable text here. For flattened edits, restore an earlier version in History. Scanned pages need text reconstruction."
        }
    }
    fun selectExistingText(run: EditableTextRun) {
        if (busy) return
        if (!billing.canEditNow()) { panel = Panel.UNLOCK; return }
        val record = current ?: return
        if (textRunRevision != "${record.id}:${record.revisionId}:$page" || run !in editableTextRuns) return
        selectedTextRun = run; panel = Panel.EDIT_TEXT; notice = null; existingTextError = null
    }
    fun selectExistingTextAt(x: Float, y: Float) {
        val hit = editableTextRuns.filter { run ->
            val r = run.rect
            x >= r.left - .004f && x <= r.right + .004f && y >= r.top - .006f && y <= r.bottom + .006f
        }.minByOrNull { run ->
            val r = run.rect
            kotlin.math.abs(y - (r.top + r.bottom) / 2f)
        }
        if (hit != null) selectExistingText(hit)
    }
    fun replaceExistingText(replacement: String) {
        val record = current ?: return
        val run = selectedTextRun ?: return
        val target = page
        if (textRunRevision != "${record.id}:${record.revisionId}:$target") {
            existingTextError = "This page changed. Select its text again."; return
        }
        if (!run.editable) { existingTextError = run.reason ?: "This text cannot be edited safely."; return }
        if (replacement == run.text) { panel = Panel.NONE; return }
        existingTextError = null
        mutate("Replacing existing text", closePanel = true, onFailure = { existingTextError = it }) { source, out ->
            engine.replaceExistingText(source, out, target, run, replacement)
        }
    }
    fun placeAt(x: Float, y: Float) {
        when (tool) {
            EditorTool.TEXT -> if (textToPlace.isNotBlank()) {
                addAnnotation(PageAnnotation.Text(x.coerceAtMost(.9f), y.coerceAtMost(.95f), textToPlace, textSize, inkColor))
                tool = EditorTool.MOVE
            }
            EditorTool.SIGN -> if (signature.isNotEmpty()) {
                val w = .32f
                val h = .1f
                val left = (x - w / 2).coerceIn(0f, 1f - w)
                val top = (y - h / 2).coerceIn(0f, 1f - h)
                addAnnotation(PageAnnotation.Signature(signature.map { stroke -> stroke.map { NormalizedPoint(left + it.x * w, top + it.y * h) } }, color = inkColor))
                tool = EditorTool.MOVE
            }
            else -> Unit
        }
    }
    fun applyRedaction() {
        val rect = selection ?: return
        val target = page
        panel = Panel.NONE; tool = EditorTool.MOVE
        mutate("Removing selected content") { source, out -> engine.rasterExport(source, out, redactions = mapOf(target to listOf(rect))) }
    }
    fun applyCrop() {
        val rect = selection ?: return
        val target = page
        panel = Panel.NONE; tool = EditorTool.MOVE
        mutate("Cropping page") { source, out -> engine.cropPage(source, out, target, rect) }
    }
    fun arrange(order: List<Int>, rotations: Map<Int, Int> = emptyMap()) {
        mutate("Saving page order", closePanel = true) { source, out -> engine.arrangePages(source, out, order, rotations) }
    }
    fun extract(order: List<Int>, rotations: Map<Int, Int> = emptyMap()) {
        val record = current ?: return
        create("${record.title.removeSuffix(".pdf")} - selected pages", "Extracting pages") { out -> engine.arrangePages(File(record.filePath), out, order, rotations) }
    }
    fun fill(values: Map<String, String>) {
        flushFormDraft()
        mutate("Saving form answers", closePanel = true) { source, out -> engine.fillForm(source, out, values) }
    }
    fun updateFormAnswer(name: String, value: String) {
        if (!formDraft.containsKey(name)) return
        if (value.length > FormDraftStore.MAX_ANSWER_LENGTH) {
            notice = "Keep each form answer under 20,000 characters."
            return
        }
        formDraft[name] = value
        scheduleFormDraftSave(immediate = false)
    }
    fun updateFormQuestion(question: Int) {
        formDraftQuestion.intValue = question.coerceAtLeast(0)
        flushFormDraft()
    }
    /** Request an immediate private draft write when leaving a question, panel, or activity. */
    fun flushFormDraft() { scheduleFormDraftSave(immediate = true) }

    private fun scheduleFormDraftSave(immediate: Boolean): Job? {
        val record = current ?: return null
        if (formDraftRevisionId != "${record.id}:${record.revisionId}" || formDraft.isEmpty()) return null
        val snapshot = FormDraft(record.revisionId, formDraft.toMap(), formDraftQuestion.intValue, System.currentTimeMillis())
        val generation = ++formDraftGeneration
        formDraftSaveJob?.cancel()
        return viewModelScope.launch {
            try {
                if (!immediate) delay(250)
                formDraftMutex.withLock {
                    if (generation != formDraftGeneration) return@withLock
                    withContext(Dispatchers.IO) { formDraftStore.save(record.id, snapshot) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { notice = "Your unfinished form draft could not be saved. Check available storage." }
        }.also { formDraftSaveJob = it }
    }

    private suspend fun flushFormDraftAndAwait() {
        (scheduleFormDraftSave(immediate = true) ?: formDraftSaveJob)?.join()
    }

    private suspend fun loadFormDraft(record: DocumentRecord, fields: List<FormFieldInfo>) {
        formDraftGeneration++
        formDraftSaveJob?.cancelAndJoin()
        formDraftSaveJob = null
        val saved = try {
            formDraftMutex.withLock { withContext(Dispatchers.IO) { formDraftStore.load(record.id, record.revisionId) } }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            notice = "The unfinished draft could not be recovered. Your saved PDF is unchanged."
            null
        }
        val editable = fields.filter { !it.readOnly && (it.kind == FormFieldKind.TEXT || it.kind == FormFieldKind.CHECKBOX ||
            ((it.kind == FormFieldKind.CHOICE || it.kind == FormFieldKind.RADIO) && it.options.isNotEmpty())) }
        formDraft.clear()
        editable.forEach { field -> formDraft[field.name] = saved?.values?.get(field.name) ?: field.value }
        formDraftRevisionId = "${record.id}:${record.revisionId}"
        formDraftQuestion.intValue = (saved?.question ?: 0).coerceIn(0, (editable.size - 1).coerceAtLeast(0))
    }
    fun correctRegion(replacement: String) {
        val rect = selection ?: return
        val target = page
        mutate("Replacing selected area", closePanel = true) { source, out -> engine.correctRegion(source, out, target, rect, replacement) }
    }
    fun undo() {
        val record = current ?: return
        operation("Restoring previous version") {
            val restored = withContext(Dispatchers.IO) { store.undo(record.id) }
            show(restored); reload(); notice = "Previous version restored"
        }
    }
    fun restoreVersion(revisionId: String) {
        val record = current ?: return
        operation("Restoring version") {
            val restored = withContext(Dispatchers.IO) { store.restoreRevision(record.id, revisionId) }
            show(restored); reload(); notice = "Version restored"
        }
    }
    fun rename(title: String) {
        val record = current ?: return
        operation("Renaming") {
            current = withContext(Dispatchers.IO) { store.rename(record.id, title) }
            panel = Panel.NONE; reload()
        }
    }
    fun moveToTrash(record: DocumentRecord) = operation("Moving to trash") {
        withContext(Dispatchers.IO) { store.remove(record.id) }
        if (current?.id == record.id) { current = null; info = null }
        reload(); notice = "Moved to trash. Restore from Settings."
    }
    fun loadTrash() = operation("Opening trash") {
        trash = withContext(Dispatchers.IO) { store.listTrash() }; panel = Panel.TRASH
    }
    fun restoreTrash(record: DocumentRecord) = operation("Restoring document") {
        withContext(Dispatchers.IO) { store.restore(record.id) }
        trash = withContext(Dispatchers.IO) { store.listTrash() }; reload()
    }
    fun deletePermanently(record: DocumentRecord) = operation("Deleting document and history") {
        flushFormDraftAndAwait()
        formDraftGeneration++
        formDraftSaveJob?.cancelAndJoin()
        formDraftSaveJob = null
        withContext(Dispatchers.IO) {
            store.deletePermanently(record.id)
        }
        trash = withContext(Dispatchers.IO) { store.listTrash() }; reload()
        notice = "Local document and history deleted"
    }
    fun emptyTrash() = operation("Deleting all trashed documents") {
        val records = withContext(Dispatchers.IO) { store.listTrash() }
        if (records.isEmpty()) { trash = emptyList(); return@operation }
        flushFormDraftAndAwait()
        formDraftGeneration++
        formDraftSaveJob?.cancelAndJoin()
        formDraftSaveJob = null
        var deleted = 0
        var failure: String? = null
        for (record in records) {
            try {
                withContext(Dispatchers.IO) { store.deletePermanently(record.id) }
                deleted++
            } catch (error: Exception) {
                failure = error.message ?: "Deletion could not be completed."
                break
            }
        }
        trash = withContext(Dispatchers.IO) { store.listTrash() }
        reload()
        notice = if (failure == null) "Deleted $deleted ${if (deleted == 1) "document" else "documents"} from Trash"
            else "Deleted $deleted of ${records.size} documents. ${failure.take(120)}"
    }
    fun restoreOriginal() {
        val record = current ?: return
        operation("Restoring untouched original") {
            val restored = withContext(Dispatchers.IO) { store.restoreOriginal(record.id) }
            show(restored); reload(); notice = "Original restored"
        }
    }
    fun saveProfile(value: UserProfile) = operation(if (value == UserProfile()) "Clearing saved details" else "Saving your profile") {
        withContext(Dispatchers.IO) { store.saveProfile(value) }
        profile = value; panel = Panel.NONE
        notice = if (value == UserProfile()) "Saved details cleared from this device" else "Profile saved only on this device"
    }
    fun search(value: String) {
        val record = current ?: return
        query = value
        if (value.isBlank()) { searchHits = emptyList(); return }
        operation("Searching PDF") {
            searchHits = withContext(Dispatchers.IO) { engine.search(File(record.filePath), value) }
        }
    }
    fun read(ocr: Boolean = false) {
        val record = current ?: return
        val target = page
        operation(if (ocr) "Recognizing this page on your device" else "Reading page") {
            readText = withContext(Dispatchers.IO) {
                if (ocr) engine.recognize(File(record.filePath), target).text else engine.extractText(File(record.filePath), target)
            }
            panel = Panel.READ
        }
    }
    fun makeSearchable() {
        panel = Panel.NONE
        mutate("Recognizing pages on your device") { source, out -> engine.searchableOcr(source, out) }
    }
    fun prepareExport(targetMb: Int? = null, removeMetadata: Boolean = false, password: String = "") {
        val record = current ?: return
        export = null
        operation("Preparing your PDF", edit = targetMb != null || removeMetadata || password.isNotEmpty()) {
            export = withContext(Dispatchers.IO) {
                val snapshot = store.exportSnapshot(record.id)
                val name = record.title.removeSuffix(".pdf").replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80).ifBlank { "Document" }
                val folder = File(File(shareDir, record.id), UUID.randomUUID().toString()).apply { mkdirs() }
                val finalFile = File(folder, "$name.pdf")
                val temporary = mutableListOf<File>()
                var stage = snapshot
                var targetMet = true
                var success = false
                try {
                    if (targetMb != null) {
                        val compressed = File.createTempFile("compact-", ".pdf", workDir).also { temporary.add(it) }
                        val result = engine.compressToTarget(stage, compressed, targetMb * 1024L * 1024L)
                        stage = compressed; targetMet = result.targetMet
                    }
                    if (removeMetadata) {
                        val clean = File.createTempFile("clean-", ".pdf", workDir).also { temporary.add(it) }
                        engine.sanitizeMetadata(stage, clean)
                        if (stage != snapshot) stage.delete()
                        stage = clean
                    }
                    val previewFile = if (password.isNotEmpty()) File(File(folder, "preview").apply { mkdirs() }, "content.pdf").also { stage.copyTo(it) } else finalFile
                    if (password.isNotEmpty()) engine.protect(stage, finalFile, password) else stage.copyTo(finalFile)
                    success = true
                    PreparedExport(finalFile, targetMet && (targetMb == null || finalFile.length() <= targetMb * 1024L * 1024L), targetMb != null, previewFile)
                } finally { temporary.forEach { it.delete() }; snapshot.delete(); if (!success) finalFile.delete() }
            }
        }
    }
    fun clearExport() { export = null }
    fun saveExport(uri: Uri) {
        val prepared = export ?: return
        operation("Saving to your chosen folder") {
            withContext(Dispatchers.IO) {
                val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("That folder is not writable. Choose another location.")
                stream.use { output -> prepared.file.inputStream().use { it.copyTo(output) } }
            }
            notice = "PDF exported successfully"
        }
    }
    fun shareIntent(): Intent? {
        val prepared = export ?: return null
        val app = getApplication<Application>()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", prepared.file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("PDF", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    override fun onCleared() { billing.close(); super.onCleared() }
}
