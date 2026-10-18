package com.easemic.app.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatZaimanhuaExpiry(expSeconds: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
    "until " + DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
        .withZone(zoneId)
        .format(Instant.ofEpochSecond(expSeconds))
