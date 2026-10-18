package com.easemic.app.sync

import java.io.File

/** Content op attempted on an encrypted library before its password was entered. */
class LibraryLockedException : Exception(MESSAGE) {
    companion object {
        const val MESSAGE = "This library is encrypted. Unlock it in More → Cloud storage."
    }
}

enum class LibraryLockState { UNKNOWN, PLAIN, LOCKED, UNLOCKED }

/**
 * #C64: a store wrapped before the last bind change was used. The bucket it
 * points at is no longer the bound one, so it may neither write nor decide
 * the shared gate. Carries no identifiers.
 */
class StaleCloudBindingException : IllegalStateException(MESSAGE) {
    companion object {
        const val MESSAGE = "CLOUD_BINDING_REPLACED"
    }
}

/**
 * Process-scoped encryption gate. Wraps the product [ObjectStore]; on first
 * content use it probes `library.lock` (one GET) and settles into PLAIN
 * (pass-through), UNLOCKED (seal/open via [EncryptedObjectStore]) or LOCKED
 * (content ops throw [LibraryLockedException]; listing stays available so the
 * usage stat keeps working). A cached device DEK unlocks silently — the
 * creator's phone never sees a prompt. GCM sealing can't detect a wrong key on
 * *encrypt*, so a cached DEK is verified against the lock's `dek_id_b64`
 * identity tag during the probe, before any op — a stale cache (library
 * recreated) is rejected and cleared there, never used to seal a write.
 *
 * #C64: each wrapper is born in a binding generation and dies when it moves
 * on; see [beginSwitch].
 */
