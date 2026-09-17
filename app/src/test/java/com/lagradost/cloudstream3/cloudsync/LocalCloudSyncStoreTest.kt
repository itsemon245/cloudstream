package com.lagradost.cloudstream3.cloudsync

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
}
