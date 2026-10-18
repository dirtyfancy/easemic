package com.easemic.app.sync

/**
 * The v5 object writer only knows how to make packs and manifests.  This seam
 * supplies their names, keeping the long-established gallery layout byte for
 * byte while allowing format-2 chapters to live below an opaque prefix.
 */
interface CloudObjectLayout {
    val objectId: String
    fun prefix(): String
    fun metaKey(): String
    fun pagesManifestKey(): String
    fun pagePackKey(packIndex: Int): String
    fun thumbPackKey(firstOrdinal: Int): String
    fun pageKeyByHash(hash: String, ext: String): String
    fun pageKey(ordinal: Int, ext: String): String
}

class GalleryCloudObjectLayout(
    override val objectId: String,
) : CloudObjectLayout {
    override fun prefix(): String = CloudCollectionKeys.comicPrefix(objectId)
    override fun metaKey(): String = CloudCollectionKeys.metaKey(objectId)
    override fun pagesManifestKey(): String = CloudCollectionKeys.pagesManifestKey(objectId)
    override fun pagePackKey(packIndex: Int): String = CloudCollectionKeys.pagePackKey(objectId, packIndex)
    override fun thumbPackKey(firstOrdinal: Int): String = CloudCollectionKeys.thumbPackKey(objectId, firstOrdinal)
    override fun pageKeyByHash(hash: String, ext: String): String = CloudCollectionKeys.pageKeyByHash(objectId, hash, ext)
    override fun pageKey(ordinal: Int, ext: String): String = CloudCollectionKeys.pageKey(objectId, ordinal, ext)
}

class ChapterCloudObjectLayout(
    override val objectId: String,
    private val chapterKey: String,
) : CloudObjectLayout {
    override fun prefix(): String = CloudCollectionKeys.chapterPrefix(objectId, chapterKey)
    override fun metaKey(): String = CloudCollectionKeys.chapterMetaKey(objectId, chapterKey)
    override fun pagesManifestKey(): String = CloudCollectionKeys.chapterPagesManifestKey(objectId, chapterKey)
    override fun pagePackKey(packIndex: Int): String = CloudCollectionKeys.chapterPagePackKey(objectId, chapterKey, packIndex)
    override fun thumbPackKey(firstOrdinal: Int): String = CloudCollectionKeys.chapterThumbPackKey(objectId, chapterKey, firstOrdinal)
    override fun pageKeyByHash(hash: String, ext: String): String = CloudCollectionKeys.chapterPageKeyByHash(objectId, chapterKey, hash, ext)
    override fun pageKey(ordinal: Int, ext: String): String =
        CloudCollectionKeys.chapterPrefix(objectId, chapterKey) + "pages/${requireOrdinal(ordinal)}.${requireObjectExt(ext)}"
}
