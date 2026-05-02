package com.openroute.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Xml
import com.openroute.app.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

data class ImportedTrack(
    val name: String,
    val hasExplicitName: Boolean,
    val points: List<LatLngPoint>,
)

class GpxImporter(
    private val contentResolver: ContentResolver,
    private val context: Context,
) {
    suspend fun import(uri: Uri): ImportedTrack = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)?.use(::parseTrack)
            ?: error(context.getString(R.string.gpx_open_failed))
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
            context.getString(R.string.gpx_not_enough_points)
        }

        return ImportedTrack(
            name = discoveredName ?: context.getString(R.string.default_imported_route_name),
            hasExplicitName = discoveredName != null,
            points = points.toList(),
        )
    }
}
