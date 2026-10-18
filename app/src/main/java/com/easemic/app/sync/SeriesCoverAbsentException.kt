package com.easemic.app.sync

/** A decoded series summary explicitly has no source cover; transport failures never create this fact. */
class SeriesCoverAbsentException(val seriesCloudId: String, val generation: Int) :
    IllegalStateException("series cover is absent")
