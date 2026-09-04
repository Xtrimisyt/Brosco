package com.brosco.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Pulled out of FileFixWorker so both the overnight (WorkManager-scheduled)
 * fix flow AND the new "fix this now" immediate flow in CommandProcessor
 * share exactly one implementation of "save the fixed file somewhere
 * downloadable" and "tell Shrey via a notification" - previously this only
 * existed inside FileFixWorker, so the immediate path had no way to
 * actually hand back a downloadable file or notify without either
 * duplicating the code or waiting on WorkManager's own delay.
 */
object FileFixNotifier {

    private const val CHANNEL_ID = "brosco_file_fix"
    private const val NOTIFICATION_ID = 3

    /**
     * Writes the corrected file to the public Downloads folder as
     * "<original name>-fixed.<ext>" so there's a real, tappable,
     * downloadable file waiting - not just text read back in chat. Returns
     * the resulting Uri (API 29+) so a notification can deep-link straight
     * to it, or null if the write failed / on older devices where a plain
     * file path is used instead.
     */
    fun saveToDownloads(context: Context, originalName: String, content: String): Uri? {
        return try {
            val dotIndex = originalName.lastIndexOf('.')
            val outputName = if (dotIndex > 0) {
                originalName.substring(0, dotIndex) + "-fixed" + originalName.substring(dotIndex)
            } else {
                "$originalName-fixed"
            }

            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, outputName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = java.io.File(downloadsDir, outputName)
                file.writeText(content)
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            // Not fatal - the fixed content is still readable via
            // FileFixStore/"give me my fixed file" even if the Downloads
            // write failed on this device.
            Log.w("Brosco", "Couldn't save fixed file to Downloads: ${e.message}")
            null
        }
    }

    /**
     * Posts the "your file is fixed" notification. Tapping it opens the
     * saved file directly (when [fileUri] is available) instead of just
     * bringing Brosco to the foreground, so "gave me a downloadable fixed
     * file" is true from the notification itself, not only from Downloads.
     */
    fun notifyFixReady(context: Context, fileName: String, fileUri: Uri? = null) {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Brosco File Fix",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Brosco fixed $fileName")
                .setContentText("Tap to open the fixed file, or say \"give me my fixed file\".")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setAutoCancel(true)

            if (fileUri != null) {
                try {
                    val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, "text/plain")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        context, NOTIFICATION_ID, viewIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.setContentIntent(pendingIntent)
                } catch (e: Exception) {
                    Log.w("Brosco", "Couldn't attach open-file action to notification: ${e.message}")
                }
            }

            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.w("Brosco", "Couldn't post file-fix-ready notification: ${e.message}")
        }
    }
}

