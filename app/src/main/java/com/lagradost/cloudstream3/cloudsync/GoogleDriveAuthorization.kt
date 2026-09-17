package com.lagradost.cloudstream3.cloudsync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface GoogleDriveAuthorizationOutcome {
    data object Authorized : GoogleDriveAuthorizationOutcome
    data class NeedsResolution(val pendingIntent: PendingIntent) : GoogleDriveAuthorizationOutcome
    data object Cancelled : GoogleDriveAuthorizationOutcome
    data object ConfigurationRequired : GoogleDriveAuthorizationOutcome
    data object Unavailable : GoogleDriveAuthorizationOutcome
    data class Failed(val error: Throwable) : GoogleDriveAuthorizationOutcome
}

internal fun googleDriveAuthorizationFailure(
    statusCode: Int?,
    error: Throwable,
): GoogleDriveAuthorizationOutcome = when (statusCode) {
    CommonStatusCodes.API_NOT_CONNECTED -> GoogleDriveAuthorizationOutcome.Unavailable
    CommonStatusCodes.DEVELOPER_ERROR -> GoogleDriveAuthorizationOutcome.ConfigurationRequired
    else -> GoogleDriveAuthorizationOutcome.Failed(error)
}

object GoogleDriveAuthorization {
    const val DRIVE_APP_DATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    fun request(
        context: Context,
        callback: (GoogleDriveAuthorizationOutcome) -> Unit,
    ) {
        if (!isGooglePlayServicesAvailable(context)) {
            callback(GoogleDriveAuthorizationOutcome.Unavailable)
            return
        }

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
            .addOnFailureListener { error ->
                callback(
                    googleDriveAuthorizationFailure(
                        statusCode = (error as? ApiException)?.statusCode,
                        error = error,
                    )
                )
            }
    }

    fun finish(context: Context, data: Intent?): GoogleDriveAuthorizationOutcome {
        if (data == null) return GoogleDriveAuthorizationOutcome.Cancelled

        return try {
            val accessToken = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
                .accessToken
            if (accessToken?.isNotBlank() == true) {
                GoogleDriveAuthorizationOutcome.Authorized
            } else {
                GoogleDriveAuthorizationOutcome.Failed(
                    IllegalStateException("Google Drive returned no access token")
                )
            }
        } catch (error: Throwable) {
            googleDriveAuthorizationFailure(
                statusCode = (error as? ApiException)?.statusCode,
                error = error,
            )
        }
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
        if (!isGooglePlayServicesAvailable(context)) {
            error("Google Play services are unavailable")
        }
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

    private fun isGooglePlayServicesAvailable(context: Context): Boolean {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
                ConnectionResult.SUCCESS
    }
}

private suspend fun <T> Task<T>.awaitValue(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}
