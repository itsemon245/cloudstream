package com.lagradost.cloudstream3.cloudsync

import android.content.Context
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.buildDefaultClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class WebDavSyncStore internal constructor(
    private val config: WebDavSyncConfig,
    private val client: OkHttpClient,
) : RemoteSyncStore {
    constructor(
        context: Context,
        config: WebDavSyncConfig,
    ) : this(config, webDavClient(buildDefaultClient(context)))

    private val resourceUrl = if (config.url.endsWith(".json", ignoreCase = true)) {
        config.url
    } else {
        "${config.url}/$CLOUD_SYNC_FILE_NAME"
    }

    override suspend fun read(): RemoteSyncDocument? = withContext(Dispatchers.IO) {
        client.newCall(requestBuilder(resourceUrl).get().build()).execute().use { response ->
            when {
                response.code == 404 -> return@use null
                response.code == 401 || response.code == 403 -> {
                    throw IOException("WebDAV authentication failed (${response.code})")
                }
                response.isRedirect -> throw redirectError(response)
                // Some servers answer GET for a zero-byte file with 204.
                response.code == 204 -> return@use emptyDocument(response)
                response.code != 200 -> throw IOException("WebDAV read failed (${response.code})")
            }

            // A login, maintenance or multistatus page is a server problem, not our file. It must
            // never be "repaired", or a healthy sync file would be overwritten.
            val contentType = response.body.contentType()
            if (contentType != null && !isPlausibleFileType(contentType)) {
                throw IOException(
                    "WebDAV server returned an unexpected ${contentType.type}/" +
                        "${contentType.subtype} response instead of the sync file"
                )
            }

            val bytes = response.body.bytes()
            val text = bytes.toString(contentType?.charset() ?: Charsets.UTF_8)
                .removePrefix("\uFEFF")
            // An interrupted upload can leave an empty file behind (Koofr is known to create the
            // file before the body arrives). There is nothing to lose, so let the next write
            // replace it.
            if (text.isBlank()) return@use emptyDocument(response)

            val document = try {
                cloudSyncJson.decodeFromString<CloudSyncDocument>(text)
            } catch (error: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException. The file is truncated or
                // otherwise unreadable by every device, so keep a copy and let the caller replace
                // it. If-Match on the following write still protects a concurrent repair.
                val backupName = backUpUnreadableFile(bytes)
                logError(
                    IOException(
                        "WebDAV sync file is unreadable (HTTP ${response.code}, " +
                            "${bytes.size} bytes); backed up as $backupName",
                        error,
                    )
                )
                return@use emptyDocument(response).copy(recoveredBackupName = backupName)
            }
            if (document.schemaVersion != CLOUD_SYNC_SCHEMA_VERSION) {
                throw IOException("Unsupported sync file version ${document.schemaVersion}")
            }
            RemoteSyncDocument(document = document, version = versionOf(response))
        }
    }

    override suspend fun write(
        document: CloudSyncDocument,
        expectedVersion: String?,
    ): String? = withContext(Dispatchers.IO) {
        val body = cloudSyncJson.encodeToString(document)
            .toRequestBody(JSON_MEDIA_TYPE)
        val builder = requestBuilder(resourceUrl).put(body)
        when {
            expectedVersion == null -> builder.header("If-None-Match", "*")
            expectedVersion != UNVERSIONED_EXISTING -> builder.header("If-Match", expectedVersion)
        }

        client.newCall(builder.build()).execute().use { response ->
            when {
                response.code == 409 || response.code == 412 -> {
                    throw RemoteSyncConflictException()
                }
                response.code == 401 || response.code == 403 -> {
                    throw IOException("WebDAV authentication failed (${response.code})")
                }
                response.isRedirect -> throw redirectError(response)
                !response.isSuccessful -> throw IOException("WebDAV write failed (${response.code})")
                else -> versionOf(response)
            }
        }
    }

    /**
     * Saves the unreadable bytes next to the sync file and returns the backup's file name. The
     * name is derived from the content, so retries after a failed replacement reuse one backup
     * instead of uploading another copy each time.
     */
    private fun backUpUnreadableFile(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(8)
            .joinToString("") { "%02x".format(it) }
        val backupUrl = "$resourceUrl.corrupt-$digest"
        val request = requestBuilder(backupUrl)
            .put(bytes.toRequestBody(OCTET_STREAM_MEDIA_TYPE))
            .header("If-None-Match", "*")
            .build()
        client.newCall(request).execute().use { response ->
            // 412: a backup of exactly these bytes already exists from an earlier attempt.
            if (!response.isSuccessful && response.code != 412) {
                throw IOException(
                    "WebDAV sync file is unreadable and could not be backed up (${response.code})"
                )
            }
        }
        return backupUrl.substringAfterLast('/')
    }

    private fun emptyDocument(response: Response) =
        RemoteSyncDocument(document = CloudSyncDocument(), version = versionOf(response))

    private fun versionOf(response: Response): String =
        response.header("ETag") ?: UNVERSIONED_EXISTING

    private fun redirectError(response: Response): IOException {
        val target = response.header("Location")
            ?.let { response.request.url.resolve(it)?.host }
            ?: "another location"
        return IOException(
            "WebDAV server redirected to $target (${response.code}); check the sync URL"
        )
    }

    private fun requestBuilder(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
                    header("Authorization", Credentials.basic(config.username, config.password))
                }
            }
    }

    companion object {
        /** Applies the WebDAV-specific client settings. Separate so tests can supply a base client. */
        internal fun webDavClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
            // The app-wide HTTP cache could serve a stale document and ETag.
            .cache(null)
            // Followed redirects turn PUT into GET (a silent no-op upload) and drop the
            // Authorization header across hosts. Report them instead.
            .followRedirects(false)
            .followSslRedirects(false)
            // No cap on the whole call: aborting a slow upload half way is what leaves a
            // truncated file on servers without atomic PUT. Stalled connections still time out.
            .callTimeout(0, TimeUnit.SECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        private fun isPlausibleFileType(type: MediaType): Boolean {
            val subtype = type.subtype.lowercase()
            return subtype != "html" && subtype != "xhtml+xml" && !subtype.endsWith("xml")
        }

        private const val UNVERSIONED_EXISTING = "webdav:unversioned"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}
