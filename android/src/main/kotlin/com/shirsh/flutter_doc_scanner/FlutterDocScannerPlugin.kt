package com.shirsh.flutter_doc_scanner

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import androidx.activity.result.IntentSenderRequest
import androidx.core.app.ActivityCompat.startIntentSenderForResult
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.documentscanner.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener

class FlutterDocScannerPlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    ActivityAware,
    ActivityResultListener {

    private val CHANNEL = "flutter_doc_scanner"

    private var channel: MethodChannel? = null
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null

    // 🔑 CLAVE: Result nullable
    private var pendingResult: MethodChannel.Result? = null

    private val REQUEST_CODE_SCAN = 213312
    private val REQUEST_CODE_SCAN_IMAGES = 215512
    private val REQUEST_CODE_SCAN_PDF = 216612
    private val REQUEST_CODE_SCAN_URI = 214412

    // -------------------- Flutter --------------------

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    // -------------------- Activity --------------------

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        activity = null
        pendingResult = null // 🔥 limpieza defensiva
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    // -------------------- MethodCall --------------------

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {

        if (pendingResult != null) {
            result.error(
                "SCAN_IN_PROGRESS",
                "A scan operation is already running",
                null
            )
            return
        }

        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }

            "getScanDocuments" -> {
                pendingResult = result
                startScan(REQUEST_CODE_SCAN, call)
            }

            "getScannedDocumentAsImages" -> {
                pendingResult = result
                startScan(REQUEST_CODE_SCAN_IMAGES, call)
            }

            "getScannedDocumentAsPdf" -> {
                pendingResult = result
                startScan(REQUEST_CODE_SCAN_PDF, call)
            }

            "getScanDocumentsUri" -> {
                pendingResult = result
                startScan(REQUEST_CODE_SCAN_URI, call)
            }

            else -> result.notImplemented()
        }
    }

    // -------------------- Scanner --------------------

    private fun startScan(requestCode: Int, call: MethodCall) {
        val page =
            (call.arguments as? Map<*, *>)?.get("page") as? Int ?: 4

        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(page.coerceAtLeast(1))
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        val task: Task<IntentSender>? =
            activity?.let { scanner.getStartScanIntent(it) }

        task?.addOnSuccessListener { intentSender ->
            try {
                startIntentSenderForResult(
                    activity!!,
                    intentSender,
                    requestCode,
                    null,
                    0,
                    0,
                    0,
                    null
                )
            } catch (e: Exception) {
                fail("INTENT_ERROR", e.message)
            }
        }?.addOnFailureListener {
            fail("SCAN_START_FAILED", it.message)
        }
    }

    // -------------------- Result --------------------

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ): Boolean {

        val result = pendingResult ?: return false
        pendingResult = null

        if (resultCode == Activity.RESULT_CANCELED) {
            result.success(null)
            return true
        }

        if (resultCode != Activity.RESULT_OK) {
            result.error("SCAN_FAILED", "Scan failed", null)
            return true
        }

        val scanningResult =
            GmsDocumentScanningResult.fromActivityResultIntent(data)

        when (requestCode) {

            REQUEST_CODE_SCAN_PDF, REQUEST_CODE_SCAN -> {
                val pdf = scanningResult?.pdf
                if (pdf != null) {
                    result.success(
                        mapOf(
                            "pdfUri" to pdf.uri.toString(),
                            "pageCount" to pdf.pageCount
                        )
                    )
                } else {
                    result.error("NO_PDF", "No PDF result", null)
                }
            }

            REQUEST_CODE_SCAN_IMAGES, REQUEST_CODE_SCAN_URI -> {
                val pages = scanningResult?.pages
                if (pages != null) {
                    result.success(
                        mapOf(
                            "Uri" to pages.map { it.imageUri.toString() },
                            "Count" to pages.size
                        )
                    )
                } else {
                    result.error("NO_IMAGES", "No images returned", null)
                }
            }
        }

        return true
    }

    private fun fail(code: String, message: String?) {
        pendingResult?.error(code, message, null)
        pendingResult = null
    }
}
