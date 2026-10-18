package com.easemic.app.sync

/**
 * #C46 D1: what the Activity row is told about an upload, in the order it
 * HAPPENS. The checkpoint callback (`onPageUploaded`) stays ordinal-ordered
 * because the resume checkpoint must be a contiguous prefix; this stream is
 * for the user's eyes and counts completions.
 */
sealed interface CloudUploadProgress {
    /**
     * A page PUT completed. [done] = page PUTs finished this run plus pages
     * that were already present on the bucket when the run started; monotone
     * and never behind the wire by more than the lanes in flight.
     */
    data class Page(val done: Int, val total: Int, val bytes: Int) : CloudUploadProgress

    /**
     * Every page is up; the tail (per-page thumbs, manifest, meta, index) is
     * running. [done]/[total] count tail objects; fired once with done = 0
     * when the page join completes, then after each tail object.
     */
    data class Finishing(val done: Int, val total: Int) : CloudUploadProgress
}