class CloudLibraryGate(
    private val loadCachedDek: () -> ByteArray?,
    private val clearCachedDek: () -> Unit,
) {
    @Volatile private var state: LibraryLockState = LibraryLockState.UNKNOWN
    @Volatile private var dek: ByteArray? = null

    /** #C64: bumped by every bind change; a wrapper is live only in the generation it was born in. */
    @Volatile private var generation: Long = 0L
    /** #C64: true between [beginSwitch] and [finishSwitch], while the durable binding is rewritten. */
    @Volatile private var switching: Boolean = false

    fun state(): LibraryLockState = state

    fun generation(): Long = generation

    @Synchronized
    fun reset() {
        generation += 1
        switching = false
        state = LibraryLockState.UNKNOWN
        dek = null
    }

    /** #C64: a bind commit is about to rewrite the durable binding — every existing wrapper dies now. */
    @Synchronized
    fun beginSwitch() {
        generation += 1
        switching = true
        state = LibraryLockState.UNKNOWN
        dek = null
    }

    /**
     * #C64: the new binding is durable. An encrypted empty-bucket bind hands
     * its DEK here and starts UNLOCKED; otherwise the next content op probes.
     */
    @Synchronized
    fun finishSwitch(newDek: ByteArray?) {
        if (newDek != null) require(newDek.size == DEK_LEN) { "dek must be $DEK_LEN bytes" }
        generation += 1
        switching = false
        dek = newDek
        state = if (newDek != null) LibraryLockState.UNLOCKED else LibraryLockState.UNKNOWN
    }

    /**
     * Successful password unlock of the SAME bucket. Returns false — and changes
     * nothing — when a bind change happened since [born] (#C64).
     */
    @Synchronized
    fun unlockedWith(dek: ByteArray, born: Long = generation): Boolean {
        require(dek.size == DEK_LEN) { "dek must be $DEK_LEN bytes" }
        if (switching || born != generation) return false
        this.dek = dek
        state = LibraryLockState.UNLOCKED
        return true
    }

    /** Bind-screen refresh: resolve and report without needing a content op. */
    fun probe(raw: ObjectStore, born: Long = generation): LibraryLockState {
        ensureResolved(raw, born)
        return state
    }

    fun wrap(raw: ObjectStore, born: Long = generation): ObjectStore = GatedObjectStore(raw, born)

    @Synchronized
    private fun checkLive(born: Long) {
        if (switching || born != generation) throw StaleCloudBindingException()
    }

    /** Atomic read of (state, dek) for a live wrapper: a lone read can race a concurrent reset. */
    @Synchronized
    private fun snapshot(born: Long): Pair<LibraryLockState, ByteArray?> {
        checkLive(born)
        return state to dek
    }

    @Synchronized
    private fun ensureResolved(raw: ObjectStore, born: Long) {
        checkLive(born)
        // Already settled: no work, and deliberately no trace line — the
        // reader's 24-page prefetch calls this once per op (C34-d). A
        // settled gate logs nothing per op; a resolve that throws leaves
        // the gate UNKNOWN and is retried — and traced — per op,
        // deliberately.
        if (state != LibraryLockState.UNKNOWN) return
        val startedNs = System.nanoTime()
        var confirm = "none"
        var outcome = "ERROR"
        try {
            resolveUnknownLocked(raw) { confirm = "prefix" }
            outcome = state.name
        } finally {
            CloudOpenTrace.log(
                phase = "gate-resolve",
                ms = (System.nanoTime() - startedNs) / 1_000_000L,
                extras = "outcome=$outcome confirm=$confirm",
            )
        }
    }

    /**
     * The probe itself. Called only while [state] is UNKNOWN and only from
     * [ensureResolved], which owns the `@Synchronized` monitor and the trace.
     * [onPrefixConfirm] fires when the 404-looking GET had to be confirmed
     * with a listing, whether or not that confirm ends in PLAIN.
     */
    private fun resolveUnknownLocked(raw: ObjectStore, onPrefixConfirm: () -> Unit) {
        val lockKey = CloudCollectionKeys.lockKey()
        val lockBytes = try {
            raw.download(lockKey)
        } catch (error: Exception) {
            if (looksLikeMissingObject(error)) {
                // A GET failure that merely looks like a 404 could be a transport
                // quirk. Confirm via a listing before declaring PLAIN — if the
                // lock still shows up there, the GET failure was something else.
                // The confirm needs only the one key, so ask for that prefix
                // (one request, <=1 page) instead of paging the whole bucket
                // (C34-b: this cold-path listing was measured at 6-13s).
                onPrefixConfirm()
                if (raw.listKeys(prefix = lockKey).none { it == lockKey }) {
                    state = LibraryLockState.PLAIN
                    return
                }
                throw error
            }
            throw error
        }
        // Cheap parse-only integrity check — no KDF, no password needed yet.
        val lockJson = String(lockBytes, Charsets.UTF_8)
        LibraryKeyslot.validate(lockJson)

        val cached = loadCachedDek()
        val accepted = cached?.takeIf {
            it.size == DEK_LEN && LibraryKeyslot.dekId(it) == LibraryKeyslot.dekIdFrom(lockJson)
        }
        if (accepted != null) {
            dek = accepted
            state = LibraryLockState.UNLOCKED
        } else {
            // Wrong length, or its identity doesn't match this lock (library was
            // recreated): a stale cache. Clear it before it can seal a write
            // under a key that no longer opens anything.
            if (cached != null) clearCachedDek()
            dek = null
            state = LibraryLockState.LOCKED
        }
    }

    private inner class GatedObjectStore(private val raw: ObjectStore, private val born: Long) : ObjectStore {
        /** #C50: v2 headers opened by any content store of this wrapper (one binding generation). */
        private val v2Headers = V2HeaderMemo()

        private fun contentStore(): ObjectStore {
            ensureResolved(raw, born)
            val (currentState, currentDek) = snapshot(born)
            return when (currentState) {
                LibraryLockState.PLAIN -> raw
                LibraryLockState.UNLOCKED -> EncryptedObjectStore(raw, checkNotNull(currentDek), v2Headers)
                else -> throw LibraryLockedException()
            }
        }

        override fun chapterKeyMaterial(): ByteArray = contentStore().chapterKeyMaterial()

        // #C67 (interim, until #C50's chunked format): an encrypted pack cannot be read by
        // Range — [EncryptedObjectStore.downloadRange] downloads and decrypts the whole
        // object, and the reader asks per 4 MiB window, several at once, so a 24.8 MB pack
        // moved ~148 MB. This wrapper lives for one binding generation (a fresh
        // [contentStore] is built per call), so it keeps the one decrypted pack here: a
        // one-entry memo + per-key single-flight + write invalidation, the #C54 shape
        // ([R2RestObjectStore]) on the plaintext side. [memoLock] never spans a request.
        private val memoLock = Any()
        private var packMemo: Pair<String, ByteArray>? = null
        private val packFlights = HashMap<String, PackFlight>()

        private inner class PackFlight {
            val latch = java.util.concurrent.CountDownLatch(1)
            @Volatile var bytes: ByteArray? = null
            @Volatile var error: Throwable? = null
            @Volatile var dirty: Boolean = false
        }

        private fun invalidatePack(objectKey: String) {
            synchronized(memoLock) {
                if (packMemo?.first == objectKey) packMemo = null
                packFlights[objectKey]?.dirty = true
            }
        }

        private fun decryptedWhole(content: ObjectStore, objectKey: String, mayRejoin: Boolean): ByteArray {
            val flight: PackFlight
            val leader: Boolean
            synchronized(memoLock) {
                packMemo?.let { (key, bytes) -> if (key == objectKey) return bytes }
                val existing = packFlights[objectKey]
                leader = existing == null
                flight = existing ?: PackFlight().also { packFlights[objectKey] = it }
            }
            if (!leader) {
                flight.latch.await()
                flight.bytes?.let { return it }
                // #C54/R2: a failed leader does not fail its waiters outright, nor turn
                // them into a herd — each rejoins once; a second failure propagates.
                if (mayRejoin) return decryptedWhole(content, objectKey, mayRejoin = false)
                throw flight.error ?: java.io.IOException("encrypted pack fetch failed")
            }
            val whole = try {
                content.download(objectKey)
            } catch (t: Throwable) {
                synchronized(memoLock) { packFlights.remove(objectKey) }
                flight.error = t
                flight.latch.countDown()
                throw t
            }
            synchronized(memoLock) {
                if (!flight.dirty) packMemo = objectKey to whole
                packFlights.remove(objectKey)
            }
            flight.bytes = whole
            flight.latch.countDown()
            return whole
        }

        override fun upload(objectKey: String, bytes: ByteArray, mime: String): Int {
            invalidatePack(objectKey)
            try {
                return contentStore().upload(objectKey, bytes, mime)
            } finally {
                invalidatePack(objectKey)
            }
        }

        override fun uploadFile(objectKey: String, file: File, mime: String): Int {
            invalidatePack(objectKey)
            try {
                return contentStore().uploadFile(objectKey, file, mime)
            } finally {
                invalidatePack(objectKey)
            }
        }

        override fun download(objectKey: String): ByteArray = contentStore().download(objectKey)

        override val supportsEfficientRange: Boolean
            get() = contentStore().supportsEfficientRange

        /** #C50: a transport property; sealing does not change the pack size a drive can take. */
        override val preferredPackBytes: Long
            get() = raw.preferredPackBytes

        /**
         * #C51: a content read — it goes through the gate exactly like [download]. A PLAIN
         * library forwards the Range read untouched; so does an encrypted one over a transport
         * that serves ranges (#C67 v2: header + covering chunks only). Only an encrypted library
         * over a transport WITHOUT efficient ranges slices the memoised decrypted pack (#C67
         * interim), so its windows still cost one whole pack, not one each.
         */
        override fun downloadRange(objectKey: String, offset: Long, length: Int): ByteArray {
            val content = contentStore()
            if (content === raw || objectKey == CloudCollectionKeys.lockKey() || content.supportsEfficientRange) {
                return content.downloadRange(objectKey, offset, length)
            }
            return sliceOfDecryptedPack(content, objectKey, offset, length)
        }

        /** #D17: forwarded exactly where the plain read is; only a store that reads by range uses [objectSize]. */
        override fun downloadRange(objectKey: String, offset: Long, length: Int, objectSize: Long): ByteArray {
            val content = contentStore()
            if (content === raw || objectKey == CloudCollectionKeys.lockKey() || content.supportsEfficientRange) {
                return content.downloadRange(objectKey, offset, length, objectSize)
            }
            return sliceOfDecryptedPack(content, objectKey, offset, length)
        }

        /** #E29: the file range never slices a memoised plaintext pack. */
        override fun downloadRangeToFile(objectKey: String, offset: Long, length: Int, objectSize: Long, dest: File) {
            contentStore().downloadRangeToFile(objectKey, offset, length, objectSize, dest)
        }

        /** #C67 interim: an encrypted library over a transport without ranges slices its memoised decrypted pack. */
        private fun sliceOfDecryptedPack(content: ObjectStore, objectKey: String, offset: Long, length: Int): ByteArray {
            require(offset >= 0) { "offset must be >= 0" }
            require(length > 0) { "length must be > 0" }
            val whole = decryptedWhole(content, objectKey, mayRejoin = true)
            if (offset + length > whole.size) {
                throw java.io.IOException("range past the end of the object: $offset+$length of ${whole.size}")
            }
            return whole.copyOfRange(offset.toInt(), (offset + length).toInt())
        }

        override fun downloadToFile(objectKey: String, dest: File) =
            contentStore().downloadToFile(objectKey, dest)

        override fun listKeys(): List<String> {
            ensureResolved(raw, born)
            return raw.listKeys()
        }

        override fun listKeys(prefix: String): List<String> {
            ensureResolved(raw, born)
            return raw.listKeys(prefix)
        }

        /**
         * #C51: metadata, like [listKeys] — available while LOCKED so the usage stat keeps working.
         *
         * #D13: while UNLOCKED the sizes are the PLAINTEXT sizes (through [EncryptedObjectStore.listObjects]). The
         * upload's resume recognises a pack already on the drive by size; the raw sealed size never matched, so every
         * resume re-uploaded every pack of an encrypted library (owner phone: comics "stuck at 196/196").
         */
        override fun listObjects(prefix: String): List<ObjectSummary> {
            ensureResolved(raw, born)
            val (currentState, currentDek) = snapshot(born)
            return if (currentState == LibraryLockState.UNLOCKED && currentDek != null) {
                EncryptedObjectStore(raw, currentDek, v2Headers).listObjects(prefix)
            } else {
                raw.listObjects(prefix)
            }
        }

        override fun listUsage(): R2BucketUsage {
            ensureResolved(raw, born)
            return raw.listUsage()
        }

        // The gate's lock is app-level UX, not a security boundary: anyone with
        // the R2 credentials can already delete regardless. Spec §4.5's
        // forgot-password recovery is "delete the library contents and start
        // again", which must work while LOCKED — so delete bypasses the gate.
        // #C64: but never through a dead wrapper.
        override fun delete(objectKey: String): Int {
            checkLive(born)
            invalidatePack(objectKey)
            try {
                return raw.delete(objectKey)
            } finally {
                invalidatePack(objectKey)
            }
        }
    }

    private companion object {
        const val DEK_LEN = 32
    }
}
