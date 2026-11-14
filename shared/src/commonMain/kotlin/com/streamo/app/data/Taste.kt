package com.streamo.app.data

import com.streamo.app.nowMillis
import com.streamo.app.utcOffsetMillis

/** Your taste profile right now, from everything saved on the phone (see [Recommender]). */
fun currentTasteProfile(): Recommender.Profile {
    val now = nowMillis()
    return Recommender.profile(
        history = LibraryStore.history.value,
        watch = LibraryStore.watch.value,
        searches = LibraryStore.searches.value,
        subscriptions = LibraryStore.subscriptions.value,
        hidden = LibraryStore.hidden.value,
        now = now,
        liked = LibraryStore.liked.value,
        impressions = LibraryStore.impressions.value,
        utcOffsetMs = utcOffsetMillis(now),
    )
}
