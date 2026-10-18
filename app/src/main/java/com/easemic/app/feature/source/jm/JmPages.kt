package com.easemic.app.feature.source.jm

import com.easemic.app.feature.download.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Download-time reconstruction. Caller offsets belong to output and never become wire ranges. */
internal class JmPages(private val api: JmApi, private val reassembler: JmReassembler = JmImageReassembler()) : DownloadPageBytesAdapter {
    override fun supports(identity: GalleryIdentityV1): Boolean = identity.providerNamespace == GalleryIdentityV1.JM &&
        identity.canonicalItemId.split('\u0000').let { it.size == 2 && it.all { id -> Regex("[0-9]+").matches(id) } }
    override fun ownsRequestPacing(identity: GalleryIdentityV1, locator: DownloadLocator) = true
    override fun prefersFileBackedOnlineRead(identity: GalleryIdentityV1, locator: DownloadLocator) = true
    // Default false: the worker deadline bounds fetch + decode + encode together.
    override fun fetchManifest(identity: GalleryIdentityV1, locator: DownloadLocator): DownloadPageManifest = runBlocking {
        val (series,id) = key(identity,locator)
        api.domains.refresh()
        val plan = api.chapterPlan(series,id)
        DownloadPageManifest(plan.chapter.filenames.size,plan.chapter.name)
    }
    override fun fetchPageBytes(identity: GalleryIdentityV1, locator: DownloadLocator, ordinal: Int): ByteArray =
        throw UnsupportedOperationException("JM pages require file transfer")

    override suspend fun fetchPageToFile(identity: GalleryIdentityV1, locator: DownloadLocator, ordinal: Int, destination: Path, offset: Long): PageTransferResult = withContext(Dispatchers.IO) {
        require(offset >= 0)
        val (series,id) = key(identity,locator)
        val source = destination.resolveSibling("${destination.fileName}.jm-source.part")
        val output = destination.resolveSibling("${destination.fileName}.jm-output.part")
        try {
            Files.createDirectories(destination.toAbsolutePath().parent)
            // Discard an old transformed prefix. It has no relation to the source's byte offsets.
            Files.deleteIfExists(destination)
            Files.deleteIfExists(source)
            Files.deleteIfExists(output)
            val plan = api.chapterPlan(series,id)
            val filename = plan.chapter.filenames.getOrNull(ordinal) ?: throw InvalidPageTransferException("JM page ordinal is invalid")
            val wireBytes = api.imageToFile(id,filename,source)
            currentCoroutineContext().ensureActive()
            val magic = JmImageMagic.read(source)
            val bands = JmBandPlan.count(plan.scrambleId,id.toLong(),filename.substringBeforeLast('.'))
            var timing: JmTransformTiming? = null
            if (magic == JmImageMagic.GIF || bands == 0) {
                Files.move(source,output,StandardCopyOption.REPLACE_EXISTING)
            } else reassembler.reassembleMeasured(source, output, bands) { timing = it }
            currentCoroutineContext().ensureActive()
            val size = Files.size(output)
            if (size <= 0) throw InvalidPageTransferException("JM reconstructed page is empty")
            timing?.let { measured ->
                com.easemic.app.sync.DiagLog.i("JmTransform",
                    "bands=$bands size=${measured.width}x${measured.height} inBytes=$wireBytes outBytes=$size " +
                        "decodeMs=${measured.decodeMs} encodeMs=${measured.encodeMs}")
            }
            runInterruptible { Files.move(output,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
            currentCoroutineContext().ensureActive()
            PageTransferResult(bytesWritten=size,expectedBytes=size,transferredBytes=wireBytes)
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(destination) }
            throw error
        } finally {
            runCatching { Files.deleteIfExists(source) }
            runCatching { Files.deleteIfExists(output) }
        }
    }
    override fun invalidatePlan(identity: GalleryIdentityV1) {
        if (supports(identity)) identity.canonicalItemId.split('\u0000').let { api.invalidateChapter(it[0],it[1]) }
    }
    private fun key(identity: GalleryIdentityV1, locator: DownloadLocator): Pair<String,String> {
        if (!supports(identity)) JmFailure.invalid()
        val parsed = parseSerializedChapterLocatorKey(locator.acquisitionUrl) ?: JmFailure.invalid()
        if (serializedChapterAdapterIdentity(parsed.first,parsed.second) != identity) JmFailure.invalid()
        return JmDecoder.id(parsed.first.canonicalSeriesId) to JmDecoder.id(parsed.second)
    }
}
