package com.pawpixel

import com.pawpixel.core.*
import com.pawpixel.sprite.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val clock = LocalClock.MANILA
    /** Local Manila day 20000 at [minute]. */
    private fun at(minute: Int, day: Long = 20000) = clock.at(day, minute)

    private val pet = Pet("p1", "Mochi", Species.DOG, 0)
    private val feed = CareTask("t1", "p1", TaskKind.FEED, "Feed", slots = listOf(7 * 60, 17 * 60), anchorDay = 0, adaptive = false)
    private val base = AppState(pets = listOf(pet), tasks = listOf(feed))

    @Test fun clockRoundTrips() {
        val t = at(7 * 60 + 30)
        assertEquals(20000, clock.dayIndex(t))
        assertEquals(450, clock.minuteOfDay(t))
    }

    @Test fun overdueAfterMissedSlot() {
        val s = CareEngine.status(feed, emptyList(), at(9 * 60), clock)
        assertEquals(1, s.passed); assertEquals(0, s.done)
        assertEquals(at(7 * 60), s.overdueSinceMs)
        assertEquals(at(17 * 60), s.nextDueMs)
    }

    @Test fun completionCoversSlot() {
        val st = StateOps.complete(base, "t1", at(7 * 60 + 5), clock)
        val s = CareEngine.status(feed, st.completions, at(9 * 60), clock)
        assertNull(s.overdueSinceMs)
        assertEquals(1, s.done)
    }

    @Test fun earlyCompletionCountsAndNextDayResets() {
        val st = StateOps.complete(StateOps.complete(base, "t1", at(6 * 60), clock), "t1", at(16 * 60), clock)
        assertTrue(CareEngine.status(feed, st.completions, at(18 * 60), clock).allDoneThisCycle)
        val tomorrow = CareEngine.status(feed, st.completions, at(8 * 60, 20001), clock)
        assertEquals(0, tomorrow.done); assertNotNull(tomorrow.overdueSinceMs)
    }

    @Test fun weeklyTaskUsesCycle() {
        val groom = CareTask("g", "p1", TaskKind.GROOM, "Groom", listOf(600), everyDays = 7, anchorDay = 20000)
        val st = base.copy(tasks = base.tasks + groom)
        val done = StateOps.complete(st, "g", at(620), clock)
        assertNull(CareEngine.status(groom, done.completions, at(600, 20003), clock).overdueSinceMs)
        assertNotNull(CareEngine.status(groom, done.completions, at(700, 20007), clock).overdueSinceMs)
    }

    @Test fun moodGetsHungryThenSad() {
        assertEquals(Mood.CONTENT, MoodEngine.read(base, "p1", at(7 * 60 + 10), clock).mood)
        assertEquals(Mood.HUNGRY, MoodEngine.read(base, "p1", at(8 * 60 + 30), clock).mood)
        val walk = CareTask("w", "p1", TaskKind.WALK, "Walk", listOf(6 * 60), anchorDay = 0, adaptive = false)
        val meds = CareTask("m", "p1", TaskKind.MEDS, "Meds", listOf(6 * 60), anchorDay = 0, adaptive = false)
        val neglected = base.copy(tasks = base.tasks + walk + meds)
        assertEquals(Mood.SAD, MoodEngine.read(neglected, "p1", at(12 * 60), clock).mood)
    }

    @Test fun moodHappyAfterCareAndSleepyAtNight() {
        val st = StateOps.complete(base, "t1", at(7 * 60), clock)
        assertEquals(Mood.HAPPY, MoodEngine.read(st, "p1", at(7 * 60 + 30), clock).mood)
        val evening = StateOps.complete(st, "t1", at(17 * 60), clock)
        assertEquals(Mood.SLEEPY, MoodEngine.read(evening, "p1", at(23 * 60), clock).mood)
    }

    @Test fun timelineStartsNowAndChanges() {
        val tl = MoodEngine.timeline(base, "p1", at(7 * 60 + 10), clock)
        assertEquals(at(7 * 60 + 10), tl.first().atMs)
        assertTrue(tl.any { it.mood == Mood.HUNGRY })
        assertTrue(tl.zipWithNext().all { (a, b) -> a.atMs < b.atMs && (a.mood != b.mood || a.caption != b.caption) })
    }

    @Test fun adaptiveTimingLearnsLaterBreakfast() {
        val task = feed.copy(adaptive = true)
        var st = base.copy(tasks = listOf(task))
        for (d in 0 until 5) st = StateOps.complete(st, "t1", at(8 * 60, 20000L + d), clock)
        val slots = AdaptiveTiming.effectiveSlots(task, st.completions, at(12 * 60, 20005), clock)
        assertEquals(listOf(8 * 60, 17 * 60), slots)
    }

    @Test fun adaptiveTimingIsCappedAndNeedsSamples() {
        val task = feed.copy(adaptive = true)
        var st = base.copy(tasks = listOf(task))
        for (d in 0 until 2) st = StateOps.complete(st, "t1", at(11 * 60, 20000L + d), clock)
        assertEquals(task.slots, AdaptiveTiming.effectiveSlots(task, st.completions, at(12 * 60, 20003), clock))
        for (d in 2 until 6) st = StateOps.complete(st, "t1", at(11 * 60 + 30, 20000L + d), clock)
        // 7:00 slot can move at most 2h, and 11:30 is nearer 7:00 than 17:00.
        assertEquals(9 * 60, AdaptiveTiming.effectiveSlots(task, st.completions, at(12 * 60, 20006), clock).first())
    }

    @Test fun circularMathWrapsMidnight() {
        assertEquals(20, AdaptiveTiming.circularDistance(1430, 10))
        assertEquals(20, AdaptiveTiming.signedCircularDiff(1430, 10))
        assertEquals(-20, AdaptiveTiming.signedCircularDiff(10, 1430))
    }

    @Test fun remindersSkipDoneSlotsAndCap() {
        val st = StateOps.complete(base, "t1", at(6 * 60 + 50), clock)
        val plan = ReminderPlanner.plan(st, at(6 * 60 + 55), clock)
        assertTrue(plan.none { it.atMs == at(7 * 60) })
        assertEquals(at(17 * 60), plan.first().atMs)
        assertEquals(plan.size, plan.map { it.id }.distinct().size)
        assertTrue(ReminderPlanner.plan(st.copy(settings = Settings(remindersEnabled = false)), at(0), clock).isEmpty())
        val many = (1..40).fold(st) { acc, i -> acc.copy(tasks = acc.tasks + feed.copy(id = "x$i")) }
        assertTrue(ReminderPlanner.plan(many, at(0), clock).size <= ReminderPlanner.MAX_PENDING)
    }

    @Test fun medsGetNudge() {
        val meds = CareTask("m", "p1", TaskKind.MEDS, "Meds", listOf(8 * 60), anchorDay = 0, adaptive = false)
        val plan = ReminderPlanner.plan(base.copy(tasks = listOf(meds)), at(7 * 60), clock, DAY_MS / 2)
        assertEquals(listOf(at(8 * 60), at(8 * 60 + 45)), plan.map { it.atMs })
    }

    @Test fun stateCodecRoundTrips() {
        val st = StateOps.complete(StateOps.addPet(AppState(), pet.copy(name = "Mó \"Chi\"\n", eyes = listOf(0.25 to 0.4, 0.75 to 0.4), ears = "POINTY"), 0, clock), "missing", 0, clock)
        val withDone = StateOps.complete(st, st.tasks.first().id, at(420), clock)
        val decoded = StateCodec.decode(StateCodec.encode(withDone))
        assertEquals(withDone, decoded)
    }

    @Test fun codecDropsOrphansAndToleratesGarbage() {
        val json = """{"pets":[],"tasks":[{"id":"t","petId":"gone","kind":"FEED","slots":[1]}],"completions":[{"taskId":"t","at":1}],"extra":true}"""
        val st = StateCodec.decode(json)
        assertTrue(st.tasks.isEmpty() && st.completions.isEmpty())
    }

    @Test fun removePetCascades() {
        val st = StateOps.complete(base, "t1", at(420), clock)
        val gone = StateOps.removePet(st, "p1")
        assertTrue(gone.pets.isEmpty() && gone.tasks.isEmpty() && gone.completions.isEmpty())
    }

    @Test fun freePetLimit() {
        assertTrue(StateOps.canAddPet(AppState()))
        assertFalse(StateOps.canAddPet(base))
        assertTrue(StateOps.canAddPet(base.copy(settings = Settings(pro = true))))
    }

    @Test fun widgetSnapshotHasActionAndIfDone() {
        val snap = WidgetSnapshot.build(base, at(9 * 60), clock)
        val p = snap["pets"].list.single()
        assertEquals("hungry", p["timeline"].list.first()["mood"].str)
        assertEquals("t1", p["action"]["taskId"].str)
        assertEquals("happy", p["action"]["timelineIfDone"].list.first()["mood"].str)
        assertEquals("sprites/p1/happy.png", p["sprites"]["happy"].str)
        // Round-trips through the JSON text the widgets actually read.
        assertEquals(snap, Json.parse(snap.stringify()))
    }

    @Test fun pendingDoneParsing() {
        assertEquals(listOf("t1" to 5L), WidgetSnapshot.parsePending("""[{"taskId":"t1","at":5},{"bad":1}]"""))
        assertTrue(WidgetSnapshot.parsePending("not json").isEmpty())
    }

    @Test fun locationGridHidesExactPosition() {
        val a = LocationGrid.snap(13.6218, 123.1948) // Naga City
        val b = LocationGrid.snap(13.6220, 123.1950) // a few metres away
        assertEquals(a.id, b.id)
        assertEquals(a.centerLat, b.centerLat)
        assertTrue(LocationGrid.distanceKm(13.6218, 123.1948, a.centerLat, a.centerLng) < 0.75)
        assertEquals("Nearby", LocationGrid.distanceLabel(a, b))
        val far = LocationGrid.snap(13.1391, 123.7438) // Legazpi
        assertEquals("50+ km", LocationGrid.distanceLabel(a, far))
    }

    @Test fun jsonEscapesAndNumbers() {
        val j = Json.parse("""{"a":"xé\n","b":[1,2.5,-3e2,true,null]}""")
        assertEquals("xé\n", j["a"].str)
        assertEquals(listOf(1.0, 2.5, -300.0), j["b"].list.take(3).map { it.double })
        assertEquals(j, Json.parse(j.stringify()))
    }
}

