package com.openroute.app.data

import android.os.Environment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DownloadsImportResult(
    val importedRoutes: List<RouteTrack> = emptyList(),
    val failedFiles: Int = 0,
)

class DownloadsGpxAutoImporter(
    private val importer: GpxImporter,
) {
    suspend fun importNewFiles(existingReferences: Set<String>): DownloadsImportResult =
        withContext(Dispatchers.IO) {
            val downloadsDirectory =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

            if (!downloadsDirectory.exists() || !downloadsDirectory.isDirectory) {
                return@withContext DownloadsImportResult()
            }

            val importedRoutes = mutableListOf<RouteTrack>()
            var failedFiles = 0

            downloadsDirectory.walkTopDown()
                .maxDepth(3)
                .filter { file -> file.isFile && file.extension.equals("gpx", ignoreCase = true) }
                .sortedByDescending(File::lastModified)
                .forEach { file ->
                    val reference = file.toImportReference()
                    if (reference in existingReferences) {
                        return@forEach
                    }

                    val importedTrack = runCatching { importer.import(file) }.getOrElse {
                        failedFiles += 1
                        return@forEach
                    }

                    importedRoutes += RouteTrack(
                        id = UUID.randomUUID().toString(),
                        name = importedTrack.resolveRouteName(file),
                        source = RouteSource.IMPORTED_GPX,
                        createdAtMillis = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
                        distanceMeters = importedTrack.points.distanceMeters(),
                        points = importedTrack.points,
                        isNew = true,
                        importReference = reference,
                        originalFileName = file.name,
                    )
                }

            DownloadsImportResult(
                importedRoutes = importedRoutes.sortedByDescending(RouteTrack::createdAtMillis),
                failedFiles = failedFiles,
            )
        }
}

private fun ImportedTrack.resolveRouteName(file: File): String {
    return name.takeUnless { it == DEFAULT_IMPORTED_ROUTE_NAME } ?: file.nameWithoutExtension
        .replace('_', ' ')
}

private fun File.toImportReference(): String = "$absolutePath::${length()}::${lastModified()}"

private const val DEFAULT_IMPORTED_ROUTE_NAME = "Imported GPX"
