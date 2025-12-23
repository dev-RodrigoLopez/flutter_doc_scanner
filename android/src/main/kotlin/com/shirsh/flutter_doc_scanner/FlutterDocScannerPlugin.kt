package com.shirsh.flutter_doc_scanner

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
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

    // Variable para guardar el resultado pendiente y responder a Flutter después
    private var pendingResult: MethodChannel.Result? = null

    private val REQUEST_CODE_SCAN = 213312
    private val REQUEST_CODE_SCAN_IMAGES = 215512
    private val REQUEST_CODE_SCAN_PDF = 216612
    private val REQUEST_CODE_SCAN_URI = 214412

    private var legacyLauncher: ActivityResultLauncher<IntentSenderRequest>? = null


    // -------------------- Flutter Lifecycle --------------------

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    // -------------------- Activity Lifecycle --------------------

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addActivityResultListener(this)

        // Registrar el launcher para versiones antiguas de Android si es necesario
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            val componentActivity = binding.activity as? ComponentActivity
            componentActivity?.let {
                legacyLauncher =
                    it.registerForActivityResult(
                        ActivityResultContracts.StartIntentSenderForResult()
                    ) { result ->
                        handleActivityResultInternal(result.resultCode, result.data, isLegacy = true)
                    }
            }
        }
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        activity = null
        pendingResult = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    // -------------------- MethodCall Handler --------------------

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (pendingResult != null) {
            result.error("SCAN_IN_PROGRESS", "A scan operation is already running", null)
            return
        }

        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${Build.VERSION.RELEASE}")
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

    // -------------------- Scanner Logic --------------------

    private fun startScan(requestCode: Int, call: MethodCall) {
        // Obtenemos la página, por defecto 4 si no viene
        val page = (call.arguments as? Map<*, *>)?.get("page") as? Int ?: 4

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
        
        // Verificación de seguridad
        if (activity == null) {
            fail("ACTIVITY_NULL", "Activity is null")
            return
        }

        scanner.getStartScanIntent(activity!!)
            .addOnSuccessListener { intentSender ->
                try {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && legacyLauncher != null) {
                        legacyLauncher?.launch(
                            IntentSenderRequest.Builder(intentSender).build()
                        )
                    } else {
                        // Aquí usamos ActivityCompat explícitamente para evitar errores de importación
                        ActivityCompat.startIntentSenderForResult(
                            activity!!,
                            intentSender,
                            requestCode,
                            null,
                            0,
                            0,
                            0,
                            null
                        )
                    }
                } catch (e: Exception) {
                    fail("INTENT_LAUNCH_ERROR", e.localizedMessage)
                }
            }
            .addOnFailureListener { e ->
                // Capturamos el error de inicialización de ML Kit y notificamos a Flutter
                fail("SCAN_START_FAILED", e.localizedMessage)
            }
    }

    // -------------------- Result Handling --------------------

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        // Solo procesamos si tenemos un resultado pendiente y el código coincide con nuestros request codes
        if (pendingResult == null) return false
        
        if (requestCode == REQUEST_CODE_SCAN || 
            requestCode == REQUEST_CODE_SCAN_IMAGES || 
            requestCode == REQUEST_CODE_SCAN_PDF || 
            requestCode == REQUEST_CODE_SCAN_URI) {
            
            handleActivityResultInternal(resultCode, data, isLegacy = false)
            return true
        }
        
        return false
    }

    // Función auxiliar para manejar la lógica de éxito/error en un solo lugar
    private fun handleActivityResultInternal(resultCode: Int, data: Intent?, isLegacy: Boolean) {
        val result = pendingResult ?: return
        
        // Limpiamos el pendingResult al final del proceso, o aquí si ya vamos a responder.
        // Lo seteamos a null justo antes de llamar a success/error para evitar doble llamada.
        
        if (resultCode == Activity.RESULT_CANCELED) {
            pendingResult = null
            result.success(null)
            return
        }

        if (resultCode != Activity.RESULT_OK) {
            fail("SCAN_FAILED", "Scan failed or cancelled")
            return
        }

        val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data)

        if (scanningResult == null) {
            fail("NO_RESULT", "No scanning result found")
            return
        }

        // Éxito: Preparamos la respuesta
        pendingResult = null // Limpiamos la referencia
        
        val successData = mutableMapOf<String, Any>()
        
        // Agregamos datos de PDF si existen
        scanningResult.pdf?.let { pdf ->
            successData["pdfUri"] = pdf.uri.toString()
            successData["pageCount"] = pdf.pageCount
        }

        // Agregamos datos de Imágenes si existen
        scanningResult.pages?.let { pages ->
            successData["Uri"] = pages.map { it.imageUri.toString() }
            successData["Count"] = pages.size
        }

        result.success(successData)
    }

    private fun fail(code: String, message: String?) {
        if (pendingResult == null) return
        try {
            pendingResult?.error(code, message ?: "Unknown error", null)
        } catch (e: Exception) {
            // Ignorar si el canal ya se cerró
        } finally {
            pendingResult = null
        }
    }
}