class SpriteTest {
    /** A synthetic "pet": a brown blob with dark eyes on a plain light background. */
    private fun fakePhoto(): PixelImage {
        val img = PixelImage(200, 160).fill(Argb.rgb(0xE8E4DC))
        for (y in 0 until 160) for (x in 0 until 200) {
            val dx = (x - 100) / 60.0; val dy = (y - 85) / 55.0
            if (dx * dx + dy * dy < 1) img[x, y] = Argb.rgb(0x9A6B3F)
        }
        for ((cx, cy) in listOf(80 to 70, 120 to 70)) for (y in cy - 4..cy + 4) for (x in cx - 4..cx + 4) img[x, y] = Argb.rgb(0x151010)
        return img
    }

    @Test fun fallbackSegmenterFindsBlob() {
        val mask = FallbackSegmenter.segment(fakePhoto())
        assertTrue(mask.coverage() in 0.25..0.45, "coverage ${mask.coverage()}")
        assertTrue(mask[100, 70] >= 0.5f) // eye hole is filled
        assertTrue(mask[5, 5] < 0.5f)
    }

    @Test fun pipelineMakesFaceWithEyes() {
        val r = SpritePipeline.generate(fakePhoto(), SpriteSettings(size = 32, colors = 6))
        assertTrue(r.backgroundRemoved)
        assertEquals(SpritePipeline.headPixels(32), r.head.width)
        assertTrue(r.palette.size <= 6)
        assertEquals(0, Argb.alpha(r.head[0, 0]), "rounded head corners are transparent")
        val colours = r.head.pixels.filter { Argb.alpha(it) > 0 }.map { Lab.fromArgb(it).l }
        assertTrue(colours.any { it < 0.3 }, "dark eyes survive")
    }

