package com.lagradost.cloudstream3.ui.browse.engine

/**
 * In-memory LRU + TTL cache of loaded section pages, shared by all Browse screens so going back
 * and forth, toggling a genre off and on or re-opening Browse does not hit providers again.
 */
class BrowsePageCache(
    private val maxEntries: Int = 200,
    private val ttlMs: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Key(val source: String, val sectionName: String, val sectionIndex: Int, val page: Int)

    private class Entry(val page: SectionPage, val storedAt: Long)

    private val map = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: Key): SectionPage? {
        val entry = map[key] ?: return null
        if (clock() - entry.storedAt > ttlMs) {
            map.remove(key)
            return null
        }
        return entry.page
    }

    @Synchronized
    fun put(key: Key, page: SectionPage) {
        map[key] = Entry(page, clock())
    }

    @Synchronized
    fun clear() = map.clear()

    @get:Synchronized
    val size: Int get() = map.size

    companion object {
        /** Process-wide instance; cleared when plugins are reloaded. */
        val shared = BrowsePageCache()
    }
}
