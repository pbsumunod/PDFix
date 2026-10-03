package com.sumclartech.paperlane

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sumclartech.paperlane.pdf.EditableTextRun
import com.sumclartech.paperlane.pdf.NormalizedRect
import com.sumclartech.paperlane.pdf.PdfEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Independent PDF fixtures verify observable document fidelity, not the editor's token algorithm. */
@RunWith(AndroidJUnit4::class)
class NativeTextEditorTest {
    private lateinit var context: Context
    private lateinit var engine: PdfEngine
    private lateinit var directory: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = PdfEngine(context)
        directory = File(context.cacheDir, "native-edit-test-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun replacementRemovesOldTextAndPreservesFontColorVectorsImageAndInteractiveForm() {
        val source = richFixture("source.pdf")
        val expected = richFixture("expected.pdf", visibleText = "DRAFT")
        val sourceHash = digest(source.readBytes())
        val sourceObjects = resourceFingerprints(source)
        val sourceForm = engine.inspect(source).formFields
        val run = editable(source, "INVOICE 2026")
        val out = file("edited.pdf")

        engine.replaceExistingText(source, out, 0, run, "DRAFT")

        val extracted = engine.extractText(out, 0)
        assertFalse("Old words must be removed, not covered", extracted.contains("INVOICE 2026"))
        assertTrue(extracted.contains("DRAFT"))
        assertTrue(extracted.contains("TAIL"))
        assertFalse("Old glyph codes must be absent from text-showing operands", shownText(out).contains("INVOICE 2026"))
        val rewritten = editable(out, "DRAFT")
        assertEquals(run.fontName, rewritten.fontName)
        assertEquals(run.fontSize, rewritten.fontSize, .001f)
        assertEquals("Existing fonts and image streams must remain intact", sourceObjects, resourceFingerprints(out))
        assertEquals("Forms must stay interactive and retain values", sourceForm, engine.inspect(out).formFields)
        for (operator in listOf("re", "f", "S", "Do")) {
            assertEquals("Vector/image operator $operator changed", operators(source).count { it == operator }, operators(out).count { it == operator })
        }
        assertGlyphPositionsEqual(glyphsFor(source, "TAIL"), glyphsFor(out, "TAIL"))
        assertGlyphPositionsEqual(glyphsFor(expected, "DRAFT"), glyphsFor(out, "DRAFT"))
        assertRenderedLike(expected, out)
        assertEquals("Source bytes must remain untouched", sourceHash, digest(source.readBytes()))
    }

    @Test fun tjKerningAndTextSpacingKeepTheFollowingTextAtItsOriginalPosition() {
        for (withSpacing in listOf(false, true)) {
            val source = richFixture("tj-$withSpacing.pdf", kerned = true, spaced = withSpacing)
            val expected = richFixture("tj-expected-$withSpacing.pdf", visibleText = "VOID", kerned = true, spaced = withSpacing)
            val run = editable(source, "INVOICE 2026")
            val out = file("tj-edited-$withSpacing.pdf")
            engine.replaceExistingText(source, out, 0, run, "VOID")
            assertFalse(shownText(out).contains("INVOICE 2026"))
            assertTrue(engine.extractText(out, 0).contains("VOID"))
            assertGlyphPositionsEqual(glyphsFor(source, "TAIL"), glyphsFor(out, "TAIL"))
            assertGlyphPositionsEqual(glyphsFor(expected, "VOID"), glyphsFor(out, "VOID"))
            assertEquals(resourceFingerprints(source), resourceFingerprints(out))
            assertRenderedLike(expected, out)
        }
    }

    @Test fun emptyReplacementDeletesGlyphsWithoutMovingFollowingText() {
        val source = richFixture("delete-source.pdf")
        val expected = richFixture("delete-expected.pdf", visibleText = "")
        val out = file("delete-edited.pdf")
        engine.replaceExistingText(source, out, 0, editable(source, "INVOICE 2026"), "")
        assertFalse(shownText(out).contains("INVOICE 2026"))
        assertTrue(engine.extractText(out, 0).contains("TAIL"))
        assertGlyphPositionsEqual(glyphsFor(source, "TAIL"), glyphsFor(out, "TAIL"))
        assertRenderedLike(expected, out)
    }

