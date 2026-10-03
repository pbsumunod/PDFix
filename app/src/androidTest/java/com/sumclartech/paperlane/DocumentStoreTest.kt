package com.sumclartech.paperlane

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sumclartech.paperlane.data.DocumentStore
import com.sumclartech.paperlane.data.DocumentStoreException
import com.sumclartech.paperlane.data.UserProfile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DocumentStoreTest {
    private lateinit var context: Context
    private lateinit var testRoot: File
    private lateinit var storeRoot: File
    private lateinit var store: DocumentStore
    private var interruptCommit = false
    private val exports = mutableListOf<File>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        testRoot = File(context.filesDir, "document_store_test_${UUID.randomUUID()}").apply { mkdirs() }
        storeRoot = File(testRoot, "repository")
        store = DocumentStore(context, storeRoot, beforeMetadataCommit = {
            if (interruptCommit) throw IOException("Simulated interruption before publishing metadata")
        })
    }

    @After
    fun tearDown() {
        val allowedRoot = context.filesDir.canonicalPath + File.separator
        check(testRoot.canonicalPath.startsWith(allowedRoot))
        testRoot.deleteRecursively()
        exports.forEach {
            check(it.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            it.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun invalidGeneratedPdfPreservesPreviousRevisionAndSource() {
        val input = validPdf("before.pdf", "Before")
        val originalBytes = input.readBytes()
        val before = store.createFromFile(input, "Contract")
        val invalid = File(testRoot, "invalid.pdf").apply { writeText("%PDF-1.7\nThis is not a PDF body") }

        expectStoreFailure { store.commitRevision(before.id, invalid) }

        val reopened = DocumentStore(context, storeRoot).getDocument(before.id)
        assertEquals(before.revisionId, reopened.revisionId)
        assertArrayEquals(originalBytes, File(reopened.filePath).readBytes())
        assertArrayEquals(originalBytes, File(storeRoot, "documents/${before.id}/source.pdf").readBytes())
        assertEquals(1, store.history(before.id).size)
        assertEquals(1, File(storeRoot, "documents/${before.id}/revisions").listFiles()!!.size)
    }

    @Test
    fun interruptedCommitRecoversOldPdfAfterRepositoryRestart() {
        val before = store.createFromFile(validPdf("before.pdf", "Before"), "Report")
        val originalBytes = File(before.filePath).readBytes()
        val replacement = validPdf("after.pdf", "After")
        interruptCommit = true

        expectStoreFailure { store.commitRevision(before.id, replacement) }

        val restarted = DocumentStore(context, storeRoot)
        val recovered = restarted.getDocument(before.id)
        assertEquals(before.revisionId, recovered.revisionId)
        assertArrayEquals(originalBytes, File(recovered.filePath).readBytes())
        assertTrue("The engine's input still belongs to the caller", replacement.exists())
        assertEquals(1, File(storeRoot, "documents/${before.id}/revisions").listFiles()!!.size)
    }

    @Test
    fun traversalNamesAndIdsCannotEscapePrivateStorage() {
        val document = store.createFromFile(validPdf("input.pdf", "Private"), "../../outside.pdf")
        assertEquals("outside.pdf", document.title)
        assertTrue(File(document.filePath).canonicalPath.startsWith(storeRoot.canonicalPath + File.separator))
        val renamed = store.rename(document.id, "..\\..\\another.pdf")
        assertEquals("another.pdf", renamed.title)
        expectStoreFailure { store.getDocument("../../outside") }
        val exported = store.exportSnapshot(document.id).also { exports.add(it) }
        assertEquals("another.pdf", exported.name)
        assertTrue(exported.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        assertFalse(File(context.filesDir, "outside.pdf").exists())
    }

    @Test
    fun failedImportLeavesNoPublishedDocumentOrTemporaryFiles() {
        val invalid = File(testRoot, "broken.pdf").apply { writeText("Not a PDF") }

        expectStoreFailure { store.importPdf(Uri.fromFile(invalid)) }

        assertTrue(store.listDocuments().isEmpty())
        assertTrue(File(storeRoot, "staging").listFiles().orEmpty().isEmpty())
        assertTrue(File(storeRoot, "documents").listFiles().orEmpty().isEmpty())
        assertEquals("Not a PDF", invalid.readText())
    }

    @Test
    fun exportRemainsStableAfterEditAndUndoRestoresOriginalBytes() {
        val before = store.createFromFile(validPdf("first.pdf", "First"), "First")
        val originalBytes = File(before.filePath).readBytes()
        val exported = store.exportSnapshot(before.id).also { exports.add(it) }
        val edited = store.commitRevision(before.id, validPdf("second.pdf", "Second"))
        assertNotEquals(before.revisionId, edited.revisionId)
        assertArrayEquals(originalBytes, exported.readBytes())
        val undone = store.undo(before.id)
        assertArrayEquals(originalBytes, File(undone.filePath).readBytes())
        assertArrayEquals(originalBytes, File(storeRoot, "documents/${before.id}/source.pdf").readBytes())
    }

    @Test
    fun revisionRetentionKeepsOriginalRecoverableAndTrashCanRestore() {
        val before = store.createFromFile(validPdf("initial.pdf", "Original"), "History")
        val originalBytes = File(before.filePath).readBytes()
        repeat(DocumentStore.MAX_REVISIONS + 2) { index ->
            store.commitRevision(before.id, validPdf("revision.pdf", "Revision $index"))
        }
        assertEquals(DocumentStore.MAX_REVISIONS, store.history(before.id).size)
        assertEquals(DocumentStore.MAX_REVISIONS,
            File(storeRoot, "documents/${before.id}/revisions").listFiles()!!.size)
        val original = store.restoreOriginal(before.id)
        assertArrayEquals(originalBytes, File(original.filePath).readBytes())
        store.remove(before.id)
        assertTrue(store.listDocuments().isEmpty())
        assertEquals(before.id, store.listTrash().single().id)
        store.restore(before.id)
        assertEquals(before.id, store.listDocuments().single().id)
    }

    @Test
    fun localProfileAndReadingPositionPersistAcrossRestart() {
        val document = store.createFromFile(validPdf("profile.pdf", "Profile"), "Profile")
        val profile = UserProfile("Jamie", "jamie@example.com", "555", "Local address", "Example")
        store.saveProfile(profile)
        store.saveBookmark(document.id, 999)
        val restarted = DocumentStore(context, storeRoot)
        assertEquals(profile, restarted.loadProfile())
        assertEquals(0, restarted.loadBookmark(document.id))
    }

    @Test
    fun permanentDeletionRequiresTrashAndRemovesOriginalHistoryAndBookmark() {
        val record = store.createFromFile(validPdf("delete.pdf", "Private"), "Delete me")
        store.commitRevision(record.id, validPdf("edited.pdf", "Edited"))
        store.saveBookmark(record.id, 0)
        val cachedSnapshot = store.exportSnapshot(record.id).also { exports.add(it) }
        val preparedSharingDirectory = File(context.cacheDir, "shared/${record.id}").apply { mkdirs() }
        val preparedShare = File(preparedSharingDirectory, "shared.pdf").apply { writeBytes(cachedSnapshot.readBytes()) }
        val privateDirectory = File(storeRoot, "documents/${record.id}")
        expectStoreFailure { store.deletePermanently(record.id) }
        assertTrue(privateDirectory.exists())
        expectStoreFailure { store.deletePermanently("../../outside") }

        store.remove(record.id)
        store.deletePermanently(record.id)

        assertFalse(privateDirectory.exists())
        assertFalse(cachedSnapshot.exists())
        assertFalse(preparedShare.exists())
        assertTrue(store.listTrash().isEmpty())
        assertTrue(store.listDocuments().isEmpty())
        assertTrue(File(storeRoot, "pending_deletions").listFiles().orEmpty().isEmpty())
        expectStoreFailure { store.getDocument(record.id) }
    }

    @Test
    fun interruptedPermanentDeletionIsHiddenAndFinishesOnRestart() {
        val record = store.createFromFile(validPdf("private.pdf", "Private"), "Private")
        store.remove(record.id)
        val interruptedStore = DocumentStore(context, storeRoot, beforePermanentDelete = {
            throw IOException("Simulated process interruption after durable deletion marker")
        })

        expectStoreFailure { interruptedStore.deletePermanently(record.id) }
        assertTrue(File(storeRoot, "documents/${record.id}").exists())
        assertTrue(interruptedStore.listTrash().isEmpty())
        expectStoreFailure { interruptedStore.restore(record.id) }

        val restarted = DocumentStore(context, storeRoot)
        assertFalse(File(storeRoot, "documents/${record.id}").exists())
        assertTrue(File(storeRoot, "pending_deletions").listFiles().orEmpty().isEmpty())
        assertTrue(restarted.listTrash().isEmpty())
    }

    @Test
    fun compatibilityRejectionDoesNotPublishAnImport() {
        val validationStore = DocumentStore(context, storeRoot, validateCompatibility = {
            throw DocumentStoreException("This PDF is not supported by the editing engine.")
        })
        val input = validPdf("unsupported.pdf", "Readable but engine rejects it")

        expectStoreFailure { validationStore.createFromFile(input, "Unsupported") }

        assertTrue(validationStore.listDocuments().isEmpty())
        assertTrue(File(storeRoot, "staging").listFiles().orEmpty().isEmpty())
        assertTrue(input.exists())
    }

    @Test
    fun overPageLimitImportLeavesNoPublishedDocument() {
        val input = File(testRoot, "too-many-pages.pdf")
        val pdf = PdfDocument()
        try {
            repeat(DocumentStore.MAX_PAGES + 1) { index ->
                pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(30, 40, index + 1).create()))
            }
            input.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }

        expectStoreFailure { store.createFromFile(input, "Too many pages") }

        assertTrue(store.listDocuments().isEmpty())
        assertTrue(File(storeRoot, "staging").listFiles().orEmpty().isEmpty())
    }

    private fun validPdf(filename: String, text: String): File {
        val file = File(testRoot, filename)
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
            page.canvas.drawText(text, 24f, 40f, Paint().apply { color = Color.BLACK; textSize = 18f })
            pdf.finishPage(page)
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return file
    }

    private fun expectStoreFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected a DocumentStoreException")
        } catch (_: DocumentStoreException) {
            // Expected; assertions after this call verify the previous valid document survives.
        }
    }
}
