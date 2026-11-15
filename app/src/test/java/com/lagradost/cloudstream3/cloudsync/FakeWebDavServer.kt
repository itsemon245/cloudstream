package com.lagradost.cloudstream3.cloudsync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A minimal in-process WebDAV file server, modelled on the behaviour of hosted providers such as
 * Koofr: GET/PUT on files, strong ETags, and If-Match / If-None-Match preconditions.
 *
 * Built on the JDK's HttpServer so the real OkHttp stack (redirects, auth header handling, body
 * decoding) runs unchanged and no extra test dependency is required.
 */
class FakeWebDavServer : AutoCloseable {
    data class StoredFile(val bytes: ByteArray, val etag: String, val contentType: String)

    data class RecordedRequest(val method: String, val path: String)

    /** A canned response that overrides the normal file semantics for one method + path. */
    data class Override(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    val files = ConcurrentHashMap<String, StoredFile>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val overrides = ConcurrentHashMap<Pair<String, String>, Override>()
    private val prefixOverrides = CopyOnWriteArrayList<Triple<String, String, Override>>()
    private var etagCounter = 0

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
            createContext("/") { exchange -> exchange.use(::handle) }
            start()
        }

    val port: Int get() = server.address.port

    /** Base URL using the `localhost` host name. */
    fun url(path: String): String = "http://localhost:$port$path"

    /** Same server reached through a different host name, so OkHttp treats it as another host. */
    fun otherHostUrl(path: String): String = "http://127.0.0.1:$port$path"

    @Synchronized
    fun put(path: String, bytes: ByteArray, contentType: String = "application/json") {
        files[path] = StoredFile(bytes, nextEtag(), contentType)
    }

    fun override(method: String, path: String, response: Override) {
        overrides[method to path] = response
    }

    /** Like [override], for every path that starts with [pathPrefix]. */
    fun overridePrefix(method: String, pathPrefix: String, response: Override) {
        prefixOverrides += Triple(method, pathPrefix, response)
    }

    fun text(path: String): String? = files[path]?.bytes?.toString(Charsets.UTF_8)

    @Synchronized
    private fun nextEtag(): String = "\"etag-${++etagCounter}\""

    private fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.path
        val body = exchange.requestBody.readBytes()
        requests += RecordedRequest(method, path)

        val override = overrides[method to path]
            ?: prefixOverrides.firstOrNull { (m, prefix) -> m == method && path.startsWith(prefix) }
                ?.third
        override?.let { override ->
            override.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            send(exchange, override.status, override.body)
            return
        }

        when (method) {
            "GET" -> {
                val file = files[path] ?: return send(exchange, 404, ByteArray(0))
                exchange.responseHeaders.add("ETag", file.etag)
                exchange.responseHeaders.add("Content-Type", file.contentType)
                send(exchange, 200, file.bytes)
            }

            "PUT" -> synchronized(this) {
                val existing = files[path]
                val ifMatch = exchange.requestHeaders.getFirst("If-Match")
                val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
                if (ifNoneMatch == "*" && existing != null) return send(exchange, 412, ByteArray(0))
                if (ifMatch != null && ifMatch != existing?.etag) {
                    return send(exchange, 412, ByteArray(0))
                }
                val contentType =
                    exchange.requestHeaders.getFirst("Content-Type") ?: "application/octet-stream"
                val stored = StoredFile(body, nextEtag(), contentType)
                files[path] = stored
                exchange.responseHeaders.add("ETag", stored.etag)
                send(exchange, if (existing == null) 201 else 204, ByteArray(0))
            }

            else -> send(exchange, 405, ByteArray(0))
        }
    }

    private fun send(exchange: HttpExchange, status: Int, body: ByteArray) {
        // -1 means "no body" for HttpServer; 0 would mean chunked.
        val length = if (body.isEmpty()) -1L else body.size.toLong()
        exchange.sendResponseHeaders(status, length)
        if (body.isNotEmpty()) exchange.responseBody.write(body)
    }

    override fun close() = server.stop(0)
}
