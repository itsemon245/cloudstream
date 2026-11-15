# Investigation: "WebDAV • Waiting to retry: WebDAV sync file is invalid" (Koofr)

**Status:** diagnosed. Failing tests added, fix not implemented yet.
**Branch:** `claude/webdav-sync-invalid-file`

## Summary

Yes, this is a bug in our implementation. Koofr's behaviour sets it off, but our code is what
makes it permanent.

1. Sometimes the remote file `cloudstream-sync-v1.json` stops being valid JSON. Most likely an
   upload was cut off, which leaves an empty or truncated file on the server. Koofr is reported
   to leave a **0-byte file behind when a PUT fails**.
2. `WebDavSyncStore.read()` treats any unparsable 2xx body as fatal: it throws
   `IOException("WebDAV sync file is invalid")`.
3. `CloudSyncManager.sync()` calls `read()` before it ever writes. Every later sync (the
   periodic worker, WorkManager retries, "Sync now") therefore fails at the same point, and
   nothing ever replaces the bad file. **The state is sticky.** The only way out is to delete
   the file by hand in Koofr.

"After some time" fits this. The whole document is uploaded on every sync (every 15 minutes
and on every change), and it only grows, because tombstones are never pruned. The upload is
also capped by a hard 20-second `callTimeout`. As the file grows, an aborted upload becomes
more likely, especially on mobile data or in Doze. One aborted upload is enough to block sync
for good.

## How the error is produced

| Step | Code |
|---|---|
| GET the file; any 2xx response is accepted, including 204, HTML, or the final page of a redirect | `app/src/main/java/com/lagradost/cloudstream3/cloudsync/WebDavSyncStore.kt:40-42` |
| Decode the body; `SerializationException` becomes "WebDAV sync file is invalid" | `WebDavSyncStore.kt:43-46` |
| `read()` runs first in every sync attempt; the exception aborts before any `write()` | `CloudSyncManager.kt:35`, `:66-69` |
| The message is saved as `lastError` and shown as "Waiting to retry: %1$s" | `CloudSyncSettings.recordFailure`, `strings.xml` `cloud_sync_last_error` |
| The worker returns `Result.retry()`, so the same failing read repeats forever | `services/CloudSyncWorkManager.kt:25` |
| The upload is capped at 20 s for the whole call, including the request body | `WebDavSyncStore.kt:100` (`callTimeout`) |
| The document only grows (tombstones are kept forever) | `CloudSyncModels.kt:9` |

The only way to reach this message is a **2xx response whose body does not decode as a
`CloudSyncDocument`**. 401/403 and other non-2xx codes produce different messages, and a
schema mismatch produces "Unsupported sync file version". The causes below are therefore
limited to ways a 2xx response can carry a body that isn't the document.

## Root causes, ranked by likelihood

### 1. Empty or truncated remote file after an interrupted upload, and the code never recovers (most likely)

**How the file gets corrupted.** `write()` sends the whole document as a single plain PUT. That
upload is not atomic on the server: there is no temp-file-then-MOVE step, and Koofr's WebDAV
does not do one for us. Any of the following can leave a 0-byte or partial file:

- `callTimeout(20s)` (`WebDavSyncStore.kt:100`) aborts a large upload part way through,
- the network drops, or Android/WorkManager stops the worker during the upload,
- Koofr itself fails the PUT (users report Koofr creating a 0-byte file even when the PUT
  returned an error, and returning HTTP 423 Locked after a timed-out PUT).

The next GET returns `200 OK` with an empty or cut-off body, and the decode fails.

**Why it never recovers.** `read()` throws, and `CloudSyncManager` cannot write without a
successful read, so the bad file stays. Nothing in the code can fix it.

**Ruled out:** our own serializer. The control test writes and reads back a 20,000-record
document with unicode and escaped quotes, and it round-trips exactly. Our encoder always
writes valid JSON that we can read again. Corruption has to happen in transit or on the
server.

**Confirmed by:**
- `zero-byte remote file left by an interrupted upload does not block sync`: fails with
  "Sync is blocked by an empty remote file: WebDAV sync file is invalid".
- `truncated remote file left by an interrupted upload does not block sync forever`: three
  retries fail with `[WebDAV sync file is invalid ×3]`.
- `corrupt remote file is backed up before it is replaced`: fails. Nothing is preserved or
  replaced today.