    @Test fun fullBodyHasLegsBelowTheHead() {
        val r = SpritePipeline.generate(fakePhoto(), SpriteSettings(size = 40))
        for (species in listOf(Species.DOG, Species.CAT)) {
            val still = r.art(species).still
            assertEquals(Chibi.WIDTH to Chibi.HEIGHT, still.width to still.height, "one size for every pet")
            val box = Animator.opaqueBounds(still)!!
            assertTrue(box[3] - box[1] >= Chibi.HEIGHT - 3, "$species stands from ears to paws")
        }
        // Fur colours come from the face, not the dark eyes.
        val fur = r.art(Species.DOG).fur
        assertTrue(Lab.fromArgb(fur.base).l > 0.3)
    }

    @Test fun faceSquareStaysInsideThePhoto() {
        val f = FaceBox(0.95, 0.02, 1.4).fitIn(300, 200)
        val px = f.inPixels(300, 200)
        assertTrue(px[0] >= -1e-9 && px[1] >= -1e-9 && px[0] + px[2] <= 300 + 1e-9 && px[1] + px[3] <= 200 + 1e-9, "inside: ${px.toList()}")
        assertEquals(1.0, f.side)
        val centred = FaceBox(0.5, 0.5, 0.4)
        assertEquals(centred, centred.fitIn(300, 200), "an inside square doesn't move")
    }

