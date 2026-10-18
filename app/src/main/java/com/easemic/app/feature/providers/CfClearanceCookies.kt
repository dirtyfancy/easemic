package com.easemic.app.feature.providers

/**
 * Extracts the `cf_clearance` value from a raw `; `-joined cookie string such
 * as the one [android.webkit.CookieManager.getCookie] returns (e.g.
 * `"a=1; cf_clearance=XYZ; b=2"`). Returns null when the string is null,
 * blank, or carries no `cf_clearance` entry.
 */
fun parseCfClearance(cookieString: String?): String? {
    if (cookieString.isNullOrBlank()) return null
    return cookieString
        .split(';')
        .asSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("cf_clearance=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeUnless { it.isEmpty() }
}

/** True once [cookieString] carries a `cf_clearance` entry (challenge cleared). */
fun hasCfClearance(cookieString: String?): Boolean = parseCfClearance(cookieString) != null
