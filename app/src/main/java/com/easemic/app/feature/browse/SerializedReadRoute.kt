package com.easemic.app.feature.browse

enum class SerializedReadRoute {
    LOCAL,
    CLOUD,
    ONLINE,
}

/** Selects the serialized chapter reader from the copies currently available. */
fun serializedReadRoute(
    localAvailable: Boolean,
    cloudAvailable: Boolean,
): SerializedReadRoute = when {
    localAvailable -> SerializedReadRoute.LOCAL
    cloudAvailable -> SerializedReadRoute.CLOUD
    else -> SerializedReadRoute.ONLINE
}
