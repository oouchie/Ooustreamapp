package com.ooustream.iptv.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases taken from the live bp-v2.net catalogue on 2026-11-07 (owner line), the day the ranking bug
 * was found through a customer's title request. Names and `added` values are real.
 */
class SearchRankerTest {

    private class Title(val name: String, val added: Long)

    // In catalogue order (= `added` ascending), the order Search used to show them in.
    private val screamCatalogue = listOf(
        Title("Ancient Evil: Scream of the Mummy (2000)", 1793082225),
        Title("A Scream in the Night (1935)", 1793082800),
        Title("Ice Scream (1997)", 1793082917),
        Title("Scream 2 (1997)", 1793083489),
        Title("Scream (1996)", 1793083490),
        Title("Screamers (1995)", 1793083509),
        Title("Scream (1981)", 1793085097),
        Title("4KDV: Scream (1996)", 1793085200),
        Title("Scream for Help (1984)", 1793085107),
        Title("The Screaming Skull (1958)", 1793086716),
        Title("4K: Scream (1996)", 1793090000),
        Title("Scream VI (2023)", 1793100000),
    )

    private fun rank(q: String, items: List<Title> = screamCatalogue) =
        SearchRanker.rank(items, SearchRanker.parseQuery(q), { it.name }, { it.added }).map { it.name }

    @Test
    fun `exact titles rank ahead of longer ones, newest release first`() {
        val r = rank("scream")
        assertEquals(screamCatalogue.size, r.size) // every title still matches — only the order changed
        assertEquals(listOf("Scream (1996)", "Scream (1981)"), r.take(2))
        // plain copy ahead of its 4K twins, both ahead of "Scream 2"
        assertTrue(r.indexOf("Scream (1996)") < r.indexOf("4K: Scream (1996)"))
        assertTrue(r.indexOf("4K: Scream (1996)") < r.indexOf("Scream 2 (1997)"))
        // substring-only matches go last
        assertEquals("Screamers (1995)", r.last { it.startsWith("Scream") })
        assertTrue(r.indexOf("Scream VI (2023)") < r.indexOf("The Screaming Skull (1958)"))
    }

    @Test
    fun `a year in the query is parsed and preferred, not required`() {
        val r = rank("Scream 1996")
        assertEquals("Scream (1996)", r[0])
        // the two 4K twins tie; the one added most recently comes first
        assertEquals("4K: Scream (1996)", r[1])
        assertEquals("4KDV: Scream (1996)", r[2])
        assertTrue("other years still listed", r.contains("Scream (1981)"))
        assertEquals(r, rank("scream (1996)"))
        val q = SearchRanker.parseQuery("Doctor Who 2005")
        assertEquals("doctor who", q.text)
        assertEquals(2005, q.year)
    }

    @Test
    fun `a bare year is searched as text`() {
        val q = SearchRanker.parseQuery("2012")
        assertEquals("2012", q.text)
        assertEquals(null, q.year)
        assertTrue(SearchRanker.titleMatches("2012 (2009)", q))
    }

    @Test
    fun `diacritics and punctuation do not block a match`() {
        val q = SearchRanker.parseQuery("jay z")
        assertTrue(SearchRanker.titleMatches("JAŸ-Z in 8 (2026)", q))
        assertTrue(SearchRanker.titleMatches("4K: JAŸ-Z in 8 (2026)", q))
        assertTrue(SearchRanker.titleMatches("Spider-Man (2002)", SearchRanker.parseQuery("spider man")))
        assertTrue(SearchRanker.titleMatches("24/7: Warehouse 13", SearchRanker.parseQuery("Warehouse 13")))
        assertFalse(SearchRanker.titleMatches("Jason Bourne (2016)", q))
    }

    @Test
    fun `word boundary beats substring, prefix beats inner word`() {
        val items = listOf(
            Title("Screamers (1995)", 1),
            Title("The Scream Team (2002)", 2),
            Title("Scream Queens (2015)", 3),
            Title("Scream (1996)", 4),
        )
        assertEquals(
            listOf("Scream (1996)", "Scream Queens (2015)", "The Scream Team (2002)", "Screamers (1995)"),
            rank("scream", items)
        )
    }

    @Test
    fun `cast-only matches are kept but ranked last`() {
        val items = listOf(Title("Heat (1995)", 1), Title("Scream (1996)", 2))
        val q = SearchRanker.parseQuery("scream")
        val r = SearchRanker.rank(items, q, { it.name }, { it.added }) { it.name.startsWith("Heat") }
        assertEquals(listOf("Scream (1996)", "Heat (1995)"), r.map { it.name })
    }

    @Test
    fun `blank query matches nothing`() {
        assertTrue(rank("   ").isEmpty())
        assertTrue(rank("()").isEmpty())
    }
}
