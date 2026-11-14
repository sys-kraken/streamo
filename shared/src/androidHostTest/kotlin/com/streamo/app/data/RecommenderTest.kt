package com.streamo.app.data

import com.streamo.app.data.Recommender.Candidate
import com.streamo.app.data.Recommender.Source
import com.streamo.app.ui.dayLabel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Offline checks of the on-phone recommendations (no YouTube needed). */
class RecommenderTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun v(id: String, title: String, channel: String, views: Long = 1000, uploaded: String? = "2 days ago") =
        VideoItem("https://youtube.com/watch?v=$id", title, channel, "https://youtube.com/channel/$channel", null, 300, views, uploaded)

    private val chess1 = v("c1", "Magnus Carlsen crushes the London system", "chesscom")
    private val chess2 = v("c2", "Carlsen blitz speedrun London opening", "chesscom")
    private val cooking = v("k1", "Easy pasta carbonara recipe", "cookchannel")

    private fun profile(
        history: List<VideoItem> = listOf(chess1),
        watch: Map<String, WatchState> = mapOf(chess1.url to WatchState(now - 2 * day, 280_000, 300_000)),
        searches: List<String> = emptyList(),
        hidden: LibraryStore.Hidden = LibraryStore.Hidden(),
    ) = Recommender.profile(history, watch, searches, emptyList(), hidden, now)

    @Test
    fun recentlyWatchedAndHiddenVideosAreLeftOut() {
        val p = profile(hidden = LibraryStore.Hidden(videos = setOf(cooking.url)))
        val feed = Recommender.rankFeed(
            listOf(Candidate(chess1, Source.RELATED, 0), Candidate(cooking, Source.RELATED, 1), Candidate(chess2, Source.RELATED, 2)),
            p, Random(1), now,
        )
        assertEquals(listOf(chess2.url), feed.map { it.url })
    }

    @Test
    fun hiddenChannelsAreLeftOut() {
        val p = profile(hidden = LibraryStore.Hidden(channels = setOf("https://youtube.com/channel/chesscom")))
        val feed = Recommender.rankFeed(listOf(Candidate(chess2, Source.RELATED, 0), Candidate(cooking, Source.TRENDING, 0)), p, Random(1), now)
        assertEquals(listOf(cooking.url), feed.map { it.url })
    }

    @Test
    fun likedTopicsAndChannelsRankHigher() {
        val other = v("x1", "Random vlog about my week", "vlogger")
        val feed = Recommender.rankFeed(
            // YouTube's own order puts the vlog first; your chess watching should win.
            listOf(Candidate(other, Source.TRENDING, 0), Candidate(chess2, Source.TRENDING, 5)),
            profile(), Random(3), now,
        )
        assertEquals(chess2.url, feed.first().url)
    }

    @Test
    fun searchesShapeTheFeed() {
        val pasta = v("k2", "Carbonara the Roman way", "otherchef")
        val p = profile(history = emptyList(), watch = emptyMap(), searches = listOf("carbonara recipe"))
        val feed = Recommender.rankFeed(
            listOf(Candidate(v("z", "Car review", "cars"), Source.TRENDING, 0), Candidate(pasta, Source.TRENDING, 3)),
            p, Random(2), now,
        )
        assertEquals(pasta.url, feed.first().url)
    }

    @Test
    fun oneChannelDoesNotTakeOverTheFeed() {
        val many = (1..6).map { v("m$it", "Chess game number $it", "chesscom") }
        val others = (1..3).map { v("o$it", "Something else $it", "ch$it") }
        val feed = Recommender.rankFeed(
            (many + others).mapIndexed { i, x -> Candidate(x, Source.RELATED, i) }, profile(), Random(4), now,
        )
        val firstFive = feed.take(5).map { it.channelName }
        assertTrue(firstFive.count { it == "chesscom" } <= 3, "first five: $firstFive")
    }

    @Test
    fun upNextSkipsWatchedVideosAndRepeats() {
        val related = listOf(chess1, chess2, cooking)
        val current = v("now", "Some chess video", "chesscom")
        val next = Recommender.upNext(current, related, profile())
        assertEquals(chess2.url, next?.url)
        // The watched one is still listed, just last.
        val ranked = Recommender.rankRelated(current, related, profile())
        assertEquals(chess1.url, ranked.last().url)
        assertNotEquals(current.url, next?.url)
    }

    @Test
    fun oldPartlyWatchedVideosMayComeBack() {
        val old = WatchState(now - 60 * day, 30_000, 600_000)
        val p = profile(history = listOf(chess1), watch = mapOf(chess1.url to old))
        assertFalse(chess1.url in p.excluded)
    }

    @Test
    fun ageFromText() {
        assertEquals(3 * day, Recommender.ageMs(v("a", "t", "c", uploaded = "3 days ago"), now))
        assertEquals(2 * 7 * day, Recommender.ageMs(v("a", "t", "c", uploaded = "Streamed 2 weeks ago"), now))
    }

    // ---------- Learning from what the viewer does ----------

    private val gaming = v("g1", "Minecraft hardcore survival day one", "gamer")
    private val gaming2 = v("g2", "Minecraft hardcore castle build", "gamer")
    private val news = v("n1", "Evening news roundup economy", "newsdesk")
    private val news2 = v("n2", "Economy news analysis tonight", "newsdesk")

    @Test
    fun quittingEarlyCountsAgainstAChannel() {
        // Watched a long gaming video for 5 seconds a week ago; finished a news video.
        val watch = mapOf(
            gaming.url to WatchState(now - 7 * day, 5_000, 1_200_000),
            news.url to WatchState(now - 7 * day, 1_150_000, 1_200_000),
        )
        val p = Recommender.profile(listOf(gaming, news), watch, emptyList(), emptyList(), LibraryStore.Hidden(), now)
        assertTrue((p.channelAffinity["https://youtube.com/channel/gamer"] ?: 0.0) < 0)
        val feed = Recommender.rankFeed(
            listOf(Candidate(gaming2, Source.RELATED, 0), Candidate(news2, Source.RELATED, 3)), p, Random(5), now, discovery = 0,
        )
        assertEquals(news2.url, feed.first().url)
    }

    @Test
    fun rewatchingAndLikingBeatOneOffViews() {
        val watch = mapOf(
            gaming.url to WatchState(now - 30 * day, 600_000, 1_200_000, plays = 4),
            news.url to WatchState(now - 30 * day, 600_000, 1_200_000, plays = 1),
        )
        val p = Recommender.profile(listOf(news, gaming), watch, emptyList(), emptyList(), LibraryStore.Hidden(), now)
        assertTrue(Recommender.personal(gaming2, p) > Recommender.personal(news2, p))

        val withLike = Recommender.profile(listOf(gaming), mapOf(gaming.url to WatchState(now - 30 * day, 0, 0)),
            emptyList(), emptyList(), LibraryStore.Hidden(), now, liked = listOf(news))
        assertTrue(Recommender.personal(news2, withLike) > Recommender.personal(gaming2, withLike))
        assertTrue(news.url in withLike.excluded, "liked videos aren't recommended again")
    }

    @Test
    fun videosShownOftenButNeverOpenedSink() {
        val a = v("a1", "Chess opening traps", "chessA")
        val b = v("b1", "Chess opening traps", "chessB")
        val fresh = Recommender.profile(emptyList(), emptyMap(), emptyList(), emptyList(), LibraryStore.Hidden(), now)
        val tired = Recommender.profile(emptyList(), emptyMap(), emptyList(), emptyList(), LibraryStore.Hidden(), now,
            impressions = mapOf(a.url to 6))
        assertTrue(Recommender.personal(a, tired) < Recommender.personal(a, fresh))
        assertEquals(Recommender.personal(b, fresh), Recommender.personal(b, tired))
    }

    @Test
    fun removedTopicsAreLeftOut() {
        val p = profile(hidden = LibraryStore.Hidden(topics = setOf("carbonara")))
        val feed = Recommender.rankFeed(
            listOf(Candidate(cooking, Source.TRENDING, 0), Candidate(chess2, Source.TRENDING, 1)), p, Random(1), now,
        )
        assertEquals(listOf(chess2.url), feed.map { it.url })
    }

    @Test
    fun timeOfDayHabitsCount() {
        // News always watched in the evening (19:00 UTC), gaming always in the morning (08:00 UTC).
        val evening = (now / day) * day + 19 * 3_600_000L
        val morning = (now / day) * day + 8 * 3_600_000L
        val history = (1..6).flatMap { d ->
            listOf(v("n$d", "News roundup $d", "newsdesk"), v("g$d", "Minecraft survival $d", "gamer"))
        }
        val watch = history.associate { x ->
            val d = x.title.last().digitToInt()
            val at = if (x.channelName == "newsdesk") evening - d * day else morning - d * day
            x.url to WatchState(at, 500_000, 600_000)
        }
        val atNight = Recommender.profile(history, watch, emptyList(), emptyList(), LibraryStore.Hidden(), evening + 30 * 60_000)
        val atBreakfast = Recommender.profile(history, watch, emptyList(), emptyList(), LibraryStore.Hidden(), morning + 30 * 60_000)
        val n = v("nx", "News roundup special", "newsdesk")
        val g = v("gx", "Minecraft survival special", "gamer")
        assertTrue(Recommender.personal(n, atNight) - Recommender.personal(g, atNight) >
            Recommender.personal(n, atBreakfast) - Recommender.personal(g, atBreakfast))
    }

    @Test
    fun discoveryMixesInNewChannelsWithReasons() {
        val known = (1..10).map { v("k$it", "Chess game $it", "chesscom") }
        val unknown = (1..4).map { v("u$it", "Woodworking project $it", "woodshop$it", views = 2_000_000) }
        val p = profile()
        val ranked = Recommender.rankFeedWithReasons(
            known.mapIndexed { i, x -> Candidate(x, Source.RELATED, i, because = chess1.title) } +
                unknown.mapIndexed { i, x -> Candidate(x, Source.TRENDING, i) },
            p, Random(9), now, discovery = 2,
        )
        val firstTen = ranked.take(10)
        assertTrue(firstTen.any { it.reason == "Something new for you" }, firstTen.map { it.reason }.toString())
        assertTrue(ranked.any { it.reason?.startsWith("Because you watched") == true })
        val none = Recommender.rankFeedWithReasons(
            known.map { Candidate(it, Source.RELATED, 0) } + unknown.map { Candidate(it, Source.TRENDING, 0) },
            p, Random(9), now, discovery = 0,
        )
        assertTrue(none.none { it.reason == "Something new for you" })
    }

    @Test
    fun dayLabels() {
        assertEquals("Today", dayLabel(now, now))
        assertEquals("Yesterday", dayLabel(now - day, now))
        val older = dayLabel(now - 40 * day, now)
        assertTrue(older.matches(Regex("[A-Z][a-z]{2} \\d{1,2}(, \\d{4})?")), older)
    }
}
