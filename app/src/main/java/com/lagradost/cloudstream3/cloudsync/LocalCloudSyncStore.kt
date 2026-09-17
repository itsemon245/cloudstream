package com.lagradost.cloudstream3.cloudsync

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.syncproviders.AccountManager
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
        return statePreferences.all.mapNotNull { (absoluteKey, value) ->
            if (!absoluteKey.startsWith(accountPrefix)) return@mapNotNull null
            val relativeKey = absoluteKey.removePrefix(accountPrefix)
            if (!isSyncable(relativeKey)) return@mapNotNull null
            val stringValue = value as? String ?: return@mapNotNull null
            relativeKey to stringValue
        }.toMap()
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
                val absoluteKey = accountPrefix + relativeKey
                val value = desired[relativeKey]
                if (value == null) remove(absoluteKey) else putString(absoluteKey, value)
            }
        }

        AccountManager.localListApi.requireLibraryRefresh = true
        MainActivity.bookmarksUpdatedEvent(true)
        MainActivity.reloadLibraryEvent(true)
        MainActivity.reloadHomeEvent(true)
        return changedKeys.size
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
    }
}
