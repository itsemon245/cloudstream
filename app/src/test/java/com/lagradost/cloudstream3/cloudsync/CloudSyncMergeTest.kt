package com.lagradost.cloudstream3.cloudsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudSyncMergeTest {
    @Test
    fun `remote apply does not overwrite a local write that raced with sync`() {
        val keys = CloudSyncMerge.keysToApply(
            currentValues = mapOf(
                "progress/1" to "new local value",
                "progress/2" to "unchanged local value",
            ),
            expectedCurrentValues = mapOf(
                "progress/1" to "old local value",
                "progress/2" to "unchanged local value",
            ),
            desiredValues = mapOf(
                "progress/1" to "remote value",
                "progress/2" to "remote value",
            ),
        )

        assertEquals(setOf("progress/2"), keys)
    }

    @Test
    fun `reconcile preserves unchanged records and timestamps changes`() {
        val journal = CloudSyncDocument(
            initialized = true,
            records = mapOf(
                "video_pos_dur/1" to CloudSyncRecord("old", 10, "phone"),
                "video_pos_dur/2" to CloudSyncRecord("removed", 20, "phone"),
            )
        )

        val reconciled = CloudSyncMerge.reconcileLocal(
            currentValues = mapOf(
                "video_pos_dur/1" to "old",
                "video_pos_dur/3" to "new",
            ),
            journal = journal,
            now = 100,
            deviceId = "pc",
        )

        assertEquals(CloudSyncRecord("old", 10, "phone"), reconciled.records["video_pos_dur/1"])
        assertEquals(CloudSyncRecord(null, 100, "pc"), reconciled.records["video_pos_dur/2"])
        assertEquals(CloudSyncRecord("new", 100, "pc"), reconciled.records["video_pos_dur/3"])
    }

    @Test
    fun `first snapshot is a baseline rather than a newly edited value`() {
        val reconciled = CloudSyncMerge.reconcileLocal(
            currentValues = mapOf("video_pos_dur/1" to "old local progress"),
            journal = CloudSyncDocument(),
            now = 100,
            deviceId = "new-device",
        )

        assertEquals(
            CloudSyncRecord("old local progress", 0, ""),
            reconciled.records["video_pos_dur/1"],
        )
    }

    @Test
    fun `merge unions records and chooses the newest value`() {
        val local = CloudSyncDocument(
            records = mapOf(
                "result_watch_state/1" to CloudSyncRecord("watching", 30, "phone"),
                "video_pos_dur/2" to CloudSyncRecord("local-only", 20, "phone"),
            )
        )
        val remote = CloudSyncDocument(
            records = mapOf(
                "result_watch_state/1" to CloudSyncRecord("completed", 40, "pc"),
                "video_pos_dur/3" to CloudSyncRecord("remote-only", 10, "pc"),
            )
        )

        val merged = CloudSyncMerge.merge(local, remote)

        assertEquals("completed", merged.records["result_watch_state/1"]?.value)
        assertEquals("local-only", merged.records["video_pos_dur/2"]?.value)
        assertEquals("remote-only", merged.records["video_pos_dur/3"]?.value)
    }

    @Test
    fun `newer tombstone wins over an older value`() {
        val merged = CloudSyncMerge.merge(
            CloudSyncDocument(
                records = mapOf("result_watch_state/1" to CloudSyncRecord(null, 50, "phone"))
            ),
            CloudSyncDocument(
                records = mapOf("result_watch_state/1" to CloudSyncRecord("watching", 40, "pc"))
            ),
        )

        assertNull(merged.records["result_watch_state/1"]?.value)
    }

    @Test
    fun `equal timestamps have a deterministic device tie breaker`() {
        val merged = CloudSyncMerge.merge(
            CloudSyncDocument(
                records = mapOf("key" to CloudSyncRecord("a", 50, "device-a"))
            ),
            CloudSyncDocument(
                records = mapOf("key" to CloudSyncRecord("b", 50, "device-b"))
            ),
        )

        assertEquals("b", merged.records["key"]?.value)
    }

    @Test
    fun `remote baseline wins over a later devices stale initial value`() {
        val merged = CloudSyncMerge.merge(
            CloudSyncDocument(
                records = mapOf("key" to CloudSyncRecord("stale pc", 0, ""))
            ),
            CloudSyncDocument(
                records = mapOf("key" to CloudSyncRecord("phone baseline", 0, ""))
            ),
        )

        assertEquals("phone baseline", merged.records["key"]?.value)
    }
}
