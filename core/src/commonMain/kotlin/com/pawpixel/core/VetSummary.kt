package com.pawpixel.core

import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName

/**
 * One page to hand the vet: who the pet is, the weight and how it moved, what was given when
 * and what's due, the daily routine, this week's walks. Plain text, so it goes by Messenger,
 * email or a screen held up at the counter. Everything comes from the phone's own records.
 */
object VetSummary {
    fun text(state: AppState, pet: Pet, nowMs: Long, clock: LocalClock, microchip: String? = null): String {
        val today = clock.dayIndex(nowMs)
        val sb = StringBuilder()
        val kind = when (pet.species) { Species.DOG -> tr("Dog"); Species.CAT -> tr("Cat"); else -> tr("Pet") }
        sb.appendLine(tr("{0} · {1}", pet.name, kind))
        pet.birthDay?.let { sb.appendLine(tr("Born {0} ({1})", LocalClock.shortDate(it), HealthPlan.ageLabel(it, today))) }
        microchip?.takeIf { it.isNotBlank() }?.let { sb.appendLine(tr("Microchip {0}", it)) }
        sb.appendLine(tr("Summary from PawPixel, {0}", LocalClock.shortDate(today)))

        val weights = state.weightsFor(pet.id)
        if (weights.isNotEmpty()) {
            sb.appendLine(); sb.appendLine(tr("WEIGHT"))
            val last = weights.last()
            val prev = weights.dropLast(1).lastOrNull()
            val change = prev?.let { p -> val d = last.grams - p.grams; if (d == 0) tr("no change since {0}", LocalClock.shortDate(p.day)) else tr("{0}{1} since {2}", if (d > 0) "+" else "−", Units.weight(kotlin.math.abs(d)), LocalClock.shortDate(p.day)) }
            sb.appendLine(if (change == null) tr("{0} on {1}", Units.weight(last.grams), LocalClock.shortDate(last.day)) else tr("{0} on {1} ({2})", Units.weight(last.grams), LocalClock.shortDate(last.day), change))
            weights.takeLast(6).dropLast(1).reversed().forEach { sb.appendLine("  " + tr("{0} on {1}", Units.weight(it.grams), LocalClock.shortDate(it.day))) }
        }

        val health = CareStats.healthDue(state, pet.id, nowMs, clock)
        if (health.isNotEmpty()) {
            sb.appendLine(); sb.appendLine(tr("VACCINES, DEWORMING, PARASITE CARE"))
            for (h in health) {
                val name = trName(h.task.title)
                val last = h.lastDoneMs?.let { tr("last {0}", LocalClock.shortDate(clock.dayIndex(it))) } ?: tr("not recorded yet")
                val due = h.dueMs?.let { if (it <= nowMs) tr("DUE") else tr("next {0}", LocalClock.shortDate(clock.dayIndex(it))) } ?: ""
                val dose = if (h.dose != null && h.doses != null) " " + tr("(dose {0} of {1})", h.dose, h.doses) else ""
                sb.appendLine("• $name: $last" + (if (due.isNotEmpty()) " · $due" else "") + dose)
            }
        }

        val daily = state.tasksFor(pet.id).filter { !it.kind.health }
        if (daily.isNotEmpty()) {
            sb.appendLine(); sb.appendLine(tr("DAILY ROUTINE"))
            for (t in daily) {
                val times = t.slots.joinToString(", ") { hm(it) }
                sb.appendLine("• " + trName(t.title) + ": " + (if (t.everyDays > 1) tr("every {0} days", t.everyDays) else times))
            }
        }

        val week = CareStats.walkWeek(state, pet.id, nowMs, clock)
        if (week.walks > 0) {
            sb.appendLine(); sb.appendLine(tr("WALKS THIS WEEK"))
            sb.appendLine(if (week.km != null) tr("{0} walks, {1} min, about {2} km", week.walks, week.minutes, ((week.km * 10).toInt() / 10.0).toString()) else tr("{0} walks, {1} min", week.walks, week.minutes))
        }
        sb.appendLine(); sb.appendLine(tr("Care logged on {0} different days.", pet.careDays.size))
        return sb.toString().trimEnd()
    }

    /** "7:00 AM", "5:30 PM". */
    private fun hm(minute: Int): String {
        val h = minute / 60; val m = minute % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:${m.toString().padStart(2, '0')} " + (if (h < 12) "AM" else "PM")
    }
}
