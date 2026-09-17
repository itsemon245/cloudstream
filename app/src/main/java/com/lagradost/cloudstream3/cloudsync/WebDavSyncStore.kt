package com.lagradost.cloudstream3.cloudsync

import android.content.Context
import com.lagradost.cloudstream3.network.buildDefaultClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebDavSyncStore(
    context: Context,
    private val config: WebDavSyncConfig,
) : RemoteSyncStore {
    private val client: OkHttpClient = buildDefaultClient(context).newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val resourceUrl = if (config.url.endsWith(".json", ignoreCase = true)) {
        config.url
    } else {
        "${config.url}/$CLOUD_SYNC_FILE_NAME"
    }

    override suspend fun read(): RemoteSyncDocument? = withContext(Dispatchers.IO) {
        client.newCall(requestBuilder().get().build()).execute().use { response ->
            when {
                response.code == 404 -> null
                response.code == 401 || response.code == 403 -> {
                    throw IOException("WebDAV authentication failed (${response.code})")
                }
                !response.isSuccessful -> throw IOException("WebDAV read failed (${response.code})")
                else -> {
                    val body = response.body.string()
                    val document = try {
                        cloudSyncJson.decodeFromString<CloudSyncDocument>(body)
                    } catch (error: SerializationException) {
                        throw IOException("WebDAV sync file is invalid", error)
                    }
                    if (document.schemaVersion != CLOUD_SYNC_SCHEMA_VERSION) {
                        throw IOException("Unsupported sync file version ${document.schemaVersion}")
                    }
                    RemoteSyncDocument(
                        document = document,
                        version = response.header("ETag") ?: UNVERSIONED_EXISTING,
                    )
                }
            }
        }
    }

    override suspend fun write(
        document: CloudSyncDocument,
        expectedVersion: String?,
    ): String? = withContext(Dispatchers.IO) {
        val body = cloudSyncJson.encodeToString(document)
            .toRequestBody(JSON_MEDIA_TYPE)
        val builder = requestBuilder().put(body)
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
                !response.isSuccessful -> throw IOException("WebDAV write failed (${response.code})")
                else -> response.header("ETag") ?: UNVERSIONED_EXISTING
            }
        }
    }

    private fun requestBuilder(): Request.Builder {
        return Request.Builder()
            .url(resourceUrl)
            .header("Accept", "application/json")
            .apply {
                if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
                    header("Authorization", Credentials.basic(config.username, config.password))
                }
            }
    }

    companion object {
        private const val UNVERSIONED_EXISTING = "webdav:unversioned"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
