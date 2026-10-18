package com.easemic.app.feature.providers

import com.easemic.app.importing.opds.OpdsCatalogClient
import com.easemic.app.ui.PreferenceMap

data class OpdsProviderConfig(
    val id: String,
    val displayName: String,
    val catalogUrl: String,
    /** Optional HTTP Basic username (Komga email). Empty for Kavita Auth-Key URLs. */
    val username: String = "",
    /** Optional HTTP Basic password. Never log or commit. */
    val password: String = "",
    /** Last time a lightweight probe reached this catalog. Null = never seen. */
    val lastSeenOkEpochMs: Long? = null,
    /** Non-null while the latest probe could not reach this catalog. */
    val unreachableSinceEpochMs: Long? = null,
) {
    override fun toString(): String =
        "OpdsProviderConfig(id=$id, displayName=$displayName, catalogUrl=${OpdsCatalogClient.redactUrl(catalogUrl)}, username=${if (username.isBlank()) "" else "<set>"}, password=${if (password.isBlank()) "" else "<redacted>"})"

    /** "Dims" the row: an unreachable library renders at reduced emphasis. */
    val isDimmed: Boolean get() = unreachableSinceEpochMs != null

    /**
     * Copies only the recorded health facts from [health]. Used when applying
     * a probe outcome to the freshest persisted row, so an in-flight probe
     * can never roll back an edit the user saved mid-probe (audit F3).
     */
    fun withHealthFrom(health: OpdsProviderConfig): OpdsProviderConfig =
        copy(
            lastSeenOkEpochMs = health.lastSeenOkEpochMs,
            unreachableSinceEpochMs = health.unreachableSinceEpochMs,
        )
}

class OpdsProviderStore(
    private val prefs: PreferenceMap,
) {
    fun readAll(): List<OpdsProviderConfig> {
        val raw = prefs.getString(KEY_PROVIDERS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val parts = record.split(FIELD_SEP)
            when (parts.size) {
                3 -> OpdsProviderConfig(
                    id = parts[0],
                    displayName = parts[1],
                    catalogUrl = parts[2],
                )
                5 -> OpdsProviderConfig(
                    id = parts[0],
                    displayName = parts[1],
                    catalogUrl = parts[2],
                    username = parts[3],
                    password = parts[4],
                )
                7 -> OpdsProviderConfig(
                    id = parts[0],
                    displayName = parts[1],
                    catalogUrl = parts[2],
                    username = parts[3],
                    password = parts[4],
                    lastSeenOkEpochMs = parts[5].toLongOrNull(),
                    unreachableSinceEpochMs = parts[6].toLongOrNull(),
                )
                else -> null
            }
        }
    }

    fun writeAll(providers: List<OpdsProviderConfig>) {
        val encoded = providers.joinToString(RECORD_SEP) { provider ->
            listOf(
                provider.id,
                provider.displayName,
                provider.catalogUrl,
                provider.username,
                provider.password,
                provider.lastSeenOkEpochMs?.toString() ?: "",
                provider.unreachableSinceEpochMs?.toString() ?: "",
            ).joinToString(FIELD_SEP)
        }
        prefs.putString(KEY_PROVIDERS, encoded)
    }

    private companion object {
        const val KEY_PROVIDERS = "opds_providers_v1"
        const val RECORD_SEP = "\u001e"
        const val FIELD_SEP = "\u001f"
    }
}
