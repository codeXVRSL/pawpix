package com.pawpixel.core

import com.pawpixel.i18n.tr

/**
 * Kilometres and kilograms for most of the world; miles and pounds where people use them. Follows
 * the phone's country unless Settings → Units says otherwise. Weights are always stored in grams
 * and distances in km; only what's shown and typed changes.
 *
 * The United States, Liberia and Myanmar use miles and pounds. The United Kingdom signs roads in
 * miles but weighs pets in kilograms at the vet, so it gets miles only.
 */
object Units {
    const val METRIC = "metric"
    const val IMPERIAL = "imperial"

    val POUND_COUNTRIES = setOf("US", "LR", "MM")
    val MILE_COUNTRIES = POUND_COUNTRIES + "GB"

    @kotlin.concurrent.Volatile
    var miles: Boolean = false
        private set
    @kotlin.concurrent.Volatile
    var pounds: Boolean = false
        private set

    /** Applies the setting ("", [METRIC] or [IMPERIAL]) for a phone in [country] ("US", "PH", ...; blank if unknown). */
    fun configure(setting: String, country: String) {
        val c = country.trim().uppercase()
        when (setting) {
            METRIC -> { miles = false; pounds = false }
            IMPERIAL -> { miles = true; pounds = true }
            else -> { miles = c in MILE_COUNTRIES; pounds = c in POUND_COUNTRIES }
        }
    }

    /** What "Phone's country" means on this phone, as a label: "km & kg" or "miles & lb". */
    fun autoLabel(country: String): String {
        val c = country.trim().uppercase()
        return label(c in MILE_COUNTRIES, c in POUND_COUNTRIES)
    }

    fun label(miles: Boolean, pounds: Boolean): String = when {
        miles && pounds -> tr("miles & lb")
        miles -> tr("miles & kg")
        else -> tr("km & kg")
    }

    const val KM_PER_MILE = 1.609344
    const val GRAMS_PER_POUND = 453.59237

    // ---------- Distance ----------

    /** "1.2 km" or "0.8 mi". */
    fun distance(km: Double): String = if (miles) tr("{0} mi", number(km / KM_PER_MILE)) else tr("{0} km", number(km))

    /** A round-ish distance in the owner's unit, for "owners within 15 km": "15 km" or "10 miles". */
    fun radius(km: Int): String = if (miles) tr("{0} miles", kotlin.math.floor(km / KM_PER_MILE / 5 + 0.5).toInt().coerceAtLeast(1) * 5) else tr("{0} km", km)

    /** One decimal under 10, none above: "0.4", "3.2", "12". */
    fun number(v: Double): String {
        val x = if (v < 0) 0.0 else v
        return if (x < 10) { val t = kotlin.math.floor(x * 10 + 0.5).toInt(); "${t / 10}.${t % 10}" } else kotlin.math.floor(x + 0.5).toInt().toString()
    }

    // ---------- Weight ----------

    /** The unit for weights as shown: "kg" or "lb". */
    val weightUnit: String get() = if (pounds) "lb" else "kg"

    /** The weight in tenths of the shown unit (0.1 kg or 0.1 lb). */
    fun weightTenths(grams: Int): Int = if (pounds) kotlin.math.floor(grams / GRAMS_PER_POUND * 10 + 0.5).toInt() else (grams + 50) / 100

    fun gramsOfTenths(tenths: Int): Int = if (pounds) kotlin.math.floor(tenths / 10.0 * GRAMS_PER_POUND + 0.5).toInt() else tenths * 100

    /** "4.2 kg" or "9.3 lb". */
    fun weight(grams: Int): String = weightInput(grams) + " " + weightUnit

    /** The weight as the owner would type it: "4.2" (kg) or "9.3" (lb). */
    fun weightInput(grams: Int): String = tenthsText(weightTenths(grams))

    fun tenthsText(tenths: Int): String = "${tenths / 10}.${tenths % 10}"

    /** One step of the +/− buttons (0.1 of the shown unit), in grams. */
    val weightStepGrams: Int get() = if (pounds) 45 else WeightTrend.STEP_G

    /**
     * Parses what an owner types ("4.2", "4,2", "9.3 lb") to grams in the shown unit; a typed "kg" or
     * "lb" wins over the setting. Null if it isn't a sensible pet weight.
     */
    fun parseWeight(text: String): Int? {
        val raw = text.trim().lowercase()
        val typedLb = raw.endsWith("lb") || raw.endsWith("lbs")
        val typedKg = raw.endsWith("kg")
        val t = raw.removeSuffix("lbs").removeSuffix("lb").removeSuffix("kg").trim().replace(',', '.')
        val v = t.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
        val inPounds = typedLb || (pounds && !typedKg)
        val tenths = kotlin.math.floor(v * 10 + 0.5).toInt()
        val grams = if (inPounds) kotlin.math.floor(tenths / 10.0 * GRAMS_PER_POUND + 0.5).toInt() else tenths * WeightTrend.STEP_G
        return grams.takeIf { it in 1..WeightTrend.MAX_G }
    }
}
