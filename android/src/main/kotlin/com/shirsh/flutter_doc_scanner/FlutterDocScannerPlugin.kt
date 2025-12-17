package com.shirsh.flutter_doc_scanner

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import androidx.activity.result.IntentSenderRequest
import androidx.core.app.ActivityCompat.startIntentSenderForResult
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener

class FlutterDocScannerPlugin :
    FlutterPlugin,
    ActivityAware,
    MethodChannel.MethodCallHandler,
    ActivityResultListener {

    private val CHANNEL = "flutter_doc_scanner"

    private var channel: MethodChannel? = null
    private var activity: Activity? = null
    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null

    private var resultChannel: MethodChannel.Result? = null

    private val REQUEST_CODE_SCAN = 213312
    private val REQUEST_CODE_SCAN_URI = 214412
    private val REQUEST_CODE_SCAN_IMAGES = 215512
    private val REQUEST_CODE_SCAN_PDF = 216612

    // ------------------------------------------------------------------------
    // Flutter calls
    // ------------------------------------------------------------------------

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val arguments = call.arguments as? Map<*, *>
        val page = (arguments?.get("page") as? Int)?.coerceAtLeast(1) ?: 4

        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }

            "getScanDocuments" -> {
                resultChannel = result
                startScan(page, REQUEST_CODE_SCAN)
            }

            "getScannedDocumentAsImages" -> {
                resultChannel = result
                startScan(page, REQUEST_CODE_SCAN_IMAGES)
            }

            "getScannedDocumentAsPdf" -> {
                resultChannel = result
                startScan(page, REQUEST_CODE_SCAN_PDF)
            }

            "getScanDocumentsUri" -> {
                resultChannel = result
                startScan(page, REQUEST_CODE_SCAN_URI)
            }

            else -> result.notImplemented()
        }
    }

    // ------------------------------------------------------------------------
    // Scan starter
    // ------------------------------------------------------------------------

    private fun startScan(page: Int, requestCode: Int) {
        if (activity == null) {
            resultChannel?.error("NO_ACTIVITY", "Activity is null", null)
            resultChannel = null
            return
        }

        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(page)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        val task: Task<IntentSender>? = scanner.getStartScanIntent(activity!!)

        task?.addOnSuccessListener { intentSender ->
            try {
                startIntentSenderForResult(
                    activity!!,
                    IntentSenderRequest.Builder(intentSender).build().intentSender,
                    requestCode,
                    null,
                    0,
                    0,
                    0,
                    null
                )
            } catch (e: Exception) {
                resultChannel?.error("INTENT_ERROR", e.message, null)
                resultChannel = null
            }
        }?.addOnFailureListener { e ->
            resultChannel?.error("SCAN_FAILED", e.message, null)
            resultChannel = null
        }
    }

    // ------------------------------------------------------------------------
    // Activity result
    // ------------------------------------------------------------------------

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {

            REQUEST_CODE_SCAN,
            REQUEST_CODE_SCAN_PDF -> {
                if (resultCode == Activity.RESULT_OK) {
                    val result = GmsDocumentScanningResult.fromActivityResultIntent(data)
                    result?.getPdf()?.let { pdf ->
                        resultChannel?.success(
                            mapOf(
                                "pdfUri" to pdf.uri.toString(),
                                "pageCount" to pdf.pageCount
                            )
                        )
                    } ?: resultChannel?.error("SCAN_FAILED", "No PDF returned", null)
                } else {
                    resultChannel?.success(null)
                }
                resultChannel = null
            }

            REQUEST_CODE_SCAN_IMAGES,
            REQUEST_CODE_SCAN_URI -> {
                if (resultCode == Activity.RESULT_OK) {
                    val result = GmsDocumentScanningResult.fromActivityResultIntent(data)
                    result?.getPages()?.let { pages ->
                        resultChannel?.success(
                            mapOf(
                                "Uri" to pages.map { it.imageUri.toString() },
                                "Count" to pages.size
                            )
                        )
                    } ?: resultChannel?.error("SCAN_FAILED", "No images returned", null)
                } else {
                    resultChannel?.success(null)
                }
                resultChannel = null
            }
        }
        return true
    }

    // ------------------------------------------------------------------------
    // Plugin lifecycle
    // ------------------------------------------------------------------------

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        pluginBinding = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        activity = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }
}
