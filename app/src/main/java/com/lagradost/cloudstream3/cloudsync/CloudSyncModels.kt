package com.lagradost.cloudstream3.cloudsync

import kotlinx.serialization.Serializable

internal const val CLOUD_SYNC_SCHEMA_VERSION = 1
internal const val CLOUD_SYNC_FILE_NAME = "cloudstream-sync-v1.json"

/**
 * A value is null when the record was deleted. Deleted records intentionally remain in the
 * document so that an offline device cannot bring them back on its next sync.
 */
@Serializable
data class CloudSyncRecord(
    val value: String? = null,
    val updatedAt: Long,
    val deviceId: String,
)

@Serializable
data class CloudSyncDocument(
    val schemaVersion: Int = CLOUD_SYNC_SCHEMA_VERSION,
    /** Distinguishes an empty first snapshot from an initialized device with no records. */
    val initialized: Boolean = false,
    val records: Map<String, CloudSyncRecord> = emptyMap(),
)

data class RemoteSyncDocument(
    val document: CloudSyncDocument,
    /** Backend-specific optimistic concurrency token, such as an ETag or Drive version. */
    val version: String?,
)

sealed interface CloudSyncResult {
    data object Disabled : CloudSyncResult
    data object AuthorizationRequired : CloudSyncResult
    data class Success(val changedLocalRecords: Int) : CloudSyncResult
    data class Unavailable(val message: String?) : CloudSyncResult
}

class RemoteSyncConflictException : Exception()

class RemoteSyncAuthorizationException : Exception()

interface RemoteSyncStore {
    suspend fun read(): RemoteSyncDocument?

    @Throws(RemoteSyncConflictException::class)
    suspend fun write(document: CloudSyncDocument, expectedVersion: String?): String?
}
