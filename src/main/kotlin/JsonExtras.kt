package io.streamcord.spyglass

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.reflect.typeOf

val safeJson = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalStdlibApi::class)
internal inline fun <reified T> Json.Default.safeDecodeFromString(text: String): T = try {
    decodeFromString(text)
} catch (ex: SerializationException) {
    logger.warn("Exception caught while deserializing from String to ${typeOf<T>().classifier?.toString()}", ex)
    safeJson.decodeFromString(text)
}