Confidence: **high** that this is the mechanism and that the stickiness is a real bug.
**Medium** on which event first corrupted the user's file. Without access to their Koofr
account I can't tell whether it was a timeout, a dropped connection, or a 423/5xx from Koofr.
Asking the user for the size and contents of `cloudstream-sync-v1.json` in Koofr's web UI would
settle it: a 0-byte or obviously cut-off file confirms this cause.

### 2. A non-file 2xx response (redirect to an HTML login or maintenance page, 204, 207) is reported as a corrupt file (possible)

We send `Accept: application/json` but never check what comes back. OkHttp follows redirects
automatically (`buildDefaultClient` sets `followRedirects(true)`), and **drops the
`Authorization` header when the redirect goes to another host**. So if Koofr ever redirects
`/dav/...` to a login, maintenance or rate-limit page that returns 200 HTML, we report "sync
file is invalid". The same happens with a 204 or a 207 Multi-Status response.

When this happens, the message is wrong: the actual problem is authentication or server
availability. It also matters for the fix. Once we add recovery for corrupt files (cause 1),
an HTML page **must not** be treated as corrupt and overwritten.

**Confirmed by:** `html page served after a redirect is not reported as a corrupt sync file`
fails with "Misleading status: 'WebDAV sync file is invalid'".

Confidence: **low to medium** for Koofr. I could not reach Koofr from this environment to check
its redirect behaviour.

### 3. A redirected PUT is silently turned into a GET (data loss, not this exact message)

When a PUT receives 301/302/303, OkHttp re-issues it as a GET (`RetryAndFollowUpInterceptor`
does this for every method except PROPFIND). The GET returns 200, so `write()` reports success
even though nothing was uploaded. A typical trigger is an `http://` URL that the server
redirects to `https://`. The UI then shows a successful sync while the remote never changes.
This is a separate bug, but the fix touches the same redirect handling.

**Confirmed by:** `redirected PUT is not silently treated as a successful upload` fails. The
server only saw `PUT` → 301 → `GET /moved`.

### Considered and ruled out (or minor)

- **Our serializer or encoding/BOM:** ruled out by the control test. OkHttp strips a UTF-8 BOM
  and decodes as UTF-8 by default.
- **gzip/chunked encoding:** OkHttp decompresses transparently because we don't set
  `Accept-Encoding` ourselves. Not a cause.
- **Concurrent writes from two devices:** `If-Match`/`If-None-Match` turn these into 412 →
  `RemoteSyncConflictException` (a different message). If Koofr ignores the preconditions,
  the result is lost updates, not an invalid file. One exception: a reader that catches a
  partially uploaded file mid-write sees a truncated body. That is transient unless the
  upload is then aborted, which is cause 1.
- **Schema version drift:** produces "Unsupported sync file version …", not this message.
- **Auth expiry (Koofr app password revoked):** Koofr answers 401 → "WebDAV authentication
  failed (401)". It only shows up as "invalid" through cause 2.
- **URL normalization:** `setWebDav` trims whitespace and a trailing `/`. The URL never changes
  after it is saved, so this can't explain a failure that starts later. Spaces in the path are
  encoded by OkHttp's `HttpUrl`.
- **Shared OkHttp HTTP cache** (`network/RequestsHelper.kt:48`, 50 MiB, shared with the whole
  app): GET responses are cacheable and can be heuristically fresh when only `Last-Modified`
  is sent. In practice our own PUT after every read invalidates the entry, so I could not
  build a realistic failure from it. It is still worth bypassing for sync traffic (see fix
  plan, step 5).

## Tests

Location: `app/src/test/java/com/lagradost/cloudstream3/cloudsync/`

- `FakeWebDavServer.kt` is an in-process WebDAV server built on the JDK's `HttpServer`. It
  supports GET, PUT, DELETE, COPY and MOVE, ETags, `If-Match`/`If-None-Match`, and canned
  responses per path. It adds no new dependencies, and the real OkHttp stack runs against it
  unchanged.
- `WebDavSyncStoreTest.kt` drives the real `WebDavSyncStore`. `syncOnce()` copies the
  read → merge → write loop from `CloudSyncManager.sync()`, which needs an Android context and
  so can't be run directly in a JVM unit test.

