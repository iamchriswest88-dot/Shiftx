package com.example.shift.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.shift.BuildConfig
import com.example.shift.utils.PolylineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class CourseDiagnosticInfo(
    val id: String,
    val name: String,
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val hasEncodedPolyline: Boolean,
    val polylinePointCount: Int
)

@Serializable
data class DiagnosticsPayload(
    val appVersion: String,
    val timestamp: Long,
    val dateString: String,
    val courses: List<CourseDiagnosticInfo>,
    val matches: List<CourseMatch>,
    val scannedCounts: Map<String, Int>,
    val scanLog: List<String>
)

object DiagnosticsExporter {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun generateAndExport(
        context: Context,
        courses: List<Course>,
        matches: List<CourseMatch>,
        scannedCounts: Map<String, Int>
    ): File? = withContext(Dispatchers.IO) {
        try {
            val courseInfos = courses.map { c ->
                val polyPoints = c.encodedPolyline?.let { PolylineUtils.decodePolyline(it) } ?: emptyList()
                CourseDiagnosticInfo(
                    id = c.id,
                    name = c.name,
                    startLat = c.startLat,
                    startLng = c.startLng,
                    endLat = c.endLat,
                    endLng = c.endLng,
                    hasEncodedPolyline = !c.encodedPolyline.isNullOrBlank(),
                    polylinePointCount = polyPoints.size
                )
            }

            val timestampStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
            val payload = DiagnosticsPayload(
                appVersion = BuildConfig.VERSION_NAME,
                timestamp = System.currentTimeMillis(),
                dateString = LocalDateTime.now().toString(),
                courses = courseInfos,
                matches = matches,
                scannedCounts = scannedCounts,
                scanLog = ScanLogBuffer.getLogs()
            )

            val jsonContent = json.encodeToString(payload)
            val fileName = "shiftx-diagnostics-$timestampStr.json"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { os ->
                        os.write(jsonContent.toByteArray(Charsets.UTF_8))
                    }
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val outFile = File(downloadsDir, fileName)
                outFile.writeText(jsonContent)
            }

            // Always write to cacheDir for FileProvider sharing
            val shareFile = File(context.cacheDir, fileName)
            shareFile.writeText(jsonContent)
            shareFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun shareDiagnosticsFile(context: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ShiftX Diagnostics Report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Diagnostics"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
