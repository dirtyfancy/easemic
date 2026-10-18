package com.easemic.app.storage

import kotlinx.coroutines.CancellationException

/**
 * A30 §2.4 (S4b/G1, H1, H2): decides whether a page that would not open is GONE or just
 * unreachable right now -- from a POSITIVE proof, never from the shape of an exception.
 *
 * Exception shape cannot answer this. The stock `ExternalStorageProvider` reports a deleted
 * tree child through `DocumentsProvider.enforceTree` -> `FileSystemProvider.isChildDocument`,
 * which throws `IllegalArgumentException("Failed to determine if … is child of …")` wrapping
 * a FileNotFoundException; and a bare FileNotFoundException also comes out of a dead provider
 * or an ejected volume, where the files are perfectly fine. Guessing either way is how a
 * comic gets marked MISSING for an access problem, which the owner has forbidden.
 *
 * The proof, in order:
 *  1. the provider must answer for the grant root at all;
 *  2. the document's parent must list its children -- absent means gone, present means the
 *     open failure was transient;
 *  3. if the parent itself cannot be listed (H2: the user deleted the comic's own directory,
 *     or its whole series folder), walk UP towards the tree root and let the first ancestor
 *     that answers decide about the child on the path;
 *  4. the answering listing must be complete (I3) -- a provider that is still loading, or
 *     that flagged an error, cannot prove that anything is absent.
 * Matching is by SAF document id (H1): document URIs embed the tree id, so after the user
 * re-picks the parent folder every stored page URI is stale and URI comparison would read
 * present files as gone. A document whose id was never recorded can never be proven absent.
 */
internal object PageContentAbsence {
    /**
     * SAF document ids of the storage providers we import from are path-shaped
     * (`primary:Download/A30-Mihon/Test Manga/Chapter_aaaa11.cbz`), which is what
     * `DocumentsContract.buildChildDocumentsUriUsingTree` consumes, so the parent directory's
     * id is the id up to the last separator. Returns null when there is no parent inside the
     * tree (a root-level id such as `primary:`), where nothing can be proven.
     */
    fun parentDocumentIdOf(documentId: String): String? {
        val separator = documentId.lastIndexOf('/')
        if (separator <= 0) return null
        val parent = documentId.substring(0, separator)
        return parent.takeIf { it.isNotBlank() && !it.endsWith(':') }
    }

    suspend fun classify(
        providerAnswers: suspend () -> Boolean,
        listChildren: suspend (String) -> ChildListing,
        treeRootDocumentId: String?,
        targetDocumentId: String?,
        targetDocumentUri: String?,
    ): PageOpenFailure {
        // No recorded id -> no proof is possible, so nothing may be recorded as missing.
        if (targetDocumentId == null || treeRootDocumentId == null) {
            return PageOpenFailure.PROVIDER_UNAVAILABLE
        }
        val answers = try {
            providerAnswers()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!answers) return PageOpenFailure.PROVIDER_UNAVAILABLE

        var child: String = targetDocumentId
        // Only the document itself may also be matched by URI (extra presence evidence,
        // never absence evidence); ancestors are matched by id alone.
        var childUri: String? = targetDocumentUri
        while (child == targetDocumentId || child.startsWith("$treeRootDocumentId/")) {
            val parent = parentDocumentIdOf(child) ?: return PageOpenFailure.PROVIDER_UNAVAILABLE
            val siblings = try {
                listChildren(parent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // H2: this directory would not list. Either it is gone with everything under
                // it, or storage is misbehaving -- ask its own parent, bounded by the root.
                if (parent == treeRootDocumentId) return PageOpenFailure.PROVIDER_UNAVAILABLE
                child = parent
                childUri = null
                continue
            }
            // S4b/I3: a listing the provider itself calls unfinished (EXTRA_LOADING) or
            // failed (EXTRA_ERROR) says nothing about what is not in it.
            if (!siblings.complete) return PageOpenFailure.PROVIDER_UNAVAILABLE
            val stillThere = siblings.children.any { sibling ->
                sibling.documentId == child || (childUri != null && sibling.uri == childUri)
            }
            // Present but unreachable is a storage problem; absent from a listing the provider
            // answered is the only thing that counts as proof the comic left the phone.
            return if (stillThere) {
                PageOpenFailure.PROVIDER_UNAVAILABLE
            } else {
                PageOpenFailure.CONTENT_GONE
            }
        }
        return PageOpenFailure.PROVIDER_UNAVAILABLE
    }
}