    @Test fun guessedFaceIsTopOfPet() {
        val r = SpritePipeline.generate(fakePhoto(), SpriteSettings())
        assertTrue(r.face.cy < 0.6 && r.face.side in 0.2..1.0, "face ${r.face}")
    }

    @Test fun pipelineIsDeterministic() {
        val a = SpritePipeline.generate(fakePhoto(), SpriteSettings())
        val b = SpritePipeline.generate(fakePhoto(), SpriteSettings())
        assertTrue(a.head.pixels.contentEquals(b.head.pixels))
    }

    @Test fun busyPhotoFallsBackToCentreCrop() {
        val noise = PixelImage(120, 120)
        val rnd = kotlin.random.Random(1)
        for (i in noise.pixels.indices) noise.pixels[i] = Argb.rgb(rnd.nextInt(0xFFFFFF))
        val r = SpritePipeline.generate(noise, SpriteSettings(size = 24))
        assertFalse(r.backgroundRemoved)
    }

    @Test fun posesShareCanvasSize() {
        val r = SpritePipeline.generate(fakePhoto(), SpriteSettings(size = 40))
        val sizes = Poses.renderAll(r.art(Species.CAT).still).values.map { it.width to it.height }.distinct()
        assertEquals(1, sizes.size)
    }

    @Test fun pngHasValidStructure() {
        val img = PixelImage(3, 2).fill(Argb.rgb(0x112233))
        val png = Png.encode(img)
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), png.take(4).map { it.toInt() and 0xff })
        val big = Png.encode(PixelImage(300, 300).fill(Argb.rgb(0x445566)))
        assertTrue(big.size < 5000, "flat image compresses: ${big.size}")
    }

    @Test fun oklabRoundTrip() {
        for (c in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF9A6B3F.toInt(), 0xFF3E8ED0.toInt())) {
            assertEquals(c, Lab.fromArgb(c).toArgb())
        }
    }

    @Test fun fontHandlesFilipinoNames() {
        assertEquals('N', PixelFont.normalize('ñ'))
        assertEquals('?', PixelFont.normalize('漢'))
        assertTrue(PixelFont.fit("A VERY LONG PET NAME INDEED", 4, 100).length < 27)
    }

    @Test fun revealCardRenders() {
        val r = SpritePipeline.generate(fakePhoto(), SpriteSettings(size = 32))
        val card = RevealCard.render(r.photoCrop, r.art(Species.DOG).still, "Piña")
        assertTrue(card.width > 600 && card.height > 300)
    }
}

