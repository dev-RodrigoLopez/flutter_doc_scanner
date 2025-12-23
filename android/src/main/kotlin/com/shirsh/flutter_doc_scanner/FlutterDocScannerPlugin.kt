package com.shirsh.flutter_doc_scanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions

class FlutterDocScannerPlugin: FlutterPlugin, MethodCallHandler, ActivityAware, ActivityResultListener {

    private var channel: MethodChannel? = null
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null

    companion object {
        // Al usar @Volatile aseguramos visibilidad entre hilos en arquitecturas ARM (Huawei)
        @Volatile
        private var pendingResult: Result? = null
        
        private const val REQUEST_CODE_SCAN = 213312
        private const val REQUEST_CODE_SCAN_IMAGES = 215512
        private const val REQUEST_CODE_SCAN_PDF = 216612
        private const val REQUEST_CODE_SCAN_URI = 214412
    }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter_doc_scanner")
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    override fun onAttachedToActivity(@NonNull binding: ActivityPluginBinding) {
        this.activity = binding.activity
        this.activityBinding = binding
        // Re-registramos el listener siempre al re-adjuntar
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        this.activity = null
    }

    override fun onReattachedToActivityForConfigChanges(@NonNull binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        this.activity = null
        // NO limpiar pendingResult aquí
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        // Si hay un resultado previo "colgado", lo limpiamos antes de empezar uno nuevo
        if (pendingResult != null) {
            try {
                pendingResult?.error("CANCELLED", "Prior scan cancelled by new request", null)
            } catch (e: Exception) {}
        }
        
        pendingResult = result

        when (call.method) {
            "getScanDocuments", "getScannedDocumentAsImages", 
            "getScannedDocumentAsPdf", "getScanDocumentsUri" -> {
                val code = when(call.method) {
                    "getScannedDocumentAsImages" -> REQUEST_CODE_SCAN_IMAGES
                    "getScannedDocumentAsPdf" -> REQUEST_CODE_SCAN_PDF
                    "getScanDocumentsUri" -> REQUEST_CODE_SCAN_URI
                    else -> REQUEST_CODE_SCAN
                }
                startScan(code, call)
            }
            else -> {
                pendingResult = null
                result.notImplemented()
            }
        }
    }

    private fun startScan(requestCode: Int, call: MethodCall) {
        val pageLimit = (call.arguments as? Map<*, *>)?.get("page") as? Int ?: 4

        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(pageLimit.coerceAtLeast(1))
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)

        activity?.let { act ->
            scanner.getStartScanIntent(act)
                .addOnSuccessListener { intentSender ->
                    try {
                        // Uso de la actividad directamente para evitar que el intent se pierda
                        act.startIntentSenderForResult(
                            intentSender, requestCode, null, 0, 0, 0
                        )
                    } catch (e: Exception) {
                        fail("INTENT_ERROR", e.message)
                    }
                }
                .addOnFailureListener { e ->
                    fail("SCANNER_UNAVAILABLE", e.message)
                }
        } ?: fail("NO_ACTIVITY", "Activity is null")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val myCodes = listOf(REQUEST_CODE_SCAN, REQUEST_CODE_SCAN_IMAGES, REQUEST_CODE_SCAN_PDF, REQUEST_CODE_SCAN_URI)
        if (!myCodes.contains(requestCode)) return false
        
        // Recuperamos la referencia estática
        val resultToUse = pendingResult
        if (resultToUse == null) return false

        if (resultCode == Activity.RESULT_OK && data != null) {
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data)
            if (scanningResult != null) {
                val response = mutableMapOf<String, Any?>()
                scanningResult.pdf?.let {
                    response["pdfUri"] = it.uri.toString()
                    response["pageCount"] = it.pageCount
                }
                scanningResult.pages?.let { pages ->
                    response["Uri"] = pages.map { it.imageUri.toString() }
                    response["Count"] = pages.size
                }
                resultToUse.success(response)
            } else {
                fail("EMPTY_RESULT", "No data received")
            }
        } else if (resultCode == Activity.RESULT_CANCELED) {
            resultToUse.success(null)
        } else {
            fail("SCAN_ERROR", "Result code: $resultCode")
        }

        pendingResult = null
        return true
    }

    private fun fail(code: String, msg: String?) {
        pendingResult?.error(code, msg, null)
        pendingResult = null
    }
}
