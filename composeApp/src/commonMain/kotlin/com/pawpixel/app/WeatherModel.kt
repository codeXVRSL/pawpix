package com.pawpixel.app

import com.pawpixel.core.Json
import com.pawpixel.core.Weather
import com.pawpixel.core.WeatherAdvice

/**
 * The weather for the owner's ~1 km area (the pet map's cell, never an exact spot), from
 * Open-Meteo, cached for an hour in the app's files. Nothing is fetched without an area.
 */
class WeatherModel(private val platform: Platform, private val files: FileStore) {
    private val FILE = "weather.json"
    private var cached: Weather? = files.readText(FILE)?.let { runCatching {
        val j = Json.parse(it)
        Weather(j["t"].double!!, j["f"].double!!, j["p"].double ?: 0.0, j["c"].int ?: 0, j["d"].bool ?: true, j["at"].long ?: 0L)
    }.getOrNull() }

    /** The cached reading, if it's still fresh. */
    fun fresh(): Weather? = cached?.takeIf { platform.nowMs() - it.fetchedAtMs < WeatherAdvice.CACHE_MS }

    /** Fetches when the cache is stale; null when there's no connection and nothing cached. */
    suspend fun current(lat: Double, lng: Double): Weather? {
        fresh()?.let { return it }
        val text = platform.fetchBytes(WeatherAdvice.url(lat, lng))?.decodeToString() ?: return cached
        val w = WeatherAdvice.parse(text, platform.nowMs()) ?: return cached
        cached = w
        files.writeText(FILE, Json.obj("t" to w.tempC, "f" to w.feelsC, "p" to w.precipMm, "c" to w.code, "d" to w.isDay, "at" to w.fetchedAtMs).stringify())
        return w
    }
}