class NewPetTest {
    @Test fun newPetIsNotPunishedForEarlierSlots() {
        val clock = LocalClock.MANILA
        val now = clock.at(20000, 9 * 60)
        val st = StateOps.addPet(AppState(), Pet("p", "Mochi", Species.DOG, now), now, clock)
        assertEquals(Mood.CONTENT, MoodEngine.read(st, "p", now, clock).mood)
        // Evening slots still count, and tomorrow's 7am breakfast is due as normal.
        val feed = st.tasks.first { it.kind == TaskKind.FEED }
        assertEquals(clock.at(20000, 17 * 60 + 30), CareEngine.status(feed, st.completions, now, clock).nextDueMs)
        // Next morning, missed breakfast + walk + water do count.
        assertTrue(MoodEngine.read(st, "p", clock.at(20001, 9 * 60), clock).mood in setOf(Mood.HUNGRY, Mood.SAD))
    }

    @Test fun petAddedAfterTodaysSlotsCanStillLogCare() {
        val clock = LocalClock.MANILA
        val now = clock.at(20000, 21 * 60) // 9 pm: every default slot has passed
        val st = StateOps.addPet(AppState(), Pet("p", "Mochi", Species.CAT, now), now, clock)
        val feed = st.tasks.first { it.kind == TaskKind.FEED }
        val before = CareEngine.status(feed, st.completions, now + 60_000, clock)
        assertFalse(before.allDoneThisCycle, "nothing was done, so don't claim it was")
        assertFalse(before.isOverdue)
        val after = StateOps.complete(st, feed.id, now + 60_000, clock)
        val s = CareEngine.status(feed, after.completions, now + 120_000, clock)
        assertTrue(s.allDoneThisCycle && s.logged == 1, "logging it counts as done today")
        assertTrue(MoodEngine.read(after, "p", now + 120_000, clock).mood in setOf(Mood.CONTENT, Mood.HAPPY))
    }
}

class AnimationTest {
    private fun sprite(): PixelImage {
        val img = PixelImage(200, 160).fill(Argb.rgb(0xE8E4DC))
        for (y in 0 until 160) for (x in 0 until 200) {
            val dx = (x - 100) / 60.0; val dy = (y - 85) / 55.0
            if (dx * dx + dy * dy < 1) img[x, y] = Argb.rgb(0x9A6B3F)
        }
        for ((cx, cy) in listOf(80 to 70, 120 to 70)) for (y in cy - 5..cy + 5) for (x in cx - 5..cx + 5) img[x, y] = Argb.rgb(0x151010)
        return SpritePipeline.generate(img, SpriteSettings(size = 40)).head
    }
    private fun art() = PetArt(sprite(), Species.DOG)

    @Test fun allFramesShareOneCanvas() {
        val set = Chibi.build(art(), emptyList())
        assertEquals(1, Frame.entries.map { set[it].width to set[it].height }.distinct().size)
    }

    @Test fun drawnEyesBlinkAndSleep() {
        val set = Chibi.build(art())
        val changed = set[Frame.BLINK].pixels.indices.count { set[Frame.BLINK].pixels[it] != set[Frame.BASE].pixels[it] }
        assertTrue(changed in 2..20, "blink only touches the eyes ($changed px)")
        assertFalse(set[Frame.SLEEP].pixels.contentEquals(set[Frame.BASE].pixels), "sleeping pose differs")
    }

