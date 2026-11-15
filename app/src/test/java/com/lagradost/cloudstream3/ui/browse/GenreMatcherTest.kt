package com.lagradost.cloudstream3.ui.browse

import com.lagradost.cloudstream3.ui.browse.engine.GenreMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreMatcherTest {
    @Test fun matchesWholeWordsInSectionNames() {
        assertTrue(GenreMatcher.matches("Action Movies", "action"))
        assertTrue(GenreMatcher.matches("Top ACTION", "action"))
        assertTrue(GenreMatcher.matches("Action & Adventure", "adventure"))
        assertFalse(GenreMatcher.matches("Transaction", "action"))
        assertFalse(GenreMatcher.matches("Latest", "action"))
    }

    @Test fun excludesFalseFriends() {
        assertFalse(GenreMatcher.matches("Live Action", "action"))
        assertFalse(GenreMatcher.matches("Star Wars Collection", "war"))
        assertTrue(GenreMatcher.matches("War Movies", "war"))
    }

    @Test fun synonymsAndAccents() {
        assertTrue(GenreMatcher.matches("Science Fiction", "scifi"))
        assertTrue(GenreMatcher.matches("Sci-Fi & Fantasy", "scifi"))
        assertTrue(GenreMatcher.matches("Sci-Fi & Fantasy", "fantasy"))
        assertTrue(GenreMatcher.matches("Acción", "action"))
        assertTrue(GenreMatcher.matches("Películas de Terror", "horror"))
        assertTrue(GenreMatcher.matches("Romantic Comedies", "romance"))
        assertTrue(GenreMatcher.matches("Romantic Comedies", "comedy"))
    }

    @Test fun resolvesDetailPageTags() {
        assertEquals(listOf("scifi"), GenreMatcher.resolve("Science Fiction"))
        assertEquals(listOf("action", "adventure"), GenreMatcher.resolve("Action & Adventure"))
        assertEquals(listOf("war", "history"), GenreMatcher.resolve("War and History"))
        assertEquals(listOf("neo noir"), GenreMatcher.resolve("Neo-noir"))
        assertEquals(emptyList<String>(), GenreMatcher.resolve("  "))
    }

    @Test fun adHocGenresMatchTheirOwnWords() {
        assertTrue(GenreMatcher.matches("Best Neo Noir", "neo noir"))
        assertFalse(GenreMatcher.matches("Noir", "neo noir"))
        assertEquals("Neo Noir", GenreMatcher.displayName("neo noir"))
        assertEquals("Sci-Fi", GenreMatcher.displayName("scifi"))
    }

    @Test fun canonicalGenresOfSection() {
        assertEquals(setOf("action", "adventure"), GenreMatcher.canonicalGenresOf("Action & Adventure"))
        assertEquals(emptySet<String>(), GenreMatcher.canonicalGenresOf("Trending Now"))
    }
}
