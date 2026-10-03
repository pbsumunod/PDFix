package com.sumclartech.paperlane

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.sumclartech.paperlane.ui.PaperlaneApp
import java.io.File

class MainActivity : ComponentActivity() {
    private val model: EditorViewModel by viewModels()
    private var openForm = false
    private var cameraUri: Uri? = null
    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.importPdf(it, openForm) }; openForm = false
    }
    private val mergePdfs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.merge(it) }
    private val images = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.importImages(it) }
    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let(model::saveExport) }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let { model.importImages(listOf(it)) }
    }
    private val scan = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty().map { it.imageUri }
            model.importImages(pages)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraUri = savedInstanceState?.getString("camera_uri")?.let(Uri::parse)
        openForm = savedInstanceState?.getBoolean("open_form", false) ?: false
        setContent {
            PaperlaneApp(model,
                onOpen = { forms -> openForm = forms; openPdf.launch(arrayOf("application/pdf")) },
                onScan = ::startScan,
                onImages = { images.launch(arrayOf("image/*")) },
                onMerge = { mergePdfs.launch(arrayOf("application/pdf")) },
                onSave = { model.export?.let { save.launch(it.file.name) } },
                onShare = { model.shareIntent()?.let { startActivity(Intent.createChooser(it, "Share PDF")) } },
                onBuy = { model.billing.buy(this) },
            )
        }
        if (savedInstanceState == null) acceptPdfIntent(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        cameraUri?.let { outState.putString("camera_uri", it.toString()) }
        outState.putBoolean("open_form", openForm)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptPdfIntent(intent) }
    @Suppress("DEPRECATION")
    private fun acceptPdfIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri?.scheme == "content") model.importPdf(uri)
    }
    override fun onResume() { super.onResume(); model.billing.refresh() }
    override fun onStop() { model.flushFormDraft(); super.onStop() }
    private fun startScan() {
        if (!model.billing.canEditNow()) { model.panel = Panel.UNLOCK; return }
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true).setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { scan.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { startCameraFallback() }
    }
    private fun startCameraFallback() {
        try {
            val directory = File(cacheDir, "camera").apply { mkdirs() }
            val photo = File.createTempFile("scan-", ".jpg", directory)
            cameraUri = FileProvider.getUriForFile(this, "$packageName.files", photo)
            camera.launch(cameraUri!!)
            model.notice = "Use Crop after capture to trim the page."
        } catch (_: Exception) { model.notice = "Camera unavailable. Use Images to PDF to import a photo." }
    }
}
