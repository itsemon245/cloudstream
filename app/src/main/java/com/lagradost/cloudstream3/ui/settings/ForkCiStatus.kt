package com.lagradost.cloudstream3.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream4.compose.BlackButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

enum class CiRunState { Passed, Failed, Running, Cancelled }

data class CiRun(val state: CiRunState, val startedAt: Long, val url: String)

/** Reads the fork's GitHub Actions runs. Only called when the status dialog is opened. */
object ForkCiStatus {
    const val SYNC_WORKFLOW = "sync-upstream.yml"
    const val BUILD_WORKFLOW = "build-apk.yml"
    private const val RUNS_PER_WORKFLOW = 3

    private data class WorkflowRun(
        @JsonProperty("status") val status: String,
        @JsonProperty("conclusion") val conclusion: String? = null,
        @JsonProperty("created_at") val createdAt: String,
        @JsonProperty("html_url") val htmlUrl: String,
    )

    private data class WorkflowRuns(
        @JsonProperty("workflow_runs") val workflowRuns: List<WorkflowRun>,
    )

    /** Anything that isn't a clear pass or a cancellation counts as failed, so problems show. */
    private fun stateOf(run: WorkflowRun): CiRunState = when {
        run.status != "completed" -> CiRunState.Running
        run.conclusion == "success" -> CiRunState.Passed
        run.conclusion == "cancelled" || run.conclusion == "skipped" -> CiRunState.Cancelled
        else -> CiRunState.Failed
    }

    fun parseRuns(json: String): List<CiRun> =
        parseJson<WorkflowRuns>(json).workflowRuns.take(RUNS_PER_WORKFLOW).map { run ->
            CiRun(stateOf(run), Instant.parse(run.createdAt).toEpochMilli(), run.htmlUrl)
        }

    suspend fun fetchRuns(workflow: String): List<CiRun> {
        val url = "https://api.github.com/repos/${GithubReleases.APK_USERNAME}/" +
                "${GithubReleases.APK_REPOSITORY}/actions/workflows/$workflow/runs" +
                "?per_page=$RUNS_PER_WORKFLOW"
        val response = app.get(url, headers = mapOf("Accept" to "application/vnd.github+json"))
        if (!response.isSuccessful) throw IllegalStateException("GitHub returned ${response.code}")
        return parseRuns(response.text)
    }
}

private data class CiStatus(val sync: List<CiRun>, val builds: List<CiRun>)

@Composable
fun ForkCiStatusDialog(onDismiss: () -> Unit) {
    val status by produceState<Result<CiStatus>?>(initialValue = null) {
        value = runCatching {
            withContext(Dispatchers.IO) {
                CiStatus(
                    sync = ForkCiStatus.fetchRuns(ForkCiStatus.SYNC_WORKFLOW),
                    builds = ForkCiStatus.fetchRuns(ForkCiStatus.BUILD_WORKFLOW),
                )
            }
        }
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.ci_status)) },
        text = {
            val result = status
            when {
                result == null -> Text(text = stringResource(R.string.loading))
                result.isFailure -> Text(text = stringResource(R.string.ci_status_error))
                else -> {
                    val loaded = result.getOrThrow()
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CiRunGroup(stringResource(R.string.ci_sync), loaded.sync)
                        CiRunGroup(stringResource(R.string.ci_builds), loaded.builds)
                    }
                }
            }
        },
        confirmButton = { BlackButton(text = stringResource(R.string.ok), onClick = onDismiss) },
    )
}

@Composable
private fun CiRunGroup(title: String, runs: List<CiRun>) {
    Column {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        if (runs.isEmpty()) {
            Text(text = stringResource(R.string.ci_no_runs))
        }
        runs.forEach { run -> CiRunRow(run) }
    }
}

@Composable
private fun CiRunRow(run: CiRun) {
    val label = stringResource(
        when (run.state) {
            CiRunState.Passed -> R.string.ci_passed
            CiRunState.Failed -> R.string.ci_failed
            CiRunState.Running -> R.string.ci_running
            CiRunState.Cancelled -> R.string.ci_cancelled
        }
    )
    val time = DateUtils.getRelativeTimeSpanString(run.startedAt)
    Text(
        text = "$label · $time",
        color = if (run.state == CiRunState.Failed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onBackground
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { CloudStreamApp.openBrowser(run.url, activity) }
            .padding(vertical = 8.dp),
    )
}
