package com.example.shift.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter

object GpxExporter {
    fun generateGpx(name: String, points: List<Pair<Double, Double>>): String {
        val sb = java.lang.StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="Shift" xmlns="http://www.topografix.com/GPX/1/1">""")
        sb.appendLine("  <trk>")
        sb.appendLine("    <name>$name</name>")
        sb.appendLine("    <trkseg>")
        for (p in points) {
            sb.appendLine("""      <trkpt lat="${p.first}" lon="${p.second}"></trkpt>""")
        }
        sb.appendLine("    </trkseg>")
        sb.appendLine("  </trk>")
        sb.appendLine("</gpx>")
        return sb.toString()
    }

    fun saveGpxToFile(context: Context, name: String, gpxContent: String): Uri? {
        return try {
            val file = File(context.cacheDir, "$name.gpx")
            FileWriter(file).use { it.write(gpxContent) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
