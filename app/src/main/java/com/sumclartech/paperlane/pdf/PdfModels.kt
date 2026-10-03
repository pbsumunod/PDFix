package com.sumclartech.paperlane.pdf

/** Coordinates use the displayed page, after crop and rotation, with (0, 0) at top left. */
data class NormalizedPoint(val x: Float, val y: Float)

data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f }) {
            "Selection must be inside the page."
        }
        require(right > left && bottom > top) { "Select a larger area." }
    }
}

data class PageInfo(val index: Int, val width: Float, val height: Float, val rotation: Int)

enum class FormFieldKind { TEXT, CHECKBOX, CHOICE, RADIO, SIGNATURE, UNSUPPORTED }

data class FormFieldInfo(
    val name: String,
    val label: String,
    val value: String,
    val kind: FormFieldKind,
    val options: List<String> = emptyList(),
    val readOnly: Boolean = false,
    val required: Boolean = false,
    val multiline: Boolean = false,
    val pageIndex: Int? = null,
    val rect: NormalizedRect? = null,
    val optionLabels: Map<String, String> = emptyMap(),
)

data class PdfInfo(
    val pages: List<PageInfo>,
    val formFields: List<FormFieldInfo>,
    val encrypted: Boolean,
    val signed: Boolean,
    val hasXfa: Boolean = false,
) {
    val pageCount: Int get() = pages.size
}

data class SearchHit(val pageIndex: Int, val excerpt: String)

/** One original PDF text-showing operation; its ID is valid only for this exact file revision. */
data class EditableTextRun(
    val id: String,
    val text: String,
    val rect: NormalizedRect,
    val fontName: String,
    val fontSize: Float,
    val editable: Boolean,
    val reason: String? = null,
)

data class OcrLine(val text: String, val rect: NormalizedRect)
data class OcrResult(val text: String, val lines: List<OcrLine>)
data class CompressionResult(val bytes: Long, val targetMet: Boolean, val maxEdge: Int, val quality: Int)

sealed interface PageAnnotation {
    /** x/y locate the upper left of the first text line; sizeFraction is relative to page width. */
    data class Text(
        val x: Float,
        val y: Float,
        val text: String,
        val sizeFraction: Float = .026f,
        val color: Int = 0xFF1F2937.toInt(),
    ) : PageAnnotation

    data class Ink(
        val points: List<NormalizedPoint>,
        val widthFraction: Float = .004f,
        val color: Int = 0xFF1F2937.toInt(),
    ) : PageAnnotation

    data class Highlight(
        val rect: NormalizedRect,
        val color: Int = 0xFFFFD54F.toInt(),
    ) : PageAnnotation

    /** Multiple strokes preserve pen lifts. This is a drawn signature, not a certificate signature. */
    data class Signature(
        val strokes: List<List<NormalizedPoint>>,
        val widthFraction: Float = .003f,
        val color: Int = 0xFF1F2937.toInt(),
    ) : PageAnnotation
}

class PdfException(message: String, cause: Throwable? = null) : Exception(message, cause)
