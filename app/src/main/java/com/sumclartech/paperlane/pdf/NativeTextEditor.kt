package com.sumclartech.paperlane.pdf

import com.tom_roush.pdfbox.contentstream.PDContentStream
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.contentstream.operator.text.*
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDSimpleFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.font.PDVectorFont
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.Bidi
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative, original-content editing, independent of the raster correction tool.
 *
 * A run is one top-level Tj/TJ operation. All graphics/font operators and resources remain intact.
 * A replacement uses that SAME font and compensates its final text advance with TJ, so subsequent
 * operations retain their positions. We never change font size, cover content, or flatten a page.
 * Form XObjects are deliberately not entered: modifying a shared form can affect other appearances.
 */
internal object NativeTextEditor {
    private const val MAX_STREAM_BYTES = 16 * 1024 * 1024
    private const val MAX_SCAN_BYTES = 48 * 1024 * 1024
    private const val MAX_TOKENS = 250_000
    private const val MAX_GLYPHS = 100_000
    private const val MAX_RUNS = 4_000
    private const val MAX_CODES = 20_000
    private const val EPSILON = .001f

    fun read(document: PDDocument, file: File, pageIndex: Int): List<EditableTextRun> {
        val page = document.getPage(pageIndex)
        val snapshot = analyze(page, content(page), fingerprint(file), pageIndex, hasReplacementText(document), CodeBook())
        return snapshot.runs.map { it.public }
    }

