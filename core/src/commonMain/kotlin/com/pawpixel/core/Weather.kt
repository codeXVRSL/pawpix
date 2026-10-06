package com.pawpixel.core

import com.pawpixel.i18n.tr

/** The weather outside, as Open-Meteo reports it for the owner's ~1 km area. */
data class Weather(val tempC: Double, val feelsC: Double, val precipMm: Double, val code: Int, val isDay: Boolean, val fetchedAtMs: Long) {
    val rain: Boolean get() = code in 51..67 || code in 80..82
    val snow: Boolean get() = code in 71..77 || code in 85..86
    val storm: Boolean get() = code >= 95
    val clear: Boolean get() = code <= 1
    val cloudy: Boolean get() = code in 2..3
    val fog: Boolean get() = code in 45..48
    /** Sunny and warm enough that asphalt burns paws (it runs 20 to 30 degrees hotter than the air). */
    val hotPavement: Boolean get() = isDay && (tempC >= 32 || (tempC >= 29 && clear))
}

/**
 * What the weather means for the pet, in the pet's own words. Free data from Open-Meteo
 * (https://open-meteo.com, CC BY 4.0), fetched for the owner's area only, at most hourly.
 */
object WeatherAdvice {
    const val CACHE_MS = 60 * 60 * 1000L

    fun url(lat: Double, lng: Double): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${fmt(lat)}&longitude=${fmt(lng)}&current=temperature_2m,apparent_temperature,precipitation,weather_code,is_day&forecast_days=1"

    private fun fmt(v: Double) = ((v * 100).toLong() / 100.0).toString()

    fun parse(json: String, nowMs: Long): Weather? = runCatching {
        val c = Json.parse(json)["current"]
        Weather(
            tempC = c["temperature_2m"].double ?: return null, feelsC = c["apparent_temperature"].double ?: (c["temperature_2m"].double ?: 0.0),
            precipMm = c["precipitation"].double ?: 0.0, code = c["weather_code"].int ?: 0, isDay = (c["is_day"].int ?: 1) == 1, fetchedAtMs = nowMs,
        )
    }.getOrNull()

    /** "31° · sunny" for the little chip in the room. */
    fun chip(w: Weather): String = "${w.tempC.toInt()}° · " + when {
        w.storm -> tr("thunder"); w.snow -> tr("snow"); w.rain -> tr("rain"); w.fog -> tr("fog")
        w.cloudy -> tr("cloudy"); w.isDay -> tr("sunny"); else -> tr("clear night")
    }

    /** Something worth saying about the weather, or null when it's just a day. */
    fun advice(w: Weather, petName: String, species: Species): String? {
        val dog = species != Species.CAT
        return when {
            w.storm -> tr("Thunder outside. {0} may want to hide: stay close and keep the doors shut.", petName)
            w.hotPavement -> if (dog) tr("{0}° out: the pavement burns paws. Walk {1} early or after sunset, and bring water.", w.tempC.toInt(), petName)
                else tr("{0}° out. Keep {1} in the shade with fresh water.", w.tempC.toInt(), petName)
            w.feelsC >= 35 -> tr("It feels like {0}° today. Water and shade for {1}, and no midday walks.", w.feelsC.toInt(), petName)
            w.snow -> tr("Snow! Short trips out for {0}, and dry those paws after.", petName)
            w.rain -> if (dog) tr("Rain out there. A short walk, then a towel for {0}.", petName) else tr("Rain today. A window-watching day for {0}.", petName)
            w.tempC <= 8 -> tr("Chilly out. {0} might like a warm spot (or a sweater) today.", petName)
            w.isDay && w.tempC in 16.0..28.0 && (w.clear || w.cloudy) && dog -> tr("Lovely out. Perfect walk weather for {0}.", petName)
            else -> null
        }
    }
}
