package com.pawpixel

import com.pawpixel.core.Settings
import com.pawpixel.core.StateCodec
import com.pawpixel.core.AppState
import com.pawpixel.core.Units
import com.pawpixel.core.WeightTrend
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitsTest {
    @AfterTest fun metricAgain() = Units.configure("", "PH")

    @Test fun followsTheCountry() {
        Units.configure("", "PH"); assertFalse(Units.miles); assertFalse(Units.pounds)
        Units.configure("", "us"); assertTrue(Units.miles); assertTrue(Units.pounds)
        Units.configure("", "GB"); assertTrue(Units.miles); assertFalse(Units.pounds)
        Units.configure("", ""); assertFalse(Units.miles)
        Units.configure(Units.IMPERIAL, "PH"); assertTrue(Units.miles); assertTrue(Units.pounds)
        Units.configure(Units.METRIC, "US"); assertFalse(Units.miles); assertFalse(Units.pounds)
        assertEquals("miles & lb", Units.autoLabel("US")); assertEquals("miles & kg", Units.autoLabel("GB")); assertEquals("km & kg", Units.autoLabel("BR"))
    }

    @Test fun distances() {
        Units.configure(Units.METRIC, "")
        assertEquals("1.2 km", Units.distance(1.24)); assertEquals("0.4 km", Units.distance(0.35)); assertEquals("12 km", Units.distance(12.4)); assertEquals("15 km", Units.radius(15))
        Units.configure(Units.IMPERIAL, "")
        assertEquals("1.0 mi", Units.distance(1.609)); assertEquals("0.6 mi", Units.distance(1.0)); assertEquals("10 miles", Units.radius(15))
    }

    @Test fun weightsShownAndTyped() {
        Units.configure(Units.METRIC, "")
        assertEquals("4.2 kg", Units.weight(4200)); assertEquals(4200, Units.parseWeight("4,2")); assertEquals(4200, Units.parseWeight("4.2 kg"))
        assertEquals(WeightTrend.kg(4200), Units.weight(4200))
        Units.configure(Units.IMPERIAL, "")
        assertEquals("9.3 lb", Units.weight(4200)); assertEquals("lb", Units.weightUnit)
        val g = Units.parseWeight("9.3")!!
        assertTrue(g in 4210..4225, "9.3 lb is about 4218 g, got $g")
        assertEquals("9.3", Units.weightInput(g)) // round-trips through the input
        assertEquals(4200, Units.parseWeight("4.2 kg")) // a typed unit wins
        assertEquals(Units.gramsOfTenths(93), Units.parseWeight("9.3 lb"))
        assertNull(Units.parseWeight("0")); assertNull(Units.parseWeight("abc")); assertNull(Units.parseWeight("9999"))
    }

    @Test fun settingSurvivesSaving() {
        val s = AppState(settings = Settings(units = Units.IMPERIAL))
        assertEquals(Units.IMPERIAL, StateCodec.decode(StateCodec.encode(s)).settings.units)
        assertEquals("", StateCodec.decode(StateCodec.encode(AppState(settings = Settings(units = "furlongs")))).settings.units)
    }
}