    private fun swatch(f: (Double, Double) -> Int) = PixelImage(26, 26, IntArray(26 * 26) { f((it % 26 + 0.5) / 26, (it / 26 + 0.5) / 26) })

    @Test fun lookReadsTonesAndMarkings() {
        val black = Argb.rgb(0x222226); val white = Argb.rgb(0xF4F1EA)
        val tux = PetLook.from(swatch { x, y -> if (y > 0.55 && kotlin.math.abs(x - 0.5) < 0.25) white else black })
        assertEquals(2, tux.tones.size)
        assertTrue(tux.light != null, "white muzzle is a light tone")
        assertEquals(tux.light, tux.toneAt(0.5, 0.8), "muzzle stays white")
        assertEquals(0, tux.toneAt(0.1, 0.1), "forehead is the main colour")
        // Lighting differences on one colour are not markings.
        val gold = PetLook.from(swatch { x, _ -> Lab(0.72 - x * 0.08, 0.03, 0.12).toArgb() })
        assertEquals(1, gold.tones.size)
        // A few dark eye pixels don't become a patch.
        val eyed = PetLook.from(swatch { x, y -> if (kotlin.math.abs(y - 0.45) < 0.04 && kotlin.math.abs(kotlin.math.abs(x - 0.5) - 0.2) < 0.04) black else Argb.rgb(0xC08050) })
        assertEquals(1, eyed.tones.size)
    }

    @Test fun lookCodeRoundTripsAndRejectsJunk() {
        val look = PetArt(sprite(), Species.CAT).look
        val code = look.encode()
        assertTrue(code.length <= 100 && Regex("^[A-Za-z0-9;,.]*$").matches(code), code)
        val back = PetLook.decode(code)!!
        assertEquals(code, back.encode())
        assertTrue(PetArt(back, Species.CAT).still.pixels.contentEquals(PetArt(look, Species.CAT).still.pixels), "same pixel pet")
        for (bad in listOf("", "2;ffffff;" + "0".repeat(64), "1;;" + "0".repeat(64), "1;ffffff;" + "1".repeat(64), "1;zzzzzz;" + "0".repeat(64), "1;ffffff;00"))
            assertEquals(null, PetLook.decode(bad), bad)
    }

    @Test fun earsDefaultBySpeciesAndCanChange() {
        val s = sprite()
        assertEquals(Ears.FLOPPY, PetArt(s, Species.DOG).ears)
        assertEquals(Ears.POINTY, PetArt(s, Species.CAT).ears)
        assertFalse(PetArt(s, Species.DOG, Ears.POINTY).still.pixels.contentEquals(PetArt(s, Species.DOG).still.pixels))
        assertEquals(Ears.FLOPPY, Ears.of("FLOPPY")); assertEquals(null, Ears.of(""))
    }

    @Test fun breatheAndWalkMovePixels() {
        val set = Chibi.build(art(), emptyList())
        for (f in listOf(Frame.BREATHE, Frame.WALK_2, Frame.LOOK_LEFT, Frame.SQUASH, Frame.STRETCH, Frame.EAT_DOWN, Frame.SHAKE_1)) {
            assertFalse(set[f].pixels.contentEquals(set[Frame.BASE].pixels), "$f differs from base")
        }
    }

    @Test fun brainIsDeterministicAndStaysOnStage() {
        val set = Chibi.build(art(), emptyList())
        val layout = StageLayout(set)
        fun run(): List<PetPose> { val b = layout.brain(42); return (0 until 3000).map { b.pose(it * 33L, Mood.RESTLESS) } }
        val a = run(); val b = run()
        assertEquals(a, b)
        assertTrue(a.all { it.x >= 0 && it.x <= layout.stageWidth - set.width })
        assertTrue(a.map { it.behavior }.toSet().contains(Behavior.WALK))
    }

