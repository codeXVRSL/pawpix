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
    /** Where the cached reading is for (the cell centre, to two decimals): a new area fetches afresh. */
    private var cachedFor: Pair<Double, Double>? = files.readText(FILE)?.let { runCatching { val j = Json.parse(it); j["lat"].double!! to j["lng"].double!! }.getOrNull() }

    /** The cached reading, if it's still fresh. */
    fun fresh(): Weather? = cached?.takeIf { platform.nowMs() - it.fetchedAtMs < WeatherAdvice.CACHE_MS }

    /** Fetches when the cache is stale or for another area; null when there's no connection and nothing cached. */
    suspend fun current(lat: Double, lng: Double): Weather? {
        val here = round2(lat) to round2(lng)
        if (cachedFor == here) fresh()?.let { return it }
        val text = platform.fetchBytes(WeatherAdvice.url(lat, lng))?.decodeToString() ?: return cached
        val w = WeatherAdvice.parse(text, platform.nowMs()) ?: return cached
        cached = w; cachedFor = here
        files.writeText(FILE, Json.obj("t" to w.tempC, "f" to w.feelsC, "p" to w.precipMm, "c" to w.code, "d" to w.isDay, "at" to w.fetchedAtMs, "lat" to here.first, "lng" to here.second).stringify())
        return w
    }

    /** Delete all my data: the reading goes too. */
    fun forget() { cached = null; cachedFor = null; files.delete(FILE) }

    private fun round2(v: Double) = kotlin.math.round(v * 100) / 100
}
