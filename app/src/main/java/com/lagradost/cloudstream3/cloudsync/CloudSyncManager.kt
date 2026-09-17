package com.lagradost.cloudstream3.cloudsync

import android.content.Context
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CloudSyncManager(private val context: Context) {
    suspend fun sync(): CloudSyncResult = syncMutex.withLock {
        val appContext = context.applicationContext
        val account = DataStoreHelper.currentAccount
        val backend = CloudSyncSettings.backend(appContext, account)
        if (backend == CloudSyncBackend.NONE) return@withLock CloudSyncResult.Disabled

        CloudSyncSettings.recordAttempt(appContext, account)
        val localStore = LocalCloudSyncStore(appContext, account)
        val deviceId = CloudSyncSettings.deviceId(appContext)
        val localDocument = CloudSyncMerge.reconcileLocal(
            currentValues = localStore.readCurrentValues(),
            journal = localStore.readJournal(),
            now = System.currentTimeMillis(),
            deviceId = deviceId,
        )
        // Persist timestamps before touching the network so retries do not make an old local edit
        // appear newer each time a service is unavailable.
        localStore.saveJournal(localDocument)

        try {
            val remoteStore = createRemoteStore(appContext, backend, account)
            var pending = localDocument
            var changedLocalRecords = 0

            repeat(MAX_CONFLICT_ATTEMPTS) { attempt ->
                val remote = remoteStore.read()
                // Remote reads can take seconds. Re-snapshot immediately before applying anything
                // so playback or library edits made while the request was in flight win normally.
                val currentValues = localStore.readCurrentValues()
                val latestLocal = CloudSyncMerge.reconcileLocal(
                    currentValues = currentValues,
                    journal = pending,
                    now = System.currentTimeMillis(),
                    deviceId = deviceId,
                )
                localStore.saveJournal(latestLocal)
                val merged = remote?.document?.let {
                    CloudSyncMerge.merge(latestLocal, it)
                } ?: latestLocal
                changedLocalRecords += localStore.apply(merged, currentValues)
                localStore.saveJournal(merged)

                try {
                    remoteStore.write(merged, remote?.version)
                    CloudSyncSettings.recordSuccess(appContext, account)
                    return@withLock CloudSyncResult.Success(changedLocalRecords)
                } catch (_: RemoteSyncConflictException) {
                    pending = merged
                    if (attempt == MAX_CONFLICT_ATTEMPTS - 1) throw RemoteSyncConflictException()
                }
            }

            error("Unreachable conflict retry state")
        } catch (_: RemoteSyncAuthorizationException) {
            CloudSyncSettings.recordFailure(appContext, account, "Authorization required")
            CloudSyncResult.AuthorizationRequired
        } catch (throwable: Throwable) {
            logError(throwable)
            val message = throwable.message ?: throwable::class.java.simpleName
            CloudSyncSettings.recordFailure(appContext, account, message)
            CloudSyncResult.Unavailable(message)
        }
    }

    private fun createRemoteStore(
        context: Context,
        backend: CloudSyncBackend,
        account: String,
    ): RemoteSyncStore = when (backend) {
        CloudSyncBackend.NONE -> error("A disabled backend cannot create a remote store")
        CloudSyncBackend.WEBDAV -> {
            val config = CloudSyncSettings.webDavConfig(context, account)
                ?: error("WebDAV credentials are unavailable")
            WebDavSyncStore(context, config)
        }
        CloudSyncBackend.GOOGLE_DRIVE -> GoogleDriveSyncStore(context)
    }

    companion object {
        private const val MAX_CONFLICT_ATTEMPTS = 3
        private val syncMutex = Mutex()
    }
}
