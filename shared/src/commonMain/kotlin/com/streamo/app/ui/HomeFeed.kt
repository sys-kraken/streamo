package com.streamo.app.ui

import com.streamo.app.data.LibraryStore
import com.streamo.app.data.Recommender
import com.streamo.app.data.Recommender.Candidate
import com.streamo.app.data.Recommender.Source
import com.streamo.app.data.currentTasteProfile
import com.streamo.app.data.SearchResult
import com.streamo.app.data.VideoItem
import com.streamo.app.data.YouTubeRepo
import com.streamo.app.nowMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

/**
 * Builds the "For you" tab: YouTube's related videos for things you watched, results for your
 * recent searches, and new uploads from your subscriptions, re-ranked on the phone by [Recommender].
 */
object HomeFeed {
    private const val DAY = 24L * 60 * 60 * 1000

    /** Why each video in the last "For you" list was picked, shown under its card. */
    var reasons: Map<String, String> = emptyMap()
        private set

    /** Videos already counted as shown this session, so scrolling back doesn't count them twice. */
    private val counted = HashSet<String>()

    /** Called with the videos visible on Home; each counts once per app session. */
    fun shown(urls: List<String>) {
        val fresh = urls.filter { counted.add(it) }
        LibraryStore.recordImpressions(fresh)
    }

    /** True when there's anything to base recommendations on. */
    fun hasSignals(): Boolean =
        LibraryStore.history.value.isNotEmpty() || LibraryStore.searches.value.isNotEmpty() ||
            LibraryStore.subscriptions.value.isNotEmpty()

    suspend fun forYou(refresh: Boolean): List<VideoItem> = coroutineScope {
        val history = LibraryStore.history.value
        val watch = LibraryStore.watch.value
        val searches = LibraryStore.searches.value
        val subs = LibraryStore.subscriptions.value
        val now = nowMillis()
        // The same order for half an hour unless you pull to refresh, then a fresh mix.
        val random = Random(if (refresh) now else now / (30 * 60_000))
        val profile = currentTasteProfile()

        val seeds = Recommender.seeds(history, watch, count = 5, random = random)
        val related = seeds.mapIndexed { i, seed ->
            async {
                runCatching { YouTubeRepo.forYou(listOf(seed)) }.getOrDefault(emptyList())
                    .take(20).mapIndexed { r, v -> Candidate(v, Source.RELATED, r, seedWeight = 1.0 - 0.12 * i, because = seed.title) }
            }
        }
        val searched = searches.take(2).mapIndexed { i, q ->
            async {
                runCatching { YouTubeRepo.search(q).items }.getOrDefault(emptyList())
                    .filterIsInstance<SearchResult.Video>().map { it.video }
                    .take(12).mapIndexed { r, v -> Candidate(v, Source.SEARCH, r, seedWeight = 1.0 - 0.25 * i, because = q) }
            }
        }
        val fromSubs = async {
            if (subs.isEmpty()) return@async emptyList()
            val feed = runCatching { Feeds.load(homeKey(FEED, subs)) { YouTubeRepo.subscriptionFeed(subs) }.await() }
                .getOrDefault(emptyList())
            feed.filter { (Recommender.ageMs(it, now) ?: 0L) < 14 * DAY }
                .take(40).mapIndexed { r, v -> Candidate(v, Source.SUBSCRIPTION, r / 2) }
        }
        var all = (related + searched).awaitAll().flatten() + fromSubs.await()
        // Not much to go on yet, or discovery is on: mix in what's popular (new channels to try).
        if (all.size < 30 || LibraryStore.settings.value.discovery > 0) {
            val trending = YouTubeRepo.exploreLists().firstOrNull()?.let { id ->
                val load = Feeds.load(homeKey(id, subs)) { YouTubeRepo.explore(id) }
                // With nothing else loaded, let a real failure (no internet) show as an error.
                if (all.isEmpty()) load.await() else runCatching { load.await() }.getOrDefault(emptyList())
            }.orEmpty()
            all = all + trending.take(30).mapIndexed { r, v -> Candidate(v, Source.TRENDING, r) }
        }
        val ranked = Recommender.rankFeedWithReasons(all, profile, random, now, LibraryStore.settings.value.discovery)
        reasons = ranked.mapNotNull { r -> r.reason?.let { r.video.url to it } }.toMap()
        ranked.map { it.video }
    }
}
