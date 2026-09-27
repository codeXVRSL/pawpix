package com.pawpixel.tools

import com.pawpixel.core.Mood
import com.pawpixel.core.Species
import com.pawpixel.core.SpriteSettings
import com.pawpixel.sprite.AnimatedExport
import com.pawpixel.sprite.Chibi
import com.pawpixel.sprite.Mask
import com.pawpixel.sprite.PixelImage
import com.pawpixel.sprite.Png
import com.pawpixel.sprite.Poses
import com.pawpixel.sprite.RevealCard
import com.pawpixel.sprite.SpritePipeline
import java.io.File
import javax.imageio.ImageIO

/**
 * Phase 0 likeness test on a computer, before touching a phone:
 *
 *   ./gradlew :core:spriteLab --args="photos/ out/"
 *
 * For every photo in the folder it writes the sprite, all mood poses and the reveal card.
 * Show owners the results and ask "is that your pet?". Build further if ~4 of 5 say yes.
 *
 * Optional: put a cut-out mask next to a photo as `<name>.mask.png` (white = pet, from any
 * background remover) to preview what the phone's native segmentation will produce.
 * Flags: --species=dog|cat --size=48 --colors=12
 */
fun main(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("--") }
    if (positional.size < 2) {
        println("usage: spriteLab <photo file or folder> <output folder> [--species=dog|cat] [--size=48] [--colors=12]")
        return
    }
    val flags = args.filter { it.startsWith("--") }.associate {
        val kv = it.removePrefix("--").split("=", limit = 2)
        kv[0] to kv.getOrElse(1) { "true" }
    }
    val species = if (flags["species"] == "cat") Species.CAT else Species.DOG
    val settings = SpriteSettings(
        size = flags["size"]?.toInt() ?: 48,
        colors = flags["colors"]?.toInt() ?: 12,
        outline = !flags.containsKey("no-outline"),
    )
    val input = File(positional[0])
    val outDir = File(positional[1]).apply { mkdirs() }
    val photos = if (input.isDirectory) {
        input.listFiles()!!.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") && !it.name.contains(".mask.") }.sorted()
    } else listOf(input)

    for (file in photos) {
        val t0 = System.currentTimeMillis()
        val photo = read(file) ?: run { println("skip ${file.name}: unreadable"); continue }
        val maskFile = File(file.parentFile, file.nameWithoutExtension + ".mask.png")
        val mask = if (maskFile.exists()) read(maskFile)?.let { m ->
            Mask(m.width, m.height, FloatArray(m.pixels.size) { i -> ((m.pixels[i] shr 8) and 0xff) / 255f * ((m.pixels[i] ushr 24) / 255f) })
        } else null

        val result = SpritePipeline.generate(photo, settings, mask)
        val art = result.art(species)
        val base = file.nameWithoutExtension
        File(outDir, "$base.sprite.png").writeBytes(Png.encode(art.still.scaled(8)))
        File(outDir, "$base.gif").writeBytes(AnimatedExport.clip(art, emptyList(), base))

        val poses = Mood.entries.map { Poses.render(if (it == Mood.SLEEPY) Chibi.sleeping(art, emptyList()) else art.still, it) }
        val sheet = PixelImage(poses.sumOf { it.width + 2 }, poses.maxOf { it.height }).fill(0xFFFFF4E0.toInt())
        var x = 0
        for (p in poses) { sheet.draw(p, x, 0); x += p.width + 2 }
        File(outDir, "$base.poses.png").writeBytes(Png.encode(sheet.scaled(6)))

        val card = RevealCard.render(result.photoCrop, art.still, base.replace('_', ' ').replace('-', ' '))
        File(outDir, "$base.reveal.png").writeBytes(Png.encode(card))
        println("${file.name}: ${result.head.width}px face, ${result.palette.size} colours, background " +
            (if (result.backgroundRemoved) "removed" + (if (mask != null) " (mask file)" else " (fallback)") else "kept (centre crop)") +
            ", ${System.currentTimeMillis() - t0} ms")
    }
}

private fun read(file: File): PixelImage? {
    val img = ImageIO.read(file) ?: return null
    val argb = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    return PixelImage(img.width, img.height, argb)
}
