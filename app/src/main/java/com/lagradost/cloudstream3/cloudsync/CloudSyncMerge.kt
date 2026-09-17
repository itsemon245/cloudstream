package com.lagradost.cloudstream3.cloudsync

object CloudSyncMerge {
    /** Returns remote changes that are still safe to apply after the last local snapshot. */
    fun keysToApply(
        currentValues: Map<String, String>,
        expectedCurrentValues: Map<String, String>,
        desiredValues: Map<String, String?>,
    ): Set<String> {
        return (currentValues.keys + desiredValues.keys).filterTo(mutableSetOf()) { key ->
            currentValues[key] == expectedCurrentValues[key] &&
                currentValues[key] != desiredValues[key]
        }
    }

    /**
     * Turns local differences into timestamped records while retaining tombstones from the last
     * sync. This is deliberately separate from normal app writes: playback and library updates
     * never wait for a remote service.
     */
    fun reconcileLocal(
        currentValues: Map<String, String>,
        journal: CloudSyncDocument,
        now: Long,
        deviceId: String,
    ): CloudSyncDocument {
        val isInitialSnapshot = !journal.initialized
        val keys = currentValues.keys + journal.records.keys
        val records = keys.associateWith { key ->
            val currentValue = currentValues[key]
            val previous = journal.records[key]
            if (previous != null && previous.value == currentValue) {
                previous
            } else {
                CloudSyncRecord(
                    value = currentValue,
                    // The first connected device establishes the remote baseline. A later device's
                    // pre-existing (and potentially stale) data must not look newer just because
                    // cloud sync was enabled there later.
                    updatedAt = if (isInitialSnapshot) 0L else now,
                    deviceId = if (isInitialSnapshot) "" else deviceId,
                )
            }
        }

        return CloudSyncDocument(initialized = true, records = records)
    }

    fun merge(
        local: CloudSyncDocument,
        remote: CloudSyncDocument,
    ): CloudSyncDocument {
        require(local.schemaVersion == CLOUD_SYNC_SCHEMA_VERSION)
        require(remote.schemaVersion == CLOUD_SYNC_SCHEMA_VERSION)

        val keys = local.records.keys + remote.records.keys
        return CloudSyncDocument(
            initialized = true,
            records = keys.associateWith { key ->
                newest(local.records[key], remote.records[key])
                    ?: error("A merged key must exist in at least one document")
            }
        )
    }

    private fun newest(
        first: CloudSyncRecord?,
        second: CloudSyncRecord?,
    ): CloudSyncRecord? {
        if (first == null) return second
        if (second == null) return first
        if (first.updatedAt != second.updatedAt) {
            return if (first.updatedAt > second.updatedAt) first else second
        }

        // A stable tie-breaker prevents devices from alternating values when clocks match.
        return if (first.deviceId > second.deviceId) first else second
    }
}
