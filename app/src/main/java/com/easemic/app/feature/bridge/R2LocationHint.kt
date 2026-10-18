package com.easemic.app.feature.bridge

/**
 * C37-j: Cloudflare places a hint-less bucket next to the CREATOR'S IP, which
 * a VPN exit fools (the owner's bucket landed in Eastern Europe). The device's
 * own time zone is the honest signal; country ISO is the fallback for
 * `UTC`/`Etc` zones. Null means "let Cloudflare decide".
 * Valid hints: wnam, enam, weur, eeur, apac, oc.
 */
object R2LocationHint {
    fun fromDevice(timeZoneId: String?, networkCountryIso: String?, simCountryIso: String?): String? {
        fromTimeZone(timeZoneId)?.let { return it }
        return fromCountry(networkCountryIso) ?: fromCountry(simCountryIso)
    }

    internal fun fromTimeZone(id: String?): String? {
        val zone = id?.trim().orEmpty()
        if (zone.isEmpty() || zone == "UTC" || zone.startsWith("Etc/") || zone.startsWith("GMT")) return null
        val region = zone.substringBefore('/')
        val city = zone.substringAfter('/', "")
        return when (region) {
            "Asia" -> "apac"
            "Australia", "Pacific" -> "oc"
            "Europe" -> if (city in EASTERN_EUROPE_CITIES) "eeur" else "weur"
            "Africa" -> "weur"
            "America" -> if (city in WESTERN_AMERICA_CITIES) "wnam" else "enam"
            "Atlantic" -> "weur"
            "Indian" -> "apac"
            else -> null
        }
    }

    internal fun fromCountry(iso: String?): String? {
        val code = iso?.trim()?.lowercase().orEmpty()
        if (code.length != 2) return null
        return when (code) {
            in APAC_COUNTRIES -> "apac"
            in OCEANIA_COUNTRIES -> "oc"
            in EASTERN_EUROPE_COUNTRIES -> "eeur"
            in WESTERN_EUROPE_COUNTRIES -> "weur"
            in AMERICAS_ENAM_COUNTRIES -> "enam"
            else -> null
        }
    }

    private val EASTERN_EUROPE_CITIES = setOf(
        "Warsaw", "Prague", "Budapest", "Bucharest", "Sofia", "Athens", "Helsinki", "Kiev", "Kyiv", "Moscow", "Minsk",
        "Riga", "Tallinn", "Vilnius", "Istanbul", "Belgrade", "Zagreb", "Bratislava", "Ljubljana", "Chisinau", "Vienna",
    )
    private val WESTERN_AMERICA_CITIES = setOf(
        "Los_Angeles", "Vancouver", "Denver", "Phoenix", "Edmonton", "Boise", "Tijuana", "Anchorage", "Juneau", "Whitehorse", "Dawson", "Mazatlan", "Hermosillo",
    )
    private val APAC_COUNTRIES = setOf("cn", "hk", "mo", "tw", "jp", "kr", "sg", "my", "th", "vn", "id", "ph", "in", "kh", "la", "mm", "bd", "lk", "np", "pk", "mn")
    private val OCEANIA_COUNTRIES = setOf("au", "nz", "fj", "pg")
    private val EASTERN_EUROPE_COUNTRIES = setOf("pl", "cz", "hu", "ro", "bg", "gr", "fi", "ua", "ru", "by", "lv", "ee", "lt", "tr", "rs", "hr", "sk", "si", "md", "at")
    private val WESTERN_EUROPE_COUNTRIES = setOf("gb", "ie", "fr", "de", "nl", "be", "lu", "es", "pt", "it", "ch", "dk", "no", "se", "is")
    // Includes South-American ISOs (br/ar/cl/co): `enam` is the closest valid R2 hint for South America.
    private val AMERICAS_ENAM_COUNTRIES = setOf("us", "ca", "mx", "br", "ar", "cl", "co")
}
