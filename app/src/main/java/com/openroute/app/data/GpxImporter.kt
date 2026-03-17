package com.openroute.app.data

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

data class ImportedTrack(
    val name: String,
    val points: List<LatLngPoint>,
)

class GpxImporter(
    private val contentResolver: ContentResolver,
) {
    suspend fun import(uri: Uri): ImportedTrack = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)?.use(::parseTrack)
            ?: error("No se pudo abrir el archivo GPX.")
    }

    suspend fun import(file: File): ImportedTrack = withContext(Dispatchers.IO) {
        file.inputStream().use(::parseTrack)
    }

    private fun parseTrack(inputStream: java.io.InputStream): ImportedTrack {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(inputStream, null)
        }

        val points = mutableListOf<LatLngPoint>()
        var eventType = parser.eventType
        var discoveredName: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "trkpt", "rtept" -> {
                            val latitude = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val longitude = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            if (latitude != null && longitude != null) {
                                points += LatLngPoint(latitude = latitude, longitude = longitude)
                            }
                        }

                        "name" -> {
                            val value = parser.nextText().trim()
                            if (!value.isNullOrBlank() && discoveredName == null) {
                                discoveredName = value
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        require(points.size >= 2) {
            "El archivo GPX no contiene suficientes puntos de ruta."
        }

        return ImportedTrack(
            name = discoveredName ?: "Imported GPX",
            points = points.toList(),
        )
    }
}
