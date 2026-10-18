package com.easemic.app.importing.ehentai

import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.importing.downloadedPageName
import com.easemic.app.importing.downloadedPageType
import com.easemic.app.model.DocumentRef
import com.easemic.app.model.ImportCandidate
import com.easemic.app.model.MetadataSnapshot
import java.io.File
import java.util.UUID

sealed class EhentaiImportResult {
    data class Ready(
        val candidate: ImportCandidate,
        val pagesDir: File,
    ) : EhentaiImportResult()

    data class Failed(val message: String) : EhentaiImportResult()
}

/**
 * Saves an E-Hentai gallery by downloading each page image into
 * app-private storage and building a legacy [ImportCandidate].
 */
class EhentaiImporter(
    private val client: EhentaiClient,
    private val imageGet: EhentaiHttpGet,
    private val downloadsRoot: File,
    private val beforeImageRequest: () -> Unit = {},
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun import(
        galleryId: Long,
        token: String,
        titleHint: String? = null,
    ): EhentaiImportResult {
        val workDir = File(downloadsRoot, idFactory()).also { it.mkdirs() }
        val pagesDir = File(workDir, "pages").also { it.mkdirs() }
        return try {
            val plan = client.fetchGallery(galleryId = galleryId, token = token)
            if (plan.pageViewPaths.isEmpty()) {
                return EhentaiImportResult.Failed("EHENTAI_EMPTY")
            }
            val docs = mutableListOf<DocumentRef>()
            plan.pageViewPaths.forEachIndexed { index, pagePath ->
                val pageNumber = index + 1
                val pageImage = client.fetchPageImage(pageViewPath = pagePath, pageNumber = pageNumber)
                beforeImageRequest()
                val response = imageGet.get(pageImage.imageUrl)
                when {
                    response.code == 429 || response.code == 503 ->
                        return EhentaiImportResult.Failed("EHENTAI_RATE_LIMITED")
                    response.code !in 200..299 ->
                        return EhentaiImportResult.Failed("EHENTAI_HTTP_${response.code}")
                }
                if (response.body.isEmpty()) {
                    return EhentaiImportResult.Failed("EHENTAI_EMPTY_PAGE")
                }
                val contentType = downloadedPageType(response.body)
                val fileName = downloadedPageName(pageNumber, contentType)
                val file = File(pagesDir, fileName)
                file.writeBytes(response.body)
                docs += DocumentRef(
                    documentId = fileName,
                    uri = file.toURI().toString(),
                    displayName = fileName,
                    mimeType = contentType.mime,
                )
            }
            val title = titleHint?.takeIf { it.isNotBlank() }
                ?: plan.title.takeIf { it.isNotBlank() }
                ?: "${EhentaiEnableStore.DISPLAY_NAME} $galleryId"
            EhentaiImportResult.Ready(
                candidate = ImportCandidate(
                    sourceTreeUri = pagesDir.toURI().toString(),
                    sourceDisplayName = EhentaiEnableStore.DISPLAY_NAME,
                    metadata = MetadataSnapshot(
                        title = title,
                        series = null,
                        alternateSeries = null,
                        creators = emptyList(),
                        genres = emptyList(),
                        tags = emptyList(),
                        language = null,
                    ),
                    pages = docs,
                ),
                pagesDir = pagesDir,
            )
        } catch (error: EhentaiException.RateLimited) {
            EhentaiImportResult.Failed("EHENTAI_RATE_LIMITED")
        } catch (error: EhentaiException.Http) {
            EhentaiImportResult.Failed("EHENTAI_HTTP_${error.code}")
        } catch (_: Exception) {
            EhentaiImportResult.Failed("EHENTAI_IMPORT_ERROR")
        }
    }
}