    @Test fun leadingTjOffsetsAndSingleByteWordSpacingRetainBaselineAndFollowingText() {
        for (leading in listOf(-120f, 120f)) {
            val source = richFixture("leading-$leading.pdf", kerned = true, spaced = true, simpleFont = true, leadingKerning = leading)
            val expected = richFixture("leading-expected-$leading.pdf", visibleText = "VOID", kerned = true, spaced = true, simpleFont = true, leadingKerning = leading)
            val out = file("leading-edited-$leading.pdf")
            engine.replaceExistingText(source, out, 0, editable(source, "INVOICE 2026"), "VOID")
            assertFalse(shownText(out).contains("INVOICE 2026"))
            assertGlyphPositionsEqual(glyphsFor(source, "TAIL"), glyphsFor(out, "TAIL"))
            assertGlyphPositionsEqual(glyphsFor(expected, "VOID"), glyphsFor(out, "VOID"))
            assertRenderedLike(expected, out)
        }
    }

    @Test fun subsetFontsReuseAvailableGlyphsAndRefuseMissingGlyphsWithoutProducingAnOutput() {
        val source = simpleFixture("subset.pdf", text = "ABBA", subset = true)
        val run = editable(source, "ABBA")
        PDDocument.load(source).use { document ->
            val resources = document.getPage(0).resources
            assertTrue("Fixture must actually contain a subset font", resources.fontNames.any { resources.getFont(it).name.contains('+') })
        }
        val out = file("subset-edited.pdf")
        engine.replaceExistingText(source, out, 0, run, "BA")
        assertEquals("BA", engine.extractText(out, 0).trim())
        assertEquals(resourceFingerprints(source), resourceFingerprints(out))
        val sourceHash = digest(source.readBytes())
        val refused = file("missing-glyph.pdf")
        expectRejected { engine.replaceExistingText(source, refused, 0, run, "Z") }
        assertFalse(refused.exists())
        assertEquals(sourceHash, digest(source.readBytes()))
    }

    @Test fun oversizedReplacementsAndStaleSelectionsAreRejectedBeforeSaving() {
        val source = simpleFixture("bounded.pdf", text = "SHORT")
        val run = editable(source, "SHORT")
        val refused = file("too-wide.pdf")
        expectRejected {
            engine.replaceExistingText(source, refused, 0, run, "THIS REPLACEMENT IS MUCH LONGER THAN THE ORIGINAL")
        }
        assertFalse(refused.exists())
        val edited = file("shortened.pdf")
        engine.replaceExistingText(source, edited, 0, run, "GO")
        val stale = file("stale.pdf")
        expectRejected { engine.replaceExistingText(edited, stale, 0, run, "NO") }
        assertFalse(stale.exists())
        assertEquals("SHORT", engine.extractText(source, 0).trim())
        assertEquals("GO", engine.extractText(edited, 0).trim())
    }