    @Test fun moodsChangeBehaviour() {
        val layout = StageLayout(Chibi.build(art(), emptyList()))
        fun behaviours(m: Mood) = layout.brain(1).let { b -> (0 until 2000).map { b.pose(it * 50L, m).behavior }.toSet() }
        assertEquals(setOf(Behavior.SLEEP), behaviours(Mood.SLEEPY))
        assertTrue(Behavior.BEG in behaviours(Mood.HUNGRY))
        assertTrue(Behavior.HOP !in behaviours(Mood.SAD))
    }

    @Test fun reactionsPlayThenReturnToNormal() {
        val layout = StageLayout(Chibi.build(art(), emptyList()))
        val b = layout.brain(9)
        b.pose(0, Mood.CONTENT)
        b.react(PetEvent.Cared(TaskKind.FEED), 1000)
        val eating = b.pose(1100, Mood.CONTENT)
        assertEquals(Behavior.EAT, eating.behavior)
        assertTrue(eating.effects.any { it.kind == EffectKind.BOWL })
        assertTrue(b.pose(5000, Mood.CONTENT).behavior != Behavior.EAT)
        b.react(PetEvent.Petted, 6000)
        assertTrue(b.pose(6100, Mood.CONTENT).effects.any { it.kind == EffectKind.HEART })
    }

    @Test fun reactionBeforeFirstFrameStillPlays() {
        val b = StageLayout(Chibi.build(art(), emptyList())).brain(4)
        b.react(PetEvent.Petted, 0)
        val first = b.pose(100, Mood.CONTENT)
        assertEquals(Behavior.PETTED, first.behavior)
        assertTrue(first.effects.any { it.kind == EffectKind.HEART })
    }

    @Test fun gifIsValidAndLoops() {
        val gif = AnimatedExport.clip(art(), emptyList(), "Mochi", durationMs = 800)
        assertEquals("GIF89a", gif.copyOfRange(0, 6).decodeToString())
        assertEquals(0x3B, gif.last().toInt())
        assertTrue(gif.decodeToString().contains("NETSCAPE2.0"))
    }

    @Test fun lzwRoundTripsThroughAReferenceDecoder() {
        val data = ByteArray(5000) { ((it / 7) % 40).toByte() }
        assertEquals(data.toList(), lzwDecode(Gif.lzw(data, 8), 8).toList())
        val noisy = ByteArray(20000) { ((it * 2654435761L) ushr 13).toInt().and(0xff).toByte() }
        assertEquals(noisy.toList(), lzwDecode(Gif.lzw(noisy, 8), 8).toList())
    }

    /** Straightforward GIF LZW decoder, used only to check the encoder. */
    private fun lzwDecode(bytes: ByteArray, minCode: Int): ByteArray {
        val clear = 1 shl minCode; val eoi = clear + 1
        var size = minCode + 1
        val dict = ArrayList<List<Int>>()
        fun reset() { dict.clear(); for (i in 0 until clear) dict += listOf(i); dict += emptyList<Int>(); dict += emptyList<Int>(); size = minCode + 1 }
        reset()
        val out = ArrayList<Int>()
        var bitPos = 0
        fun read(): Int {
            var v = 0
            for (i in 0 until size) {
                val byte = bytes[(bitPos + i) / 8].toInt() and 0xff
                v = v or (((byte shr ((bitPos + i) % 8)) and 1) shl i)
            }
            bitPos += size; return v
        }
        var prev: List<Int>? = null
        while (true) {
            val code = read()
            if (code == clear) { reset(); prev = null; continue }
            if (code == eoi) break
            val entry = if (code < dict.size) dict[code] else prev!! + prev[0]
            out += entry
            if (prev != null && dict.size < 4096) dict += prev + entry[0]
            prev = entry
            if (dict.size == (1 shl size) && size < 12) size++
        }
        return ByteArray(out.size) { out[it].toByte() }
    }
}
