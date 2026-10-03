package com.sumclartech.paperlane

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sumclartech.paperlane.data.FormDraft
import com.sumclartech.paperlane.data.FormDraftStore
import com.sumclartech.paperlane.data.carryForwardFormAnswers
import com.sumclartech.paperlane.pdf.NormalizedRect
import com.sumclartech.paperlane.pdf.PageAnnotation
import com.sumclartech.paperlane.pdf.PdfEngine
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormDraftStoreTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var document: File
    private lateinit var store: FormDraftStore
    private val documentId = UUID.randomUUID().toString()
    private val revisionId = UUID.randomUUID().toString()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "draft-test-${UUID.randomUUID()}").apply { mkdirs() }
        document = File(root, documentId).apply { mkdirs() }
        File(document, "state.json").writeText("{}")
        store = FormDraftStore(context, root)
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun answersAndQuestionRecoverInANewStoreButNotOnAnotherRevision() {
        val draft = FormDraft(revisionId, mapOf("name" to "Zoë García", "plan" to "Line one\nLine two"), 1, 1_234_567_890L)
        assertTrue(store.save(documentId, draft))
        val reopened = FormDraftStore(context, root)
        assertEquals(draft, reopened.load(documentId, revisionId))
        assertNull(reopened.load(documentId, UUID.randomUUID().toString()))
        assertEquals(draft, reopened.load(documentId, revisionId))
    }

    @Test fun failedAtomicWriteKeepsThePreviousDraft() {
        val draft = FormDraft(revisionId, mapOf("answer" to "Keep this answer"), 0, 99L)
        store.save(documentId, draft)
        val atomic = AtomicFile(File(document, "form-draft.json"))
        val unfinished = atomic.startWrite()
        unfinished.write("{partial".toByteArray())
        atomic.failWrite(unfinished)
        assertEquals(draft, FormDraftStore(context, root).load(documentId, revisionId))
    }

    @Test fun deletedDocumentsAreNeverRecreatedAndPathTraversalIsRejected() {
        val draft = FormDraft(revisionId, mapOf("answer" to "Local only"), 0, 100L)
        assertTrue(document.deleteRecursively())
        assertFalse(store.save(documentId, draft))
        assertFalse(document.exists())
        try { store.save("../escape", draft); fail("Invalid document identifiers must fail.") }
        catch (_: IllegalArgumentException) { }
        assertFalse(File(root.parentFile, "escape").exists())
    }

    @Test fun unfinishedAnswersSurviveAnnotationsWithoutOverwritingFilledOrFlattenedFields() {
        val engine = PdfEngine(context)
        val source = File(root, "form.pdf")
        engine.demo(source)
        val originalFields = engine.inspect(source).formFields
        val pending = mapOf("name" to "Zoë García")
        val originalDraft = FormDraft(revisionId, pending, 1, 100L)
        store.save(documentId, originalDraft)

        val annotated = File(root, "highlighted.pdf")
        engine.annotate(source, annotated, 0, listOf(PageAnnotation.Highlight(NormalizedRect(.1f, .1f, .5f, .15f))))
        val annotatedFields = engine.inspect(annotated).formFields
        val carried = carryForwardFormAnswers(originalFields, annotatedFields, pending)
        assertEquals(pending, carried)
        val nextRevision = UUID.randomUUID().toString()
        store.save(documentId, FormDraft(nextRevision, carried, 1, 200L))
        assertEquals(pending, FormDraftStore(context, root).load(documentId, nextRevision)?.values)

        val filled = File(root, "filled.pdf")
        engine.fillForm(annotated, filled, mapOf("name" to "Saved answer"))
        assertTrue(carryForwardFormAnswers(annotatedFields, engine.inspect(filled).formFields, pending).isEmpty())
        val flattened = File(root, "flattened.pdf")
        engine.rasterExport(annotated, flattened, 600, 70)
        assertTrue(carryForwardFormAnswers(annotatedFields, engine.inspect(flattened).formFields, pending).isEmpty())
        assertTrue(engine.inspect(source).formFields.all { it.value.isEmpty() })
    }
}
