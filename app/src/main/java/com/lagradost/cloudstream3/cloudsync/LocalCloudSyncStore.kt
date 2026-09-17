package com.lagradost.cloudstream3.cloudsync

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.PREFERENCES_NAME
import com.lagradost.cloudstream3.utils.RESULT_DUB
import com.lagradost.cloudstream3.utils.RESULT_EPISODE
import com.lagradost.cloudstream3.utils.RESULT_FAVORITES_STATE_DATA
import com.lagradost.cloudstream3.utils.RESULT_RESUME_WATCHING
import com.lagradost.cloudstream3.utils.RESULT_SEASON
import com.lagradost.cloudstream3.utils.RESULT_SUBSCRIBED_STATE_DATA
import com.lagradost.cloudstream3.utils.RESULT_WATCH_STATE
import com.lagradost.cloudstream3.utils.RESULT_WATCH_STATE_DATA
import com.lagradost.cloudstream3.utils.VIDEO_POS_DUR
import com.lagradost.cloudstream3.utils.VIDEO_WATCH_STATE
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Kept below the existing resume namespace so older clients preserve these records even though
 * they do not know how to restore the corresponding display cache yet.
 */
internal const val CLOUD_SYNC_RESUME_METADATA =
    "$RESULT_RESUME_WATCHING/__cloud_sync_metadata__"

class LocalCloudSyncStore(
    context: Context,
    private val account: String,
) {
    private val appContext = context.applicationContext
    private val statePreferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val settingsPreferences = PreferenceManager.getDefaultSharedPreferences(appContext)
    private val journalKey = "cloud_sync_journal_$account"
    private val accountPrefix = "$account/"

    fun readCurrentValues(): Map<String, String> {
        val allValues = statePreferences.all
        val currentValues = projectCurrentValues(allValues, account)
        mirrorResumeMetadata(currentValues, allValues)
        return currentValues
    }

    fun readJournal(): CloudSyncDocument {
        val json = settingsPreferences.getString(journalKey, null) ?: return CloudSyncDocument()
        return try {
            cloudSyncJson.decodeFromString<CloudSyncDocument>(json)
                .takeIf { it.schemaVersion == CLOUD_SYNC_SCHEMA_VERSION }
                ?: CloudSyncDocument()
        } catch (_: SerializationException) {
            CloudSyncDocument()
        } catch (_: IllegalArgumentException) {
            CloudSyncDocument()
        }
    }

    fun saveJournal(document: CloudSyncDocument) {
        settingsPreferences.edit(commit = true) {
            putString(journalKey, cloudSyncJson.encodeToString(document))
        }
    }

    fun apply(
        document: CloudSyncDocument,
        expectedCurrentValues: Map<String, String>,
    ): Int {
        val current = readCurrentValues()
        val desired = document.records
            .filterKeys(::isSyncable)
            .mapValues { it.value.value }
        val changedKeys = CloudSyncMerge.keysToApply(current, expectedCurrentValues, desired)
        if (changedKeys.isEmpty()) return 0

        statePreferences.edit(commit = true) {
            changedKeys.forEach { relativeKey ->
                val value = desired[relativeKey]
                val accountStorageKey = accountPrefix + relativeKey
                if (value == null) {
                    // The shared header cache can still be required by a download or another
                    // profile. Removing the resume pointer is enough to hide the card.
                    remove(accountStorageKey)
                } else {
                    putString(accountStorageKey, value)
                    storageKey(account, relativeKey)
                        .takeIf { it != accountStorageKey }
                        ?.let { putString(it, value) }
                }
            }
        }

        AccountManager.localListApi.requireLibraryRefresh = true
        MainActivity.bookmarksUpdatedEvent(true)
        MainActivity.reloadLibraryEvent(true)
        MainActivity.reloadHomeEvent(true)
        return changedKeys.size
    }

    private fun mirrorResumeMetadata(
        currentValues: Map<String, String>,
        allValues: Map<String, *>,
    ) {
        val missingMirrors = currentValues.flatMap { (relativeKey, value) ->
            val parentId = resumeMetadataId(relativeKey) ?: return@flatMap emptyList()
            listOf(
                accountPrefix + relativeKey,
                "$DOWNLOAD_HEADER_CACHE/$parentId",
            ).mapNotNull { absoluteKey ->
                (absoluteKey to value).takeIf { allValues[absoluteKey] != value }
            }
        }
        if (missingMirrors.isEmpty()) return

        statePreferences.edit(commit = true) {
            missingMirrors.forEach { (absoluteKey, value) ->
                putString(absoluteKey, value)
            }
        }
    }

    companion object {
        private val syncableFolders = setOf(
            RESULT_WATCH_STATE,
            RESULT_WATCH_STATE_DATA,
            RESULT_RESUME_WATCHING,
            RESULT_SUBSCRIBED_STATE_DATA,
            RESULT_FAVORITES_STATE_DATA,
            VIDEO_POS_DUR,
            VIDEO_WATCH_STATE,
            RESULT_EPISODE,
            RESULT_SEASON,
            RESULT_DUB,
        )

        internal fun isSyncable(relativeKey: String): Boolean {
            val folder = relativeKey.substringBefore('/', missingDelimiterValue = "")
            return folder in syncableFolders && relativeKey.substringAfter('/', "").isNotEmpty()
        }

        internal fun projectCurrentValues(
            allValues: Map<String, *>,
            account: String,
        ): Map<String, String> {
            val accountPrefix = "$account/"
            val syncableValues = allValues.mapNotNull { (absoluteKey, value) ->
                if (!absoluteKey.startsWith(accountPrefix)) return@mapNotNull null
                val relativeKey = absoluteKey.removePrefix(accountPrefix)
                if (!isSyncable(relativeKey) || resumeMetadataId(relativeKey) != null) {
                    return@mapNotNull null
                }
                val stringValue = value as? String ?: return@mapNotNull null
                relativeKey to stringValue
            }.toMap()

            val resumeMetadata = syncableValues.keys.mapNotNull(::resumeParentId)
                .associate { parentId ->
                    val relativeKey = "$CLOUD_SYNC_RESUME_METADATA/$parentId"
                    val value = allValues["$DOWNLOAD_HEADER_CACHE/$parentId"] as? String
                        ?: allValues["$DOWNLOAD_HEADER_CACHE_BACKUP/$parentId"] as? String
                        ?: allValues[accountPrefix + relativeKey] as? String
                    relativeKey to value
                }
                .filterValues { it != null }
                .mapValues { it.value!! }

            return syncableValues + resumeMetadata
        }

        internal fun storageKey(account: String, relativeKey: String): String {
            val parentId = resumeMetadataId(relativeKey)
            return if (parentId == null) {
                "$account/$relativeKey"
            } else {
                "$DOWNLOAD_HEADER_CACHE/$parentId"
            }
        }

        private fun resumeParentId(relativeKey: String): String? {
            val prefix = "$RESULT_RESUME_WATCHING/"
            if (!relativeKey.startsWith(prefix)) return null
            return relativeKey.removePrefix(prefix)
                .takeIf { '/' !in it && it.toIntOrNull() != null }
        }

        private fun resumeMetadataId(relativeKey: String): String? {
            val prefix = "$CLOUD_SYNC_RESUME_METADATA/"
            if (!relativeKey.startsWith(prefix)) return null
            return relativeKey.removePrefix(prefix)
                .takeIf { '/' !in it && it.toIntOrNull() != null }
        }
    }
}
