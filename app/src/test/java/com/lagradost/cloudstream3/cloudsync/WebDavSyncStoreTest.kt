package com.lagradost.cloudstream3.cloudsync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.Proxy

/**
 * Reproduces "WebDAV • Waiting to retry: WebDAV sync file is invalid" against a fake WebDAV server.
 * See docs/investigations/webdav-sync-invalid-file.md.
 */
class WebDavSyncStoreTest {
    private val server = FakeWebDavServer()
    private val folder = "/dav/Koofr/CloudStream"
    private val filePath = "$folder/$CLOUD_SYNC_FILE_NAME"

    private val store = WebDavSyncStore(
        config = WebDavSyncConfig(
            url = server.url(folder),
            username = "user@example.com",
            password = "app-password",
        ),
        client = WebDavSyncStore.webDavClient(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()),
    )

    private val localDocument = CloudSyncDocument(
        initialized = true,
        records = mapOf(
            "video_pos_dur/42" to CloudSyncRecord("{\"position\":10,\"duration\":20}", 5L, "phone"),
        ),
    )

    @After
    fun tearDown() = server.close()

    /**
     * Mirrors the read → merge → write loop of [CloudSyncManager.sync], which itself needs an
     * Android context. If this throws, the manager records the message as the sync status.
     */
    private fun syncOnce(local: CloudSyncDocument = localDocument): CloudSyncDocument = runBlocking {
        repeat(3) {
            val remote = store.read()
            val merged = remote?.document?.let { CloudSyncMerge.merge(local, it) } ?: local
            try {
                store.write(merged, remote?.version)
                return@runBlocking merged
            } catch (_: RemoteSyncConflictException) {
                // retry, exactly like CloudSyncManager
            }
        }
        throw RemoteSyncConflictException()
    }

    private fun remoteDocument(): CloudSyncDocument {
        val text = server.text(filePath)
        assertNotNull("The sync file is missing on the server", text)
        return cloudSyncJson.decodeFromString(CloudSyncDocument.serializer(), text!!)
    }

    // ---------------------------------------------------------------------------------------
    // Control: our own writes always round-trip, even for large documents with unicode.
    // (Passes on current code; rules out our serializer as the source of the invalid file.)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `control - a large document written by us is read back intact`() {
        val big = CloudSyncDocument(
            initialized = true,
            records = (1..20_000).associate { i ->
                "result_resume_watching/$i" to CloudSyncRecord(
                    value = "{\"name\":\"Ep $i — ünïcødé ✓ \\\"quoted\\\"\"}",
                    updatedAt = 1_700_000_000_000L + i,
                    deviceId = "device-$i",
                )
            },
        )
        syncOnce(big)
        val read = runBlocking { store.read() }
        assertEquals(big, read?.document)
    }

    // ---------------------------------------------------------------------------------------
    // Cause 1: an interrupted/failed PUT leaves an empty or truncated file on the server
    // (Koofr is reported to create a 0-byte file even when the PUT fails). The current code
    // throws "WebDAV sync file is invalid" on every subsequent read and never overwrites it,
    // so sync is blocked forever.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `zero-byte remote file left by an interrupted upload does not block sync`() {
        server.put(filePath, ByteArray(0))

        try {
            syncOnce()
        } catch (error: IOException) {
            fail("Sync is blocked by an empty remote file: ${error.message}")
        }

        assertEquals(localDocument.records, remoteDocument().records)
    }

    @Test
    fun `truncated remote file left by an interrupted upload does not block sync forever`() {
        val previous = cloudSyncJson.encodeToString(
            CloudSyncDocument(
                initialized = true,
                records = (1..200).associate {
                    "video_pos_dur/$it" to CloudSyncRecord("value $it", it.toLong(), "tablet")
                },
            )
        ).toByteArray()
        server.put(filePath, previous.copyOf(previous.size / 2))

        // The worker retries with exponential backoff. Each retry must eventually make progress
        // instead of hitting the same exception forever.
        val errors = (1..3).mapNotNull {
            try {
                syncOnce()
                null
            } catch (error: IOException) {
                error.message
            }
        }

        assertTrue(
            "Every retry failed with the same error, sync can never recover: $errors",
            errors.size < 3,
        )
        assertTrue(remoteDocument().records.keys.containsAll(localDocument.records.keys))
    }

    @Test
    fun `corrupt remote file is backed up before it is replaced`() {
        val corrupt = "{\"schemaVersion\":1,\"initialized\":true,\"records\":{\"video_pos".toByteArray()
        server.put(filePath, corrupt)

        runCatching { syncOnce() }

        val backups = server.files.filterKeys { it != filePath }.values
        assertTrue(
            "The unreadable sync file must be preserved (e.g. renamed to *.corrupt-<time>) " +
                "before being overwritten, so nothing is silently lost",
            backups.any { it.bytes.contentEquals(corrupt) },
        )
        assertEquals(localDocument.records, remoteDocument().records)
    }

    // ---------------------------------------------------------------------------------------
    // Cause 2: a redirect (e.g. to a login/maintenance page on another host) returns an HTML
    // page with 200 OK. OkHttp drops the Authorization header on cross-host redirects, and the
    // current code reports this as a corrupt sync file, which is misleading and, once a
    // "recover corrupt file" fix exists, would be dangerous (it must NOT overwrite the file).
    // ---------------------------------------------------------------------------------------

    @Test
    fun `html page served after a redirect is not reported as a corrupt sync file`() {
        val original = cloudSyncJson.encodeToString(localDocument)
        server.put(filePath, original.toByteArray())
        server.override(
            "GET", filePath,
            FakeWebDavServer.Override(302, mapOf("Location" to server.otherHostUrl("/login"))),
        )
        server.override(
            "GET", "/login",
            FakeWebDavServer.Override(
                200,
                mapOf("Content-Type" to "text/html; charset=utf-8"),
                "<!DOCTYPE html><html><body>Sign in to Koofr</body></html>".toByteArray(),
            ),
        )

        val error = try {
            runBlocking { store.read() }
            fail("An HTML page must not be accepted as the sync document")
            return
        } catch (error: IOException) {
            error
        }

        assertFalse(
            "Misleading status: '${error.message}'. A redirect/HTML response is a server or " +
                "authentication problem, not a corrupt sync file",
            error.message.orEmpty().contains("sync file is invalid"),
        )
        assertEquals("The real sync file must be left untouched", original, server.text(filePath))
    }

    // ---------------------------------------------------------------------------------------
    // Cause 3 (silent data loss, not the reported message): OkHttp turns a PUT that receives a
    // 301/302 into a GET. The redirected GET returns 200, so write() reports success even
    // though nothing was uploaded.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `redirected PUT is not silently treated as a successful upload`() {
        server.override(
            "PUT", filePath,
            FakeWebDavServer.Override(301, mapOf("Location" to server.url("/moved"))),
        )
        server.override("GET", "/moved", FakeWebDavServer.Override(200))

        val succeeded = runCatching { runBlocking { store.write(localDocument, null) } }.isSuccess

        assertFalse(
            "write() reported success but the server never stored the document " +
                "(requests: ${server.requests.map { "${it.method} ${it.path}" }})",
            succeeded && server.text(filePath) == null,
        )
    }
}
