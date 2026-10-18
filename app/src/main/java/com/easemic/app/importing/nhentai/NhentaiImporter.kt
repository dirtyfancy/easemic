package com.easemic.app.importing.nhentai

import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.importing.downloadedPageName
import com.easemic.app.importing.downloadedPageType
import com.easemic.app.model.DocumentRef
import com.easemic.app.model.ImportCandidate
import com.easemic.app.model.MetadataSnapshot
import java.io.File
import java.util.UUID

sealed class NhentaiImportResult {
    data class Ready(
        val candidate: ImportCandidate,
        val pagesDir: File,
    ) : NhentaiImportResult()

    data class Failed(val message: String) : NhentaiImportResult()
}

/**
 * Saves an nhentai gallery by downloading each page image into
 * app-private storage and building a legacy [ImportCandidate].
 */
class NhentaiImporter(
    private val client: NhentaiClient,
    private val imageGet: NhentaiHttpGet,
    private val downloadsRoot: File,
    private val beforeImageRequest: () -> Unit = {},
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun import(
        galleryId: Long,
        titleHint: String? = null,
    ): NhentaiImportResult {
        val workDir = File(downloadsRoot, idFactory()).also { it.mkdirs() }
        val pagesDir = File(workDir, "pages").also { it.mkdirs() }
        return try {
            val plan = client.fetchGallery(galleryId)
            if (plan.imageUrls.isEmpty()) {
                return NhentaiImportResult.Failed("NHENTAI_EMPTY")
            }
            val docs = mutableListOf<DocumentRef>()
            plan.imageUrls.forEachIndexed { index, imageUrl ->
                val pageNumber = index + 1
                beforeImageRequest()
                val response = imageGet.get(imageUrl)
                when {
                    response.code == 429 || response.code == 503 ->
                        return NhentaiImportResult.Failed("NHENTAI_RATE_LIMITED")
                    response.code !in 200..299 ->
                        return NhentaiImportResult.Failed("NHENTAI_HTTP_${response.code}")
                }
                if (response.body.isEmpty()) {
                    return NhentaiImportResult.Failed("NHENTAI_EMPTY_PAGE")
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
                ?: "${NhentaiEnableStore.DISPLAY_NAME} $galleryId"
            NhentaiImportResult.Ready(
                candidate = ImportCandidate(
                    sourceTreeUri = pagesDir.toURI().toString(),
                    sourceDisplayName = NhentaiEnableStore.DISPLAY_NAME,
                    metadata = MetadataSnapshot(
                        title = title,
                        series = null,
                        alternateSeries = null,
                        creators = (plan.artists + plan.groups).distinct(),
                        genres = listOfNotNull(plan.category),
                        tags = plan.tags,
                        language = plan.language,
                    ),
                    pages = docs,
                ),
                pagesDir = pagesDir,
            )
        } catch (_: NhentaiException.RateLimited) {
            NhentaiImportResult.Failed("NHENTAI_RATE_LIMITED")
        } catch (error: NhentaiException.Http) {
            NhentaiImportResult.Failed("NHENTAI_HTTP_${error.code}")
        } catch (_: Exception) {
            NhentaiImportResult.Failed("NHENTAI_IMPORT_ERROR")
        }
    }
}
