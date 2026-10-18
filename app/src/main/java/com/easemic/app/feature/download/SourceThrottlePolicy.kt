package com.easemic.app.feature.download

/**
 * Per-source request pacing + recovery config, cited from
 * docs/research/2026-08-29-source-rate-limit-playbook.md §2 and re-based on
 * 2026-09-10 (#C17).
 *
 * What spacing here does and does not buy, since the original numbers were
 * picked on the opposite belief: two research passes found NO official or
 * community document from e-hentai, exhentai, or nhentai that requires a
 * delay between page-image fetches. Every written e-hentai limit is a
 * consumable budget — points per image and per MB, FIQ per MB, MB over a
 * 7-day window — none of which a sleep conserves. So these gaps are a
 * concurrency bound, not quota insurance, and picking them "conservatively"
 * has a real cost the owner named: throttling past the evidence is its own
 * harm to the user. Each number must now cite an IMAGE-scoped source.
 *
 * The mature-client shape to preserve (FooIbar/EhViewer): a MIN-SPACING gate
 * before the request, never an additive sleep after it. A request slower than
 * the cadence then waits zero. Upstream deleted its post-success sleep for
 * exactly this reason.
 */
data class SourceThrottlePolicy(
    /** Minimum ms between sequential requests to this source (jittered by [interRequestJitterMs]). */
    val interRequestDelayMs: Long,
    /** Random additional 0..N ms added to each inter-request delay to avoid lockstep. */
    val interRequestJitterMs: Long,
    /** Max concurrent page/image fetches (image CDN tolerance). */
    val maxConcurrentFetches: Int,
    /**
     * Base park for the FIRST retry after a JITTER strike, doubling per
     * consecutive strike (`DownloadWorkerLoop.jitterBackoffMs`).
     *
     * Deliberately SEPARATE from [interRequestDelayMs] (#C17, 2026-09-10).
     * The two were one field, so re-basing nhentai's page spacing onto its
     * real image-host cap also made its failure retries 9x more aggressive —
     * a healthy-path speed fix silently changing unhealthy-path politeness.
     * They answer different questions: spacing is "how fast may I ask when
     * things work", backoff is "how long do I wait when they don't".
     */
    val jitterBackoffBaseMs: Long,
    /** Seconds to wait on a PACED failure when the source gives no Retry-After. */
    val defaultPacedWaitSeconds: Int,
    /** How long a QUOTA hard-idle should last before probing again, ms. */
    val quotaIdleMs: Long,
) {
    companion object {
        /**
         * e-hentai/exhentai. Concurrency (3) matches the studied reference's
         * `download_thread` default; the 1000 ms per-page min-spacing is OUR
         * OWN conservative floor, NOT upstream parity.
         *
         * Correction (#C38/D9, C40 §B5, reference `xiaojieonly/Ehviewer_CN_SXJ`
         * @ 2c451bf3): the earlier note here claimed 1000 ms was "EhViewer's
         * download_delay default (min-spacing)". At the studied commit that
         * default is **0 ms**, and it is a POST-SUCCESS ADDITIVE sleep, not a
         * min-spacing admission gate. #C17 audit A3 correction: this KDoc then
         * over-corrected into a blanket "upstream does not pace at all" —
         * false. The two studied forks disagree: FooIbar @ b9729e94 ships
         * `download_delay_3 = 1000` as a min-spacing gate (`SpiderQueen.kt:554-556`);
         * xiaojieonly @ 2c451bf3 ships 0 ms as a post-success additive sleep.
         * Neither is a policy document; 1000 ms is our own floor (owner-gated,
         * #C48), not a value inherited from either. It is kept (not dropped to 0)
         * because `ehwiki.org/wiki/Downloading` warns that "too intensive
         * downloads will trigger IP bans" with no published threshold, the ban
         * risk is asymmetric and owner-owned, and dropping e-hentai spacing to
         * 0 (or exposing a 0 ms / higher-thread ceiling to the user) is a
         * separate, owner-gated decision (#C17 deferred it). The C38 speed
         * wins come from failure isolation, node rotation, the fast first-byte
         * cutoff and admission — none of which touch this floor. 1 s spacing +
         * 3 in flight ≈ 1 page/s, which the owner reports never triggered a ban.
         */
        val EHENTAI = SourceThrottlePolicy(
            // #C48 (owner 2026-09-26): EhViewer parity — xiaojieonly/Ehviewer_CN_SXJ @ 3974bda ships
            // DEFAULT_DOWNLOAD_DELAY = 0 and 3 workers; the 1000 ms floor described above was ours and is gone.
            // The namespace semaphore (3) still bounds e-h to 3 in flight however many galleries run.
            interRequestDelayMs = 0L,
            interRequestJitterMs = 0L,
            maxConcurrentFetches = 3,       // matches the reference's download_thread default (3)
            jitterBackoffBaseMs = 1_000L,   // unchanged from when spacing was the base
            defaultPacedWaitSeconds = 30,   // "opening pages too fast" warning: 30 s is OUR choice; the playbook §PACED [judgment] says 10 min full idleness then half rate — recorded as an owner question in ledger #C17, not adopted here
            quotaIdleMs = 3 * 60 * 60 * 1_000L, // 509 quota: no readable first-party page (ehwiki/509 unreadable 2026-09-10 — see ledger #C17); 3 h is our own margin
        )

        /**
         * nhentai — image-scoped pacing (#C17, re-based 2026-09-10).
         *
         * This policy is consumed by `SourcePacingGate.paced`, which wraps
         * page-IMAGE fetches. It previously carried 3000 ms, taken from the
         * playbook's "3 s for /api/v2/galleries/{id}" — a METADATA figure.
         * The playbook's image row says the opposite: "image hosts at 3
         * req/s". Same number, inverted unit, 9x too slow on the one path
         * this policy governs. 333 ms is that 3 req/s expressed as spacing.
         *
         * Spacing is kept positive on purpose: with `maxConcurrentFetches`
         * it is what bounds instantaneous concurrency against an origin with
         * no published threshold. It is NOT quota insurance — the docs pass
         * found no source charging by request rate.
         */
        val NHENTAI = SourceThrottlePolicy(
            interRequestDelayMs = 333L,     // 3 req/s image-host cap, as spacing
            interRequestJitterMs = 0L,      // min-spacing gate: jitter only widens the gap
            maxConcurrentFetches = 3,       // 3 is OUR judgment (playbook §2 marks it [judgment]); the RicterZ/nhentai_archivist consensus is 5
            jitterBackoffBaseMs = 3_000L,   // 3000: inherited from the pre-re-base combined field (metadata-API gap); kept for retry politeness only — not image-scoped evidence
            defaultPacedWaitSeconds = 60,   // gallery-dl 429 default 60s
            quotaIdleMs = 0L,               // nh has NO quota system (playbook §1b)
        )

        /**
         * OPDS (self-hosted Kavita / Komga / Ubooquity / Calibre-web): the
         * user's OWN server. #C17 audit A1 + re-review item 2 (2026-09-19):
         * the old 250 ms + 250 ms jitter had no citation; worse, this
         * adapter makes ONE archive GET per comic (`OpdsAcquisitionPageBytes.load`)
         * and then serves every page from its private extracted files, so a
         * per-page spacing paces nothing server-facing — it is N × spacing of
         * sleep on local reads (a 200-page chapter: ~75 s at 250+250, ~10 s at
         * 50). The server-facing bound is `maxConcurrentFetches` (for the
         * download pump; the online reader's `OpdsPsePageFetch` fetches one
         * server GET per page under no policy, and `pagesFor` re-downloads the
         * archive after an eviction — both outside this gate) (at most that
         * many archive GETs in flight) — none of the three lab servers
         * (Kavita 0.9.0.2 / Komga / Ubooquity, docker, 2026-09-19) publishes a
         * request-rate limit, and a 2 MB chapter GET over loopback completed
         * in 3–15 ms (controller measurement; evidence
         * `docs/superpowers/evidence/2026-09-19-c17-throttle-rebaseline-reverification.md`).
         * No jitter: on a min-spacing gate it only widens the gap.
         */
        val OPDS_DEFAULT = SourceThrottlePolicy(
            interRequestDelayMs = 0L,       // per-page path is an in-memory read — see KDoc
            interRequestJitterMs = 0L,
            maxConcurrentFetches = 4,       // the ONLY server-facing bound: concurrent archive GETs
            jitterBackoffBaseMs = 250L,     // unchanged: retry politeness against a flapping self-hosted server
            defaultPacedWaitSeconds = 15,
            quotaIdleMs = 0L,
        )

        /**
         * #C17 re-review item 4: a namespace this policy does not know
         * (the #C50 drive backends — WebDAV / OneDrive / Google Drive — until
         * each declares its own). Deliberately the gentle profile OPDS had
         * before its per-page spacing was proven to pace nothing: an unknown
         * remote must never silently inherit a faster profile than it was
         * ever measured against.
         */
        val FALLBACK = SourceThrottlePolicy(
            interRequestDelayMs = 250L,
            interRequestJitterMs = 0L,
            maxConcurrentFetches = 4,
            jitterBackoffBaseMs = 250L,
            defaultPacedWaitSeconds = 15,
            quotaIdleMs = 0L,
        )

        /**
         * The user's own bucket (Cloudflare R2 / BYO S3): no rate limit to
         * respect, so no inter-request gap and wide parallelism — owner
         * 2026-09-09: "跟 cf r2 交互的逻辑就不需要节流了，可劲物理最快速度".
         */
        val CLOUD = SourceThrottlePolicy(
            interRequestDelayMs = 0L,
            interRequestJitterMs = 0L,
            maxConcurrentFetches = 6,
            // NOT 0: when this doubled as the backoff base, a cloud JITTER
            // strike parked 0 ms and the doubling ladder stayed 0 forever, so
            // a flapping bucket was retried in a hot loop until JITTER
            // exhaustion. Zero spacing is right for a healthy bucket; zero
            // backoff never was.
            jitterBackoffBaseMs = 1_000L,
            defaultPacedWaitSeconds = 5,
            quotaIdleMs = 0L,
        )

        /**
         * 再漫画. Five requests per second through one client in the keiyoushi
         * behavior (research `docs/research/2026-10-01-e2-zaimanhua-behaviour.md` §3).
         * The per-page spacing only paces local archive reads once B2 saves through
         * the ZIP (as with OPDS), at most N × 200 ms per chapter.
         */
        val ZAIMANHUA = SourceThrottlePolicy(
            interRequestDelayMs = 200L,
            interRequestJitterMs = 0L,
            maxConcurrentFetches = 3,
            jitterBackoffBaseMs = 1_000L,
            defaultPacedWaitSeconds = 60,
            quotaIdleMs = 0L,
        )

        /** JM: our conservative wire policy, Task 0/Controller review 1 (not a server quota). */
        val JM = SourceThrottlePolicy(
            interRequestDelayMs = 750L,
            interRequestJitterMs = 0L,
            maxConcurrentFetches = 2,
            jitterBackoffBaseMs = 1_000L,
            defaultPacedWaitSeconds = 60,
            quotaIdleMs = 0L,
        )

        /** Resolve by GalleryIdentityV1 providerNamespace. Unknown → FALLBACK (conservative). */
        fun forNamespace(providerNamespace: String): SourceThrottlePolicy = when {
            providerNamespace == GalleryIdentityV1.EHENTAI -> EHENTAI
            providerNamespace == GalleryIdentityV1.NHENTAI -> NHENTAI
            providerNamespace == GalleryIdentityV1.ZAIMANHUA -> ZAIMANHUA
            providerNamespace == GalleryIdentityV1.JM -> JM
            providerNamespace == GalleryIdentityV1.CLOUD_COLLECTION -> CLOUD
            providerNamespace.startsWith(GalleryIdentityV1.R2_SOURCE_PREFIX) -> CLOUD
            providerNamespace.startsWith("opds:") -> OPDS_DEFAULT
            else -> FALLBACK
        }
    }
}
