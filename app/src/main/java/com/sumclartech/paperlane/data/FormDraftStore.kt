package com.sumclartech.paperlane.data

import android.content.Context
import android.util.AtomicFile
import com.sumclartech.paperlane.pdf.FormFieldInfo
import com.sumclartech.paperlane.pdf.FormFieldKind
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class FormDraft(
    val revisionId: String,
    val values: Map<String, String>,
    val question: Int,
    val updatedAtMillis: Long,
)

/** Carries unsaved answers only across an edit that left the corresponding PDF fields unchanged. */
internal fun carryForwardFormAnswers(
    previousFields: List<FormFieldInfo>,
    updatedFields: List<FormFieldInfo>,
    pendingAnswers: Map<String, String>,
): Map<String, String> {
    val previous = previousFields.associateBy { it.name }
    return buildMap {
        for (updated in updatedFields) {
            val before = previous[updated.name] ?: continue
            val pending = pendingAnswers[updated.name] ?: continue
            if (updated.readOnly || updated.kind == FormFieldKind.SIGNATURE || updated.kind == FormFieldKind.UNSUPPORTED) continue
            if (before.kind != updated.kind || before.label != updated.label || before.value != updated.value ||
                before.options != updated.options || before.optionLabels != updated.optionLabels || pending == before.value) continue
            if ((updated.kind == FormFieldKind.CHOICE || updated.kind == FormFieldKind.RADIO) && pending !in updated.options) continue
            put(updated.name, pending)
        }
    }
}

/** Private, atomic draft recovery. Drafts live with their document and are deleted with its history. */
class FormDraftStore internal constructor(context: Context, private val documentsRoot: File) {
    constructor(context: Context) : this(context, File(context.filesDir, "paperlane/documents"))

    private val root = documentsRoot.canonicalFile
    private val lock = LOCKS.getOrPut(root.path) { Any() }

    init {
        val privateRoot = context.applicationContext.filesDir.canonicalFile
        require(root.path.startsWith(privateRoot.path + File.separator)) { "Draft storage must be private to this app." }
    }

    /** A draft belongs only to the exact revision whose fields the user was answering. */
    fun load(documentId: String, revisionId: String): FormDraft? = synchronized(lock) {
        validId(revisionId)
        val file = draftFile(documentId)
        if (!file.exists() && !File(file.path + ".bak").exists()) return@synchronized null
        try {
            val json = AtomicFile(file).openRead().use { input ->
                if (input.channel.size() > MAX_JSON_BYTES) throw IOException("Draft size limit exceeded")
                JSONObject(input.bufferedReader(Charsets.UTF_8).readText())
            }
            if (json.optInt("schemaVersion") != 1 || json.optString("revisionId") != revisionId) return@synchronized null
            val data = json.getJSONObject("values")
            if (data.length() > MAX_FIELDS) throw IOException("Draft field limit exceeded")
            val values = buildMap {
                val keys = data.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = data.getString(key)
                    if (key.length > 1024 || value.length > MAX_ANSWER_LENGTH) throw IOException("Draft answer limit exceeded")
                    put(key, value)
                }
            }
            FormDraft(revisionId, values, json.optInt("question").coerceIn(0, MAX_FIELDS), json.optLong("updatedAtMillis"))
        } catch (error: Exception) {
            throw IOException("The unfinished form draft could not be recovered. Saved PDF answers are unchanged.", error)
        }
    }

    /** Returns false for a deleted document; never creates a new document directory. */
    fun save(documentId: String, draft: FormDraft): Boolean = synchronized(lock) {
        validId(draft.revisionId)
        require(draft.question in 0..MAX_FIELDS) { "This form has too many questions to keep a draft." }
        require(draft.values.size <= MAX_FIELDS) { "This form has too many fields to keep a draft." }
        require(draft.values.all { (key, value) -> key.length <= 1024 && value.length <= MAX_ANSWER_LENGTH }) { "A form answer is too long to keep a draft." }
        val file = draftFile(documentId)
        val directory = file.parentFile!!
        if (!directory.isDirectory || !File(directory, "state.json").isFile) return@synchronized false
        val json = JSONObject().put("schemaVersion", 1).put("revisionId", draft.revisionId)
            .put("question", draft.question).put("updatedAtMillis", draft.updatedAtMillis)
            .put("values", JSONObject(draft.values))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "These form answers are too large to keep a draft." }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            atomic.finishWrite(stream)
            true
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw IOException("The form draft could not be saved. Check available storage.", error)
        }
    }

    fun clear(documentId: String) = synchronized(lock) { AtomicFile(draftFile(documentId)).delete() }

    private fun draftFile(documentId: String): File {
        val directory = File(root, validId(documentId)).canonicalFile
        require(directory.parentFile == root) { "Invalid draft location." }
        return File(directory, "form-draft.json")
    }

    private fun validId(value: String): String {
        require(ID_PATTERN.matches(value)) { "Invalid document identifier." }
        return value
    }

    companion object {
        const val MAX_ANSWER_LENGTH = 20_000
        private const val MAX_FIELDS = 5_000
        private const val MAX_JSON_BYTES = 4 * 1024 * 1024
        private val ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val LOCKS = ConcurrentHashMap<String, Any>()
    }
}
