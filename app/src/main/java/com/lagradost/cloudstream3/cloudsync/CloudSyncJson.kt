package com.lagradost.cloudstream3.cloudsync

import kotlinx.serialization.json.Json

internal val cloudSyncJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}