    fun replace(document: PDDocument, file: File, pageIndex: Int, selection: EditableTextRun, replacement: String) {
        require(replacement.length <= 2_000) { "Edit a shorter section, up to 2000 characters." }
        unsupportedText(replacement)?.let { throw PdfException(it) }
        val hash = fingerprint(file)
        if (!selection.id.startsWith("$hash:$pageIndex:")) {
            throw PdfException("This text selection is from an older revision. Select the text again.")
        }
        val page = document.getPage(pageIndex)
        val bytes = content(page)
        val book = CodeBook()
        val snapshot = analyze(page, bytes, hash, pageIndex, hasReplacementText(document), book)
        val run = snapshot.runs.firstOrNull { it.public.id == selection.id && it.public.text == selection.text }
            ?: throw PdfException("This text has changed. Select it again.")
        if (!run.public.editable) throw PdfException(run.public.reason ?: "This text cannot be edited while preserving its appearance.")

        // Codes in the selected run take precedence when a font maps multiple glyphs to one character.
        val preferred = run.glyphs.filter { it.unicode != null }.associate { it.unicode!! to it.bytes }
        var encoded = encode(run.font, replacement, preferred, book)
        if (encoded == null) {
            // An embedded subset may contain a needed character used elsewhere in this document.
            // Learn existing raw codes without mutating a font, creating a substitute, or guessing GIDs.
            var total = bytes.size
            for (index in 0 until document.numberOfPages) {
                if (index == pageIndex) continue
                val other = document.getPage(index)
                val remaining = MAX_SCAN_BYTES - total
                if (remaining <= 0) break
                val more = try { content(other, min(MAX_STREAM_BYTES, remaining)) } catch (_: PdfException) { continue }
                total += more.size
                analyze(other, more, hash, index, false, book, collectRuns = false)
                encoded = encode(run.font, replacement, preferred, book)
                if (encoded != null) break
            }
        }
        val newBytes = encoded ?: throw PdfException(
            "The original font does not contain one or more replacement characters. Use characters already available in this font. No font was substituted."
        )
        val glyphs = decode(run.font, newBytes)
        var advance = 0f
        var visibleRight = 0f
        glyphs.forEach { glyph ->
            visibleRight = max(visibleRight, advance + glyph.width * run.fontSize * run.horizontalScale)
            advance += run.advance(glyph)
        }
        if (!advance.isFinite() || !visibleRight.isFinite()) throw PdfException("This font has unsupported text measurements.")
        val room = run.visibleRight - run.firstOffset
        if (visibleRight > room + .02f) {
            throw PdfException("The new text is too wide for the original space. Shorten it to keep the original font and surrounding text in place.")
        }

        val divisor = run.fontSize * run.horizontalScale
        val replacementArray = COSArray()
        // TJ can begin with an adjustment; keep the first glyph at its original baseline position.
        if (abs(run.firstOffset) > EPSILON) replacementArray.add(COSFloat(-run.firstOffset * 1000f / divisor))
        if (newBytes.isNotEmpty()) replacementArray.add(COSString(newBytes))
        val remainingAdvance = run.totalAdvance - run.firstOffset - advance
        if (abs(remainingAdvance) > EPSILON || replacementArray.size() == 0) {
            replacementArray.add(COSFloat(-remainingAdvance * 1000f / divisor))
        }
        val operation = snapshot.operations[run.operation]
        val rewritten = ArrayList<Any>(snapshot.tokens.size + 1)
        rewritten.addAll(snapshot.tokens.subList(0, operation.start))
        rewritten.add(replacementArray)
        rewritten.add(Operator.getOperator("TJ"))
        rewritten.addAll(snapshot.tokens.subList(operation.end + 1, snapshot.tokens.size))

        // Copy on write: /Contents can be an array and its streams may be shared with other pages.
        // Never modify any existing COSStream or shared /Resources dictionary.
        val newStream = PDStream(document)
        newStream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(rewritten) }
        page.setContents(newStream)
    }

    private data class Operation(val start: Int, val end: Int, val name: String)
    private data class Snapshot(val tokens: List<Any>, val operations: List<Operation>, val runs: List<Run>)
    private data class Glyph(val bytes: ByteArray, val code: Int, val unicode: String?, val width: Float)
    private data class Run(
        val public: EditableTextRun,
        val operation: Int,
        val font: PDFont,
        val fontSize: Float,
        val horizontalScale: Float,
        val charSpacing: Float,
        val wordSpacing: Float,
        val firstOffset: Float,
        val visibleRight: Float,
        val totalAdvance: Float,
        val glyphs: List<Glyph>,
    ) {
        fun advance(glyph: Glyph): Float = (glyph.width * fontSize + charSpacing +
            if (glyph.bytes.size == 1 && glyph.code == 32) wordSpacing else 0f) * horizontalScale
    }

    private class CodeBook {
        private val fonts = IdentityHashMap<COSDictionary, MutableMap<String, ByteArray>>()
        private val glyphAvailability = IdentityHashMap<COSDictionary, MutableMap<Int, Boolean>>()
        private var count = 0
        fun add(font: PDFont, glyph: Glyph) {
            val unicode = glyph.unicode ?: return
            if (unicode.isEmpty() || unicode.length > 8 || glyph.code == 0 || count >= MAX_CODES) return
            val codes = fonts.getOrPut(font.cosObject) { mutableMapOf() }
            if (!codes.containsKey(unicode)) { codes[unicode] = glyph.bytes; count++ }
        }
        fun code(font: PDFont, unicode: String): ByteArray? = fonts[font.cosObject]?.get(unicode)
        fun available(font: PDFont, code: Int): Boolean = glyphAvailability.getOrPut(font.cosObject) { mutableMapOf() }
            .getOrPut(code) { hasGlyph(font, code) }
    }

    private fun analyze(page: PDPage, bytes: ByteArray, hash: String, pageIndex: Int,
                        semanticReplacement: Boolean, book: CodeBook, collectRuns: Boolean = true): Snapshot {
        val parser = PDFStreamParser(bytes)
        val tokens = ArrayList<Any>()
        val operations = ArrayList<Operation>()
        var start = 0
        while (true) {
            val token = parser.parseNextToken() ?: break
            if (tokens.size >= MAX_TOKENS) throw PdfException("This page is too complex for original text editing.")
            tokens.add(token)
            if (token is Operator) {
                operations.add(Operation(start, tokens.lastIndex, token.name))
                start = tokens.size
            }
        }
        if (start != tokens.size) throw PdfException("This page contains an incomplete PDF operation.")
        val scanner = Scanner(page, hash, pageIndex, semanticReplacement, book, collectRuns)
        scanner.scan(bytes)
        if (scanner.operatorCount != operations.size) throw PdfException("This page's text structure could not be verified.")
        return Snapshot(tokens, operations, scanner.runs)
    }

    private class Scanner(
        private val page: PDPage,
        private val hash: String,
        private val pageIndex: Int,
        private val semanticReplacement: Boolean,
        private val book: CodeBook,
        private val collectRuns: Boolean,
    ) : PDFStreamEngine() {
        val runs = mutableListOf<Run>()
        var operatorCount = 0; private set
        private var depth = 0
        private var glyphCount = 0
        private var clipped = false
        // PDF text clipping is committed by ET, independently of q/Q inside the text object.
        private var textClipPending = false
        private val clipStack = mutableListOf<Boolean>()
        private val markedContent = mutableListOf<Boolean>()

        init {
            addOperator(BeginText()); addOperator(EndText())
            addOperator(Concatenate()); addOperator(Save()); addOperator(Restore()); addOperator(SetGraphicsStateParameters())
            addOperator(NextLine()); addOperator(MoveText()); addOperator(MoveTextSetLeading()); addOperator(SetMatrix())
            addOperator(SetFontAndSize()); addOperator(SetCharSpacing()); addOperator(SetWordSpacing())
            addOperator(SetTextHorizontalScaling()); addOperator(SetTextLeading()); addOperator(SetTextRenderingMode()); addOperator(SetTextRise())
            addOperator(ShowText()); addOperator(ShowTextAdjusted()); addOperator(ShowTextLine()); addOperator(ShowTextLineAndSpace())
            // Do is intentionally unregistered: shared Form XObjects and image data stay untouched.
        }

        fun scan(bytes: ByteArray) {
            val boundedStream = object : PDContentStream {
                override fun getContents() = ByteArrayInputStream(bytes)
                override fun getResources(): PDResources? = page.resources
                override fun getBBox(): PDRectangle = page.cropBox
                override fun getMatrix(): Matrix = page.matrix
            }
            processChildStream(boundedStream, page)
        }

        override fun processOperator(operator: Operator, arguments: MutableList<COSBase>?) {
            // PDFBox's quote operators dispatch operand-free T* through the Java API with null.
            val operands = arguments ?: mutableListOf()
            val top = depth == 0
            val index = if (top) operatorCount++ else -1
            if (top) {
                when (operator.name) {
                    "q" -> clipStack.add(clipped)
                    "Q" -> { if (clipStack.isEmpty()) throw PdfException("This page has unbalanced graphics state.")
                        clipped = clipStack.removeAt(clipStack.lastIndex) }
                    "W", "W*" -> clipped = true
                    "ET" -> { clipped = clipped || textClipPending; textClipPending = false }
                    "'", "\"" -> if (graphicsState.textState.renderingMode.isClip) {
                        clipped = true; textClipPending = true
                    }
                    "BMC" -> markedContent.add(false)
                    "BDC" -> {
                        val value = operands.getOrNull(1)
                        val properties = when (value) {
                            is COSDictionary -> value
                            is COSName -> resources?.getProperties(value)?.cosObject
                            else -> null
                        }
                        markedContent.add(operands.firstOrNull() == COSName.OC || properties?.containsKey(COSName.ACTUAL_TEXT) == true)
                    }
                    "EMC" -> if (markedContent.isNotEmpty()) markedContent.removeAt(markedContent.lastIndex)
                    "Tj", "TJ" -> capture(index, operator.name, operands)
                }
            }
            depth++
            try { super.processOperator(operator, operands) } finally { depth-- }
        }

        override fun operatorException(operator: Operator, operands: MutableList<COSBase>, error: IOException) {
            // The base engine tolerates malformed operators. Editing must not guess their meaning.
            throw error
        }

        override fun showGlyph(matrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
            // We measure original operands directly; do not enter Type3 glyph drawing streams.
        }

        private fun capture(index: Int, operator: String, operands: List<COSBase>) {
            val state = graphicsState.textState
            val font = state.font ?: return
            val matrix = textMatrix ?: return
            val fontSize = state.fontSize
            val scale = state.horizontalScaling / 100f
            val base = matrix.multiply(graphicsState.currentTransformationMatrix)
            val objects: List<COSBase> = when {
                operator == "Tj" && operands.size == 1 && operands[0] is COSString -> listOf(operands[0])
                operator == "TJ" && operands.size == 1 && operands[0] is COSArray -> (operands[0] as COSArray).toList()
                else -> throw PdfException("This page has an unsupported text operation.")
            }
            val glyphs = mutableListOf<Glyph>()
            val text = StringBuilder()
            var offset = 0f
            var firstOffset: Float? = null
            var right = Float.NEGATIVE_INFINITY
            var left = Float.POSITIVE_INFINITY
            var missingUnicode = false
            var missingGlyph = false
            var backwards = false
            var positionedGap = false
            var pendingAdjustment = 0f
            // Small kerning is supported. Larger interior movements can represent word spaces or
            // separate table cells absent from the Unicode string; do not collapse those layouts.
            val gapThreshold = (runCatching { font.spaceWidth }.getOrDefault(250f) * .6f)
                .takeIf { it.isFinite() }?.coerceIn(100f, 200f) ?: 150f
            objects.forEach { value ->
                when (value) {
                    is COSNumber -> {
                        val adjustment = value.floatValue()
                        offset -= adjustment / 1000f * fontSize * scale
                        pendingAdjustment += adjustment
                    }
                    is COSString -> decode(font, value.bytes).forEach { glyph ->
                        glyphCount++
                        if (glyphCount > MAX_GLYPHS) throw PdfException("This page has too much text to edit safely.")
                        glyphs.add(glyph)
                        val available = book.available(font, glyph.code)
                        if (available) book.add(font, glyph) else missingGlyph = true
                        if (firstOffset != null && abs(pendingAdjustment) > gapThreshold) positionedGap = true
                        pendingAdjustment = 0f
                        if (firstOffset == null) firstOffset = offset
                        if (offset < (firstOffset ?: offset) - EPSILON) backwards = true
                        left = min(left, offset)
                        right = max(right, offset + glyph.width * fontSize * scale)
                        if (glyph.unicode == null || glyph.code == 0) missingUnicode = true
                        text.append(glyph.unicode ?: "\uFFFD")
                        offset += (glyph.width * fontSize + state.characterSpacing +
                            if (glyph.bytes.size == 1 && glyph.code == 32) state.wordSpacing else 0f) * scale
                    }
                    else -> throw PdfException("This page has an unsupported text spacing array.")
                }
            }
            val mode = state.renderingMode
            val wasClipped = clipped
            if (mode.isClip) { clipped = true; textClipPending = true }
            if (!collectRuns || glyphs.isEmpty() || text.isBlank()) return
            if (runs.size >= MAX_RUNS) throw PdfException("This page has too many separate text sections.")
            if (!listOf(offset, left, right, fontSize, scale, state.rise, state.characterSpacing, state.wordSpacing).all { it.isFinite() }) {
                throw PdfException("This page contains invalid text measurements.")
            }
            val original = text.toString()
            val reason = when {
                original.length > 2_000 -> "This text section is too long to edit on your phone."
                font is PDType3Font -> "This PDF uses a custom Type 3 drawing font."
                font.isDamaged -> "The original font is damaged and cannot be edited reliably."
                font.isVertical -> "Vertical writing is not supported for original text editing."
                missingUnicode -> "This font does not provide a reliable character map."
                missingGlyph -> "The original font is missing a required character shape."
                fontSize <= 0f || scale <= 0f || scale > 10f -> "This text uses unsupported font scaling."
                abs(base.shearX) > EPSILON || abs(base.shearY) > EPSILON || base.scaleX <= 0f || base.scaleY <= 0f ->
                    "Rotated or skewed text is not supported. Page rotation is supported."
                mode == RenderingMode.NEITHER || mode.isClip || wasClipped -> "Text used as a clipping mask or inside a clipped area cannot be edited safely."
                graphicsState.softMask != null || graphicsState.nonStrokeAlphaConstant == 0.0 || graphicsState.alphaConstant == 0.0 ->
                    "Hidden or masked text cannot be edited safely."
                markedContent.any { it } || semanticReplacement -> "This text has a separate accessibility replacement or optional layer."
                positionedGap -> "This section uses positioned word or column gaps. Editing it as a line would change the spacing."
                backwards || right <= left || offset <= 0f || state.characterSpacing < 0f -> "This text uses overlapping or reversed spacing."
                else -> unsupportedText(original)
            }
            val descriptor = font.fontDescriptor
            val ascent = (descriptor?.ascent?.takeIf { it.isFinite() && it > 0f } ?: 800f).coerceIn(300f, 1500f) / 1000f
            val descent = (descriptor?.descent?.takeIf { it.isFinite() && it <= 0f } ?: -200f).coerceIn(-700f, 0f) / 1000f
            val bounds = bounds(page, base, left, right, state.rise + fontSize * descent, state.rise + fontSize * ascent) ?: return
            runs.add(Run(
                EditableTextRun("$hash:$pageIndex:$index", original, bounds, font.name.orEmpty(),
                    abs(fontSize * base.scalingFactorY), reason == null, reason),
                index, font, fontSize, scale, state.characterSpacing, state.wordSpacing,
                firstOffset ?: 0f, right, offset, glyphs,
            ))
        }
    }

    private fun decode(font: PDFont, bytes: ByteArray): List<Glyph> {
        if (bytes.size > MAX_STREAM_BYTES) throw PdfException("This text section is too large.")
        val input = ByteArrayInputStream(bytes)
        val result = ArrayList<Glyph>()
        while (input.available() > 0) {
            if (result.size >= MAX_GLYPHS) throw PdfException("This text section is too large.")
            val start = bytes.size - input.available()
            val code = font.readCode(input)
            val end = bytes.size - input.available()
            if (end <= start || end - start > 4) throw PdfException("This font has an unsupported character encoding.")
            val width = font.getDisplacement(code).x
            if (!width.isFinite() || width < 0f) throw PdfException("This font has invalid character widths.")
            result.add(Glyph(bytes.copyOfRange(start, end), code, font.toUnicode(code), width))
        }
        return result
    }

    private fun encode(font: PDFont, replacement: String, preferred: Map<String, ByteArray>, book: CodeBook): ByteArray? {
        if (font.isDamaged) return null
        val output = ByteArrayOutputStream()
        var index = 0
        while (index < replacement.length) {
            val codePoint = replacement.codePointAt(index)
            val character = String(Character.toChars(codePoint))
            val encoded = preferred[character] ?: book.code(font, character) ?: try { font.encode(character) } catch (_: Exception) { null }
            if (encoded == null || encoded.isEmpty()) return null
            // Some imported subset fonts encode a Unicode value to a GID absent from their PDF CMap.
            // Never trust encode() alone: require the original font's complete round-trip mapping.
            val decoded = try { decode(font, encoded) } catch (_: Exception) { return null }
            if (decoded.any { it.code == 0 || !book.available(font, it.code) } ||
                decoded.joinToString("") { it.unicode.orEmpty() } != character) return null
            output.write(encoded)
            index += Character.charCount(codePoint)
        }
        return output.toByteArray()
    }

    /** A valid ToUnicode entry alone can still point at GID 0 / .notdef in a damaged subset. */
    private fun hasGlyph(font: PDFont, code: Int): Boolean = try {
        if (font.isDamaged || code == 0 || font is PDType3Font) false
        else when (font) {
            is PDVectorFont -> font.hasGlyph(code)
            is PDSimpleFont -> {
                val name = font.encoding?.getName(code)
                name != null && name != ".notdef" && font.hasGlyph(name)
            }
            else -> false
        }
    } catch (_: Exception) { false }

    private fun unsupportedText(text: String): String? {
        if (text.any { it == '\n' || it == '\r' || it == '\t' || Character.isISOControl(it) }) {
            return "Keep the replacement on one line. Paragraph reflow is not supported."
        }
        if (Bidi.requiresBidi(text.toCharArray(), 0, text.length)) return "Right-to-left text needs a shaping engine and is not supported here."
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            val type = Character.getType(cp)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt() || type == Character.FORMAT.toInt() ||
                cp in 0x0900..0x109F || cp in 0x1780..0x17FF) {
                return "This text needs complex character shaping. Its original appearance cannot be preserved yet."
            }
            index += Character.charCount(cp)
        }
        return null
    }

    private fun bounds(page: PDPage, matrix: Matrix, left: Float, right: Float, bottom: Float, top: Float): NormalizedRect? {
        val crop = page.cropBox
        if (crop.width <= 0f || crop.height <= 0f) return null
        val rotation = ((page.rotation % 360) + 360) % 360
        if (rotation !in listOf(0, 90, 180, 270)) return null
        val corners = listOf(matrix.transformPoint(left, bottom), matrix.transformPoint(left, top),
            matrix.transformPoint(right, bottom), matrix.transformPoint(right, top)).map { point ->
            val x = (point.x - crop.lowerLeftX) / crop.width
            val y = (point.y - crop.lowerLeftY) / crop.height
            when (rotation) {
                90 -> NormalizedPoint(y, x)
                180 -> NormalizedPoint(1f - x, y)
                270 -> NormalizedPoint(1f - y, 1f - x)
                else -> NormalizedPoint(x, 1f - y)
            }
        }
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val x1 = corners.minOf { it.x }.coerceIn(0f, 1f)
        val y1 = corners.minOf { it.y }.coerceIn(0f, 1f)
        val x2 = corners.maxOf { it.x }.coerceIn(0f, 1f)
        val y2 = corners.maxOf { it.y }.coerceIn(0f, 1f)
        return if (x2 > x1 && y2 > y1) NormalizedRect(x1, y1, x2, y2) else null
    }

    private fun content(page: PDPage, limit: Int = MAX_STREAM_BYTES): ByteArray = page.contents.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) throw PdfException("This page is too complex for original text editing.")
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hasReplacementText(document: PDDocument): Boolean {
        val structure = document.documentCatalog.cosObject.getDictionaryObject(COSName.STRUCT_TREE_ROOT) ?: return false
        val pending = ArrayDeque<COSBase>()
        val visited = IdentityHashMap<COSBase, Boolean>()
        pending.add(structure)
        while (pending.isNotEmpty()) {
            val value = pending.removeLast()
            if (visited.put(value, true) != null) continue
            if (visited.size > 20_000) return true // Do not traverse an unbounded structure tree on a phone.
            when (value) {
                is COSObject -> pending.add(value.`object`)
                is COSArray -> value.forEach { pending.add(it) }
                is COSDictionary -> {
                    if (value.containsKey(COSName.ACTUAL_TEXT)) return true
                    value.getDictionaryObject(COSName.K)?.let { pending.add(it) }
                }
            }
        }
        return false
    }
}
