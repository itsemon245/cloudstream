package com.lagradost.cloudstream3.cloudsync

import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCloudSyncStoreTest {
    @Test
    fun `only library and progress records are syncable`() {
        assertTrue(LocalCloudSyncStore.isSyncable("result_watch_state/123"))
        assertTrue(LocalCloudSyncStore.isSyncable("result_watch_state_data/123"))
        assertTrue(LocalCloudSyncStore.isSyncable("result_resume_watching_2/123"))
        assertTrue(LocalCloudSyncStore.isSyncable("video_pos_dur/456"))
        assertTrue(LocalCloudSyncStore.isSyncable("video_watch_state/456"))
    }

    @Test
    fun `settings credentials and malformed paths are not syncable`() {
        assertFalse(LocalCloudSyncStore.isSyncable("auth_data/google"))
        assertFalse(LocalCloudSyncStore.isSyncable("video_pos_dur"))
        assertFalse(LocalCloudSyncStore.isSyncable("video_pos_dur/"))
        assertFalse(LocalCloudSyncStore.isSyncable("other/video_pos_dur/1"))
    }

    @Test
    fun `resume card metadata round trips through a new device`() {
        val account = "Profile 1"
        val parentId = "123"
        val resume = "{\"parentId\":123,\"episodeId\":456}"
        val header = "{\"name\":\"A show\",\"id\":123}"
        val sourceValues = mapOf(
            "$account/result_resume_watching_2/$parentId" to resume,
            "$DOWNLOAD_HEADER_CACHE/$parentId" to header,
        )

        val exported = LocalCloudSyncStore.projectCurrentValues(sourceValues, account)
        val metadataKey = "$CLOUD_SYNC_RESUME_METADATA/$parentId"

        assertEquals(resume, exported["result_resume_watching_2/$parentId"])
        assertEquals(header, exported[metadataKey])
        assertTrue(LocalCloudSyncStore.isSyncable(metadataKey))
        assertEquals(
            "$DOWNLOAD_HEADER_CACHE/$parentId",
            LocalCloudSyncStore.storageKey(account, metadataKey),
        )
    }

    @Test
    fun `resume metadata export falls back to the backup cache`() {
        val account = "Profile 1"
        val parentId = "123"
        val header = "{\"name\":\"A show\",\"id\":123}"
        val sourceValues = mapOf(
            "$account/result_resume_watching_2/$parentId" to "resume",
            "$DOWNLOAD_HEADER_CACHE_BACKUP/$parentId" to header,
        )

        val exported = LocalCloudSyncStore.projectCurrentValues(sourceValues, account)

        assertEquals(header, exported["$CLOUD_SYNC_RESUME_METADATA/$parentId"])
    }

    @Test
    fun `resume metadata stored by an older client remains exportable`() {
        val account = "Profile 1"
        val parentId = "123"
        val metadataKey = "$CLOUD_SYNC_RESUME_METADATA/$parentId"
        val header = "{\"name\":\"A show\",\"id\":123}"
        val sourceValues = mapOf(
            "$account/result_resume_watching_2/$parentId" to "resume",
            "$account/$metadataKey" to header,
        )

        val exported = LocalCloudSyncStore.projectCurrentValues(sourceValues, account)

        assertEquals(header, exported[metadataKey])
    }

    @Test
    fun `unrelated header cache entries are not exported`() {
        val account = "Profile 1"
        val sourceValues = mapOf(
            "$account/result_resume_watching_2/123" to "resume",
            "$DOWNLOAD_HEADER_CACHE/456" to "unrelated header",
        )

        val exported = LocalCloudSyncStore.projectCurrentValues(sourceValues, account)

        assertFalse(exported.containsKey("$CLOUD_SYNC_RESUME_METADATA/456"))
    }
}
