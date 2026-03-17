package com.ooustream.iptv.common

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.firebase.storage.FirebaseStorage
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.ooustream.iptv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Packages diagnostic logs and uploads to Firebase Storage.
 * TV: shows QR code with reference ID for customer to share with support.
 * Phone: uploads then opens email with reference ID.
 */
@Singleton
class SendDebugLogManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: StreamDiagnosticLogger
) {
    companion object {
        private const val SUPPORT_EMAIL = "info@ooustick.com"
    }

    fun sendDebugLog(activityContext: Context, description: String = "") {
        try {
            val logFile = logger.exportCombinedLog()
            uploadToFirebase(activityContext, logFile, description)
        } catch (e: Exception) {
            Toast.makeText(
                activityContext,
                "Failed to prepare debug log: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun uploadToFirebase(activityContext: Context, logFile: File, description: String) {
        val refId = "DL-${UUID.randomUUID().toString().take(8).uppercase()}"
        val deviceInfo = "${Build.BRAND} ${Build.MODEL}"
        val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val storagePath = "debug-logs/$dateStr-$refId.txt"

        // Show uploading dialog
        val progressDialog = AlertDialog.Builder(activityContext)
            .setTitle("Uploading Debug Log...")
            .setMessage("Reference: $refId")
            .setView(ProgressBar(activityContext).apply {
                setPadding(0, 32, 0, 32)
            })
            .setCancelable(false)
            .show()

        val storageRef = FirebaseStorage.getInstance().reference.child(storagePath)
        val uri = android.net.Uri.fromFile(logFile)

        // Add metadata: description + device info
        val metadata = com.google.firebase.storage.StorageMetadata.Builder()
            .setCustomMetadata("ref_id", refId)
            .setCustomMetadata("device", deviceInfo)
            .setCustomMetadata("app_version", BuildConfig.VERSION_NAME)
            .setCustomMetadata("android_version", Build.VERSION.RELEASE)
            .setCustomMetadata("description", description.take(500))
            .setContentType("text/plain")
            .build()

        storageRef.putFile(uri, metadata)
            .addOnSuccessListener {
                progressDialog.dismiss()
                if (DeviceUtils.isTV(activityContext)) {
                    showQrCodeDialog(activityContext, refId, description)
                } else {
                    showSuccessAndEmail(activityContext, refId, description)
                }
            }
            .addOnFailureListener { e ->
                progressDialog.dismiss()
                // Fallback: show log locally if upload fails
                Toast.makeText(
                    activityContext,
                    "Upload failed: ${e.message}. Log saved locally.",
                    Toast.LENGTH_LONG
                ).show()
                showLocalFallback(activityContext, logFile)
            }
    }

    /**
     * Fire TV: Show QR code with mailto: link containing reference ID.
     * Customer scans with phone → email opens pre-filled.
     */
    private fun showQrCodeDialog(activityContext: Context, refId: String, description: String) {
        val deviceInfo = "${Build.BRAND} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        val subject = "Ooustream Debug Log — $refId"
        val body = "Reference: $refId\nDevice: $deviceInfo\nApp: ${BuildConfig.VERSION_NAME}\n\n" +
                "Issue: ${description.ifBlank { "(describe your issue here)" }}"
        val mailtoUrl = "mailto:$SUPPORT_EMAIL?subject=${android.net.Uri.encode(subject)}&body=${android.net.Uri.encode(body)}"

        val qrBitmap = generateQrCode(mailtoUrl, 400)

        val layout = LinearLayout(activityContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 32, 48, 32)
        }

        if (qrBitmap != null) {
            layout.addView(ImageView(activityContext).apply {
                setImageBitmap(qrBitmap)
                layoutParams = LinearLayout.LayoutParams(400, 400).apply {
                    gravity = Gravity.CENTER
                    bottomMargin = 24
                }
            })
        }

        layout.addView(TextView(activityContext).apply {
            text = "Reference: $refId"
            textSize = 18f
            setTextColor(0xFFFFD700.toInt()) // Gold
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        layout.addView(TextView(activityContext).apply {
            text = "Log uploaded successfully!\n\n" +
                    "Scan this QR code with your phone to email support,\n" +
                    "or contact $SUPPORT_EMAIL with reference:\n$refId"
            textSize = 13f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        })

        AlertDialog.Builder(activityContext)
            .setTitle("Debug Log Sent")
            .setView(layout)
            .setPositiveButton("Close", null)
            .show()
    }

    /**
     * Phone: show success toast and open email with reference ID.
     */
    private fun showSuccessAndEmail(activityContext: Context, refId: String, description: String) {
        val deviceInfo = "${Build.BRAND} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Ooustream Debug Log — $refId")
            putExtra(Intent.EXTRA_TEXT, buildString {
                appendLine("Reference: $refId")
                appendLine()
                appendLine("Issue Description:")
                appendLine(description.ifBlank { "(No description provided)" })
                appendLine()
                appendLine("Device: $deviceInfo")
                appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Date: $dateStr")
                appendLine()
                appendLine("Debug log uploaded to Firebase Storage with reference $refId.")
            })
        }

        try {
            activityContext.startActivity(Intent.createChooser(intent, "Email Support"))
        } catch (_: Exception) {
            Toast.makeText(
                activityContext,
                "Log uploaded! Reference: $refId\nEmail $SUPPORT_EMAIL with this reference.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showLocalFallback(activityContext: Context, logFile: File) {
        AlertDialog.Builder(activityContext)
            .setTitle("Debug Log Saved")
            .setMessage("Log saved to: ${logFile.absolutePath}\n\nContact $SUPPORT_EMAIL for support.")
            .setPositiveButton("Close", null)
            .show()
    }

    private fun generateQrCode(content: String, size: Int): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix: BitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            bitmap
        } catch (_: Exception) { null }
    }
}