| Test | Current result |
|---|---|
| `control - a large document written by us is read back intact` | passes |
| `zero-byte remote file left by an interrupted upload does not block sync` | **FAILS** |
| `truncated remote file left by an interrupted upload does not block sync forever` | **FAILS** |
| `corrupt remote file is backed up before it is replaced` | **FAILS** |
| `html page served after a redirect is not reported as a corrupt sync file` | **FAILS** |
| `redirected PUT is not silently treated as a successful upload` | **FAILS** |

Run:

```
./gradlew --no-daemon --max-workers=2 :app:testPrereleaseDebugUnitTest \
  --tests 'com.lagradost.cloudstream3.cloudsync.WebDavSyncStoreTest'
```

To make the store testable, `WebDavSyncStore` gained an `internal` constructor
`(config, client)`. Timeout setup moved to `WebDavSyncStore.webDavClient(base)`. The public
`(context, config)` constructor behaves exactly as before.

## Fix plan

1. **Classify the GET response before decoding** (`WebDavSyncStore.read`):
   - 3xx (once redirects are off, see step 4) → `IOException("WebDAV server redirected to …")`.
   - Status other than 200, or a `Content-Type` that is clearly not the file (`text/html`,
     `application/xml`/207) → `IOException("WebDAV server returned an unexpected response (…)")`.
     **Never overwrite in this case.**
   - 200 with an **empty body** → treat as "file exists but has no content": return
     `RemoteSyncDocument(CloudSyncDocument(), version = ETag)` so the next write replaces it
     using `If-Match`.
   - 200 with a non-empty body that fails to decode → return a new result type such as
     `RemoteSyncDocument.Corrupt(etag)` (or a `corrupt` flag) instead of throwing.
2. **Recover from a corrupt file** (`CloudSyncManager.sync`): on `Corrupt`, first copy the
   bad file aside (WebDAV `COPY` to `cloudstream-sync-v1.json.corrupt-<epochMillis>`, falling
   back to GET + PUT). Then write `latestLocal` with `If-Match: <etag>`. This is safe: every
   device keeps its full timestamped journal (`LocalCloudSyncStore.saveJournal`), so other
   devices merge their records back in on their next sync. A 412 here means another device
   already repaired the file, and the normal conflict retry handles that. Surface a one-time
   status such as "Recovered from an unreadable sync file".
3. **Make uploads atomic** (`WebDavSyncStore.write`): PUT to a temp name
   (`cloudstream-sync-v1.json.<deviceId>.tmp`), then `MOVE` it onto the real name with
   `Overwrite: T` and `If-Match`/`If-None-Match` on the destination. Delete the temp file if
   the move fails. Readers then only ever see a complete file. Also consider embedding a
   `contentLength`/SHA-256 in the document, or comparing against the PUT `Content-Length`, to
   catch servers that accept a truncated body.
4. **Turn off automatic redirects for sync traffic**: `followRedirects(false)` and
   `followSslRedirects(false)` on the WebDAV client. Report a 3xx as a configuration error
   ("server redirected to <host>, update the URL"). This fixes cause 3 (PUT → GET) and stops
   the `Authorization` header from being silently dropped.
5. **Bypass the shared app HTTP cache**: build the WebDAV client with `.cache(null)`, or send
   `CacheControl.FORCE_NETWORK` on GET, so sync never reads a stale document or ETag.
6. **Reduce upload size and timeout pressure**: replace the 20 s `callTimeout` with
   connect/read/write timeouts only (or scale it with body size), and prune tombstones older
   than N days so the document stops growing forever.
7. **Make retries visible**: keep `Result.retry()`, but include the HTTP status and body size
   in `lastError` (for example "invalid (200, 0 bytes)") so reports like this one can be
   diagnosed from the status line alone.

Steps 1 and 2 alone make all three cause-1 tests and the HTML test pass. Step 4 makes the
redirected-PUT test pass.

**Workaround for the user today:** delete `cloudstream-sync-v1.json` from the configured Koofr
folder, then tap "Sync now". The device re-uploads its journal, and other devices merge back
in on their next sync.

## References

- Koofr creating a 0-byte file on a failed PUT (Zotero forum):
  https://forums.zotero.org/discussion/132802/webdav-verification-suddenly-failing-with-http-405-on-koofr
- Koofr returning HTTP 423 on PUT after a timeout (Zotero forum):
  https://forums.zotero.org/discussion/133306/
