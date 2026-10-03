package com.sumclartech.paperlane

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sumclartech.paperlane.pdf.NormalizedPoint
import com.sumclartech.paperlane.pdf.NormalizedRect
import com.sumclartech.paperlane.pdf.PageAnnotation
import com.sumclartech.paperlane.pdf.PdfEngine
import com.sumclartech.paperlane.pdf.PdfException
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfEngineTest {
    private lateinit var context: Context
    private lateinit var engine: PdfEngine
    private lateinit var directory: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = PdfEngine(context)
        directory = File(context.cacheDir, "engine-test-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun unicodeAndVectorAnnotationsSurviveReopeningWithoutChangingSource() {
        val source = source("Original document")
        val originalHash = digest(source)
        val out = file("annotated.pdf")
        engine.annotate(source, out, 0, listOf(
            PageAnnotation.Text(.1f, .25f, "Résumé — Ελληνικά — Москва", .025f),
            PageAnnotation.Highlight(NormalizedRect(.1f, .45f, .45f, .51f)),
            PageAnnotation.Ink(listOf(NormalizedPoint(.1f, .7f), NormalizedPoint(.4f, .7f))),
            PageAnnotation.Signature(listOf(listOf(NormalizedPoint(.5f, .7f), NormalizedPoint(.55f, .65f), NormalizedPoint(.6f, .7f)))),
        ))
        val extracted = engine.extractText(out, 0)
        assertTrue(extracted.contains("Résumé"))
        assertTrue(extracted.contains("Ελληνικά"))
        assertTrue(extracted.contains("Москва"))
        assertTrue(extracted.contains("Original document"))
        assertEquals(originalHash, digest(source))
        val bitmap = engine.render(out, 0, 1000)
        try {
            val highlighted = pixel(bitmap, .2f, .48f)
            assertTrue(Color.red(highlighted) > 220)
            assertTrue(Color.blue(highlighted) < 240)
            assertTrue(darkNear(bitmap, .25f, .7f))
        } finally { bitmap.recycle() }
    }

    @Test fun marksUseDisplayedCoordinatesForAllPageRotationsAndCropOrigins() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val source = file("rotation-$rotation.pdf")
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(10f, 15f, 450f, 650f))
                page.cropBox = PDRectangle(30f, 40f, 400f, 600f)
                page.rotation = rotation
                document.addPage(page)
                document.save(source)
            }
            val out = file("rotation-$rotation-marks.pdf")
            engine.annotate(source, out, 0, listOf(
                PageAnnotation.Highlight(NormalizedRect(.12f, .18f, .32f, .3f)),
                PageAnnotation.Ink(listOf(NormalizedPoint(.55f, .68f), NormalizedPoint(.85f, .68f)), .01f),
                PageAnnotation.Text(.12f, .4f, "TOP LEFT", .04f),
            ))
            assertTrue(engine.extractText(out, 0).contains("TOP LEFT"))
            val bitmap = engine.render(out, 0, 1000)
            try {
                val highlight = pixel(bitmap, .22f, .24f)
                assertTrue("Highlight misplaced for rotation $rotation", Color.blue(highlight) < 240)
                assertTrue("Ink misplaced for rotation $rotation", darkNear(bitmap, .7f, .68f))
                assertEquals("Opposite corner should be empty for rotation $rotation", Color.WHITE, pixel(bitmap, .9f, .1f))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun formQuestionnaireRoundTripsAndFlattenedValuesSurvivePageExtraction() {
        val source = file("form.pdf")
        engine.demo(source)
        val original = engine.inspect(source)
        assertEquals(2, original.pageCount)
        assertEquals(setOf("name", "email", "plan"), original.formFields.map { it.name }.toSet())
        assertTrue(original.formFields.all { it.pageIndex == 1 && it.rect != null })
        val filled = file("filled.pdf")
        engine.fillForm(source, filled, mapOf("name" to "Zoë García", "email" to "zoe@example.org", "plan" to "Build something useful\nShare it with friends"))
        val values = engine.inspect(filled).formFields.associate { it.name to it.value }
        assertEquals("Zoë García", values["name"])
        assertEquals("zoe@example.org", values["email"])
        assertEquals("Build something useful\nShare it with friends", values["plan"])
        assertTrue(engine.inspect(source).formFields.all { it.value.isEmpty() })
        val extracted = file("form-page.pdf")
        engine.arrangePages(filled, extracted, listOf(1))
        assertEquals(1, engine.inspect(extracted).pageCount)
        assertTrue(engine.inspect(extracted).formFields.isEmpty())
        assertTrue(engine.extractText(extracted, 0).contains("Zoë García"))
    }

    @Test fun pageOperationsPreserveTheSelectedOrderAndRotation() {
        val source = source("PAGE_ALPHA", "PAGE_BETA", "PAGE_GAMMA")
        val arranged = file("arranged.pdf")
        engine.arrangePages(source, arranged, listOf(2, 0), mapOf(2 to 90))
        val info = engine.inspect(arranged)
        assertEquals(2, info.pageCount)
        assertEquals(90, info.pages[0].rotation)
        assertTrue(engine.extractText(arranged, 0).contains("PAGE_GAMMA"))
        assertTrue(engine.extractText(arranged, 1).contains("PAGE_ALPHA"))
        assertFalse((engine.extractText(arranged, 0) + engine.extractText(arranged, 1)).contains("PAGE_BETA"))
        val merged = file("merged.pdf")
        engine.merge(listOf(arranged, source), merged)
        assertEquals(5, engine.inspect(merged).pageCount)
        assertTrue(engine.extractText(merged, 2).contains("PAGE_ALPHA"))
        assertTrue(engine.extractText(merged, 4).contains("PAGE_GAMMA"))
    }

    @Test fun permanentRedactionRebuildsEveryPageWithoutSourceTextOrHiddenObjects() {
        val source = source("PRIVATE-SECRET-4821", "OTHER-PAGE-SECRET-9372")
        val decorated = file("with-hidden-note.pdf")
        PDDocument.load(source).use { document ->
            document.documentInformation.author = "PRIVATE-AUTHOR-4821"
            val note = PDAnnotationText()
            note.contents = "PRIVATE-HIDDEN-NOTE-4821"
            note.rectangle = PDRectangle(300f, 300f, 40f, 40f)
            document.getPage(1).annotations.add(note)
            document.save(decorated)
        }
        val originalHash = digest(decorated)
        val out = file("redacted.pdf")
        engine.rasterExport(decorated, out, 1000, 85, mapOf(0 to listOf(NormalizedRect(.05f, .05f, .65f, .25f))))
        assertEquals(originalHash, digest(decorated))
        assertEquals(2, engine.inspect(out).pageCount)
        assertTrue(engine.extractText(out, 0).isBlank())
        assertTrue(engine.extractText(out, 1).isBlank())
        PDDocument.load(out).use { document ->
            assertNull(document.documentInformation.author)
            assertNull(document.documentCatalog.acroForm)
            assertNull(document.documentCatalog.names)
            for (page in document.pages) {
                assertTrue(page.annotations.isEmpty())
                assertFalse(page.resources.fontNames.iterator().hasNext())
                assertTrue(page.resources.xObjectNames.iterator().hasNext())
            }
        }
        val bitmap = engine.render(out, 0, 800)
        try { assertTrue(Color.red(pixel(bitmap, .35f, .15f)) < 15) }
        finally { bitmap.recycle() }
    }

    @Test fun cropUsesDisplayedCoordinatesForRotatedPages() {
        val source = source("CROP_SOURCE")
        val rotated = file("rotated.pdf")
        engine.arrangePages(source, rotated, listOf(0), mapOf(0 to 90))
        val before = engine.inspect(rotated).pages.first()
        val out = file("cropped.pdf")
        engine.cropPage(rotated, out, 0, NormalizedRect(.1f, .2f, .9f, .7f))
        val after = engine.inspect(out).pages.first()
        assertEquals(before.width * .8f, after.width, .01f)
        assertEquals(before.height * .5f, after.height, .01f)
        assertEquals(90, after.rotation)
    }

    @Test fun redactionAndCorrectionUseDisplayedCoordinatesAfterRotationAndCrop() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val source = file("sensitive-$rotation.pdf")
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(10f, 15f, 450f, 650f))
                page.cropBox = PDRectangle(30f, 40f, 400f, 600f)
                page.rotation = rotation
                document.addPage(page)
                document.save(source)
            }
            val marked = file("sensitive-marked-$rotation.pdf")
            engine.annotate(source, marked, 0, listOf(
                PageAnnotation.Text(.12f, .2f, "OLD-PRIVATE-TEXT", .04f),
                PageAnnotation.Ink(listOf(NormalizedPoint(.2f, .7f), NormalizedPoint(.8f, .7f)), .01f),
            ))
            val region = NormalizedRect(.1f, .18f, .85f, .36f)
            val redacted = file("redaction-$rotation.pdf")
            engine.rasterExport(marked, redacted, 900, 90, mapOf(0 to listOf(region)))
            val redactedBitmap = engine.render(redacted, 0, 900)
            try {
                assertTrue("Black area misplaced on rotation $rotation", Color.red(pixel(redactedBitmap, .5f, .26f)) < 15)
                assertTrue("Unselected visual content must survive", darkNear(redactedBitmap, .5f, .7f))
            } finally { redactedBitmap.recycle() }
            val corrected = file("correction-$rotation.pdf")
            engine.correctRegion(marked, corrected, 0, region, "Révisé approved")
            val text = engine.extractText(corrected, 0)
            assertTrue(text.contains("Révisé approved"))
            assertFalse(text.contains("OLD-PRIVATE-TEXT"))
            val correctedBitmap = engine.render(corrected, 0, 900)
            try {
                assertTrue("Replacement background must be white", Color.red(pixel(correctedBitmap, .7f, .3f)) > 245)
                assertTrue("Unselected visual content must survive correction", darkNear(correctedBitmap, .5f, .7f))
            } finally { correctedBitmap.recycle() }
            assertTrue(engine.extractText(marked, 0).contains("OLD-PRIVATE-TEXT"))
        }
    }

    @Test fun signedSourcesAreViewableButRefuseEditing() {
        val source = source("Signed source marker")
        val signed = file("signature-marker.pdf")
        PDDocument.load(source).use { document ->
            val form = PDAcroForm(document)
            document.documentCatalog.acroForm = form
            val field = PDSignatureField(form)
            field.partialName = "approval"
            field.setValue(PDSignature())
            form.fields.add(field)
            document.save(signed)
        }
        assertTrue(engine.inspect(signed).signed)
        val before = digest(signed)
        try {
            engine.annotate(signed, file("invalid-edit.pdf"), 0, listOf(PageAnnotation.Text(.1f, .2f, "Do not modify")))
            fail("A signature field with a signature dictionary must prevent accidental modification.")
        } catch (expected: PdfException) { assertTrue(expected.message.orEmpty().contains("signature", true)) }
        assertEquals(before, digest(signed))
        assertFalse(file("invalid-edit.pdf").exists())
    }

    @Test fun metadataCleanupAndPasswordProtectionAreReal() {
        val source = source("Password test")
        val tagged = file("tagged.pdf")
        PDDocument.load(source).use { document ->
            document.documentInformation.author = "Private author"
            document.documentInformation.title = "Private title"
            document.save(tagged)
        }
        val clean = file("clean.pdf")
        engine.sanitizeMetadata(tagged, clean)
        PDDocument.load(clean).use { document ->
            assertNull(document.documentInformation.author)
            assertNull(document.documentInformation.title)
        }
        val protected = file("protected.pdf")
        engine.protect(clean, protected, "A good password 4821")
        try { engine.inspect(protected); fail("Opening without the password must fail.") }
        catch (expected: PdfException) { assertTrue(expected.message.orEmpty().contains("password", true)) }
        PDDocument.load(protected, "A good password 4821").use { document -> assertTrue(document.isEncrypted) }
        assertTrue(engine.extractText(clean, 0).contains("Password test"))
    }

    @Test fun imageImportAppliesExifOrientation() {
        val photo = file("photo.jpg")
        val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(photo.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val out = file("photo.pdf")
        engine.imagesToPdf(listOf(Uri.fromFile(photo)), out)
        val page = engine.inspect(out).pages.first()
        assertTrue("EXIF rotation makes the imported page portrait", page.height > page.width)
        val rendered = engine.render(out, 0, 500)
        try { assertTrue(Color.red(pixel(rendered, .5f, .5f)) > 220) }
        finally { rendered.recycle() }
    }

    @Test fun compressionReportsActualResultAndKeepsSource() {
        val source = source("Keep my original text")
        val originalHash = digest(source)
        val out = file("small.pdf")
        val result = engine.compressToTarget(source, out, 250_000)
        assertEquals(out.length(), result.bytes)
        assertEquals(out.length() <= 250_000, result.targetMet)
        assertTrue(out.length() > 0)
        assertEquals(originalHash, digest(source))
        assertTrue(engine.extractText(out, 0).isBlank())
        assertNotEquals(digest(source), digest(out))
    }

    @Test fun bundledOcrCreatesSearchableTextOnImageOnlyPages() = runBlocking {
        val source = source("OFFLINE TEXT RECOGNITION")
        val imageOnly = file("scan.pdf")
        engine.rasterExport(source, imageOnly, 1600, 95)
        assertTrue(engine.extractText(imageOnly, 0).isBlank())
        val recognized = engine.recognize(imageOnly, 0)
        assertTrue(recognized.text.contains("OFFLINE", ignoreCase = true))
        assertTrue(recognized.lines.isNotEmpty())
        val searchable = file("searchable.pdf")
        assertEquals(1, engine.searchableOcr(imageOnly, searchable))
        assertTrue(engine.search(searchable, "OFFLINE").isNotEmpty())
        val before = engine.render(imageOnly, 0, 900)
        val after = engine.render(searchable, 0, 900)
        try { assertTrue("Invisible OCR must not change page appearance", before.sameAs(after)) }
        finally { before.recycle(); after.recycle() }
    }

    private fun file(name: String) = File(directory, name)

    private fun source(vararg texts: String): File {
        val result = file("source-${UUID.randomUUID()}.pdf")
        PDDocument().use { document ->
            val font = context.assets.open("fonts/Inter-Regular.ttf").use { PDType0Font.load(document, it) }
            texts.forEach { text ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText(); stream.setFont(font, 20f); stream.newLineAtOffset(40f, 740f); stream.showText(text); stream.endText()
                }
            }
            document.save(result)
        }
        return result
    }

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun pixel(bitmap: Bitmap, x: Float, y: Float): Int = bitmap.getPixel((x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1), (y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1))

    private fun darkNear(bitmap: Bitmap, x: Float, y: Float): Boolean {
        val px = (x * bitmap.width).toInt()
        val py = (y * bitmap.height).toInt()
        return (-4..4).any { dy -> (-4..4).any { dx ->
            val color = bitmap.getPixel((px + dx).coerceIn(0, bitmap.width - 1), (py + dy).coerceIn(0, bitmap.height - 1))
            Color.red(color) < 100 && Color.green(color) < 100 && Color.blue(color) < 100
        } }
    }
}
