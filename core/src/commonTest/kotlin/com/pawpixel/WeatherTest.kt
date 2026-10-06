package com.pawpixel

import com.pawpixel.core.Species
import com.pawpixel.core.WeatherAdvice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {
    private val sample = """{"latitude":13.625,"longitude":123.1875,"current":{"time":"2026-10-06T14:00","interval":900,"temperature_2m":33.4,"apparent_temperature":39.1,"precipitation":0.0,"weather_code":1,"is_day":1}}"""

    @Test fun parsesOpenMeteoAndWarnsAboutHotPavement() {
        val w = WeatherAdvice.parse(sample, 5L)!!
        assertEquals(33.4, w.tempC); assertTrue(w.isDay && w.clear && w.hotPavement)
        assertEquals("33° · sunny", WeatherAdvice.chip(w))
        assertEquals("33° out: the pavement burns paws. Walk Kape early or after sunset, and bring water.", WeatherAdvice.advice(w, "Kape", Species.DOG))
        assertEquals("33° out. Keep Mochi in the shade with fresh water.", WeatherAdvice.advice(w, "Mochi", Species.CAT))
        assertTrue(WeatherAdvice.url(13.6218, 123.1948).startsWith("https://api.open-meteo.com/v1/forecast?latitude=13.62&longitude=123.19&current="))
    }

    @Test fun rainStormAndAPlainDay() {
        val rain = WeatherAdvice.parse(sample.replace("\"weather_code\":1", "\"weather_code\":61").replace("33.4", "26.0").replace("39.1", "27.0"), 0)!!
        assertEquals("Rain out there. A short walk, then a towel for Kape.", WeatherAdvice.advice(rain, "Kape", Species.DOG))
        assertEquals("26° · rain", WeatherAdvice.chip(rain))
        val storm = WeatherAdvice.parse(sample.replace("\"weather_code\":1", "\"weather_code\":95"), 0)!!
        assertTrue(WeatherAdvice.advice(storm, "Kape", Species.DOG)!!.startsWith("Thunder"))
        val plain = WeatherAdvice.parse(sample.replace("33.4", "30.5").replace("39.1", "31.0").replace("\"weather_code\":1", "\"weather_code\":3"), 0)!!
        assertNull(WeatherAdvice.advice(plain, "Mochi", Species.CAT))
        assertNull(WeatherAdvice.parse("<html>", 0))
    }
}
