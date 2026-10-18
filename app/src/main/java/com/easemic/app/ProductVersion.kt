package com.easemic.app

/**
 * #E1: one version name per release, `MAJOR.MINOR.PATCH`; the Android versionCode is derived from it so it can only go
 * up. `app/build.gradle.kts` applies the same rule (Gradle cannot call app code) and [ProductVersionTest] keeps the two
 * equal; easemic-site `scripts/release.mjs` `versionCodeOf` is the third copy.
 */
object ProductVersion {
    private val PATTERN = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun codeOf(versionName: String): Int {
        val (major, minor, patch) = requireNotNull(PATTERN.matchEntire(versionName)) {
            "version must be MAJOR.MINOR.PATCH, got: $versionName"
        }.destructured.toList().map(String::toInt)
        return major * 1_000_000 + minor * 1_000 + patch
    }
}
