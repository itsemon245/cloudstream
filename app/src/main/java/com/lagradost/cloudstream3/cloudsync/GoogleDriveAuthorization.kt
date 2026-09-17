package com.lagradost.cloudstream3.cloudsync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface GoogleDriveAuthorizationOutcome {
    data object Authorized : GoogleDriveAuthorizationOutcome
    data class NeedsResolution(val pendingIntent: PendingIntent) : GoogleDriveAuthorizationOutcome
    data class Failed(val error: Throwable) : GoogleDriveAuthorizationOutcome
}

object GoogleDriveAuthorization {
    const val DRIVE_APP_DATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    fun request(
        context: Context,
        callback: (GoogleDriveAuthorizationOutcome) -> Unit,
    ) {
        Identity.getAuthorizationClient(context)
            .authorize(buildRequest())
            .addOnSuccessListener { result ->
                val pendingIntent = result.pendingIntent
                when {
                    result.hasResolution() && pendingIntent != null -> callback(
                        GoogleDriveAuthorizationOutcome.NeedsResolution(pendingIntent)
                    )
                    result.accessToken?.isNotBlank() == true -> callback(
                        GoogleDriveAuthorizationOutcome.Authorized
                    )
                    else -> callback(
                        GoogleDriveAuthorizationOutcome.Failed(
                            IllegalStateException("Google Drive returned no access token")
                        )
                    )
                }
            }
            .addOnFailureListener { callback(GoogleDriveAuthorizationOutcome.Failed(it)) }
    }

    fun finish(context: Context, data: Intent?): Boolean {
        if (data == null) return false
        return runCatching {
            Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
                .accessToken
                ?.isNotBlank() == true
        }.getOrDefault(false)
    }

    /** Best-effort revocation; local disconnect never waits for Google to be reachable. */
    fun revoke(context: Context) {
        runCatching {
            val request = RevokeAccessRequest.builder()
                .setScopes(requestedScopes())
                .build()
            Identity.getAuthorizationClient(context).revokeAccess(request)
        }
    }

    internal suspend fun accessToken(context: Context): String {
        val result = Identity.getAuthorizationClient(context)
            .authorize(buildRequest())
            .awaitValue()
        if (result.hasResolution()) throw RemoteSyncAuthorizationException()
        return result.accessToken?.takeIf(String::isNotBlank)
            ?: throw RemoteSyncAuthorizationException()
    }

    private fun buildRequest(): AuthorizationRequest {
        return AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes())
            .build()
    }

    private fun requestedScopes(): List<Scope> = listOf(Scope(DRIVE_APP_DATA_SCOPE))
}

private suspend fun <T> Task<T>.awaitValue(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}
