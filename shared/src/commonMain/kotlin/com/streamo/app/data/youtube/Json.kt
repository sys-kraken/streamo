package com.streamo.app.data.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Small helpers for reading YouTube's deeply nested JSON without a pile of casts.
// Every step is null-safe: a missing key anywhere just gives null.

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

internal operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

internal val JsonElement?.list: List<JsonElement> get() = (this as? JsonArray).orEmpty()

internal val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.content?.toLongOrNull()

internal val JsonElement?.bool: Boolean get() = (this as? JsonPrimitive)?.content == "true"

/** The text of YouTube's text objects: `{"simpleText"}`, `{"runs": [{"text"}]}` or `{"content"}`. */
internal val JsonElement?.text: String?
    get() = this["simpleText"].str
        ?: this["runs"].list.mapNotNull { it["text"].str }.takeIf { it.isNotEmpty() }?.joinToString("")
        ?: this["content"].str
        ?: str

/** Every value stored under [key], anywhere inside this element, in document order. */
internal fun JsonElement?.findAll(key: String): List<JsonElement> {
    val out = mutableListOf<JsonElement>()
    fun visit(e: JsonElement?) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> if (k == key) out += v; visit(v) }
            is JsonArray -> e.forEach(::visit)
            else -> {}
        }
    }
    visit(this)
    return out
}

internal fun JsonElement?.findFirst(key: String): JsonElement? = findAll(key).firstOrNull()

/** One picture in a list of sizes. */
internal class Thumb(val url: String, val height: Int)

/** The pictures in a `{"thumbnails": [...]}` or `{"sources": [...]}` list, or such a list itself. */
internal fun JsonElement?.thumbs(): List<Thumb> {
    val items = (this as? JsonArray) ?: this["thumbnails"] as? JsonArray ?: this["sources"] as? JsonArray
    return items.orEmpty().mapNotNull { t ->
        val url = t["url"].str ?: return@mapNotNull null
        Thumb(if (url.startsWith("//")) "https:$url" else url, t["height"].long?.toInt() ?: 0)
    }
}

/** Smallest picture that is at least [targetHeight] tall, else the biggest one. */
internal fun List<Thumb>.pick(targetHeight: Int): String? {
    if (isEmpty()) return null
    val known = filter { it.height > 0 }.sortedBy { it.height }
    return (known.firstOrNull { it.height >= targetHeight } ?: known.lastOrNull() ?: first()).url
}