    @Test fun cropOriginsAndEveryPageRotationHaveAccurateSelectionsAndUnchangedTextPlacement() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val source = simpleFixture("rotated-$rotation.pdf", "ROTATE", rotation = rotation, crop = true)
            val expected = simpleFixture("rotated-expected-$rotation.pdf", "RATE", rotation = rotation, crop = true)
            val run = editable(source, "ROTATE")
            val bitmap = engine.render(source, 0, 1000)
            try {
                val ink = redInkBounds(bitmap)
                assertNotNull("The fixture should contain visible red glyphs", ink)
                assertEquals("Selection left, rotation $rotation", ink!!.left, run.rect.left, .035f)
                assertEquals("Selection top, rotation $rotation", ink.top, run.rect.top, .035f)
                assertEquals("Selection right, rotation $rotation", ink.right, run.rect.right, .035f)
                assertEquals("Selection bottom, rotation $rotation", ink.bottom, run.rect.bottom, .035f)
            } finally { bitmap.recycle() }
            val out = file("rotated-edited-$rotation.pdf")
            engine.replaceExistingText(source, out, 0, run, "RATE")
            assertEquals(rotation, engine.inspect(out).pages.first().rotation)
            assertEquals(engine.inspect(source).pages, engine.inspect(out).pages)
            assertRenderedLike(expected, out)
            assertFalse(shownText(out).contains("ROTATE"))
        }
    }

    @Test fun sharedPageStreamsAreIsolatedAndWrongPageSelectionsAreRejected() {
        val single = simpleFixture("shared-base.pdf", "SHARED")
        val source = file("shared-pages.pdf")
        PDDocument.load(single).use { document ->
            val first = document.getPage(0)
            val second = PDPage(PDRectangle.A4)
            second.resources = first.resources
            second.cosObject.setItem(COSName.CONTENTS, first.cosObject.getDictionaryObject(COSName.CONTENTS))
            document.addPage(second)
            document.save(source)
        }
        val run = editable(source, "SHARED")
        val wrongPage = file("wrong-page.pdf")
        expectRejected { engine.replaceExistingText(source, wrongPage, 1, run, "EDIT") }
        assertFalse(wrongPage.exists())
        val out = file("shared-edited.pdf")
        engine.replaceExistingText(source, out, 0, run, "EDIT")
        assertEquals("EDIT", engine.extractText(out, 0).trim())
        assertEquals("SHARED", engine.extractText(out, 1).trim())
        assertEquals("SHARED", engine.extractText(source, 0).trim())
        assertEquals("SHARED", engine.extractText(source, 1).trim())
    }

    @Test fun signedAndEncryptedSourcesRefuseListingAndReplacement() {
        val source = simpleFixture("permissions-base.pdf", "PROTECTED")
        val run = editable(source, "PROTECTED")
        val signed = file("signed.pdf")
        PDDocument.load(source).use { document ->
            val form = PDAcroForm(document)
            document.documentCatalog.acroForm = form
            val field = PDSignatureField(form)
            field.partialName = "approval"
            field.setValue(PDSignature())
            form.fields.add(field)
            document.save(signed)
        }
        val encrypted = file("encrypted.pdf")
        PDDocument.load(source).use { document ->
            val policy = StandardProtectionPolicy("owner-secret", "", AccessPermission())
            policy.encryptionKeyLength = 128
            policy.isPreferAES = true
            document.protect(policy)
            document.save(encrypted)
        }
        for (input in listOf(signed, encrypted)) {
            val before = digest(input.readBytes())
            expectRejected { engine.editableText(input, 0) }
            val out = file("refused-${input.name}")
            expectRejected { engine.replaceExistingText(input, out, 0, run, "EDIT") }
            assertFalse(out.exists())
            assertEquals(before, digest(input.readBytes()))
        }
    }

    @Test fun clippingIntroducedByEitherQuoteOperatorMakesFollowingTextReadOnly() {
        for (doubleQuote in listOf(false, true)) {
            val source = file("quote-clipping-$doubleQuote.pdf")
            PDDocument().use { document ->
                val font = font(document)
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                val clipHex = font.encode("CLIP").joinToString("") { "%02X".format(it) }
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText(); stream.setFont(font, 20f); stream.newLineAtOffset(50f, 750f)
                    stream.setLeading(60f)
                    // These operators combine line movement with text showing; the resulting text
                    // clipping region still affects subsequent ordinary Tj content after ET.
                    stream.appendRawCommands("7 Tr\n" + if (doubleQuote) "0 0 <$clipHex> \"\n" else "<$clipHex> '\n")
                    stream.endText()
                    stream.beginText(); stream.setFont(font, 20f)
                    stream.appendRawCommands("0 Tr\n")
                    stream.newLineAtOffset(50f, 690f); stream.showText("FOLLOWING"); stream.endText()
                }
                document.save(source)
            }
            val run = engine.editableText(source, 0).firstOrNull { it.text == "FOLLOWING" }
            assertNotNull("Ordinary following text should explain its unsupported clipping state", run)
            assertFalse("Prior quote-operator clipping must prevent unsafe editing", run!!.editable)
            assertFalse(run.reason.isNullOrBlank())
            val out = file("quote-clipping-refused-$doubleQuote.pdf")
            val originalHash = digest(source.readBytes())
            expectRejected { engine.replaceExistingText(source, out, 0, run, "FLOW") }
            assertFalse(out.exists())
            assertEquals(originalHash, digest(source.readBytes()))
        }
    }

    @Test fun largeInteriorTjSpacingIsNotSilentlyRemovedWhenReplacingText() {
        val source = file("interior-gap.pdf")
        PDDocument().use { document ->
            val font = font(document)
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText(); stream.setFont(font, 20f); stream.newLineAtOffset(50f, 700f)
                stream.showTextWithPositioning(arrayOf<Any>("Hello", -300f, "world"))
                stream.endText()
            }
            document.save(source)
        }
        val run = engine.editableText(source, 0).firstOrNull { it.text == "Helloworld" }
        assertNotNull(run)
        assertFalse("An intentional interior gap cannot be treated as ordinary kerning", run!!.editable)
        assertFalse(run.reason.isNullOrBlank())
        val originalHash = digest(source.readBytes())
        val out = file("gap-refused.pdf")
        expectRejected { engine.replaceExistingText(source, out, 0, run, "Hello") }
        assertFalse(out.exists())
        assertEquals(originalHash, digest(source.readBytes()))
    }

    @Test fun unicodeMappingAloneDoesNotMakeAMissingCidGlyphSafeToInsert() {
        val base = file("cid-base.pdf")
        var zCode = -1
        PDDocument().use { document ->
            val font = font(document)
            zCode = font.readCode(ByteArrayInputStream(font.encode("Z")))
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText(); stream.setFont(font, 20f); stream.newLineAtOffset(50f, 700f); stream.showText("ABBA"); stream.endText()
            }
            document.save(base)
        }
        assertTrue("The fixture uses a real nonzero CID", zCode > 0)
        val source = file("missing-cid-glyph.pdf")
        PDDocument.load(base).use { document ->
            val resources = document.getPage(0).resources
            val font = resources.getFont(resources.fontNames.first()) as PDType0Font
            assertEquals("Z", font.toUnicode(zCode))
            val mapping = ByteArray(65536 * 2)
            for (cid in 0..65535) {
                mapping[cid * 2] = (cid ushr 8).toByte()
                mapping[cid * 2 + 1] = cid.toByte()
            }
            mapping[zCode * 2] = 0; mapping[zCode * 2 + 1] = 0
            val stream = PDStream(document)
            stream.createOutputStream(COSName.FLATE_DECODE).use { it.write(mapping) }
            font.descendantFont.cosObject.setItem(COSName.getPDFName("CIDToGIDMap"), stream)
            document.save(source)
        }
        PDDocument.load(source).use { document ->
            val resources = document.getPage(0).resources
            val font = resources.getFont(resources.fontNames.first()) as PDType0Font
            assertEquals("The deliberately misleading Unicode mapping survives", "Z", font.toUnicode(zCode))
            assertFalse("Its glyph must actually map to .notdef", font.hasGlyph(zCode))
        }
        val run = editable(source, "ABBA")
        val originalHash = digest(source.readBytes())
        val out = file("missing-cid-refused.pdf")
        expectRejected { engine.replaceExistingText(source, out, 0, run, "Z") }
        assertFalse(out.exists())
        assertEquals(originalHash, digest(source.readBytes()))
        assertEquals("ABBA", engine.extractText(source, 0).trim())
    }

    private fun editable(file: File, text: String): EditableTextRun {
        val candidates = engine.editableText(file, 0)
        val run = candidates.firstOrNull { it.text == text }
        assertNotNull("No text operator found for '$text': ${candidates.map { it.text }}", run)
        assertTrue("The ordinary text fixture should be editable: ${run!!.reason}", run.editable)
        return run
    }

    private fun file(name: String) = File(directory, name)

    private fun font(document: PDDocument, subset: Boolean = false) =
        context.assets.open("fonts/Inter-Regular.ttf").use { PDType0Font.load(document, it, subset) }

    /** The expected image is built independently, with the following text positioned explicitly. */
    private fun richFixture(name: String, visibleText: String? = null, kerned: Boolean = false, spaced: Boolean = false, simpleFont: Boolean = false, leadingKerning: Float = 0f): File {
        val result = file(name)
        PDDocument().use { document ->
            val font: PDFont = if (simpleFont) PDType1Font.HELVETICA else font(document)
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val size = 20f
            val original = "INVOICE 2026"
            val charSpacing = if (spaced) .8f else 0f
            val scale = if (spaced) .85f else 1f
            val numericKerning = if (kerned) leadingKerning + 80f - 35f else 0f
            val wordSpacing = if (spaced) 3f else 0f
            // Only simple, one-byte space code 32 receives Tw; Identity-H is two-byte.
            val wordAdvance = if (simpleFont) original.count { it == ' ' } * wordSpacing else 0f
            val advance = (font.getStringWidth(original) / 1000f * size + original.length * charSpacing + wordAdvance - numericKerning / 1000f * size) * scale
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(24 * 24) { index -> if ((index % 24 / 6 + index / 24 / 6) % 2 == 0) Color.BLUE else Color.GREEN }
            bitmap.setPixels(pixels, 0, 24, 0, 0, 24, 24)
            val image = JPEGFactory.createFromImage(document, bitmap, .95f)
            bitmap.recycle()
            PDPageContentStream(document, page).use { stream ->
                for (index in 0 until 32) {
                    if (index % 2 == 0) stream.setNonStrokingColor(235, 205, 130) else stream.setNonStrokingColor(110, 190, 190)
                    stream.addRect(40f + index * 10f, 680f, 10f, 56f)
                    stream.fill()
                }
                stream.setStrokingColor(30, 100, 55)
                stream.setLineWidth(3f)
                stream.moveTo(40f, 620f); stream.lineTo(330f, 590f); stream.stroke()
                stream.drawImage(image, 410f, 610f, 100f, 100f)
                stream.setNonStrokingColor(190, 25, 35)
                stream.beginText()
                stream.setFont(font, size)
                stream.setCharacterSpacing(charSpacing)
                stream.setWordSpacing(wordSpacing)
                stream.setHorizontalScaling(scale * 100)
                val initialOffset = if (visibleText != null && kerned) -leadingKerning / 1000f * size * scale else 0f
                stream.newLineAtOffset(55f + initialOffset, 700f)
                if (visibleText != null) stream.showText(visibleText)
                else if (kerned) stream.showTextWithPositioning(arrayOf<Any>(leadingKerning, "IN", 80f, "VO", -35f, "ICE 2026"))
                else stream.showText(original)
                if (visibleText != null) stream.setTextMatrix(Matrix.getTranslateInstance(55f + advance, 700f))
                stream.showText("  TAIL")
                stream.endText()
            }
            val form = PDAcroForm(document)
            document.documentCatalog.acroForm = form
            form.defaultResources = PDResources()
            val fontName = form.defaultResources.add(font).name
            form.defaultAppearance = "/$fontName 12 Tf 0 g"
            form.setNeedAppearances(false)
            val field = PDTextField(form)
            field.partialName = "contact"
            field.alternateFieldName = "Contact"
            field.defaultAppearance = form.defaultAppearance
            field.widgets.first().apply { rectangle = PDRectangle(40f, 500f, 220f, 30f); this.page = page; isPrinted = true }
            form.fields.add(field)
            page.annotations.add(field.widgets.first())
            field.value = "Sam"
            document.save(result)
        }
        return result
    }

    private fun simpleFixture(name: String, text: String, subset: Boolean = false, rotation: Int = 0, crop: Boolean = false): File {
        val result = file(name)
        PDDocument().use { document ->
            val font = font(document, subset)
            val page = if (crop) PDPage(PDRectangle(10f, 15f, 450f, 650f)) else PDPage(PDRectangle.A4)
            if (crop) page.cropBox = PDRectangle(30f, 40f, 400f, 600f)
            page.rotation = rotation
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.setNonStrokingColor(190, 25, 35)
                stream.beginText(); stream.setFont(font, 20f); stream.newLineAtOffset(55f, 540f); stream.showText(text); stream.endText()
            }
            document.save(result)
        }
        return result
    }

    private fun operators(file: File): List<String> = PDDocument.load(file).use { document ->
        val parser = PDFStreamParser(document.getPage(0))
        parser.parse()
        parser.tokens.filterIsInstance<Operator>().map { it.name }
    }

    /** Decodes only operands that actually occur in page content, using the active Tf font. */
    private fun shownText(file: File): String = PDDocument.load(file).use { document ->
        val page = document.getPage(0)
        val parser = PDFStreamParser(page)
        parser.parse()
        val operands = mutableListOf<Any>()
        var activeFont: PDFont? = null
        buildString {
            for (token in parser.tokens) {
                if (token is Operator) {
                    if (token.name == "Tf") activeFont = page.resources.getFont(operands.first() as COSName)
                    if (token.name == "Tj" || token.name == "TJ") {
                        val strings = operands.flatMap { operand -> when (operand) {
                            is COSString -> listOf(operand)
                            is COSArray -> operand.toList().filterIsInstance<COSString>()
                            else -> emptyList()
                        } }
                        strings.forEach { value ->
                            ByteArrayInputStream(value.bytes).use { input ->
                                while (input.available() > 0) {
                                    val code = requireNotNull(activeFont).readCode(input)
                                    append(activeFont!!.toUnicode(code).orEmpty())
                                }
                            }
                        }
                    }
                    operands.clear()
                } else operands.add(token)
            }
        }
    }

    private data class Glyph(val unicode: String, val x: Float, val y: Float, val width: Float)

    private fun glyphsFor(file: File, word: String): List<Glyph> = PDDocument.load(file).use { document ->
        val glyphs = mutableListOf<Glyph>()
        val stripper = object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                glyphs.add(Glyph(text.unicode, text.xDirAdj, text.yDirAdj, text.widthDirAdj))
                super.processTextPosition(text)
            }
        }
        stripper.getText(document)
        glyphs.windowed(word.length).firstOrNull { chars -> chars.joinToString("") { it.unicode } == word }
            ?: error("Missing expected surrounding word $word")
    }

    private fun assertGlyphPositionsEqual(before: List<Glyph>, after: List<Glyph>) {
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (old, new) ->
            assertEquals(old.unicode, new.unicode)
            assertEquals("Following glyph moved horizontally", old.x, new.x, .02f)
            assertEquals("Following glyph moved vertically", old.y, new.y, .02f)
            assertEquals("Following glyph was resized", old.width, new.width, .02f)
        }
    }

    private fun resourceFingerprints(file: File): Map<String, String> = PDDocument.load(file).use { document ->
        val resources = document.getPage(0).resources
        buildMap {
            resources.fontNames.forEach { name ->
                val font = resources.getFont(name)
                put("font:${name.name}:name", font.name)
                font.fontDescriptor?.fontFile2?.let { put("font:${name.name}:program", digest(it.toByteArray())) }
            }
            resources.xObjectNames.forEach { name ->
                val objectStream = resources.getXObject(name).cosObject
                put("object:${name.name}", objectStream.createInputStream().use { digest(it.readBytes()) })
            }
        }
    }

    private fun assertRenderedLike(expected: File, actual: File) {
        val before = engine.render(expected, 0, 1000)
        val after = engine.render(actual, 0, 1000)
        try {
            assertEquals(before.width, after.width)
            assertEquals(before.height, after.height)
            val first = IntArray(before.width * before.height)
            val second = IntArray(first.size)
            before.getPixels(first, 0, before.width, 0, 0, before.width, before.height)
            after.getPixels(second, 0, after.width, 0, 0, after.width, after.height)
            var materiallyDifferent = 0
            first.indices.forEach { index ->
                val a = first[index]; val b = second[index]
                if (abs(Color.red(a) - Color.red(b)) > 12 || abs(Color.green(a) - Color.green(b)) > 12 || abs(Color.blue(a) - Color.blue(b)) > 12) materiallyDifferent++
            }
            assertTrue("Unexpected changes to text color/layout or colored background: $materiallyDifferent pixels", materiallyDifferent <= first.size / 1000)
        } finally { before.recycle(); after.recycle() }
    }

    private fun redInkBounds(bitmap: Bitmap): NormalizedRect? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
        pixels.indices.forEach { index ->
            val color = pixels[index]
            if (Color.red(color) > 130 && Color.green(color) < 95 && Color.blue(color) < 95) {
                val x = index % bitmap.width; val y = index / bitmap.width
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        return if (right <= left || bottom <= top) null else NormalizedRect(left.toFloat() / bitmap.width, top.toFloat() / bitmap.height, (right + 1f) / bitmap.width, (bottom + 1f) / bitmap.height)
    }

    private fun expectRejected(block: () -> Unit) {
        try { block(); fail("This edit must be rejected without creating an output.") }
        catch (expected: Exception) { assertFalse("Failures should explain why the edit is unavailable", expected.message.isNullOrBlank()) }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
