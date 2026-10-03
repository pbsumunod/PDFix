package com.sumclartech.paperlane

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sumclartech.paperlane.billing.TrialPolicy
import com.sumclartech.paperlane.data.DocumentStore
import com.sumclartech.paperlane.pdf.PageAnnotation
import com.sumclartech.paperlane.pdf.PdfEngine
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Activity, UI, PDF engine, local storage, and FileProvider. No fake billing. */
@RunWith(AndroidJUnit4::class)
class EditorUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var libraryRoot: File
    private lateinit var libraryBackup: File
    private lateinit var fixtureDirectory: File
    private lateinit var model: EditorViewModel
    private var scenario: ActivityScenario<MainActivity>? = null
    private var previousBilling: Map<String, *> = emptyMap<String, Any>()
    private var previousShares: Set<String> = emptySet()
    private var fixturePrepared = false

    @Before fun prepareIsolatedAppData() {
        check(BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "validation") {
            "These instrumentation fixtures must target a debug or isolated validation build"
        }
        context = InstrumentationRegistry.getInstrumentation().targetContext
        libraryRoot = File(context.filesDir, "paperlane")
        libraryBackup = File(context.filesDir, "paperlane-ui-backup-${UUID.randomUUID()}")
        check(libraryRoot.canonicalFile.parentFile == context.filesDir.canonicalFile)
        if (libraryRoot.exists()) check(libraryRoot.renameTo(libraryBackup))
        val preferences = context.getSharedPreferences("paperlane_billing", Context.MODE_PRIVATE)
        previousBilling = preferences.all.toMap()
        check(preferences.edit().clear().commit())
        val shared = File(context.cacheDir, "shared").apply { mkdirs() }
        previousShares = shared.listFiles().orEmpty().map { it.name }.toSet()
        fixtureDirectory = File(shared, "ui-fixtures-${UUID.randomUUID()}").apply { mkdirs() }
        fixturePrepared = true
    }

    @After fun restoreAppData() {
        // Activity destruction clears its ViewModel and closes the real billing connection.
        scenario?.close()
        scenario = null
        if (!fixturePrepared) return
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        check(libraryRoot.canonicalFile.parentFile == context.filesDir.canonicalFile)
        check(!libraryRoot.exists() || libraryRoot.deleteRecursively())
        if (libraryBackup.exists()) check(libraryBackup.renameTo(libraryRoot))
        val preferences = context.getSharedPreferences("paperlane_billing", Context.MODE_PRIVATE)
        val edit = preferences.edit().clear()
        previousBilling.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(edit.commit())
        val shared = File(context.cacheDir, "shared")
        shared.listFiles().orEmpty().filter { it.name !in previousShares }.forEach {
            check(it.canonicalFile.parentFile == shared.canonicalFile)
            it.deleteRecursively()
        }
    }

    @Test fun demoTextFormPagesAndExportProduceTheExpectedPdf() {
        launch()
        compose.onNodeWithTag("desk_list").performScrollToNode(hasText("Try it out"))
        compose.onNodeWithText("Try it out").performClick()
        waitForDocument()
        assertEquals(2, model.info!!.pageCount)

        val firstRevision = model.current!!.revisionId
        compose.onNodeWithContentDescription("Add text").performClick()
        compose.onNodeWithTag("annotation_text").performTextInput("UI export proof")
        compose.onNodeWithText("Place on page").performScrollTo().performClick()
        compose.onNodeWithTag("pdf_page").performTouchInput {
            click(Offset(width * .15f, height * .88f))
        }
        waitForRevisionOtherThan(firstRevision)
        assertTrue(model.engine.extractText(File(model.current!!.filePath), 0).contains("UI export proof"))

        val noteRevision = model.current!!.revisionId
        compose.onNodeWithText("Fill form").performClick()
        compose.onNodeWithTag("form_answer").performTextReplacement("Avery Test")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithTag("form_answer").performTextReplacement("avery@example.test")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithTag("form_answer").performTextReplacement("Send a verified PDF")
        compose.onNodeWithText("Save answers").performClick()
        waitForRevisionOtherThan(noteRevision)
        val answers = model.engine.inspect(File(model.current!!.filePath)).formFields.associate { it.name to it.value }
        assertEquals("Avery Test", answers["name"])
        assertEquals("avery@example.test", answers["email"])
        assertEquals("Send a verified PDF", answers["plan"])

        val formRevision = model.current!!.revisionId
        compose.onNodeWithText("Pages").performClick()
        compose.onNodeWithContentDescription("Rotate page 1 clockwise").performClick()
        compose.onNodeWithText("Save pages").performClick()
        waitForRevisionOtherThan(formRevision)
        assertEquals(90, model.info!!.pages.first().rotation)

        val output = prepareOriginalExport()
        val exportedInfo = model.engine.inspect(output)
        assertEquals(2, exportedInfo.pageCount)
        assertEquals(90, exportedInfo.pages.first().rotation)
        assertTrue(model.engine.extractText(output, 0).contains("UI export proof"))
        assertTrue(model.engine.extractText(output, 1).contains("Avery Test"))
        assertArrayEquals(File(model.current!!.filePath).readBytes(), output.readBytes())
        assertReadableSharingUri(output)
    }

    @Test fun existingTextEditsPreserveOriginalAndRejectOverflowWithoutClosingTheEditor() {
        launch()
        compose.onNodeWithTag("desk_list").performScrollToNode(hasText("Try it out"))
        compose.onNodeWithText("Try it out").performClick()
        waitForDocument()
        val before = model.current!!
        val originalFile = File(before.filePath)
        val originalBytes = originalFile.readBytes()

        compose.onNodeWithContentDescription("Edit text").performClick()
        compose.waitUntil(timeoutMillis = 45_000) { !model.busy && model.editableTextRuns.isNotEmpty() }
        compose.onNodeWithContentDescription("Edit text: Make room for what matters.").performClick()
        compose.onNodeWithTag("existing_text_value").assertTextContains("Make room for what matters.")
        compose.onNodeWithTag("existing_text_value").performTextReplacement("Make room for what matters. ".repeat(20))
        // Activate the accessible Save action; the system keyboard animates in a separate window.
        compose.onNodeWithText("Save text").performScrollTo().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(timeoutMillis = 45_000) { !model.busy && model.existingTextError != null }
        compose.onNodeWithTag("existing_text_error").performScrollTo().assertIsDisplayed()
        assertEquals(before.revisionId, model.current!!.revisionId)
        assertArrayEquals(originalBytes, originalFile.readBytes())

        compose.onNodeWithTag("existing_text_value").performScrollTo().performTextReplacement("Make room for new ideas.")
        compose.onNodeWithText("Save text").performScrollTo().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        waitForRevisionOtherThan(before.revisionId)
        assertEquals(Panel.NONE, model.panel)
        val editedText = model.engine.extractText(File(model.current!!.filePath), 0)
        assertTrue(editedText.contains("Make room for new ideas."))
        assertFalse(editedText.contains("Make room for what matters."))
        assertArrayEquals(originalBytes, originalFile.readBytes())
        assertEquals(3, model.info!!.formFields.size)

        // Fresh selection must analyze the saved operators rather than reuse an old rectangle/ID.
        compose.onNodeWithContentDescription("Edit text").performClick()
        compose.waitUntil(timeoutMillis = 45_000) { !model.busy && model.editableTextRuns.isNotEmpty() }
        compose.onNodeWithContentDescription("Edit text: Make room for new ideas.").assertExists()
        val exported = prepareOriginalExport()
        assertTrue(model.engine.extractText(exported, 0).contains("Make room for new ideas."))
        assertReadableSharingUri(exported)
    }

    @Test fun coldActivityViewIntentWaitsForLibraryLoadAndDoesNotImportTwiceOnRotation() {
        val source = File(fixtureDirectory, "Cold import.pdf")
        PdfEngine(context).demo(source)
        val original = source.readBytes()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, "application/pdf")
            clipData = ClipData.newRawUri("Incoming PDF", uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(intent)
        waitForDocument()
        compose.onNodeWithText("Cold import").assertIsDisplayed()
        assertEquals(1, model.documents.size)
        val importedId = model.current!!.id
        assertArrayEquals(original, File(model.current!!.filePath).readBytes())
        assertArrayEquals(original, source.readBytes())

        scenario!!.recreate()
        scenario!!.onActivity { model = ViewModelProvider(it)[EditorViewModel::class.java] }
        waitForDocument()
        assertEquals(importedId, model.current!!.id)
        assertEquals(1, model.documents.size)
        assertEquals(1, DocumentStore(context).listDocuments().size)
        assertEquals(2, model.info!!.pageCount)
    }

    @Test fun expiredTrialBlocksNewEditsButExistingWorkStillExportsAndRecovers() {
        val engine = PdfEngine(context)
        val original = File(fixtureDirectory, "existing.pdf")
        val annotated = File(fixtureDirectory, "saved-edit.pdf")
        engine.demo(original)
        val store = DocumentStore(context)
        val initial = store.createFromFile(original, "Expired trial document")
        engine.annotate(original, annotated, 0, listOf(PageAnnotation.Text(.15f, .88f, "Already saved")))
        val saved = store.commitRevision(initial.id, annotated)
        expireLocalTrialBeforeLaunch()

        launch()
        compose.onNodeWithTag("desk_list").performScrollToNode(hasText("Expired trial document"))
        compose.onNodeWithText("Expired trial document").performClick()
        waitForDocument()
        assertTrue(model.billing.state.value.expired)
        assertFalse(model.billing.state.value.canEdit)

        compose.onNodeWithContentDescription("Edit text").performClick()
        compose.onNodeWithText("Yours for good.").assertIsDisplayed()
        assertTrue(model.editableTextRuns.isEmpty())
        compose.onNodeWithContentDescription("Close panel").performClick()
        compose.onNodeWithContentDescription("Add text").performClick()
        compose.onNodeWithText("Yours for good.").assertIsDisplayed()
        assertEquals(saved.revisionId, model.current!!.revisionId)
        compose.onNodeWithContentDescription("Close panel").performClick()

        // The operation boundary must also refuse an edit from an already-selected tool.
        compose.runOnIdle { model.addAnnotation(PageAnnotation.Text(.1f, .5f, "BLOCKED NEW EDIT")) }
        compose.onNodeWithText("Yours for good.").assertIsDisplayed()
        assertEquals(saved.revisionId, model.current!!.revisionId)
        assertFalse(engine.extractText(File(model.current!!.filePath), 0).contains("BLOCKED NEW EDIT"))
        compose.onNodeWithContentDescription("Close panel").performClick()

        val exported = prepareOriginalExport()
        assertArrayEquals(File(saved.filePath).readBytes(), exported.readBytes())
        assertTrue(engine.extractText(exported, 0).contains("Already saved"))
        assertReadableSharingUri(exported)
        compose.onNodeWithContentDescription("Close panel").performClick()

        compose.onNodeWithText("History").performScrollTo().performClick()
        compose.onNodeWithText("Restore untouched original").performClick()
        waitForRevisionOtherThan(saved.revisionId)
        assertFalse(engine.extractText(File(model.current!!.filePath), 0).contains("Already saved"))
        assertTrue(model.billing.state.value.expired)
        assertFalse(model.billing.state.value.canEdit)
        compose.onNodeWithText("Yours for good.").assertDoesNotExist()
    }

    private fun launch(intent: Intent = Intent(context, MainActivity::class.java)) {
        scenario = ActivityScenario.launch(intent)
        scenario!!.onActivity { model = ViewModelProvider(it)[EditorViewModel::class.java] }
        compose.waitUntil(timeoutMillis = 30_000) { !model.busy }
        compose.waitForIdle()
    }

    private fun waitForDocument() {
        compose.waitUntil(timeoutMillis = 45_000) { !model.busy && model.current != null && model.info != null }
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithContentDescription("Document page ${model.page + 1}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pdf_page").assertIsDisplayed()
    }

    private fun waitForRevisionOtherThan(previous: String) {
        compose.waitUntil(timeoutMillis = 45_000) { !model.busy && model.current?.revisionId != previous }
        waitForDocument()
    }

    private fun prepareOriginalExport(): File {
        compose.onNodeWithContentDescription("Save or share PDF").performClick()
        compose.onNodeWithText("Prepare PDF").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 45_000) { !model.busy && model.export != null }
        compose.onNodeWithText("Save to Files").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        val preview = hasContentDescription("Page 1 preview") and hasAnyAncestor(hasTestTag("export_preview"))
        compose.onNodeWithTag("export_preview").performScrollTo()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodes(preview, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("export_preview").assertIsDisplayed()
        return requireNotNull(model.export).file.also { assertTrue(it.isFile && it.length() > 0L) }
    }

    @Suppress("DEPRECATION")
    private fun assertReadableSharingUri(file: File) {
        val intent = compose.runOnIdle { requireNotNull(model.shareIntent()) }
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/pdf", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = requireNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.files", uri.authority)
        val sharedBytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
        assertArrayEquals(file.readBytes(), sharedBytes)
    }

    private fun expireLocalTrialBeforeLaunch() {
        val now = System.currentTimeMillis()
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        // Fixture only: production has no reset/expiry switch or purchase bypass.
        check(context.getSharedPreferences("paperlane_billing", Context.MODE_PRIVATE).edit()
            .putLong("trial_start", now - 31L * TrialPolicy.DAY_MILLIS)
            .putLong("trial_high_water", now)
            .putLong("trial_elapsed", SystemClock.elapsedRealtime())
            .putInt("trial_boot", boot)
            .commit())
    }
}
