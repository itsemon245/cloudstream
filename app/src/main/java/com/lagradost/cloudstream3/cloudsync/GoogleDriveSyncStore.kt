package com.lagradost.cloudstream3.cloudsync

import android.content.Context
import com.lagradost.cloudstream3.network.buildDefaultClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class GoogleDriveSyncStore(context: Context) : RemoteSyncStore {
    private val appContext = context.applicationContext
    private val client: OkHttpClient = buildDefaultClient(context).newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun read(): RemoteSyncDocument? = withContext(Dispatchers.IO) {
        val token = GoogleDriveAuthorization.accessToken(appContext)
        val file = findFile(token) ?: return@withContext null
        val request = authorizedRequest(
            "https://www.googleapis.com/drive/v3/files/${file.id}?alt=media",
            token,
        ).get().build()

        client.newCall(request).execute().use { response ->
            checkResponse(response.code, "download")
            val document = try {
                cloudSyncJson.decodeFromString<CloudSyncDocument>(response.body.string())
            } catch (error: SerializationException) {
                throw IOException("Google Drive sync file is invalid", error)
            }
            if (document.schemaVersion != CLOUD_SYNC_SCHEMA_VERSION) {
                throw IOException("Unsupported sync file version ${document.schemaVersion}")
            }
            RemoteSyncDocument(document, file.versionToken())
        }
    }

    override suspend fun write(
        document: CloudSyncDocument,
        expectedVersion: String?,
    ): String? = withContext(Dispatchers.IO) {
        val token = GoogleDriveAuthorization.accessToken(appContext)
        val currentFile = findFile(token)
        if (currentFile?.versionToken() != expectedVersion) throw RemoteSyncConflictException()

        val json = cloudSyncJson.encodeToString(document)
        if (currentFile == null) {
            createFile(token, json).versionToken()
        } else {
            updateFile(token, currentFile, json).versionToken()
        }
    }

    private fun findFile(token: String): DriveFile? {
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name = '$CLOUD_SYNC_FILE_NAME' and trashed = false")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("pageSize", "1")
            .addQueryParameter("fields", "files(id,name,version)")
            .build()
        val request = authorizedRequest(url.toString(), token).get().build()

        client.newCall(request).execute().use { response ->
            checkResponse(response.code, "list")
            return cloudSyncJson.decodeFromString<DriveFileList>(response.body.string())
                .files
                .firstOrNull()
        }
    }

    private fun createFile(token: String, json: String): DriveFile {
        val metadata = """{"name":"$CLOUD_SYNC_FILE_NAME","parents":["appDataFolder"]}"""
        val multipart = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(metadata.toRequestBody(JSON_MEDIA_TYPE))
            .addPart(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val request = authorizedRequest(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,version",
            token,
        ).post(multipart).build()

        client.newCall(request).execute().use { response ->
            checkResponse(response.code, "create")
            return cloudSyncJson.decodeFromString(response.body.string())
        }
    }

    private fun updateFile(token: String, file: DriveFile, json: String): DriveFile {
        val request = authorizedRequest(
            "https://www.googleapis.com/upload/drive/v3/files/${file.id}?uploadType=media&fields=id,name,version",
            token,
        ).patch(json.toRequestBody(JSON_MEDIA_TYPE)).build()

        client.newCall(request).execute().use { response ->
            checkResponse(response.code, "update")
            return cloudSyncJson.decodeFromString(response.body.string())
        }
    }

    private fun authorizedRequest(url: String, token: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
    }

    private fun checkResponse(code: Int, operation: String) {
        if (code == 401) throw RemoteSyncAuthorizationException()
        if (code !in 200..299) throw IOException("Google Drive $operation failed ($code)")
    }

    @Serializable
    private data class DriveFileList(
        val files: List<DriveFile> = emptyList(),
    )

    @Serializable
    private data class DriveFile(
        val id: String,
        val name: String? = null,
        @SerialName("version") val version: String? = null,
    ) {
        fun versionToken(): String = "$id:${version.orEmpty()}"
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val MULTIPART_RELATED = "multipart/related".toMediaType()
    }
}
