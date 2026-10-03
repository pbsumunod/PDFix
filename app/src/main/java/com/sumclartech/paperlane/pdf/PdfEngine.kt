package com.sumclartech.paperlane.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.util.UUID
import java.text.Bidi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Offline document operations. All blocking functions must be called on Dispatchers.IO.
 * Every edit writes a separate output file. Sources are never saved or modified.
 * Resource limits make failures recoverable on phones; they are not a promise that every PDF renders.
 */
class PdfEngine(context: Context) {
    private val context = context.applicationContext

    init { PDFBoxResourceLoader.init(this.context) }

    companion object {
        const val MAX_INPUT_BYTES = 80L * 1024 * 1024
        const val MAX_PAGES = 250
        const val MAX_RENDER_EDGE = 2400
        private const val FONT_ASSET = "fonts/Inter-Regular.ttf"
    }

    fun importPdf(uri: Uri, out: File): PdfInfo {
        require(!out.exists()) { "Choose a new file for this import." }
        try {
            atomicWrite(out) { target ->
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_INPUT_BYTES) throw PdfException("PDFs up to 80 MB are supported.")
                        target.write(buffer, 0, read)
                    }
                } ?: throw PdfException("The document could not be opened. Choose it again.")
            }
            return inspect(out)
        } catch (error: Exception) {
            out.delete()
            throw error
        }
    }

    fun inspect(file: File): PdfInfo = open(file).use { document ->
        val pages = (0 until document.numberOfPages).map { pageInfo(document.getPage(it), it) }
        val form = document.documentCatalog.acroForm
        PdfInfo(
            pages = pages,
            formFields = form?.fieldTree?.map { fieldInfo(document, it) }.orEmpty(),
            encrypted = document.isEncrypted,
            signed = document.signatureDictionaries.isNotEmpty(),
            hasXfa = form?.hasXFA() == true,
        )
    }

    fun render(file: File, pageIndex: Int, maxEdge: Int = 1600): Bitmap {
        checkFile(file)
        require(maxEdge in 128..MAX_RENDER_EDGE) { "Preview size must be between 128 and 2400 pixels." }
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(pageIndex in 0 until renderer.pageCount) { "Page no longer exists." }
                    renderer.openPage(pageIndex).use { page ->
                        val scale = maxEdge.toFloat() / max(page.width, page.height).coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(
                            max(1, (page.width * scale).toInt()),
                            max(1, (page.height * scale).toInt()),
                            Bitmap.Config.ARGB_8888,
                        )
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            return bitmap
                        } catch (error: Exception) {
                            bitmap.recycle()
                            throw error
                        }
                    }
                }
            }
        } catch (error: SecurityException) {
            throw PdfException("Password-protected PDFs cannot be previewed here. Open an unlocked copy.", error)
        } catch (error: IOException) {
            throw PdfException("This PDF could not be rendered. It may be damaged or unsupported.", error)
        }
    }

    fun extractText(file: File, pageIndex: Int): String = open(file).use { document ->
        require(pageIndex in 0 until document.numberOfPages) { "Page no longer exists." }
        requireExtractAllowed(document)
        extract(document, pageIndex)
    }

    /** Original PDF text runs. Unsupported runs explain why their font/layout cannot be preserved. */
    fun editableText(file: File, pageIndex: Int): List<EditableTextRun> = openEditable(file).use { document ->
        requireExtractAllowed(document)
        require(pageIndex in 0 until document.numberOfPages) { "Page no longer exists." }
        NativeTextEditor.read(document, file, pageIndex)
    }

    /** Rewrites the selected original text operation; never covers pixels or rasterizes a page. */
    fun replaceExistingText(file: File, out: File, pageIndex: Int, run: EditableTextRun, replacement: String) {
        requireDistinct(file, out)
        openEditable(file).use { document ->
            requireExtractAllowed(document)
            require(pageIndex in 0 until document.numberOfPages) { "Page no longer exists." }
            NativeTextEditor.replace(document, file, pageIndex, run, replacement)
            save(document, out)
        }
    }

    fun search(file: File, query: String): List<SearchHit> = open(file).use { document ->
        require(query.isNotBlank()) { "Enter a word or phrase." }
        requireExtractAllowed(document)
        buildList {
            for (index in 0 until document.numberOfPages) {
                val text = extract(document, index).replace(Regex("\\s+"), " ")
                val found = text.indexOf(query, ignoreCase = true)
                if (found >= 0) add(SearchHit(index, text.substring(max(0, found - 45), min(text.length, found + query.length + 95))))
            }
        }
    }

    fun annotate(file: File, out: File, pageIndex: Int, annotations: List<PageAnnotation>) {
        requireDistinct(file, out)
        require(annotations.isNotEmpty()) { "Add an annotation first." }
        require(annotations.size <= 1000) { "Save these annotations before adding more." }
        openEditable(file).use { document ->
            require(pageIndex in 0 until document.numberOfPages) { "Page no longer exists." }
            val page = document.getPage(pageIndex)
            val info = pageInfo(page, pageIndex)
            val font = if (annotations.any { it is PageAnnotation.Text }) font(document) else null
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                stream.transform(visualMatrix(page))
                annotations.forEach { annotation ->
                    stream.saveGraphicsState()
                    when (annotation) {
                        is PageAnnotation.Text -> drawText(stream, font!!, info, annotation)
                        is PageAnnotation.Ink -> drawInk(stream, info, annotation.points, annotation.widthFraction, annotation.color)
                        is PageAnnotation.Signature -> annotation.strokes.forEach { drawInk(stream, info, it, annotation.widthFraction, annotation.color) }
                        is PageAnnotation.Highlight -> {
                            val state = PDExtendedGraphicsState()
                            state.nonStrokingAlphaConstant = .34f
                            state.blendMode = BlendMode.MULTIPLY
                            stream.setGraphicsStateParameters(state)
                            setFillColor(stream, annotation.color)
                            val rect = annotation.rect
                            stream.addRect(rect.left * info.width, (1f - rect.bottom) * info.height,
                                (rect.right - rect.left) * info.width, (rect.bottom - rect.top) * info.height)
                            stream.fill()
                        }
                    }
                    stream.restoreGraphicsState()
                }
            }
            save(document, out)
        }
    }

    fun fillForm(file: File, out: File, values: Map<String, String>) {
        requireDistinct(file, out)
        openEditable(file).use { document ->
            val form = document.documentCatalog.acroForm ?: throw PdfException("This PDF has no interactive fields. Use Add text instead.")
            if (form.hasXFA()) throw PdfException("This is an XFA form. Fill it in a compatible desktop application.")
            val resources = form.defaultResources ?: PDResources().also { form.defaultResources = it }
            val font = font(document, subset = false)
            val name = resources.add(font).name
            form.setNeedAppearances(false)
            values.forEach { (key, value) ->
                val field = form.getField(key) ?: throw PdfException("A form field is missing: $key")
                if (field.isReadOnly) throw PdfException("The field ${field.alternateFieldName ?: key} is read only.")
                when (field) {
                    is PDTextField -> {
                        checkText(font, value)
                        if (field.maxLen > 0 && value.length > field.maxLen) throw PdfException("${field.alternateFieldName ?: key} allows ${field.maxLen} characters.")
                        field.defaultAppearance = "/$name 11 Tf 0 g"
                        field.setValue(value)
                    }
                    is PDCheckBox -> when (value.lowercase()) {
                        "true", "yes", "1", "on" -> field.check()
                        "false", "no", "0", "off", "" -> field.unCheck()
                        else -> if (value == field.onValue) field.check() else throw PdfException("Choose a valid checkbox value.")
                    }
                    is PDRadioButton -> {
                        if (value !in field.exportValues && value !in field.onValues) throw PdfException("Choose one of the available options for $key.")
                        field.setValue(value)
                    }
                    is PDChoice -> {
                        if (field.isMultiSelect) throw PdfException("Multi-select fields are not supported in this questionnaire.")
                        checkText(font, value)
                        if (value !in field.optionsExportValues && value !in field.optionsDisplayValues) throw PdfException("Choose one of the available options for $key.")
                        field.defaultAppearance = "/$name 11 Tf 0 g"
                        field.setValue(if (value in field.optionsExportValues) value else field.optionsExportValues[field.optionsDisplayValues.indexOf(value)])
                    }
                    else -> throw PdfException("This field type cannot be filled here: $key")
                }
            }
            save(document, out)
        }
    }

    /** order can select, delete, duplicate, or reorder pages. Rotation values are clockwise deltas. */
    fun arrangePages(file: File, out: File, order: List<Int>, rotations: Map<Int, Int> = emptyMap()) {
        requireDistinct(file, out)
        require(order.isNotEmpty() && order.size <= MAX_PAGES) { "Keep between 1 and $MAX_PAGES pages." }
        require(rotations.values.all { it % 90 == 0 }) { "Page rotation must be a multiple of 90 degrees." }
        openEditable(file).use { source ->
            require(order.all { it in 0 until source.numberOfPages }) { "A selected page no longer exists." }
            // Importing only selected pages avoids retaining deleted pages through a form or page tree.
            // Flatten before copying so filled values survive, including widgets shared between pages.
            source.documentCatalog.acroForm?.let { form ->
                if (form.hasXFA()) throw PdfException("Organizing XFA forms is not supported.")
                form.flatten()
            }
            PDDocument(memory()).use { destination ->
                order.forEach { index ->
                    val original = source.getPage(index)
                    val imported = destination.importPage(original)
                    imported.resources = original.resources
                    imported.cropBox = original.cropBox
                    imported.mediaBox = original.mediaBox
                    imported.rotation = normalizeRotation(original.rotation + (rotations[index] ?: 0))
                    // Link destinations may refer to removed pages; keep marks, remove broken navigation.
                    imported.annotations = imported.annotations.filter { it.subtype != "Link" && it.subtype != "Widget" }
                }
                save(destination, out)
            }
        }
    }

    fun merge(files: List<File>, out: File) {
        require(files.size in 2..30) { "Choose between 2 and 30 PDFs." }
        files.forEach { requireDistinct(it, out) }
        require(files.sumOf { it.length() } <= MAX_INPUT_BYTES) { "Choose PDFs totaling less than 80 MB." }
        PDDocument(memory()).use { destination ->
            val merger = PDFMergerUtility()
            for (file in files) openEditable(file).use { source ->
                if (destination.numberOfPages + source.numberOfPages > MAX_PAGES) throw PdfException("A merged PDF can have up to $MAX_PAGES pages.")
                merger.appendDocument(destination, source)
            }
            save(destination, out)
        }
    }

    /** Changes the visible crop only. Content outside the crop is retained; this is not redaction. */
    fun cropPage(file: File, out: File, pageIndex: Int, rect: NormalizedRect) {
        requireDistinct(file, out)
        openEditable(file).use { document ->
            require(pageIndex in 0 until document.numberOfPages) { "Page no longer exists." }
            val page = document.getPage(pageIndex)
            val box = page.cropBox
            fun userPoint(x: Float, y: Float): NormalizedPoint = when (normalizeRotation(page.rotation)) {
                90 -> NormalizedPoint(box.lowerLeftX + y * box.width, box.lowerLeftY + x * box.height)
                180 -> NormalizedPoint(box.upperRightX - x * box.width, box.lowerLeftY + y * box.height)
                270 -> NormalizedPoint(box.upperRightX - y * box.width, box.upperRightY - x * box.height)
                else -> NormalizedPoint(box.lowerLeftX + x * box.width, box.upperRightY - y * box.height)
            }
            val a = userPoint(rect.left, rect.top)
            val b = userPoint(rect.right, rect.bottom)
            val width = kotlin.math.abs(a.x - b.x)
            val height = kotlin.math.abs(a.y - b.y)
            require(width >= 10 && height >= 10) { "Keep a crop at least 10 PDF points wide and tall." }
            page.cropBox = PDRectangle(min(a.x, b.x), min(a.y, b.y), width, height)
            save(document, out)
        }
    }

    fun imagesToPdf(images: List<Uri>, out: File) {
        require(images.isNotEmpty() && images.size <= 50) { "Choose between 1 and 50 photos." }
        PDDocument(memory()).use { document ->
            for (uri in images) {
                val bitmap = decodePhoto(uri)
                try {
                    val portrait = bitmap.height >= bitmap.width
                    val page = PDPage(if (portrait) PDRectangle.A4 else PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width))
                    document.addPage(page)
                    val image = JPEGFactory.createFromImage(document, bitmap, .9f)
                    val scale = min((page.mediaBox.width - 32) / bitmap.width, (page.mediaBox.height - 32) / bitmap.height)
                    val width = bitmap.width * scale
                    val height = bitmap.height * scale
                    PDPageContentStream(document, page).use { stream ->
                        stream.drawImage(image, (page.mediaBox.width - width) / 2, (page.mediaBox.height - height) / 2, width, height)
                    }
                } finally { bitmap.recycle() }
            }
            save(document, out)
        }
    }

    /** Removes document info, catalog XMP, and page XMP. Visible text and attachments are unaffected. */
    fun sanitizeMetadata(file: File, out: File) {
        requireDistinct(file, out)
        openEditable(file).use { document ->
            document.documentInformation = PDDocumentInformation()
            document.documentCatalog.cosObject.removeItem(COSName.METADATA)
            document.pages.forEach { it.cosObject.removeItem(COSName.METADATA) }
            save(document, out)
        }
    }

    fun protect(file: File, out: File, password: String) {
        requireDistinct(file, out)
        require(password.length >= 6) { "Use a password with at least 6 characters." }
        require(password.length <= 64) { "Use a password with up to 64 characters." }
        openEditable(file).use { document ->
            val policy = StandardProtectionPolicy(UUID.randomUUID().toString(), password, AccessPermission())
            policy.encryptionKeyLength = 256
            policy.isPreferAES = true
            document.protect(policy)
            save(document, out)
        }
    }

    /**
     * Rebuilds every page from pixels. No source page, text, link, attachment, form, or metadata is copied.
     * Redaction is permanent in the output. The original is intentionally retained by the caller.
     * Raster output loses selectable text, vector sharpness, interactive fields, and accessibility tags.
     */
    fun rasterExport(
        file: File,
        out: File,
        maxEdge: Int = 1600,
        jpegQuality: Int = 80,
        redactions: Map<Int, List<NormalizedRect>> = emptyMap(),
    ) {
        rasterRebuild(file, out, maxEdge, jpegQuality, redactions, Color.BLACK)
    }

    /**
     * A visual correction, not original PDF text editing. Erases the chosen pixels to white and
     * rebuilds every page, then embeds wrapped replacement text. All original interactive content
     * and selectable text are removed. Callers must disclose this before applying the operation.
     */
    fun correctRegion(
        file: File,
        out: File,
        pageIndex: Int,
        rect: NormalizedRect,
        replacement: String,
        fontSizeFraction: Float = .025f,
        color: Int = 0xFF1F2937.toInt(),
    ) {
        require(replacement.isNotBlank()) { "Enter replacement text." }
        require(replacement.length <= 2000) { "Replace a shorter section, up to 2000 characters." }
        require(fontSizeFraction in 0.006f..0.2f) { "Choose a smaller text size." }
        rasterRebuild(file, out, 2000, 92, mapOf(pageIndex to listOf(rect)), Color.WHITE,
            RegionCorrection(pageIndex, rect, replacement, fontSizeFraction, color))
    }

    private data class RegionCorrection(val pageIndex: Int, val rect: NormalizedRect, val text: String, val sizeFraction: Float, val color: Int)

    private fun rasterRebuild(
        file: File,
        out: File,
        maxEdge: Int,
        jpegQuality: Int,
        masks: Map<Int, List<NormalizedRect>>,
        maskColor: Int,
        correction: RegionCorrection? = null,
    ) {
        requireDistinct(file, out)
        require(jpegQuality in 30..100) { "Image quality must be between 30 and 100." }
        val info = openEditable(file).use { document ->
            (0 until document.numberOfPages).map { pageInfo(document.getPage(it), it) }
        }
        require(masks.keys.all { it in info.indices }) { "A selection refers to a missing page." }
        PDDocument(memory()).use { destination ->
            val correctionFont = correction?.let { font(destination).also { loaded -> checkText(loaded, it.text) } }
            for (pageInfo in info) {
                val bitmap = render(file, pageInfo.index, maxEdge)
                try {
                    val canvas = Canvas(bitmap)
                    val paint = Paint().apply { color = maskColor; isAntiAlias = false }
                    masks[pageInfo.index].orEmpty().forEach { rect ->
                        // Extra pixels prevent antialiasing at selection edges from exposing glyph fragments.
                        canvas.drawRect(rect.left * bitmap.width - 2, rect.top * bitmap.height - 2,
                            rect.right * bitmap.width + 2, rect.bottom * bitmap.height + 2, paint)
                    }
                    val page = PDPage(PDRectangle(pageInfo.width, pageInfo.height))
                    destination.addPage(page)
                    val image = JPEGFactory.createFromImage(destination, bitmap, jpegQuality / 100f)
                    PDPageContentStream(destination, page).use { stream ->
                        stream.drawImage(image, 0f, 0f, pageInfo.width, pageInfo.height)
                        if (correction != null && correction.pageIndex == pageInfo.index) {
                            drawCorrection(stream, requireNotNull(correctionFont), pageInfo, correction)
                        }
                    }
                } finally { bitmap.recycle() }
            }
            save(destination, out)
        }
    }

    private fun drawCorrection(stream: PDPageContentStream, font: PDType0Font, page: PageInfo, correction: RegionCorrection) {
        val rect = correction.rect
        val padding = max(1.5f, page.width * .003f)
        val width = (rect.right - rect.left) * page.width - padding * 2
        val height = (rect.bottom - rect.top) * page.height - padding * 2
        require(width >= 12f && height >= 8f) { "Select a larger area for the replacement text." }
        var size = min(page.width * correction.sizeFraction, height).coerceAtMost(100f)
        var lines = emptyList<String>()
        var fits = false
        while (size >= 5f) {
            lines = wrapText(font, correction.text, size, width)
            if (lines.size * size * 1.25f <= height && lines.all { font.getStringWidth(it) * size / 1000f <= width }) { fits = true; break }
            size -= .5f
        }
        if (!fits) throw PdfException("The replacement text does not fit. Select a larger area or shorten it.")
        stream.saveGraphicsState()
        stream.addRect(rect.left * page.width + padding, (1f - rect.bottom) * page.height + padding, width, height)
        stream.clip()
        setFillColor(stream, correction.color)
        stream.beginText()
        stream.setFont(font, size)
        stream.setLeading(size * 1.25f)
        stream.newLineAtOffset(rect.left * page.width + padding, (1f - rect.top) * page.height - padding - size)
        lines.forEachIndexed { index, line ->
            if (index > 0) stream.newLine()
            stream.showText(line)
        }
        stream.endText()
        stream.restoreGraphicsState()
    }

    private fun wrapText(font: PDType0Font, text: String, size: Float, availableWidth: Float): List<String> {
        fun width(value: String) = font.getStringWidth(value) * size / 1000f
        return buildList {
            text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { paragraph ->
                if (paragraph.isBlank()) add("")
                else {
                    var line = ""
                    for (word in paragraph.trim().split(Regex("\\s+"))) {
                        val combined = if (line.isEmpty()) word else "$line $word"
                        if (width(combined) <= availableWidth) line = combined
                        else {
                            if (line.isNotEmpty()) { add(line); line = "" }
                            if (width(word) <= availableWidth) line = word
                            else {
                                for (codePoint in word.codePoints().toArray()) {
                                    val glyph = String(Character.toChars(codePoint))
                                    if (line.isNotEmpty() && width(line + glyph) > availableWidth) { add(line); line = "" }
                                    line += glyph
                                }
                            }
                        }
                    }
                    if (line.isNotEmpty()) add(line)
                }
            }
        }
    }

    /** Best effort, never silently claims a byte target was met. Always creates an image-only PDF. */
    fun compressToTarget(file: File, out: File, targetBytes: Long): CompressionResult {
        requireDistinct(file, out)
        require(targetBytes >= 50 * 1024) { "Choose a size of at least 50 KB." }
        val candidate = File.createTempFile("paperlane-compress-", ".pdf", context.cacheDir)
        try {
            val attempts = listOf(1800 to 85, 1400 to 75, 1100 to 65, 900 to 50, 700 to 40)
            var result: CompressionResult? = null
            for ((edge, quality) in attempts) {
                rasterExport(file, candidate, edge, quality)
                result = CompressionResult(candidate.length(), candidate.length() <= targetBytes, edge, quality)
                if (result.targetMet) break
            }
            atomicWrite(out) { output -> candidate.inputStream().use { it.copyTo(output) } }
            return requireNotNull(result)
        } finally { candidate.delete() }
    }

    suspend fun recognize(file: File, pageIndex: Int): OcrResult {
        val bitmap = render(file, pageIndex, 2000)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        // ML Kit keeps reading the bitmap after cancellation, so release it only in task completion.
        return suspendCancellableCoroutine { continuation ->
            val task = try { recognizer.process(InputImage.fromBitmap(bitmap, 0)) }
            catch (error: Exception) {
                bitmap.recycle()
                recognizer.close()
                continuation.resumeWithException(PdfException("Text recognition could not start.", error))
                return@suspendCancellableCoroutine
            }
            task
                .addOnCompleteListener { task ->
                    try {
                        if (continuation.isActive) {
                            if (task.isSuccessful) {
                                val result = task.result
                                val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                    val bounds = line.boundingBox ?: return@mapNotNull null
                                    val left = (bounds.left.toFloat() / bitmap.width).coerceIn(0f, 1f)
                                    val top = (bounds.top.toFloat() / bitmap.height).coerceIn(0f, 1f)
                                    val right = (bounds.right.toFloat() / bitmap.width).coerceIn(0f, 1f)
                                    val bottom = (bounds.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f)
                                    if (right <= left || bottom <= top) null else OcrLine(line.text, NormalizedRect(left, top, right, bottom))
                                }
                                continuation.resume(OcrResult(result.text, lines))
                            } else continuation.resumeWithException(PdfException("Text recognition could not finish. Try a clearer scan.", task.exception))
                        }
                    } finally {
                        bitmap.recycle()
                        recognizer.close()
                    }
                }
        }
    }

    /** Adds a real invisible text layer to pages without existing text. OCR supports Latin script. */
    suspend fun searchableOcr(file: File, out: File): Int {
        requireDistinct(file, out)
        openEditable(file).use { document ->
            val font = font(document)
            var recognizedPages = 0
            for (index in 0 until document.numberOfPages) {
                if (extract(document, index).trim().length > 8) continue
                val result = recognize(file, index)
                if (result.lines.isEmpty()) continue
                val page = document.getPage(index)
                val info = pageInfo(page, index)
                PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    stream.transform(visualMatrix(page))
                    for (line in result.lines) {
                        checkText(font, line.text)
                        val size = max(3f, (line.rect.bottom - line.rect.top) * info.height * .85f)
                        val naturalWidth = font.getStringWidth(line.text) / 1000f * size
                        if (naturalWidth <= 0) continue
                        stream.beginText()
                        stream.setRenderingMode(RenderingMode.NEITHER)
                        stream.setFont(font, size)
                        stream.setHorizontalScaling(((line.rect.right - line.rect.left) * info.width / naturalWidth * 100).coerceIn(10f, 1000f))
                        stream.newLineAtOffset(line.rect.left * info.width, (1f - line.rect.bottom) * info.height + size * .12f)
                        stream.showText(line.text)
                        stream.endText()
                    }
                }
                recognizedPages++
            }
            save(document, out)
            return recognizedPages
        }
    }

    fun demo(out: File) {
        PDDocument(memory()).use { document ->
            val font = font(document, subset = false)
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.setNonStrokingColor(22, 55, 46)
                stream.addRect(0f, 640f, page.mediaBox.width, 202f)
                stream.fill()
                writeDemoText(stream, font, "Make room for what matters.", 34f, 42f, 755f, Color.WHITE)
                writeDemoText(stream, font, "A little paperwork. A lot more possibility.", 15f, 44f, 712f, 0xFFDAEBE0.toInt())
                writeDemoText(stream, font, "Your next chapter", 25f, 44f, 593f, 0xFF16372E.toInt())
                writeDemoText(stream, font, "Try highlighting this line, then add a note or signature.", 12f, 44f, 563f, 0xFF4C5F59.toInt())
                writeDemoText(stream, font, "01     Read and make your mark", 17f, 44f, 497f, 0xFF16372E.toInt())
                writeDemoText(stream, font, "Use the visual toolbar to add text, ink, or a highlight.", 12f, 44f, 471f, 0xFF4C5F59.toInt())
                writeDemoText(stream, font, "02     Put pages in their place", 17f, 44f, 416f, 0xFF16372E.toInt())
                writeDemoText(stream, font, "Rotate, reorder, and combine documents in a few taps.", 12f, 44f, 390f, 0xFF4C5F59.toInt())
                writeDemoText(stream, font, "03     Send with confidence", 17f, 44f, 335f, 0xFF16372E.toInt())
                writeDemoText(stream, font, "Save a separate copy. Your original stays safe.", 12f, 44f, 309f, 0xFF4C5F59.toInt())
                stream.setNonStrokingColor(235, 241, 235)
                stream.addRect(44f, 107f, 507f, 128f)
                stream.fill()
                writeDemoText(stream, font, "Made for your phone. Yours to keep.", 19f, 64f, 193f, 0xFF16372E.toInt())
                writeDemoText(stream, font, "No ads. Local files. No account needed to edit.", 12f, 64f, 164f, 0xFF4C5F59.toInt())
                writeDemoText(stream, font, "PDFIX     /     SAMPLE DOCUMENT", 9f, 44f, 48f, 0xFF7B8D83.toInt())
            }
            val formPage = PDPage(PDRectangle.A4)
            document.addPage(formPage)
            PDPageContentStream(document, formPage).use { stream ->
                writeDemoText(stream, font, "Small plans, big beginnings", 27f, 44f, 766f, 0xFF16372E.toInt())
                writeDemoText(stream, font, "A real fillable form. Tap Forms to answer one field at a time.", 12f, 44f, 731f, 0xFF4C5F59.toInt())
                writeDemoText(stream, font, "Your name", 12f, 44f, 672f, 0xFF4C5F59.toInt())
                writeDemoText(stream, font, "Email", 12f, 44f, 587f, 0xFF4C5F59.toInt())
                writeDemoText(stream, font, "One thing you want to make happen", 12f, 44f, 502f, 0xFF4C5F59.toInt())
                stream.setStrokingColor(164, 188, 173)
                stream.setLineWidth(1f)
                listOf(PDRectangle(44f, 626f, 507f, 34f), PDRectangle(44f, 541f, 507f, 34f), PDRectangle(44f, 336f, 507f, 154f)).forEach {
                    stream.addRect(it.lowerLeftX, it.lowerLeftY, it.width, it.height)
                    stream.stroke()
                }
                writeDemoText(stream, font, "Drawn signature", 12f, 44f, 250f, 0xFF4C5F59.toInt())
                stream.moveTo(44f, 162f); stream.lineTo(551f, 162f); stream.stroke()
                writeDemoText(stream, font, "This sample contains no personal information until you add it.", 10f, 44f, 80f, 0xFF7B8D83.toInt())
            }
            val form = PDAcroForm(document)
            document.documentCatalog.acroForm = form
            val resources = PDResources()
            val fontName = resources.add(font).name
            form.defaultResources = resources
            form.defaultAppearance = "/$fontName 12 Tf 0 g"
            form.setNeedAppearances(false)
            listOf(
                Triple("name", "Your name", PDRectangle(46f, 628f, 503f, 30f)),
                Triple("email", "Email", PDRectangle(46f, 543f, 503f, 30f)),
                Triple("plan", "One thing you want to make happen", PDRectangle(46f, 338f, 503f, 150f)),
            ).forEach { (name, label, bounds) ->
                val field = PDTextField(form)
                field.partialName = name
                field.alternateFieldName = label
                field.defaultAppearance = "/$fontName 12 Tf 0 g"
                field.isMultiline = name == "plan"
                val widget = field.widgets.first()
                widget.rectangle = bounds
                widget.page = formPage
                widget.isPrinted = true
                form.fields.add(field)
                formPage.annotations.add(widget)
                field.value = ""
            }
            save(document, out)
        }
    }

    private fun memory(): MemoryUsageSetting = MemoryUsageSetting.setupMixed(16L * 1024 * 1024).setTempDir(context.cacheDir)

    private fun checkFile(file: File) {
        if (!file.isFile || file.length() == 0L) throw PdfException("The document is missing or empty. Import it again.")
        if (file.length() > MAX_INPUT_BYTES) throw PdfException("PDFs up to 80 MB are supported.")
    }

    private fun open(file: File): PDDocument {
        checkFile(file)
        val document = try { PDDocument.load(file, memory()) }
        catch (error: InvalidPasswordException) { throw PdfException("This PDF needs a password. Import an unlocked copy to edit it.", error) }
        catch (error: IOException) { throw PdfException("This PDF could not be opened. It may be damaged or unsupported.", error) }
        if (document.numberOfPages !in 1..MAX_PAGES) {
            document.close()
            throw PdfException("PDFs must have between 1 and $MAX_PAGES pages.")
        }
        return document
    }

    private fun openEditable(file: File): PDDocument {
        val document = open(file)
        try {
            if (document.isEncrypted) throw PdfException("Editing encrypted PDFs is not supported. Use an unlocked copy.")
            if (document.signatureDictionaries.isNotEmpty()) throw PdfException("This PDF has a digital signature. Editing would invalidate it. Use an unsigned source copy.")
            return document
        } catch (error: Exception) { document.close(); throw error }
    }

    private fun requireExtractAllowed(document: PDDocument) {
        if (!document.currentAccessPermission.canExtractContent()) throw PdfException("This PDF does not allow text extraction.")
    }

    private fun requireDistinct(file: File, out: File) {
        require(file.canonicalFile != out.canonicalFile) { "Save to a separate file to protect your original." }
    }

    private fun save(document: PDDocument, out: File) = atomicWrite(out) { stream ->
        // PDFBox closes its output. Keep AtomicFile's descriptor open until it has fsynced and committed.
        val bounded = object : FilterOutputStream(stream) {
            private var written = 0L
            private fun count(bytes: Int) {
                written += bytes
                if (written > MAX_INPUT_BYTES) throw PdfException("The result exceeds 80 MB. Use fewer pages or lower image quality.")
            }
            override fun write(value: Int) { count(1); stream.write(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { count(length); stream.write(buffer, offset, length) }
            override fun close() { flush() }
        }
        document.save(BufferedOutputStream(bounded, 64 * 1024))
    }

    private fun atomicWrite(out: File, writer: (FileOutputStream) -> Unit) {
        out.parentFile?.mkdirs()
        val atomic = AtomicFile(out)
        val stream = atomic.startWrite()
        try { writer(stream); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    private fun font(document: PDDocument, subset: Boolean = true): PDType0Font =
        context.assets.open(FONT_ASSET).use { PDType0Font.load(document, it, subset) }

    private fun checkText(font: PDType0Font, text: String) {
        require(text.length <= 20_000) { "Add text in smaller sections." }
        if (Bidi.requiresBidi(text.toCharArray(), 0, text.length)) {
            throw PdfException("Right-to-left text editing is not supported yet. Use Latin, Greek, or Cyrillic text.")
        }
        try { text.split('\n').forEach { font.getStringWidth(it.replace("\r", "").replace("\t", "    ")) } }
        catch (error: IllegalArgumentException) { throw PdfException("This text includes a character the bundled font cannot display. Try Latin, Greek, or Cyrillic text without emoji.", error) }
    }

    private fun extract(document: PDDocument, pageIndex: Int): String = PDFTextStripper().apply {
        startPage = pageIndex + 1; endPage = pageIndex + 1; sortByPosition = true
    }.getText(document)

    private fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

    private fun pageInfo(page: PDPage, index: Int): PageInfo {
        val box = page.cropBox
        require(box.width.isFinite() && box.height.isFinite() && box.width > 0 && box.height > 0 && box.width < 20_000 && box.height < 20_000) { "This page has unsupported dimensions." }
        val rotation = normalizeRotation(page.rotation)
        return if (rotation == 90 || rotation == 270) PageInfo(index, box.height, box.width, rotation)
        else PageInfo(index, box.width, box.height, rotation)
    }

    /** Transform visual bottom-left coordinates into the PDF's unrotated crop coordinate space. */
    private fun visualMatrix(page: PDPage): Matrix {
        val box = page.cropBox
        return when (normalizeRotation(page.rotation)) {
            90 -> Matrix(0f, 1f, -1f, 0f, box.upperRightX, box.lowerLeftY)
            180 -> Matrix(-1f, 0f, 0f, -1f, box.upperRightX, box.upperRightY)
            270 -> Matrix(0f, -1f, 1f, 0f, box.lowerLeftX, box.upperRightY)
            else -> Matrix(1f, 0f, 0f, 1f, box.lowerLeftX, box.lowerLeftY)
        }
    }

    private fun drawText(stream: PDPageContentStream, font: PDType0Font, page: PageInfo, text: PageAnnotation.Text) {
        checkPoint(NormalizedPoint(text.x, text.y))
        require(text.sizeFraction in 0.006f..0.2f) { "Choose a smaller text size." }
        checkText(font, text.text)
        val size = page.width * text.sizeFraction
        val availableWidth = (1f - text.x) * page.width
        require(availableWidth >= size) { "Place text farther from the right edge." }
        val lines = wrapText(font, text.text, size, availableWidth)
        require(lines.size * size * 1.25f <= (1f - text.y) * page.height) { "The text reaches beyond the page. Use a smaller size or place it higher." }
        require(lines.all { font.getStringWidth(it) * size / 1000f <= availableWidth }) { "Place text farther from the right edge or use a smaller size." }
        setFillColor(stream, text.color)
        stream.beginText()
        stream.setFont(font, size)
        stream.setLeading(size * 1.25f)
        stream.newLineAtOffset(text.x * page.width, (1f - text.y) * page.height - size)
        lines.forEachIndexed { index, line ->
            if (index > 0) stream.newLine()
            stream.showText(line.replace("\t", "    "))
        }
        stream.endText()
    }

    private fun drawInk(stream: PDPageContentStream, page: PageInfo, points: List<NormalizedPoint>, width: Float, color: Int) {
        require(points.size <= 20_000) { "Save this drawing before adding more strokes." }
        require(width in 0.0005f..0.04f) { "Choose a smaller pen width." }
        points.forEach(::checkPoint)
        if (points.isEmpty()) return
        stream.setStrokingColor(Color.red(color), Color.green(color), Color.blue(color))
        stream.setLineWidth(width * page.width)
        stream.setLineCapStyle(1)
        stream.setLineJoinStyle(1)
        stream.moveTo(points.first().x * page.width, (1f - points.first().y) * page.height)
        if (points.size == 1) stream.lineTo(points.first().x * page.width + .01f, (1f - points.first().y) * page.height)
        else points.drop(1).forEach { stream.lineTo(it.x * page.width, (1f - it.y) * page.height) }
        stream.stroke()
    }

    private fun checkPoint(point: NormalizedPoint) {
        require(point.x.isFinite() && point.y.isFinite() && point.x in 0f..1f && point.y in 0f..1f) { "Place the mark inside the page." }
    }

    private fun setFillColor(stream: PDPageContentStream, color: Int) = stream.setNonStrokingColor(Color.red(color), Color.green(color), Color.blue(color))

    private fun fieldInfo(document: PDDocument, field: PDField): FormFieldInfo {
        val widget = field.widgets.firstOrNull()
        val pageIndex = (0 until document.numberOfPages).firstOrNull { index ->
            val page = document.getPage(index)
            widget != null && (widget.page?.cosObject == page.cosObject || page.annotations.any { it.cosObject == widget.cosObject })
        }
        val kind = when (field) {
            is PDTextField -> FormFieldKind.TEXT
            is PDCheckBox -> FormFieldKind.CHECKBOX
            is PDRadioButton -> FormFieldKind.RADIO
            is PDChoice -> if (field.isMultiSelect) FormFieldKind.UNSUPPORTED else FormFieldKind.CHOICE
            is PDSignatureField -> FormFieldKind.SIGNATURE
            else -> FormFieldKind.UNSUPPORTED
        }
        val options = when (field) {
            is PDChoice -> field.optionsExportValues
            is PDRadioButton -> field.exportValues.ifEmpty { field.onValues.toList() }
            else -> emptyList()
        }
        return FormFieldInfo(
            name = field.fullyQualifiedName,
            label = field.alternateFieldName?.takeIf { it.isNotBlank() } ?: field.partialName ?: field.fullyQualifiedName,
            value = when (field) {
                is PDCheckBox -> field.isChecked.toString()
                is PDChoice -> field.value.firstOrNull().orEmpty()
                is PDRadioButton -> field.selectedExportValues.firstOrNull().orEmpty().takeUnless { it == "Off" }.orEmpty()
                else -> field.valueAsString.orEmpty()
            },
            kind = kind,
            options = options,
            readOnly = field.isReadOnly || kind == FormFieldKind.SIGNATURE || kind == FormFieldKind.UNSUPPORTED,
            required = field.isRequired,
            multiline = field is PDTextField && field.isMultiline,
            pageIndex = pageIndex,
            rect = if (pageIndex != null && widget?.rectangle != null) normalizedBounds(document.getPage(pageIndex), widget.rectangle) else null,
            optionLabels = if (field is PDChoice) field.optionsExportValues.zip(field.optionsDisplayValues).toMap() else emptyMap(),
        )
    }

    private fun normalizedBounds(page: PDPage, rectangle: PDRectangle): NormalizedRect? {
        val box = page.cropBox
        fun point(x: Float, y: Float): NormalizedPoint = when (normalizeRotation(page.rotation)) {
            90 -> NormalizedPoint((y - box.lowerLeftY) / box.height, (x - box.lowerLeftX) / box.width)
            180 -> NormalizedPoint((box.upperRightX - x) / box.width, (y - box.lowerLeftY) / box.height)
            270 -> NormalizedPoint((box.upperRightY - y) / box.height, (box.upperRightX - x) / box.width)
            else -> NormalizedPoint((x - box.lowerLeftX) / box.width, (box.upperRightY - y) / box.height)
        }
        val a = point(rectangle.lowerLeftX, rectangle.lowerLeftY)
        val b = point(rectangle.upperRightX, rectangle.upperRightY)
        val left = min(a.x, b.x).coerceIn(0f, 1f)
        val top = min(a.y, b.y).coerceIn(0f, 1f)
        val right = max(a.x, b.x).coerceIn(0f, 1f)
        val bottom = max(a.y, b.y).coerceIn(0f, 1f)
        return if (right > left && bottom > top) NormalizedRect(left, top, right, bottom) else null
    }

    private fun decodePhoto(uri: Uri): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: throw PdfException("A selected photo could not be opened.")
        boundsStream.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw PdfException("A selected image format is not supported.")
        if (options.outWidth.toLong() * options.outHeight > 150_000_000L) throw PdfException("This photo is too large. Choose one under 150 megapixels.")
        var sample = 1
        while (max(options.outWidth, options.outHeight) / sample > MAX_RENDER_EDGE) sample *= 2
        val decode = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decode) }
            ?: throw PdfException("A selected photo could not be decoded.")
        try {
            val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
                try { ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
            } ?: ExifInterface.ORIENTATION_NORMAL
            val matrix = AndroidMatrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            }
            val downscale = min(1f, MAX_RENDER_EDGE.toFloat() / max(source.width, source.height))
            matrix.postScale(downscale, downscale)
            val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (transformed != source) source.recycle()
            // JPEG has no alpha; composite PNG transparency onto white explicitly.
            val result = Bitmap.createBitmap(transformed.width, transformed.height, Bitmap.Config.ARGB_8888)
            Canvas(result).apply { drawColor(Color.WHITE); drawBitmap(transformed, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)) }
            transformed.recycle()
            return result
        } catch (error: Exception) { if (!source.isRecycled) source.recycle(); throw error }
    }

    private fun writeDemoText(stream: PDPageContentStream, font: PDType0Font, text: String, size: Float, x: Float, y: Float, color: Int) {
        setFillColor(stream, color)
        stream.beginText(); stream.setFont(font, size); stream.newLineAtOffset(x, y); stream.showText(text); stream.endText()
    }
}
