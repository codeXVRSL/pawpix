package com.pawpixel

import com.pawpixel.core.Species
import com.pawpixel.sprite.Coat
import com.pawpixel.sprite.CoatDetector
import com.pawpixel.sprite.Mask
import com.pawpixel.sprite.Pattern
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.PetLook
import com.pawpixel.sprite.PetStyle
import com.pawpixel.sprite.PixelImage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Body markings from the photo: tabby stripes, white socks and patches, in the look code and on the chibi. */
class CoatTest {
    private val brown = 0xFFA0784E.toInt()
    private val cream = 0xFFF2E6D0.toInt()
    private val look = PetLook(listOf(brown, cream), IntArray(PetLook.GRID * PetLook.GRID))

    @Test fun aLookWithoutACoatKeepsTodaysCode() {
        assertTrue(look.encode().startsWith("3;"), "pets made before coats keep the same code, so nothing redraws")
        assertTrue(look.withStyle(PetStyle.DEFAULT.copy(pattern = Pattern.SPOTS)).encode().startsWith("4;"))
    }

    @Test fun theCoatTravelsInTheLookCode() {
        val tabby = look.withCoat(Coat(stripes = true, socks = true))
        val code = tabby.encode()
        assertTrue(code.startsWith("5;") && code.endsWith(";TS"), code)
        assertEquals(Coat(stripes = true, socks = true), PetLook.decode(code)?.coat)

        val styled = look.withStyle(PetStyle.DEFAULT.copy(pattern = Pattern.SOLID)).withCoat(Coat(patchTone = 1))
        val back = assertNotNull(PetLook.decode(styled.encode()))
        assertEquals(Coat(patchTone = 1), back.coat)
        assertEquals(Pattern.SOLID, back.style.pattern)
        assertEquals(styled.encode(), back.encode())
    }

    @Test fun aBadOrUnknownCoatReadsAsNone() {
        val base = look.encode().removePrefix("3;")
        assertEquals(Coat.NONE, PetLook.decode("5;$base;T-x")?.coat)
        assertEquals(Coat(stripes = true), PetLook.decode("5;$base;TQZ")?.coat, "unknown letters from a newer build are ignored")
        assertNull(PetLook.decode("5;$base;P9")?.coat?.patchTone, "a patch tone no look can have is dropped")
        assertNull(PetLook.decode("5;$base;SP3")?.coat?.patchTone, "a patch tone this two-tone look doesn't have is dropped")
        assertNull(PetLook.decode("5;$base"), "format 5 needs its coat part")
    }

    @Test fun stripesAreDrawnOnACatButNotOnADog() {
        val tabby = look.withCoat(Coat(stripes = true))
        assertFalse(PetArt(tabby, Species.CAT).still.pixels.contentEquals(PetArt(look, Species.CAT).still.pixels), "a tabby cat has lines")
        assertContentEquals(PetArt(look, Species.DOG).still.pixels, PetArt(tabby, Species.DOG).still.pixels, "a dog's fur reads like lines too often")
    }

    @Test fun socksAndPatchesShowOnTheBody() {
        // A one-tone pet: one with a light tone already has light paws.
        val solid = PetLook(listOf(brown), IntArray(PetLook.GRID * PetLook.GRID))
        assertFalse(PetArt(solid, Species.DOG).still.pixels.contentEquals(PetArt(solid.withCoat(Coat(socks = true)), Species.DOG).still.pixels))
        val plain = PetArt(look, Species.DOG).still.pixels
        assertFalse(plain.contentEquals(PetArt(look.withCoat(Coat(patchTone = 1)), Species.DOG).still.pixels))
    }

    @Test fun aStudioPatternReplacesThePhotosCoat() {
        val solid = PetStyle.DEFAULT.copy(pattern = Pattern.SOLID)
        assertContentEquals(
            PetArt(look, Species.CAT, style = solid).still.pixels,
            PetArt(look.withCoat(Coat(stripes = true, socks = true)), Species.CAT, style = solid).still.pixels,
        )
    }

    // ---- Detection on drawn photos (384 px, the pipeline's working size) ----

    private val size = 384
    private val face = doubleArrayOf(96.0, 20.0, 192.0, 192.0)

    private fun photo(paint: (Int, Int) -> Int) = PixelImage(size, size).also { img -> for (y in 0 until size) for (x in 0 until size) img[x, y] = paint(x, y) }

    @Test fun aStripedForeheadIsATabby() {
        val dark = 0xFF3A2A1A.toInt()
        val img = photo { x, y -> if (y in 40..120 && x % 9 < 3) dark else brown }
        val coat = CoatDetector.detect(img, Mask.full(size, size), face, look, removed = true)
        assertTrue(coat.stripes, "measured ${CoatDetector.measure(img, Mask.full(size, size), face, true)}")
    }

    @Test fun plainFurIsNotATabby() {
        val coat = CoatDetector.detect(photo { _, _ -> brown }, Mask.full(size, size), face, look, removed = true)
        assertEquals(Coat.NONE, coat)
    }

    @Test fun whitePawsBelowADarkerBodyAreSocks() {
        val img = photo { _, y -> if (y >= size - size / 10) 0xFFFAFAF5.toInt() else brown }
        assertTrue(CoatDetector.detect(img, Mask.full(size, size), face, look, removed = true).socks)
        assertFalse(CoatDetector.detect(img, Mask.full(size, size), face, look, removed = false).socks, "without a cut-out the paws can't be trusted")
    }

    @Test fun aSecondColourOnTheBodyIsAPatch() {
        // A cream patch on the side of a brown body (the look has cream as its second tone).
        val img = photo { x, y -> if (y in 230..330 && x < 150) cream else brown }
        assertEquals(1, CoatDetector.detect(img, Mask.full(size, size), face, look, removed = true).patchTone)
    }

    @Test fun shadeOnAWhiteDogIsNotAPatch() {
        val white = 0xFFF4F4F2.toInt(); val shade = 0xFFC9C9C6.toInt()
        val img = photo { x, y -> if (y in 230..330 && x < 150) shade else white }
        val whiteLook = PetLook(listOf(white, shade), IntArray(PetLook.GRID * PetLook.GRID))
        assertNull(CoatDetector.detect(img, Mask.full(size, size), face, whiteLook, removed = true).patchTone)
    }
}
