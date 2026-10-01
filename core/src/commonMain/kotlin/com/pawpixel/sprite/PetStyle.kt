package com.pawpixel.sprite

import kotlin.random.Random

/**
 * Everything an owner can change about how their pixel pet is drawn, on top of what the photo
 * gave it (the fur colours and markings): the Pet Studio. Every choice has an "as the photo says"
 * or "natural" default, so a pet made from a photo looks exactly as before until its owner plays.
 *
 * Travels inside the look code (see [PetLook.encode]) so a household and the pet map draw the
 * same pet: 16 single-character trait indices, then up to four colours.
 */
data class PetStyle(
    val head: HeadShape = HeadShape.ROUND,
    val eyes: EyeShape = EyeShape.ROUND,
    val eyeColor: EyeColor = EyeColor.AUTO,
    val shine: EyeShine = EyeShine.SINGLE,
    val brows: Brows = Brows.NONE,
    val nose: NoseShape = NoseShape.AUTO,
    val noseColor: NoseColor = NoseColor.AUTO,
    val mouth: Mouth = Mouth.SMILE,
    val ears: EarStyle = EarStyle.AUTO,
    val body: BodyShape = BodyShape.NORMAL,
    val tail: TailStyle = TailStyle.AUTO,
    val pattern: Pattern = Pattern.AUTO,
    val chest: Chest = Chest.AUTO,
    val blush: Blush = Blush.SOFT,
    val whiskers: Whiskers = Whiskers.NONE,
    val collar: Collar = Collar.NONE,
    /** Fur colours (ARGB), or null to keep the photo's. */
    val furBase: Int? = null,
    val furLight: Int? = null,
    /** The darker tone patterns use (mask, spots, stripes); null = a shade of the base. */
    val furDark: Int? = null,
    val collarColor: Int? = null,
) {
    val isDefault: Boolean get() = this == DEFAULT

    /** "4302100003000010.n,n,n,e8374e": trait indices in base 36, then colours (n = none). */
    fun encode(): String {
        val traits = listOf(
            head.ordinal, eyes.ordinal, eyeColor.ordinal, shine.ordinal, brows.ordinal, nose.ordinal, noseColor.ordinal,
            mouth.ordinal, ears.ordinal, body.ordinal, tail.ordinal, pattern.ordinal, chest.ordinal, blush.ordinal,
            whiskers.ordinal, collar.ordinal,
        ).joinToString("") { it.toString(36) }
        val colours = listOf(furBase, furLight, furDark, collarColor).joinToString(",") { hex(it) }
        return "$traits.$colours"
    }

    companion object {
        val DEFAULT = PetStyle()

        /** The 16 fur swatches of the Studio: natural coats first, then the fun ones. */
        val FUR_SWATCHES: List<Int> = listOf(
            0xFF2B2430, 0xFF5A4636, 0xFF8B5A2B, 0xFFB07A4A, 0xFFD9A066, 0xFFE8C9A0, 0xFFF4EBDD, 0xFFFFFFFF,
            0xFF9A9AA6, 0xFFC9C3CF, 0xFFD96A3C, 0xFFF2B84B, 0xFFF59AB8, 0xFF8C7BE0, 0xFF6EC1E4, 0xFF7CCB8A,
        ).map { it.toInt() }

        /** Collar colours. */
        val COLLAR_SWATCHES: List<Int> = listOf(
            0xFFE8374E, 0xFF3E7BD6, 0xFFF6C744, 0xFF5DAE5B, 0xFFF59AB8, 0xFF8C7BE0, 0xFF2B2135, 0xFFFFFFFF,
        ).map { it.toInt() }

        private fun hex(c: Int?): String = c?.let { (it and 0xFFFFFF).toString(16).padStart(6, '0') } ?: "n"

        private fun colour(s: String): Int? = when {
            s == "n" -> null
            s.length == 6 && s.all { it in '0'..'9' || it in 'a'..'f' } -> s.toInt(16) or (0xFF shl 24)
            else -> null
        }

        private inline fun <reified E : Enum<E>> pick(traits: String, i: Int): E? {
            val c = traits.getOrNull(i) ?: return null
            val n = c.digitToIntOrNull(36) ?: return null
            return enumValues<E>().getOrNull(n)
        }

        /** Reads [encode]'s form; null when it isn't one (an older client, or junk). */
        fun decode(code: String): PetStyle? {
            val parts = code.split('.')
            if (parts.size != 2) return null
            val t = parts[0]
            if (t.length != 16) return null
            val colours = parts[1].split(',')
            if (colours.size != 4) return null
            for (c in colours) if (c != "n" && colour(c) == null) return null
            return PetStyle(
                head = pick<HeadShape>(t, 0) ?: return null, eyes = pick<EyeShape>(t, 1) ?: return null,
                eyeColor = pick<EyeColor>(t, 2) ?: return null, shine = pick<EyeShine>(t, 3) ?: return null,
                brows = pick<Brows>(t, 4) ?: return null, nose = pick<NoseShape>(t, 5) ?: return null,
                noseColor = pick<NoseColor>(t, 6) ?: return null, mouth = pick<Mouth>(t, 7) ?: return null,
                ears = pick<EarStyle>(t, 8) ?: return null, body = pick<BodyShape>(t, 9) ?: return null,
                tail = pick<TailStyle>(t, 10) ?: return null, pattern = pick<Pattern>(t, 11) ?: return null,
                chest = pick<Chest>(t, 12) ?: return null, blush = pick<Blush>(t, 13) ?: return null,
                whiskers = pick<Whiskers>(t, 14) ?: return null, collar = pick<Collar>(t, 15) ?: return null,
                furBase = colour(colours[0]), furLight = colour(colours[1]), furDark = colour(colours[2]), collarColor = colour(colours[3]),
            )
        }

        /** A surprise: every trait rolled, colours kept from the photo (the pet stays theirs). */
        fun random(seed: Int): PetStyle {
            val r = Random(seed)
            fun <E : Enum<E>> roll(all: Array<E>): E = all[r.nextInt(all.size)]
            return PetStyle(
                head = roll(HeadShape.entries.toTypedArray()), eyes = roll(EyeShape.entries.toTypedArray()),
                eyeColor = roll(EyeColor.entries.toTypedArray()), shine = roll(EyeShine.entries.toTypedArray()),
                brows = roll(Brows.entries.toTypedArray()), nose = roll(NoseShape.entries.toTypedArray()),
                noseColor = roll(NoseColor.entries.toTypedArray()), mouth = roll(Mouth.entries.toTypedArray()),
                ears = roll(EarStyle.entries.toTypedArray()), body = roll(BodyShape.entries.toTypedArray()),
                tail = roll(TailStyle.entries.toTypedArray()), pattern = roll(Pattern.entries.toTypedArray()),
                chest = roll(Chest.entries.toTypedArray()), blush = roll(Blush.entries.toTypedArray()),
                whiskers = roll(Whiskers.entries.toTypedArray()), collar = roll(Collar.entries.toTypedArray()),
                collarColor = COLLAR_SWATCHES[r.nextInt(COLLAR_SWATCHES.size)],
            )
        }
    }
}

