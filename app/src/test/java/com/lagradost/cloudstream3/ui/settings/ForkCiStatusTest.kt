package com.lagradost.cloudstream3.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ForkCiStatusTest {
    private fun run(status: String, conclusion: String?) = """
        {"id":1,"name":"Sync upstream","status":"$status",
         "conclusion":${conclusion?.let { "\"$it\"" } ?: "null"},
         "created_at":"2026-11-05T20:17:00Z",
         "html_url":"https://github.com/itsemon245/cloudstream/actions/runs/1"}
    """

    private fun stateOf(status: String, conclusion: String?): CiRunState =
        ForkCiStatus.parseRuns("""{"total_count":1,"workflow_runs":[${run(status, conclusion)}]}""")
            .single().state

    @Test
    fun `success passes`() {
        assertEquals(CiRunState.Passed, stateOf("completed", "success"))
    }

    @Test
    fun `failures and unknown conclusions show as failed`() {
        listOf("failure", "timed_out", "startup_failure", "action_required", "something_new", null)
            .forEach { assertEquals(it, CiRunState.Failed, stateOf("completed", it)) }
    }

    @Test
    fun `cancelled and skipped runs are not failures`() {
        assertEquals(CiRunState.Cancelled, stateOf("completed", "cancelled"))
        assertEquals(CiRunState.Cancelled, stateOf("completed", "skipped"))
    }

    @Test
    fun `unfinished runs show as running`() {
        assertEquals(CiRunState.Running, stateOf("in_progress", null))
        assertEquals(CiRunState.Running, stateOf("queued", null))
    }

    @Test
    fun `keeps the link and time, and only the latest three runs`() {
        val item = run("completed", "failure")
        val runs = ForkCiStatus.parseRuns(
            """{"workflow_runs":[$item,$item,$item,$item]}"""
        )

        assertEquals(3, runs.size)
        assertEquals("https://github.com/itsemon245/cloudstream/actions/runs/1", runs[0].url)
        assertEquals(1793909820000L, runs[0].startedAt)
    }

    @Test
    fun `no runs yet is an empty list`() {
        assertEquals(emptyList<CiRun>(), ForkCiStatus.parseRuns("""{"workflow_runs":[]}"""))
    }
}
