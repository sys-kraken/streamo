package com.streamo.app.data

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Streamo's matchmaking: finds the videos that suit *this* viewer instead of what's popular with
 * everyone. It all runs on the phone; nothing about you is sent anywhere.
 *
 * YouTube supplies candidates (related videos, search results, subscription uploads, charts).
 * Each candidate is matched against a taste profile learned from what you actually do:
 *  - what you watch, how much of it (finishing = liked it, quitting early = didn't) and rewatches,
 *  - videos you like, channels you subscribe to, what you search for,
 *  - what you tend to watch at this time of day, and how long your videos usually are,
 *  - videos shown to you again and again that you never open (you're probably not interested),
 *  - "Not interested", "Don't recommend channel" and topics removed under "Your interests".
 * Then the list is spread out so no channel or topic takes over, a few "something new" picks are
 * mixed in so your feed can grow beyond what you already watch, and each video says why it's there.
 */
object Recommender {

    /** Where a candidate came from. The weight says how much that kind of source is trusted. */
    enum class Source(val weight: Double) {
        RELATED(1.0), SEARCH(0.75), SUBSCRIPTION(0.9), TRENDING(0.35)
    }

    /** A candidate video; [because] names what led to it (a watched video's title or a search). */
    data class Candidate(
        val video: VideoItem,
        val source: Source,
        val rank: Int,
        val seedWeight: Double = 1.0,
        val because: String? = null,
    )

    /** A recommended video and the short reason shown under it. */
    data class Ranked(val video: VideoItem, val reason: String?)

    /** What Streamo has learned about you. All maps are scaled so the strongest liking is 1. */
    class Profile(
        /** -1..1 per channel: negative for channels you keep abandoning. */
        val channelAffinity: Map<String, Double>,
        /** -1..1 per word in titles you watch and search for. */
        val keywords: Map<String, Double>,
        val subscribed: Set<String>,
        /** Videos not to suggest: watched recently, finished, or hidden. */
        val excluded: Set<String>,
        val hiddenChannels: Set<String>,
        val hiddenVideos: Set<String> = emptySet(),
        val hiddenTopics: Set<String> = emptySet(),
        val likedChannels: Set<String> = emptySet(),
        /** 0..1 per length bucket (see [lengthBucket]); empty until there's enough history. */
        val lengthPreference: Map<Int, Double> = emptyMap(),
        /** 0..1 per channel and per word for what you watch at this time of day. */
        val timeOfDayChannels: Map<String, Double> = emptyMap(),
        val timeOfDayWords: Map<String, Double> = emptyMap(),
        val impressions: Map<String, Int> = emptyMap(),
        /** Channels you've watched at all; anything else counts as "new" for discovery. */
        val knownChannels: Set<String> = emptySet(),
    ) {
        /** Your strongest interests, for the "Your interests" page. */
        fun topTopics(n: Int): List<String> =
            keywords.filter { it.value > 0.15 }.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }

    private const val DAY = 24L * 60 * 60 * 1000
    private const val HOUR = 60L * 60 * 1000

    fun profile(
        history: List<VideoItem>,
        watch: Map<String, WatchState>,
        searches: List<String>,
        subscriptions: List<ChannelRef>,
        hidden: LibraryStore.Hidden,
        now: Long,
        liked: List<VideoItem> = emptyList(),
        impressions: Map<String, Int> = emptyMap(),
        utcOffsetMs: Long = 0,
    ): Profile {
        val affinity = HashMap<String, Double>()
        val words = HashMap<String, Double>()
        val nowBucket = dayPart(now, utcOffsetMs)
        val todChannels = HashMap<String, Double>()
        val todWords = HashMap<String, Double>()
        val lengths = HashMap<Int, Double>()
        var lengthSamples = 0

        history.take(200).forEachIndexed { i, v ->
            val w = watch[v.url]
            val recency = 0.97.pow(i)
            // How much you liked it, judged by what you did: finished > half > quit right away.
            // The video playing now (or just opened) doesn't count as abandoned yet.
            val settled = w == null || now - w.watchedAtMs > 10 * 60_000
            val f = w?.fraction
            var engagement = when {
                f == null -> 0.6
                !settled -> 0.6
                f < 0.12 && (w?.durationMs ?: 0L) > 90_000 -> -0.45 // opened, then left within seconds
                else -> 0.25 + f
            }
            // Watching something again says more than anything else.
            if (w != null && w.plays > 1) engagement += 0.35 * (w.plays - 1).coerceAtMost(4)
            val weight = recency * engagement
            channelKey(v)?.let { affinity[it] = (affinity[it] ?: 0.0) + weight }
            if (i < 60) tokens(v.title).forEach { t -> words[t] = (words[t] ?: 0.0) + weight }

            if (engagement > 0) {
                if (v.durationSec > 0) {
                    val b = lengthBucket(v.durationSec)
                    lengths[b] = (lengths[b] ?: 0.0) + engagement
                    lengthSamples++
                }
                if (w != null && dayPart(w.watchedAtMs, utcOffsetMs) == nowBucket) {
                    channelKey(v)?.let { todChannels[it] = (todChannels[it] ?: 0.0) + recency }
                    tokens(v.title).forEach { t -> todWords[t] = (todWords[t] ?: 0.0) + recency }
                }
            }
        }
        // Liked videos count like a finished, rewatched video.
        liked.take(100).forEachIndexed { i, v ->
            val weight = 1.6 * 0.98.pow(i)
            channelKey(v)?.let { affinity[it] = (affinity[it] ?: 0.0) + weight }
            tokens(v.title).forEach { t -> words[t] = (words[t] ?: 0.0) + weight }
        }
        searches.take(15).forEachIndexed { i, q ->
            val weight = 1.5 * 0.85.pow(i)
            tokens(q).forEach { t -> words[t] = (words[t] ?: 0.0) + weight }
        }
        subscriptions.forEach { affinity[it.url] = (affinity[it.url] ?: 0.0) + 1.5 }
        hidden.topics.forEach { words.remove(it) }

        val excluded = HashSet<String>(hidden.videos)
        history.forEach { v ->
            val w = watch[v.url]
            val recent = w == null || now - w.watchedAtMs < 21 * DAY
            val finished = (w?.fraction ?: 0f) > 0.85f
            if (recent || finished) excluded += v.url
        }
        excluded += liked.map { it.url }

        return Profile(
            channelAffinity = normaliseSigned(affinity),
            keywords = normaliseSigned(words),
            subscribed = subscriptions.map { it.url }.toSet(),
            excluded = excluded,
            hiddenChannels = hidden.channels,
            hiddenVideos = hidden.videos,
            hiddenTopics = hidden.topics,
            likedChannels = liked.mapNotNull { channelKey(it) }.toSet(),
            lengthPreference = if (lengthSamples >= 5) normaliseBuckets(lengths) else emptyMap(),
            timeOfDayChannels = normalise(todChannels),
            timeOfDayWords = normalise(todWords),
            impressions = impressions,
            knownChannels = (history.mapNotNull { channelKey(it) } + liked.mapNotNull { channelKey(it) } +
                subscriptions.map { it.url }).toSet(),
        )
    }

    /** Which watched videos to fetch related videos for: recent, enjoyed ones first, varied channels. */
    fun seeds(history: List<VideoItem>, watch: Map<String, WatchState>, count: Int, random: Random): List<VideoItem> {
        val scored = history.take(30).mapIndexed { i, v ->
            val w = watch[v.url]
            val f = w?.fraction ?: 0.5f
            val rewatch = 1.0 + 0.3 * ((w?.plays ?: 1) - 1).coerceAtMost(3)
            v to (0.9.pow(i) * (0.3 + f) * rewatch * (0.8 + 0.4 * random.nextDouble()))
        }.sortedByDescending { it.second }
        val picked = mutableListOf<VideoItem>()
        val channels = mutableSetOf<String?>()
        for ((v, _) in scored) {
            if (picked.size >= count) break
            val ch = channelKey(v)
            if (ch in channels && scored.size > count * 2) continue
            channels += ch
            picked += v
        }
        return picked
    }

    /** The Home feed as plain videos (see [rankFeedWithReasons]). */
    fun rankFeed(candidates: List<Candidate>, profile: Profile, random: Random, now: Long, discovery: Int = 1): List<VideoItem> =
        rankFeedWithReasons(candidates, profile, random, now, discovery).map { it.video }

    /**
     * The Home feed: candidates merged and matched to your profile, spread out so no channel or topic
     * takes over, with "something new" picks mixed in ([discovery]: 0 none, 1 some, 2 more).
     */
    fun rankFeedWithReasons(
        candidates: List<Candidate>,
        profile: Profile,
        random: Random,
        now: Long,
        discovery: Int = 1,
    ): List<Ranked> {
        class Entry(val video: VideoItem, var prior: Double, var best: Candidate)
        val byUrl = LinkedHashMap<String, Entry>()
        for (c in candidates) {
            val v = c.video
            if (!allowed(v, profile)) continue
            // Earlier in YouTube's own list = YouTube thinks it's a better match.
            val prior = c.source.weight * c.seedWeight / (1.0 + 0.08 * c.rank)
            val old = byUrl[v.url]
            if (old == null) byUrl[v.url] = Entry(v, prior, c)
            else {
                // Showing up from several sources (related to two videos, and a search) is a strong sign.
                old.prior += prior * 0.6
                if (c.source.weight * c.seedWeight > old.best.source.weight * old.best.seedWeight) old.best = c
            }
        }
        val scored = byUrl.values.map { e ->
            val match = personal(e.video, profile)
            val score = e.prior + match + freshness(e.video, now) + popularity(e.video) + 0.2 * random.nextDouble()
            Triple(e, match, score)
        }
        val reasons = scored.associate { (e, match, _) -> e.video.url to reason(e.best, e.video, match, profile) }

        // Discovery picks: new channels, decent on their own merits, not against your tastes.
        val discoveryPool = if (discovery <= 0) emptyList() else scored
            .filter { (e, match, _) -> channelKey(e.video) !in profile.knownChannels && match > -0.2 }
            .sortedByDescending { (e, _, score) -> score + popularity(e.video) }
            .map { it.first.video }
        val main = diversify(scored.map { it.first.video to it.third })
        if (discoveryPool.isEmpty()) return main.map { Ranked(it, reasons[it.url]) }

        // Every 6th (or 4th, with more discovery) spot from the third onwards is something new.
        val every = if (discovery >= 2) 4 else 6
        val out = ArrayList<Ranked>(main.size)
        val used = HashSet<String>()
        val pool = ArrayDeque(discoveryPool)
        val rest = ArrayDeque(main)
        var position = 0
        while (rest.isNotEmpty() || pool.isNotEmpty()) {
            val wantNew = position >= 2 && (position - 2) % every == 0
            var next: VideoItem? = null
            if (wantNew) {
                // Skip new-channel videos that already made it into the feed on their own.
                while (pool.isNotEmpty() && next == null) next = pool.removeFirst().takeIf { it.url !in used }
            }
            val pick = next ?: rest.removeFirstOrNull() ?: pool.removeFirstOrNull() ?: break
            if (!used.add(pick.url)) continue
            out += Ranked(pick, if (next != null) "Something new for you" else reasons[pick.url])
            position++
        }
        return out
    }

    /**
     * Up next for the watch page and autoplay: YouTube's related list, nudged towards channels and
     * topics you like and the video being watched, with recently watched videos moved to the end.
     */
    fun rankRelated(current: VideoItem, related: List<VideoItem>, profile: Profile): List<VideoItem> {
        val currentWords = tokens(current.title).toSet()
        val fresh = mutableListOf<Pair<VideoItem, Double>>()
        val seen = mutableListOf<VideoItem>()
        related.distinctBy { it.url }.forEachIndexed { i, v ->
            if (v.url == current.url || v.url in profile.hiddenVideos || channelKey(v) in profile.hiddenChannels) return@forEachIndexed
            if (hasHiddenTopic(v, profile)) return@forEachIndexed
            if (v.url in profile.excluded) { seen += v; return@forEachIndexed }
            val overlap = tokens(v.title).count { it in currentWords }.toDouble()
            val score = 1.6 / (1.0 + 0.12 * i) + 0.5 * personal(v, profile) + 0.08 * overlap - if (v.isLive) 0.4 else 0.0
            fresh += v to score
        }
        return diversify(fresh, penalty = 0.75) + seen
    }

    /** The video to play automatically after [current], or null if there's nothing suitable. */
    fun upNext(current: VideoItem, related: List<VideoItem>, profile: Profile): VideoItem? =
        rankRelated(current, related, profile).firstOrNull { it.url !in profile.excluded && !it.isLive && it.url != current.url }

    // ---------- Scoring parts ----------

    private fun allowed(v: VideoItem, p: Profile): Boolean =
        v.url !in p.excluded && channelKey(v) !in p.hiddenChannels && v.title.isNotBlank() && !hasHiddenTopic(v, p)

    private fun hasHiddenTopic(v: VideoItem, p: Profile) =
        p.hiddenTopics.isNotEmpty() && tokens(v.title).any { it in p.hiddenTopics }

    /** How well a video matches you, roughly -1..2.5. */
    fun personal(v: VideoItem, p: Profile): Double {
        val ch = channelKey(v)
        var channel = ch?.let { p.channelAffinity[it] } ?: 0.0
        if (ch != null && ch in p.subscribed) channel += 0.3
        if (ch != null && ch in p.likedChannels) channel += 0.25
        val t = tokens(v.title)
        val topic = if (t.isEmpty()) 0.0 else (t.sumOf { p.keywords[it] ?: 0.0 } / sqrt(t.size.toDouble())).coerceIn(-1.0, 1.5)
        // What you usually watch at this hour (e.g. music at night, news in the morning).
        val timeOfDay = (ch?.let { p.timeOfDayChannels[it] } ?: 0.0) * 0.5 +
            (if (t.isEmpty()) 0.0 else t.sumOf { p.timeOfDayWords[it] ?: 0.0 } / t.size).coerceAtMost(1.0) * 0.5
        val length = if (p.lengthPreference.isEmpty() || v.durationSec <= 0) 0.0
            else (p.lengthPreference[lengthBucket(v.durationSec)] ?: 0.0) - 0.4
        // Shown again and again but never opened: probably not for you.
        val fatigue = 0.1 * ((p.impressions[v.url] ?: 0) - 1).coerceIn(0, 5)
        return 0.9 * channel + 0.7 * topic + 0.3 * timeOfDay + 0.25 * length - fatigue
    }

    private fun reason(c: Candidate, v: VideoItem, match: Double, p: Profile): String? {
        val ch = channelKey(v)
        return when {
            c.source == Source.SUBSCRIPTION || (ch != null && ch in p.subscribed) -> "From your subscriptions"
            c.source == Source.RELATED && c.because != null -> "Because you watched “${short(c.because)}”"
            c.source == Source.SEARCH && c.because != null -> "Because you searched “${short(c.because)}”"
            ch != null && ch in p.likedChannels -> "From a channel you liked"
            ch != null && (p.channelAffinity[ch] ?: 0.0) > 0.4 -> "From a channel you watch"
            match > 0.5 -> "Matches what you watch"
            c.source == Source.TRENDING -> "Popular right now"
            else -> null
        }
    }

    private fun short(text: String) = if (text.length <= 38) text else text.take(36).trimEnd() + "…"

    private fun freshness(v: VideoItem, now: Long): Double {
        val age = ageMs(v, now) ?: return 0.0
        val days = age.toDouble() / DAY
        return when {
            days < 2 -> 0.35
            days < 7 -> 0.25
            days < 30 -> 0.12
            days < 365 -> 0.03
            else -> 0.0
        }
    }

    private fun popularity(v: VideoItem): Double =
        if (v.views <= 0) 0.0 else (ln(v.views.toDouble()) / ln(10.0) / 8.0).coerceIn(0.0, 1.0) * 0.2

    /** Picks greedily; each further video from a channel (or on a topic) already picked counts for less. */
    private fun diversify(scored: List<Pair<VideoItem, Double>>, penalty: Double = 0.6): List<VideoItem> {
        val remaining = scored.toMutableList()
        val perChannel = HashMap<String?, Int>()
        val perTopic = HashMap<String, Int>()
        val out = ArrayList<VideoItem>(scored.size)
        while (remaining.isNotEmpty()) {
            var best = 0
            var bestScore = Double.NEGATIVE_INFINITY
            remaining.forEachIndexed { i, (v, s) ->
                val topicRepeats = tokens(v.title).maxOfOrNull { perTopic[it] ?: 0 } ?: 0
                // Scores can be negative; shift so the penalties always push down.
                val base = (s + 2.0).coerceAtLeast(0.01)
                val adjusted = base * penalty.pow(perChannel[channelKey(v)] ?: 0) * 0.9.pow(topicRepeats.coerceAtMost(4))
                if (adjusted > bestScore) { bestScore = adjusted; best = i }
            }
            val (v, _) = remaining.removeAt(best)
            val key = channelKey(v)
            perChannel[key] = (perChannel[key] ?: 0) + 1
            tokens(v.title).forEach { perTopic[it] = (perTopic[it] ?: 0) + 1 }
            out += v
        }
        return out
    }

    private fun normalise(map: Map<String, Double>): Map<String, Double> {
        val top = map.values.maxOrNull() ?: return emptyMap()
        if (top <= 0) return emptyMap()
        return map.filterValues { it > 0 }.mapValues { it.value / top }
    }

    private fun normaliseBuckets(map: Map<Int, Double>): Map<Int, Double> {
        val top = map.values.maxOrNull() ?: return emptyMap()
        if (top <= 0) return emptyMap()
        return map.mapValues { it.value / top }
    }

    /** Scales so the strongest liking is 1, keeping dislikes negative (no lower than -1). */
    private fun normaliseSigned(map: Map<String, Double>): Map<String, Double> {
        val top = map.values.maxOrNull()?.takeIf { it > 0 } ?: map.values.maxOfOrNull { abs(it) } ?: return emptyMap()
        if (top == 0.0) return emptyMap()
        return map.mapValues { (it.value / top).coerceIn(-1.0, 1.0) }
    }

    /** 0: under 4 min, 1: 4-20 min, 2: 20-60 min, 3: over an hour. */
    fun lengthBucket(seconds: Long): Int = when {
        seconds < 240 -> 0
        seconds < 1200 -> 1
        seconds < 3600 -> 2
        else -> 3
    }

    /** 0 night (0-6), 1 morning, 2 afternoon, 3 evening, in the phone's time. */
    fun dayPart(ms: Long, utcOffsetMs: Long): Int = (((ms + utcOffsetMs) / HOUR).mod(24L) / 6).toInt()

    fun channelKey(v: VideoItem): String? = v.channelUrl ?: v.channelName.takeIf { it.isNotBlank() }

    private val stopWords = setOf(
        "the", "and", "for", "with", "you", "your", "this", "that", "from", "are", "was", "how", "what",
        "why", "who", "official", "video", "videos", "new", "full", "part", "feat", "ft", "vs", "his", "her",
        "into", "out", "all", "not", "but", "can", "will", "just", "has", "have", "about", "when", "best", "top",
        "lyrics", "audio", "music", "hd", "4k", "episode", "ep", "live", "2024", "2025", "2026", "upgrade",
        "remastered", "version", "mix", "shorts", "short", "ang", "mga", "sa", "ng",
    )

    private inline fun String.splitWords(isSeparator: (Char) -> Boolean): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        for (c in this) {
            if (isSeparator(c)) { if (word.isNotEmpty()) { out += word.toString(); word.clear() } } else word.append(c)
        }
        if (word.isNotEmpty()) out += word.toString()
        return out
    }

    fun tokens(text: String): List<String> =
        text.lowercase().splitWords { !it.isLetterOrDigit() }
            .filter { it.length >= 3 && it !in stopWords && !it.all(Char::isDigit) }
            .distinct()

    /** Roughly how old a video is, from its date or from text like "3 days ago". */
    fun ageMs(v: VideoItem, now: Long): Long? {
        v.uploadedEpochMs?.let { return max(0L, now - it) }
        val text = v.uploaded?.lowercase() ?: return null
        val m = Regex("(\\d+)\\s+(second|minute|hour|day|week|month|year)").find(text) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val unit = when (m.groupValues[2]) {
            "second" -> 1000L
            "minute" -> 60_000L
            "hour" -> 3_600_000L
            "day" -> DAY
            "week" -> 7 * DAY
            "month" -> 30 * DAY
            else -> 365 * DAY
        }
        return n * unit
    }
}