// Each option's label is English at the call site, translated with tr() when shown (TranslationTest
// checks the Studio's tr(label) strings).

enum class HeadShape(val label: String) { ROUND("Round"), WIDE("Wide"), TALL("Tall"), CHUBBY("Chubby cheeks") }

enum class EyeShape(val label: String) { ROUND("Round"), BIG("Big"), ALMOND("Almond"), SLEEPY("Sleepy"), SPARKLE("Sparkly"), WINK("Wink") }

enum class EyeColor(val label: String, val argb: Int) {
    AUTO("From the photo", 0), DARK("Dark", 0xFF2A1E2E.toInt()), AMBER("Amber", 0xFFC98A3C.toInt()),
    GREEN("Green", 0xFF6DB85A.toInt()), BLUE("Blue", 0xFF4C8FE0.toInt()), HAZEL("Hazel", 0xFF8F6A3A.toInt()),
    /** One green, one blue. */
    ODD("Odd eyes", 0xFF6DB85A.toInt()),
}

enum class EyeShine(val label: String) { SINGLE("One shine"), DOUBLE("Two shines"), STAR("Starry") }

enum class Brows(val label: String) { NONE("None"), SOFT("Soft"), WORRIED("Worried"), FIERCE("Fierce") }

enum class NoseShape(val label: String) { AUTO("Natural"), BUTTON("Button"), TRIANGLE("Triangle"), HEART("Heart"), WIDE("Wide") }

enum class NoseColor(val label: String, val argb: Int) {
    AUTO("Natural", 0), BLACK("Black", 0xFF33242A.toInt()), PINK("Pink", 0xFFE88AA0.toInt()), BROWN("Brown", 0xFF7A4A32.toInt()),
}

enum class Mouth(val label: String) { SMILE("Smile"), OPEN("Open smile"), CAT("Cat mouth"), TONGUE("Tongue out"), CALM("Calm") }

enum class EarStyle(val label: String) {
    AUTO("Natural"), POINTY("Pointy"), FLOPPY("Floppy"), ROUND("Round"), FOLDED("Folded"), BIG("Big pointy"), TUFTED("Tufted"),
}

enum class BodyShape(val label: String) { NORMAL("Normal"), CHUBBY("Chubby"), SLIM("Slim"), FLUFFY("Fluffy") }

enum class TailStyle(val label: String) { AUTO("Natural"), CURLY("Curly"), STRAIGHT("Straight"), FLUFFY("Fluffy"), STUB("Stub"), LONG("Long") }

enum class Pattern(val label: String) {
    AUTO("From the photo"), SOLID("Solid"), TUXEDO("Tuxedo"), MASK("Mask"), SOCKS("Socks"), SPOTS("Spots"), TABBY("Tabby"), PATCH("Eye patch"),
}

enum class Chest(val label: String) { AUTO("Natural"), LIGHT("Light chest"), HEART("Heart mark"), PLAIN("Plain") }

enum class Blush(val label: String) { NONE("None"), SOFT("Soft"), ROSY("Rosy") }

enum class Whiskers(val label: String) { NONE("None"), SHORT("Short"), LONG("Long") }

enum class Collar(val label: String) { NONE("None"), PLAIN("Collar"), BELL("Collar with bell"), BOW("Collar with bow") }
