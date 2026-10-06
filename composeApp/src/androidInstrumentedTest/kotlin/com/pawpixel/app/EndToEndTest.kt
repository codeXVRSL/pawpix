package com.pawpixel.app

import android.Manifest
import android.graphics.Rect
import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.isInternal
import com.pawpixel.core.Backup
import com.pawpixel.core.ReminderPlanner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.pawpixel.app.widget.PetWidgetReceiver
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.not
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * The whole app, the way an owner uses it, on a real Android system:
 * photo → pixel pet → name and save → care tasks → Done → add a task → reminder notification and
 * its Done button → home-screen widget → share GIF and before/after card.
 *
 * Only the system photo picker is stubbed (it returns a bundled cat photo). Everything else is
 * real: ML Kit, the sprite engine, storage, alarms, notifications, Glance widget, share intents.
 * Screenshots and a step log go to files/e2e (pulled by scripts/android-e2e.sh).
 *
 * UiAutomator rather than the Compose test rule: the living pet animates forever, which would
 * keep a Compose rule from ever going idle.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val out = File(ctx.filesDir, "e2e")
    private val log = StringBuilder()
    private val repo get() = PawPixelApplication.repo(ctx)
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        out.deleteRecursively(); out.mkdirs()
        Configurator.getInstance().waitForIdleTimeout = 1_000
        if (Build.VERSION.SDK_INT >= 33) {
            instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        runBlocking { repo.deleteAllData() }
        val photo = File(ctx.cacheDir, "e2e-pet.jpg")
        instr.context.assets.open("pet.jpg").use { input -> photo.outputStream().use { input.copyTo(it) } }
        Intents.init()
        // Anything that leaves the app (photo picker, share sheet, browser) is answered by the stub.
        intending(not(isInternal())).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(photo))),
        )
    }

    @After
    fun tearDown() {
        Intents.release()
        scenario?.close()
        File(out, "steps.txt").writeText(log.toString())
    }

    @Test
    fun ownerJourney() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // Nothing else matters if these fail: stop early with a clear message.
        fun petId() = repo.state.value.pets.singleOrNull()?.id

        step("first open goes straight to making a pet") {
            find(By.text("Make your pixel pet"))
            shot("01-first-open")
        }

        step("photo becomes a pixel pet") {
            retrying { find(By.text("Choose a photo")).click() }
            find(By.text("Is that your pet? Tap to give pets."), 90_000)
            Thread.sleep(2_000) // let it walk around a bit for the screenshot
            shot("02-your-pixel-pet")
        }

        step("memory just after turning the photo into a pet") {
            val mem = shell("dumpsys meminfo ${ctx.packageName}")
            File(out, "meminfo-sprite.txt").writeText(mem)
            note("memory: " + memorySummary(mem))
        }

        step("cat body, floppy ears, then back to pointy") {
            retrying { scrollTo(By.text("Cat body")).click() }
            retrying { scrollTo(By.text("Floppy ears")).click() }
            Thread.sleep(800)
            shot("03-floppy-ears")
            retrying { find(By.text("Pointy ears")).click() }
        }

        step("face square is shown and can be resized") {
            retrying { scrollTo(By.text("Bigger")).click() }
            Thread.sleep(1_500)
            retrying { scrollTo(By.text("Smaller")).click() }
            Thread.sleep(1_500)
            shot("04-face-framing")
        }

        step("name and save") {
            val field = By.clazz("android.widget.EditText")
            retrying { scrollTo(field).click() }
            Thread.sleep(500)
            retrying { find(field).text = "Chelsea" }
            waitFor("name typed") { device.findObject(By.text("Chelsea")) != null }
            Thread.sleep(300)
            device.pressBack() // closes the keyboard
            Thread.sleep(500)
            // The header's Save (the bottom one says "Save Chelsea").
            retrying { scrollTo(By.text("Save")).click() }
            // The pet's page opens on its world; the care list is a scroll below it.
            waitFor("pet saved", 30_000) { repo.state.value.pets.singleOrNull()?.name == "Chelsea" }
            find(By.text("Chelsea"), 30_000) // her name, under her world
            scrollTo(By.text("Care"))
            Thread.sleep(1_500)
            shot("05-pet-screen")
        }

        val petId = petId() ?: run {
            File(out, "steps.txt").writeText(log.toString())
            throw AssertionError("No pet was created, so the rest of the journey can't run:\n$log")
        }
        fun completions() = repo.state.value.completions.size

        step("Done on a care task counts and cheers the pet up") {
            val before = completions()
            // The need meter under the room: tapping it logs the care ("Mark Feed done for Chelsea").
            retrying { find(By.descStartsWith("Mark ")).click() }
            waitFor("completion recorded") { completions() == before + 1 }
            find(By.text("Undo")) // the Undo strip, for a few seconds
            Thread.sleep(1_200) // the eating reaction
            shot("06-after-done")
        }

        step("add a medicine task") {
            retrying { find(By.text("Care")).click() } // the Care panel over the room
            retrying { scrollTo(By.text("+ Add care task")).click() }
            find(By.text("Times"))
            retrying { scrollTo(By.textContains("Medicine")).click() }
            shot("07-task-editor")
            retrying { scrollTo(By.text("Save")).click() }
            waitFor("medicine task saved") { repo.state.value.tasksFor(petId).any { it.kind.name == "MEDS" } }
            device.wait(Until.gone(By.text("Times")), 5_000) // the editor has left
            scrollTo(By.textContains("Medicine")) // in the Care panel's list
            closePanel() // the panel drops: back to the room
            find(By.desc("Settings"))
        }

        /** The Done button in the same card as [row] (the nearest one vertically). */
        fun doneNextTo(row: UiObject2): UiObject2 =
            device.findObjects(By.text("Done")).minByOrNull { kotlin.math.abs(it.visibleBounds.centerY() - row.visibleBounds.centerY()) }
                ?: throw AssertionError("no Done button next to ${row.text}")

        step("health: a kitten's birthday gives her the first-year plan") {
            retrying { find(By.text("Health")).click() } // the Health key under the room
            retrying { scrollTo(By.text("+ Add health reminders")).click() }
            find(By.textContains("born?"))
            // "About how old" is the default, at 8 weeks: two taps make Chelsea a 10-week-old kitten.
            retrying { find(By.desc("Older")).click() }
            retrying { find(By.desc("Older")).click() }
            find(By.textStartsWith("About 10 weeks old"))
            shot("health-birthday")
            retrying { find(By.text("Save")).click() }
            // A kitten: anti-rabies, FVRCP, deworming, tick & flea, check-up.
            waitFor("health tasks added") { repo.state.value.tasksFor(petId).count { it.kind.health } == 5 }
            val today = repo.clock.dayIndex(repo.now())
            check(repo.state.value.pet(petId)?.birthDay == today - 70) { "birthday not saved: ${repo.state.value.pet(petId)?.birthDay}" }
            scrollTo(By.text("FVRCP vaccine"))
            find(By.textStartsWith("Dose 1 of 3"))
            find(By.textStartsWith("Typical schedule"))
            Thread.sleep(500)
            shot("health-series")
        }

        step("health: record a dose with a photo of the vaccination card") {
            val fvrcp = repo.state.value.tasksFor(petId).first { it.title == "FVRCP vaccine" }
            retrying { doneNextTo(scrollTo(By.text("FVRCP vaccine"))).click() }
            find(By.text("When was it done?"))
            // The stubbed photo picker returns the test photo.
            retrying { find(By.text("Add photo")).click() }
            find(By.text("Photo added"), 20_000)
            shot("health-record")
            retrying { find(By.text("Save")).click() }
            waitFor("dose recorded") { repo.state.value.completions.any { it.taskId == fvrcp.id } }
            val record = repo.state.value.completions.last { it.taskId == fvrcp.id }
            waitFor("photo saved with it", 20_000) { repo.recordPhoto(petId, record.id) != null }
            check(repo.recordPhoto(petId, record.id)!!.let { it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }) { "record photo isn't a JPEG" }
            scrollTo(By.text("FVRCP vaccine"))
            scrollTo(By.textStartsWith("Dose 2 of 3")) // the row moved down the list (next due later)
            val thumb = By.desc("Photo of Chelsea's card for FVRCP vaccine")
            find(thumb, 20_000)
            Thread.sleep(800) // the thumbnail decodes in the background
            shot("health-thumbnail")
            retrying { find(thumb).click() }
            find(By.text("Delete photo"))
            Thread.sleep(800)
            shot("health-photo")
            retrying { find(By.text("Close")).click() }
            retrying { scrollTo(By.text("History (1)")).click() }
            find(By.text("Dose 1"))
            Thread.sleep(500)
            shot("health-history")
            retrying { find(By.text("Close")).click() }
        }

        step("health: an anti-rabies shot given a month ago is next due in 11 months") {
            val vaccine = repo.state.value.tasksFor(petId).first { it.title == "Anti-rabies shot" }
            retrying { doneNextTo(scrollTo(By.text("Anti-rabies shot"))).click() }
            find(By.text("When was it done?"))
            retrying { find(By.text("A month ago")).click() }
            retrying { find(By.text("Save")).click() }
            waitFor("shot recorded") { repo.state.value.completions.any { it.taskId == vaccine.id } }
            val item = com.pawpixel.core.CareStats.healthDue(repo.state.value, petId, repo.now(), repo.clock).first { it.task.id == vaccine.id }
            val label = com.pawpixel.core.CareStats.dueLabel(item, repo.now(), repo.clock)
            note("anti-rabies after 'a month ago': $label")
            check(label == "Due in 11 months") { "expected 'Due in 11 months', got '$label'" }
            scrollTo(By.text(label))
            Thread.sleep(500)
            shot("health-section")
            // Rabies rules and local help.
            retrying { scrollTo(By.text("Rabies rules and where to get shots")).click() }
            scrollTo(By.textContains("City Veterinary Office"))
            shot("health-local-help")
            scrollTo(By.textStartsWith("March is Rabies Awareness Month"))
            shot("health-local-help-2")
            closePanel() // the local help is part of the Health panel: the panel drops, the room shows
            find(By.text("10 weeks old")) // her age, on her name plate
        }

        step("weight: two weigh-ins draw the chart; the list opens") {
            val today = repo.clock.dayIndex(repo.now())
            retrying { find(By.text("Health")).click() }
            retrying { scrollTo(By.text("Weight")).click() } // the Weight door at the top of Health
            retrying { scrollTo(By.text("+ Add weight")).click() }
            retrying { find(By.clazz("android.widget.EditText")).text = "4.0" }
            retrying { find(By.text("Yesterday")).click() }
            shot("weight-dialog")
            retrying { find(By.text("Save")).click() }
            waitFor("first weigh-in saved") { repo.state.value.weightsFor(petId).singleOrNull()?.let { it.grams == 4000 && it.day == today - 1 } == true }
            retrying { scrollTo(By.text("+ Add weight")).click() }
            retrying { find(By.clazz("android.widget.EditText")).text = "4.2" }
            retrying { find(By.text("Save")).click() }
            waitFor("second weigh-in saved") { repo.state.value.weightsFor(petId).map { it.grams } == listOf(4000, 4200) }
            scrollTo(By.descStartsWith("Weight chart"))
            scrollTo(By.text("4.2 kg")) // the latest weigh-in, big, at the top of the weight card
            Thread.sleep(500)
            shot("weight")
            retrying { scrollTo(By.text("All weigh-ins (2)")).click() }
            scrollTo(By.text("Hide weigh-ins"))
            shot("weight-list")
            retrying { find(By.text("Hide weigh-ins")).click() }
            device.pressBack()
            scrollTo(By.textStartsWith("4.2 kg")) // the Weight door shows the latest weigh-in
            closePanel() // the room
        }

        step("milestone: 7 days of care is celebrated and shareable") {
            // A week of care, as if logged over the past days.
            val today = repo.clock.dayIndex(repo.now())
            runBlocking { repo.update { s -> com.pawpixel.core.StateOps.markCareDays(s, petId, (today - 6..today).toList()) } }
            val before = choosers()
            retrying { scrollTo(By.text("Share the card")).click() }
            waitFor("milestone share sheet") { choosers() == before + 1 }
            scrollTo(By.textStartsWith("7 days of care"))
            shot("milestone")
            retrying { find(By.text("Nice!")).click() }
            waitFor("celebrated once") { repo.state.value.pet(petId)?.milestoneSeen == 7 }
        }

        step("outfits: a week of care earns a bandana, and it shows on the pet") {
            retrying { find(By.text("Wardrobe")).click() } // the Wardrobe key under the room
            retrying { scrollTo(By.text("Bandana")).click() }
            waitFor("wearing it") { repo.state.value.pet(petId)?.accessory == "BANDANA" }
            scrollTo(By.textStartsWith("Crown"))
            shot("outfits")
            closePanel()
            find(By.text("Wardrobe"))
            retrying { find(By.text("Wardrobe")).click() }
            retrying { scrollTo(By.text("None")).click() }
            waitFor("took it off") { repo.state.value.pet(petId)?.accessory == null }
            closePanel()
        }

        step("share animation and before/after card open the share sheet") {
            val before = choosers()
            retrying { find(By.text("Share")).click() } // the Share key under the room
            retrying { scrollTo(By.text("Share animation")).click() }
            waitFor("GIF share sheet", 60_000) { choosers() == before + 1 }
            retrying { scrollTo(By.text("Before/after")).click() }
            waitFor("card share sheet") { choosers() == before + 2 }
            shot("08-share-section")
            // The Pet ID card: made on the server (the real one, or the demo), its QR drawn, then removed.
            val model = if (repo.map.settings.isConfigured) repo.map else repo.demoMap
            retrying { scrollTo(By.text("Make an ID card")).click() }
            retrying { device.findObjects(By.clazz("android.widget.EditText"))[0].text = "Friendly but shy, on a special diet" }
            retrying { scrollTo(By.text("Make the card")).click() }
            find(By.text("Scan to reach my owner"), 40_000)
            find(By.descStartsWith("QR code for Chelsea"))
            val cardId = model.cardFor(petId)
            check(cardId != null && runBlocking { model.client.cards.mine() }.any { it.id == cardId && it.note == "Friendly but shy, on a special diet" }) { "card not on the server: $cardId" }
            shot("08-pet-id-card")
            retrying { scrollTo(By.text("Remove card")).click() }
            waitFor("card removed") { model.cardFor(petId) == null }
            find(By.text("Make an ID card"), 15_000)
            closePanel()
        }

        step("album: a photo with a caption, kept with the pet and in its backup") {
            retrying { find(By.text("More")).click() }
            retrying { scrollTo(By.text("Album")).click() }
            find(By.text("No photos yet"), 15_000)
            shot("album-empty")
            retrying { find(By.text("Add photo")).click() } // the stubbed picker returns the test photo
            find(By.text("Caption"), 20_000)
            retrying { find(By.clazz("android.widget.EditText")).text = "First day home" }
            waitFor("caption typed") { device.findObject(By.text("First day home")) != null }
            // A tap while the keyboard is still settling can go nowhere: Save until the dialog is gone.
            retrying {
                find(By.text("Save")).click()
                check(device.wait(Until.gone(By.text("Caption")), 3_000) == true) { "the caption dialog is still open" }
            }
            waitFor("caption saved") { repo.state.value.albumFor(petId).singleOrNull()?.caption == "First day home" }
            val added = repo.state.value.albumFor(petId).single()
            val bytes = repo.albumPhoto(added)
            check(bytes != null && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) { "album photo isn't a JPEG" }
            check(Backup.albumPhotoPath(petId, added.id) in Backup.filesFor(repo.state.value, petId)) { "album photo not in the backup list" }
            val thumb = By.desc("Photo of Chelsea: First day home")
            find(thumb, 20_000)
            Thread.sleep(800) // the thumbnail decodes in the background
            shot("album")
            retrying { find(thumb).click() }
            find(By.text("Edit caption"))
            Thread.sleep(800)
            shot("album-photo")
            retrying { find(By.text("Close")).click() }
            closePanel() // the album
            closePanel() // the More panel
            find(By.text("Chelsea"))
        }

        step("in loving memory: the room goes quiet, the album stays, and it can be undone") {
            retrying { find(By.text("More")).click() }
            retrying { scrollTo(By.text("In loving memory")).click() }
            find(By.text("Remember Chelsea?"))
            retrying { find(By.text("Remember Chelsea")).click() }
            waitFor("pet remembered") { repo.state.value.pets.single().remembered }
            closePanel() // the More panel
            find(By.text("Forever in your heart"), 15_000)
            find(By.text("In loving memory"))
            check(repo.state.value.albumFor(petId).size == 1) { "the album should stay" }
            check(ReminderPlanner.plan(repo.state.value, repo.now(), repo.clock).isEmpty()) { "a remembered pet gets no reminders" }
            Thread.sleep(1_000)
            shot("memory")
            retrying { find(By.text("More")).click() }
            retrying { scrollTo(By.text("Remembered")).click() }
            retrying { find(By.text("Bring back")).click() }
            waitFor("pet cared for again") { !repo.state.value.pets.single().remembered }
            closePanel()
            find(By.text("Care"), 15_000) // the Care key is back in the dock
        }

        step("lost and found: the alert goes up with the album photo, shows on the map, a sighting comes in, safe home") {
            // The real server when this build has one (the test account signs in), else the in-app demo.
            val real = repo.map.settings.isConfigured
            val model = if (real) repo.map else repo.demoMap
            retrying { find(By.text("More")).click() }
            retrying { scrollTo(By.text("Lost?")).click() }
            find(By.text("Is Chelsea missing?"), 15_000)
            retrying { device.findObjects(By.clazz("android.widget.EditText"))[0].text = "Grey tabby, pink collar with a bell" }
            retrying { scrollTo(By.text("Earlier today")).click() }
            scrollTo(By.text("Alert owners nearby"))
            Thread.sleep(600)
            shot("lost-form")
            retrying { scrollTo(By.text("Alert owners nearby")).click() }
            find(By.text("Alert is on for Chelsea"), 40_000)
            val id = model.alertFor(petId)
            check(id != null) { "no alert recorded for the pet" }
            val details = runBlocking { model.client.lost.details(id!!) }
            check(details != null && details.photos.size == 1 && details.description == "Grey tabby, pink collar with a bell" && details.mine) { "alert not as sent: $details" }
            // Earlier runs that died mid-way may have left alerts open on the shared test account: at most 3 are allowed.
            runBlocking { model.client.lost.mine().filter { it.foundAtMs == null && it.id != id }.forEach { model.client.lost.cancel(it.id) } }
            shot("lost-alert")
            closePanel() // the alert page drops to the More panel
            closePanel() // and More drops: the room shows it's reported lost
            find(By.text("Reported lost"), 15_000)

            // On the map: red flag, the Lost pets tab, and (demo) the neighbourhood's own lost dog with a sighting to report.
            retrying { find(By.text("More")).click() }
            retrying { scrollTo(By.text("Pet map")).click() }
            if (!real) retrying { find(By.text("Try the demo map")).click() }
            retrying { find(By.text("I'm 18 or older")).click() }
            retrying { find(By.textStartsWith("Show my pixel pets")).click() }
            retrying { scrollTo(By.textContains("and join")).click() }
            find(By.text("Nearby"), 60_000)
            retrying { find(By.textStartsWith("Lost pets (")).click() }
            find(By.textStartsWith("Yours · Chelsea"), 20_000)
            if (!real) {
                retrying { find(By.text("Kape")).click() }
                find(By.text("LOST: Kape"), 15_000)
                retrying { find(By.text("I saw them")).click() }
                find(By.text("You saw Kape?"), 15_000)
                retrying { device.findObjects(By.clazz("android.widget.EditText")).last().text = "Near the plaza fountain" }
                retrying { find(By.text("Send sighting")).click() }
                find(By.textStartsWith("Thank you."), 20_000)
                shot("lost-sheet")
                retrying { find(By.text("Close")).click() }
                val kape = runBlocking { model.client.lost.nearby(model.myArea!!.centerLat, model.myArea!!.centerLng) }.first { it.name == "Kape" }
                check(kape.sightings == 1) { "the sighting wasn't counted: $kape" }
            }
            retrying { find(By.text("Nearby")).click() }
            Thread.sleep(1_500)
            shot("lost-map")
            retrying { find(By.text("More")).click() }
            retrying { find(By.text("Leave the map")).click() }
            retrying { find(By.text("Leave")).click() }
            find(By.text("I'm 18 or older"), 20_000)
            closePanel() // the map
            closePanel() // the More panel
            find(By.text("Reported lost"), 15_000)

            // Safe home: (demo) a sighting has come in by now; the alert closes with a welcome.
            if (!real) waitFor("the demo neighbourhood reports a sighting", 40_000) { runBlocking { model.client.lost.sightings(id!!) }.isNotEmpty() }
            retrying { find(By.text("Alert")).click() }
            find(By.text("Alert is on for Chelsea"), 20_000)
            if (!real) { find(By.text("Sightings (1)"), 15_000); find(By.textContains("market gate")) }
            retrying { scrollTo(By.text("Safe home!")).click() }
            retrying { find(By.text("Yes, safe home")).click() }
            find(By.text("Welcome home, Chelsea!"), 20_000)
            shot("lost-home")
            check(runBlocking { model.client.lost.details(id!!) }?.foundAtMs != null) { "the alert should be closed on the server" }
            retrying { find(By.textStartsWith("Back to Chelsea")).click() }
            find(By.text("Chelsea"), 15_000)
            check(model.alertFor(petId) == null) { "the phone should forget the closed alert" }
            if (real) runBlocking { model.client.lost.cancel(id!!) } // leave the test account clean
        }

        step("pals: your code, add a pal by code, their pixel pets, a treat, the visitor in the room") {
            val real = repo.map.settings.isConfigured
            val model = if (real) repo.map else repo.demoMap
            retrying { find(By.text("More")).click() }
            retrying { scrollTo(By.text("Pals")).click() }
            find(By.text("Your pal code"), 15_000)
            find(By.descStartsWith("Pal code "), 40_000)
            shot("pals-code")
            if (!real) {
                retrying { device.findObjects(By.clazz("android.widget.EditText"))[0].text = "jam1la" }
                retrying { find(By.text("Add")).click() }
                find(By.textStartsWith("You're pals"), 20_000)
                find(By.text("Biscuit"), 15_000) // the pal's dog (the demo's owner-1 has Biscuit and Tala)
                retrying { scrollTo(By.text("Treat")).click() }
                retrying { find(By.text("A ball")).click() }
                retrying { find(By.text("Send")).click() }
                find(By.textStartsWith("Sent to"), 15_000)
                check(model.pals.size == 1 && model.pals[0].pets.map { it.name }.containsAll(listOf("Biscuit", "Tala"))) { "pals not cached: ${model.pals}" }
                shot("pals")
                // Back in the room: the pal's pet is visiting, and 15 s later the demo pal's treat shows as the bubble.
                waitFor("the demo pal sends a ball", 40_000) { runBlocking { model.client.pals.inbox() }.isNotEmpty() }
                retrying { find(By.desc("Back")).click() }
                closePanel() // the More panel
                find(By.textContains("is visiting"), 15_000)
                find(By.textContains("sent Chelsea a ball"), 15_000)
                Thread.sleep(600)
                shot("pals-room")
                retrying { find(By.text("More")).click() }
                retrying { scrollTo(By.text("Pals")).click() }
                retrying { scrollTo(By.text("Unpal")).click() }
                waitFor("unpalled") { model.pals.isEmpty() }
            }
            retrying { find(By.desc("Back")).click() }
            closePanel()
            find(By.text("Chelsea"))
        }

        step("walk: timed from the Care panel, the pet trots, ending it logs the walk as care") {
            retrying { find(By.text("Care")).click() }
            retrying { scrollTo(By.text("Start a walk")).click() }
            find(By.textStartsWith("Walk with Chelsea"), 15_000)
            // The emulator may ask for the activity permission (Android 10+): either answer is fine for a timed walk.
            device.findObject(By.textContains("Allow"))?.click() ?: device.findObject(By.textContains("While using the app"))?.click()
            Thread.sleep(2_500)
            shot("walk")
            retrying { find(By.text("End walk")).click() }
            waitFor("walk recorded") { repo.state.value.walksFor(petId).size == 1 }
            val walk = repo.state.value.walksFor(petId).single()
            check(walk.endMs - walk.startMs >= 2_000) { "walk too short: $walk" }
            find(By.textStartsWith("1 walk ·"), 15_000) // back on the Care panel, the week's line updated
            closePanel()
            find(By.text("Chelsea"), 15_000) // the room
        }

        step("home is the pet's room") {
            find(By.text("Chelsea")) // her name plate
            find(By.desc("Settings"))
            Thread.sleep(800)
            shot("09-home")
        }

        step("settings open and close") {
            retrying { find(By.desc("Settings")).click() }
            scrollTo(By.text("Bedtime")) // a few groups down the page
            shot("10-settings")
            device.pressBack()
            find(By.text("Chelsea"))
        }

        step("away mode pauses care and comes back") {
            retrying { find(By.desc("Settings")).click() }
            retrying { scrollTo(By.text("Away 3 days")).click() }
            find(By.text("I'm back"))
            check(repo.state.value.isAway(repo.now())) { "not away" }
            val away = repo.state.value
            check(ReminderPlanner.plan(away, repo.now(), repo.clock).none { it.atMs < away.settings.awayUntilMs }) { "reminders still planned while away" }
            shot("away-mode")
            retrying { find(By.text("I'm back")).click() }
            waitFor("back home") { !repo.state.value.isAway(repo.now()) }
            device.pressBack()
            find(By.text("Chelsea"))
        }

        step("backup: save a file, then restore one") {
            retrying { find(By.desc("Settings")).click() }
            val before = choosers()
            retrying { scrollTo(By.text("Save backup file")).click() }
            waitFor("backup share sheet") { choosers() == before + 1 }
            // Restore a backup of this phone with one change (bedtime 9 PM), picked from "Files".
            val state = repo.state.value
            val files = state.pets.flatMap { Backup.filesFor(state, it.id) }.mapNotNull { path -> repo.platform.files.readBytes(path)?.let { path to it } }.toMap()
            check(files.keys.any { "/rec-" in it }) { "the dose's photo isn't in the backup: ${files.keys}" }
            val backup = File(ctx.cacheDir, "e2e-backup.json")
            backup.writeText(Backup.encode(state.copy(settings = state.settings.copy(nightStart = 21 * 60)), files, repo.now()))
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(backup))),
            )
            retrying { scrollTo(By.text("Restore")).click() }
            find(By.text("Restore this backup?"))
            shot("backup-restore")
            retrying { find(By.text("Replace with backup")).click() }
            find(By.text("Restored 1 pet."), 20_000)
            check(repo.state.value.settings.nightStart == 21 * 60) { "backup not applied" }
            check(repo.state.value.pets.single().let { repo.art(it) } != null) { "pet's look not restored" }
            val photoRecord = repo.state.value.completions.first { repo.recordPhoto(petId, it.id) != null }
            note("record photo restored for ${photoRecord.id}")
            device.pressBack()
            find(By.text("Chelsea"))
        }

        step("pet map: join, see the pixel pets nearby, report, RSVP, leave") {
            if (!repo.map.settings.isConfigured) { note("map not set up in this build; skipped"); return@step }
            retrying { find(By.text("More")).click() }
            retrying { find(By.text("Pet map")).click() }
            retrying { find(By.text("I'm 18 or older")).click() }
            retrying { find(By.textStartsWith("Show my pixel pets")).click() }
            shot("pet-map-join")
            retrying { scrollTo(By.textContains("and join")).click() }
            find(By.text("Nearby"), 60_000)
            val area = repo.map.myArea
            check(area != null) { "no area after joining" }
            val areas = runBlocking { repo.map.client.nearbyAreas(area) }
            check(areas.any { it.cellId == area.id }) { "your area (${area.id}) should show with 3+ owners: $areas" }
            Thread.sleep(2_500)
            shot("pet-map")
            // Your area's pin sits at the centre of the map, which fills the screen below the tabs.
            val tabsBottom = find(By.text("Nearby")).visibleBounds.bottom
            val mapBottom = device.displayHeight - (48 * ctx.resources.displayMetrics.density).toInt()
            device.click(device.displayWidth / 2, (tabsBottom + mapBottom) / 2 + (4 * ctx.resources.displayMetrics.density).toInt())
            find(By.textStartsWith("Your area"), 15_000)
            find(By.text("Chelsea"))
            Thread.sleep(1_000)
            shot("pet-map-area")
            retrying { find(By.text("⋯")).click() }
            retrying { find(By.text("Report")).click() }
            retrying { find(By.text("Send report")).click() }
            find(By.text("Thanks for telling us"))
            retrying { find(By.text("OK")).click() }
            retrying { find(By.text("Close")).click() }

            retrying { find(By.text("Gatherings")).click() }
            find(By.textStartsWith("Sunday pet walk"), 20_000)
            retrying { scrollTo(By.text("I'm going")).click() }
            waitFor("RSVP saved on the server", 20_000) { runBlocking { repo.map.client.gatherings() }.any { it.iAmGoing } }
            find(By.textStartsWith("Meet at: Plaza Rizal"), 20_000)
            shot("pet-map-gathering")

            // Host a walk: the form, the meeting spot tapped on the map, and the proposal waiting for approval.
            find(By.textContains("walks coming up"), 15_000)
            retrying { scrollTo(By.text("Host a walk")).click() }
            find(By.text("Send for approval"), 15_000)
            // The fields, top to bottom: title, meeting place, area. The spot comes from a tap on the map.
            retrying { device.findObjects(By.clazz("android.widget.EditText"))[0].text = "Saturday walk" }
            retrying { device.findObjects(By.clazz("android.widget.EditText"))[1].text = "Plaza Rizal fountain" }
            retrying { dialogScrollTo(By.text("Tap the spot on the map")).click() }
            find(By.textStartsWith("Tap the meeting place"), 15_000)
            shot("pet-map-place-spot")
            Thread.sleep(800)
            device.click(device.displayWidth / 2, (tabsBottom + mapBottom) / 2 + (40 * ctx.resources.displayMetrics.density).toInt())
            dialogScrollTo(By.text("Spot set"))
            val tomorrow = com.pawpixel.app.ui.formatDay(repo.clock.dayIndex(repo.now()) + 1)
            retrying { dialogScrollTo(By.text(tomorrow)).click() }
            shot("pet-map-host-walk")
            retrying { find(By.text("Send for approval")).click() }
            find(By.textStartsWith("Waiting for approval"), 20_000)
            val mine = runBlocking { repo.map.client.myWalks() }
            check(mine.any { it.title == "Saturday walk" && !it.approved && it.venueName == "Plaza Rizal fountain" }) { "walk not proposed: $mine" }
            shot("pet-map-my-walk")
            retrying { scrollTo(By.text("Cancel this walk")).click() }
            retrying { find(By.text("Cancel the walk")).click() }
            waitFor("walk cancelled", 20_000) { runBlocking { repo.map.client.myWalks() }.none { it.title == "Saturday walk" } }

            retrying { find(By.text("More")).click() }
            retrying { find(By.text("Leave the map")).click() }
            retrying { find(By.text("Leave")).click() }
            find(By.text("I'm 18 or older"), 20_000)
            check(!runBlocking { repo.map.client.hasJoined() }) { "still on the map after leaving" }
            closePanel() // the map
            closePanel() // the More panel drops: the room
            find(By.text("Chelsea"))
        }

        step("household: share Chelsea, a partner joins, her Done and her Undo show on this phone") {
            if (!repo.map.settings.isConfigured) { note("server not set up in this build; skipped"); return@step }
            retrying { find(By.text("Share")).click() } // the Share key under the room
            retrying { scrollTo(By.text("Share with your household")).click() }
            if (device.hasObject(By.text(repo.map.signInLabel))) retrying { find(By.text(repo.map.signInLabel)).click() }
            val nameField = find(By.clazz("android.widget.EditText"), 20_000)
            retrying { nameField.click() }
            retrying { find(By.clazz("android.widget.EditText")).text = "Save" }
            device.pressBack()
            Thread.sleep(800) // the keyboard closing
            shot("h-household-start")
            retrying { scrollTo(By.text("Create household")).click() }
            val code = find(By.text(Pattern.compile("[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}")), 20_000).text
            shot("h-household-invite")
            waitFor("Chelsea is shared", 20_000) { repo.state.value.pets.single().shared }
            // Jamaica, on her own phone (here: the app's own client, signed in as the seeded partner).
            val partnerApi = com.pawpixel.map.SupabaseApi(repo.map.settings, repo.platform.http, object : com.pawpixel.map.SessionStore {
                var s: String? = null; override fun load() = s; override fun save(json: String?) { s = json }
            }, repo.platform::nowMs)
            val partner = com.pawpixel.map.HouseholdClient(partnerApi)
            val hid = runBlocking { partnerApi.signInWithPassword("partner@test.pawpixel", "partner-pass-123"); partner.join(code, "Jamaica") }
            waitFor("Chelsea reaches the household", 30_000) { runBlocking { partner.pull(hid) }.changes.pets.any { it.name == "Chelsea" } }
            val shared = runBlocking { partner.pull(hid) }.changes
            check(shared.pets.single().lookCode != null) { "no pixel look shared" }
            // Litter, not water: the reminder step after this one uses the water task.
            val litter = shared.tasks.first { it.kind.name == "LITTER" }
            // Jamaica cleans the litter.
            val now = repo.now()
            runBlocking {
                partner.push(hid, com.pawpixel.core.SyncPush(addCompletions = listOf(com.pawpixel.core.Completion(
                    litter.id, now, repo.clock.minuteOfDay(now), repo.clock.dayIndex(now), id = "partnerlitter1"))))
            }
            retrying { scrollTo(By.text("Sync now")).click() }
            waitFor("her Done arrives", 30_000) { repo.state.value.completions.any { it.id == "partnerlitter1" && it.by == partnerApi.userId } }
            scrollTo(By.text("Jamaica"))
            shot("h-household-members")
            // Back to Chelsea's room: "Litter cleaned by Jamaica · <time>" in the Care panel.
            closePanel() // the household screen
            closePanel() // the Share panel drops: the room
            retrying { find(By.text("Care")).click() } // the Care panel
            scrollTo(By.text("+ Add care task"))
            val row = scrollTo(By.textStartsWith("Litter cleaned by Jamaica"))
            note("on Chelsea's page: " + row.text)
            Thread.sleep(500)
            shot("h-household-done-by")
            // Jamaica undoes it on her phone; the next sync (as when the app is reopened) takes it back here too.
            runBlocking { partner.push(hid, com.pawpixel.core.SyncPush(undoCompletionIds = listOf("partnerlitter1"))) }
            check(runBlocking { repo.family.sync() }) { "sync failed: ${repo.family.status.value.error}" }
            waitFor("her Undo arrives", 20_000) { repo.state.value.completions.none { it.id == "partnerlitter1" } }
            check(device.wait(Until.gone(By.textStartsWith("Litter cleaned by Jamaica")), 10_000)) { "'Litter cleaned by Jamaica' still shown after her Undo" }
            closePanel()
            find(By.text("Care"))
        }

        step("reminder notification with a working Done button") {
            // A daily task with a planned time still open today (a reminder for a covered one is skipped).
            val st = repo.state.value
            // (Late at night today's slots may all be past or done: then tomorrow's first one.)
            val (task, slot) = st.tasksFor(petId).filter { !it.kind.health && it.remindersOn }.firstNotNullOf { t ->
                val s = com.pawpixel.core.CareEngine.status(t, st.completions, repo.now(), repo.clock)
                (s.slotTimes.getOrNull(s.done) ?: s.nextDueMs)?.let { t to it }
            }
            val before = completions()
            ctx.sendBroadcast(
                Intent(ctx, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_SHOW)
                    .putExtra(ReminderReceiver.EXTRA_TASK, task.id)
                    .putExtra(ReminderReceiver.EXTRA_REFS, "${task.id}@$slot")
                    .putExtra(ReminderReceiver.EXTRA_ID, 4242)
                    .putExtra(ReminderReceiver.EXTRA_TITLE, "${task.kind.emoji} ${task.title} · Chelsea")
                    .putExtra(ReminderReceiver.EXTRA_BODY, "Time to ${task.title.lowercase()} for Chelsea."),
            )
            device.openNotification()
            val title = find(By.textContains("Chelsea."), 15_000)
            Thread.sleep(800)
            shot("11-notification")
            val done = device.findObject(By.text(Pattern.compile("(?i)done")))
                ?: run { title.click(); null } // not expanded: some shades hide actions until tapped
            if (done != null) {
                done.click()
                waitFor("Done from the notification") { completions() == before + 1 }
                note("pressed Done on the notification")
            } else {
                note("notification actions hidden; opened the app from it instead")
            }
            device.pressBack()
            if (!device.hasObject(By.pkg(ctx.packageName))) scenario = ActivityScenario.launch(MainActivity::class.java)
        }

        step("home-screen widget can be added and draws the pet") {
            goHome()
            val awm = AppWidgetManager.getInstance(ctx)
            val provider = ComponentName(ctx, PetWidgetReceiver::class.java)
            check(awm.isRequestPinAppWidgetSupported) { "this launcher can't pin widgets" }
            find(By.text("Chelsea"))
            // The Care panel offers the widget until one is added.
            retrying { find(By.text("Care")).click() }
            retrying { scrollTo(By.text("Add widget")).click() }
            val add = find(By.text(Pattern.compile("(?i)add( to home screen)?|add automatically")), 15_000)
            shot("12-widget-dialog")
            add.click()
            waitFor("widget placed", 15_000) { awm.getAppWidgetIds(provider).isNotEmpty() }
            device.pressHome()
            Thread.sleep(4_000) // Glance renders asynchronously
            shot("13-home-screen-widget")
            check(!device.hasObject(By.textContains("Can't load widget"))) { "the widget failed to load" }
            check(!device.hasObject(By.textContains("Problem loading widget"))) { "the widget failed to load" }
        }

        step("widget: the pet is alive on the home screen") {
            // The idle animation plays in the launcher itself (a ViewFlipper): screenshots a beat
            // apart differ below the status bar, where nothing else on the home screen moves. (Some
            // steps repeat a frame, so a few beats are compared and any change counts.)
            val shots = (0 until 5).map { if (it > 0) Thread.sleep(350); instr.uiAutomation.takeScreenshot() }
            val first = shots[0]
            val top = (first.height * 0.08).toInt()
            var most = 0
            for (second in shots.drop(1)) {
                var differ = 0
                for (y in top until first.height step 2) for (x in 0 until first.width step 2) if (first.getPixel(x, y) != second.getPixel(x, y)) differ++
                most = maxOf(most, differ)
            }
            note("home screen pixels that changed within 1.4 s: $most")
            File(out, "14-home-screen-widget-a-beat-later.png").outputStream().use { shots[2].compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            check(most > 50) { "the widget's pet did not move on the home screen" }
            // And the frames themselves: the widget hands the launcher several distinct pictures.
            val remote = runBlocking {
                com.pawpixel.app.widget.PetWidget().compose(ctx, size = androidx.compose.ui.unit.DpSize(120.dp, 120.dp),
                    state = androidx.datastore.preferences.core.emptyPreferences())
            }
            instr.runOnMainSync {
                val frame = android.widget.FrameLayout(ctx)
                val view = remote.apply(ctx, frame)
                var flipper: android.widget.ViewFlipper? = null
                fun walk(v: android.view.View) { if (v is android.widget.ViewFlipper) flipper = v; if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
                walk(view)
                val f = flipper ?: throw AssertionError("no ViewFlipper in the widget")
                check(f.isAutoStart) { "the flipper doesn't start by itself" }
                check(f.childCount >= 6) { "only ${f.childCount} frames" }
                val bitmaps = (0 until f.childCount).map { ((f.getChildAt(it) as android.widget.ImageView).drawable as android.graphics.drawable.BitmapDrawable).bitmap }
                // Asleep (the run can fall in the pet's night), the pet lies still: the frames are alike by design.
                val asleep = com.pawpixel.core.MoodEngine.read(repo.state.value, petId, repo.now(), repo.clock).mood == com.pawpixel.core.Mood.SLEEPY
                check(asleep || bitmaps.toSet().size >= 3) { "the frames are all the same picture" }
                note("widget animation: ${f.childCount} steps, ${bitmaps.toSet().size} distinct frames, every ${f.flipInterval} ms")
            }
        }

        step("widget: good layouts from a one-row strip to 4x3, light and dark") {
            // Launchers can't be resized from a test, so the widget is drawn off-screen at each size,
            // exactly as a launcher would (Glance → RemoteViews → views).
            val sizes = listOf("2x1" to (110 to 50), "4x1" to (250 to 50), "2x2" to (120 to 120), "2x3" to (120 to 200),
                "3x2" to (180 to 120), "4x2" to (250 to 120), "4x3" to (250 to 190))
            for ((name, wh) in sizes) {
                val texts = renderWidget(name, wh.first, wh.second, dark = false)
                check("Chelsea" in texts || texts.any { it.startsWith("Chelsea ") }) { "$name shows no Chelsea: $texts" }
            }
            renderWidget("2x2", 120, 120, dark = true)
            renderWidget("4x2", 250, 120, dark = true)
        }

        step("widget: Done on the widget logs care and cheers the pet up") {
            goHome()
            // Something due right now: fresh water that was due half an hour ago.
            val minute = repo.clock.minuteOfDay(repo.now())
            val water = com.pawpixel.core.CareTask("e2ewater", petId, com.pawpixel.core.TaskKind.WATER, "Fresh water",
                listOf((minute - 30).coerceAtLeast(0)), anchorDay = 0, adaptive = false, createdAtMs = 0)
            runBlocking { repo.update { com.pawpixel.core.StateOps.upsertTask(it, water) } }
            val face = widgetFace()
            if (face?.actionTaskId == null) throw AssertionError("no Done on the widget: $face")
            val label = "${face.actionEmoji} Done"
            note("widget button: $label for ${face.actionTaskId}")
            renderWidget("4x1-due", 250, 50, dark = false)
            renderWidget("2x3-due", 120, 200, dark = false)
            renderWidget("4x2-due", 250, 120, dark = true)
            device.pressHome()
            val button = find(By.text(label), 20_000)
            Thread.sleep(1_000)
            shot("widget-due")
            val before = repo.state.value.completions.count { it.taskId == face.actionTaskId }
            button.click()
            waitFor("Done from the widget", 20_000) { repo.state.value.completions.count { it.taskId == face.actionTaskId } == before + 1 }
            // The widget redraws from the new file (the pet cheers up; another task may be due next).
            val after = widgetFace()
            note("widget after Done: ${after?.caption}, button for ${after?.actionTitle}")
            if (after?.actionTaskId != face.actionTaskId) check(device.wait(Until.gone(By.text(label)), 15_000)) { "the widget still offers '$label'" }
            Thread.sleep(1_500)
            shot("widget-after-done")
        }

        step("widget: tapping it opens the pet's page") {
            // From the app, Home shows the widget's page (Home on the launcher would switch pages).
            goHome()
            device.pressHome()
            retrying { find(By.desc(Pattern.compile("Chelsea: .*"))).click() }
            check(device.wait(Until.hasObject(By.pkg(ctx.packageName)), 15_000)) { "the app didn't open" }
            find(By.text("Chelsea")) // her room
            find(By.text("Care"))
            shot("widget-opens-pet")
            goHome()
        }

        step("widget: each widget can show a different pet") {
            val awm = AppWidgetManager.getInstance(ctx)
            val widgetId = awm.getAppWidgetIds(ComponentName(ctx, PetWidgetReceiver::class.java)).first()
            // A second pet (a family pet, drawn from Chelsea's look).
            val chelsea = repo.state.value.pet(petId)!!
            val kiko = chelsea.copy(id = "e2ekiko", name = "Kiko", shared = false)
            runBlocking { repo.update { it.copy(pets = it.pets + kiko) } }
            repo.redrawPoses(listOf(kiko.id))
            ctx.startActivity(Intent(ctx, com.pawpixel.app.widget.PetPickerActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            find(By.text("Which pet should this widget show?"))
            find(By.text("Whoever needs you most"))
            Thread.sleep(500)
            shot("widget-pick-pet")
            retrying { find(By.text("Kiko")).click() }
            goHome()
            device.pressHome()
            find(By.desc(Pattern.compile("Kiko: .*")), 20_000)
            Thread.sleep(1_000)
            shot("widget-kiko")
            // Kiko leaves: the widget goes back to the first pet.
            runBlocking { repo.deletePet(kiko.id) }
            find(By.desc(Pattern.compile("Chelsea: .*")), 20_000)
        }

        step("bundled reminder: one notification, and its Done logs everything in it") {
            if (!device.hasObject(By.pkg(ctx.packageName))) scenario = ActivityScenario.launch(MainActivity::class.java)
            val st = repo.state.value
            val open = st.tasksFor(petId).filter { !it.kind.health && it.remindersOn }.mapNotNull { t ->
                val s = com.pawpixel.core.CareEngine.status(t, st.completions, repo.now(), repo.clock)
                (s.slotTimes.getOrNull(s.done) ?: s.nextDueMs)?.let { com.pawpixel.core.ReminderRef(t.id, it) }
            }.take(2)
            check(open.size == 2) { "need two open tasks: $open" }
            val names = open.map { st.task(it.taskId)!!.title.lowercase() }
            val before = completions()
            ctx.sendBroadcast(
                Intent(ctx, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_SHOW)
                    .putExtra(ReminderReceiver.EXTRA_TASK, open[0].taskId)
                    .putExtra(ReminderReceiver.EXTRA_REFS, com.pawpixel.core.ReminderRef.encodeAll(open))
                    .putExtra(ReminderReceiver.EXTRA_ID, 4343)
                    .putExtra(ReminderReceiver.EXTRA_TITLE, "🐾 Care time · Chelsea")
                    .putExtra(ReminderReceiver.EXTRA_BODY, "Chelsea: ${names.joinToString(" and ")}. Tap Done when it's all done."),
            )
            device.openNotification()
            val title = find(By.textContains("Care time"), 15_000)
            Thread.sleep(800)
            shot("bundled-notification")
            val done = device.findObject(By.text(Pattern.compile("(?i)done")))
                ?: run { title.click(); null }
            if (done != null) {
                done.click()
                waitFor("both logged from one Done") { completions() == before + 2 }
                check(open.all { r -> repo.state.value.completions.any { it.taskId == r.taskId } })
            } else {
                note("notification actions hidden; opened the app from it instead")
            }
            device.pressBack()
            if (!device.hasObject(By.pkg(ctx.packageName))) scenario = ActivityScenario.launch(MainActivity::class.java)
        }

        step("reminders that never arrived: a one-time tip for this phone's background setting") {
            // As if two reminders were lost while the phone had PawPixel stopped.
            AndroidPlatform.reminderPrefs(ctx).edit().putInt(AndroidPlatform.KEY_LOST, 2).commit()
            goHome()
            retrying { find(By.desc("Settings")).click() }
            find(By.text("Some reminders didn't arrive"))
            Thread.sleep(500)
            shot("background-tip")
            retrying { find(By.text("Open settings")).click() }
            // The emulator isn't one of the makers with their own screen: the app's details page.
            waitFor("the phone's settings opened") { Intents.getIntents().any { it.action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS } }
            retrying { find(By.text("Got it")).click() }
            check(device.wait(Until.gone(By.text("Some reminders didn't arrive")), 5_000)) { "tip still shown" }
            check(AndroidPlatform.reminderPrefs(ctx).getBoolean(AndroidPlatform.KEY_TIP_DISMISSED, false)) { "dismissal not saved" }
            device.pressBack()
            find(By.text("Chelsea"))
        }

        step("performance: frame times on Chelsea's page (living pet idle, then scrolling)") {
            goHome()
            find(By.text("Care")) // her room, the pet living in it
            Thread.sleep(1_000)
            shell("dumpsys gfxinfo ${ctx.packageName} reset")
            Thread.sleep(5_000) // nothing but the pet breathing, blinking and wandering
            val idle = shell("dumpsys gfxinfo ${ctx.packageName}")
            File(out, "gfxinfo-idle.txt").writeText(idle)
            note("idle 5 s: " + frameSummary(idle))
            shell("dumpsys gfxinfo ${ctx.packageName} reset")
            val x = device.displayWidth / 2
            repeat(4) { device.swipe(x, (device.displayHeight * 0.75).toInt(), x, (device.displayHeight * 0.3).toInt(), 15); Thread.sleep(400) }
            repeat(4) { device.swipe(x, (device.displayHeight * 0.3).toInt(), x, (device.displayHeight * 0.75).toInt(), 15); Thread.sleep(400) }
            val scrolling = shell("dumpsys gfxinfo ${ctx.packageName}")
            File(out, "gfxinfo-scroll.txt").writeText(scrolling)
            note("scrolling: " + frameSummary(scrolling))
            val mem = shell("dumpsys meminfo ${ctx.packageName}")
            File(out, "meminfo-pet-page.txt").writeText(mem)
            note("memory: " + memorySummary(mem))
            find(By.desc("Settings"))
        }

        step("large text (200%) on a small phone: the main screens still fit") {
            try {
                // A 360 x 720 dp screen, like a budget Oppo, Realme or Vivo, with the biggest font.
                shell("wm size 720x1440")
                shell("wm density 320")
                shell("settings put system font_scale 2.0")
                Thread.sleep(3_000) // the app redraws for the new size and font
                goHome()
                find(By.text("Chelsea"))
                Thread.sleep(800)
                shot("large-home")
                Thread.sleep(1_500)
                shot("large-pet")
                retrying { find(By.desc("Care")).click() } // the keys show icons only at this size, each spoken by name
                Thread.sleep(900)
                shot("large-care")
                pageDown(); Thread.sleep(900); shot("large-care-2")
                retrying { scrollTo(By.text("+ Add care task")).click() }
                Thread.sleep(1_000)
                shot("large-task-editor")
                pageDown(); Thread.sleep(900)
                shot("large-task-editor-2")
                device.pressBack()
                scrollTo(By.text("+ Add care task"))
                closePanel()
                retrying { find(By.desc("Settings")).click() }
                Thread.sleep(800)
                shot("large-settings")
                pageDown(); Thread.sleep(900)
                shot("large-settings-2")
                device.pressBack()
                find(By.text("Chelsea"))
            } finally {
                shell("settings put system font_scale 1.0")
                shell("wm size reset")
                shell("wm density reset")
                Thread.sleep(3_000)
            }
        }

        step("dark mode: the main screens") {
            try {
                shell("cmd uimode night yes")
                Thread.sleep(3_000)
                goHome()
                find(By.text("Chelsea"))
                Thread.sleep(800)
                shot("dark-home")
                Thread.sleep(1_500)
                shot("dark-pet")
                retrying { find(By.text("Care")).click() }
                Thread.sleep(900)
                shot("dark-care")
                closePanel()
                retrying { find(By.desc("Settings")).click() }
                Thread.sleep(800)
                shot("dark-settings")
                device.pressBack()
                find(By.text("Chelsea"))
            } finally {
                shell("cmd uimode night no")
                Thread.sleep(3_000)
            }
        }

        step("reopening the app keeps the pet") {
            scenario?.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            find(By.text("Chelsea"))
        }

        val failed = log.lines().filter { it.startsWith("FAIL") }
        check(failed.isEmpty()) { "Some steps failed:\n" + failed.joinToString("\n") }
    }

    // ---- helpers ----

    private var shotCount = 0

    /** Runs one step; a failure is recorded (with a screenshot and the screen's structure) and the journey continues. */
    private fun step(name: String, block: () -> Unit) {
        val t0 = System.currentTimeMillis()
        try {
            block()
            log.appendLine("PASS  $name  (${System.currentTimeMillis() - t0} ms)")
        } catch (e: Throwable) {
            val tag = "FAILED-" + name.take(40).replace(Regex("[^A-Za-z0-9]+"), "-")
            runCatching { device.takeScreenshot(File(out, "$tag.png")) }
            runCatching { device.dumpWindowHierarchy(File(out, "$tag.xml")) }
            log.appendLine("FAIL  $name: ${e::class.simpleName}: ${e.message}")
            log.appendLine(e.stackTraceToString().lines().take(12).joinToString("\n") { "      $it" })
        }
    }

    /** Compose redraws can swap the node under us between finding and using it: find it again. */
    private fun retrying(block: () -> Unit) {
        repeat(3) { attempt ->
            try { block(); return } catch (e: StaleObjectException) {
                if (attempt == 2) throw e
                Thread.sleep(500)
            }
        }
    }

    private fun note(msg: String) { log.appendLine("      note: $msg") }

    /** The app's own Back key (the system's navigation bar has one with the same name). */
    private fun appBack(): BySelector = By.pkg(ctx.packageName).desc("Back")

    /**
     * Drops a panel over the room: its Back key when on screen, else the phone's back (the panel may
     * be scrolled past its key). Waits for the screen before it to finish leaving first: a tap on
     * that screen's fading Back key goes nowhere.
     */
    private fun closePanel() {
        Thread.sleep(700)
        if (fresh(appBack()) != null) retrying { find(appBack()).click() } else device.pressBack()
        Thread.sleep(600)
    }

    /** Back to the home screen from wherever an earlier (failed) step left the app. */
    private fun goHome() {
        repeat(8) {
            // Pressed back once too often (the screen was still settling): open the app again.
            if (device.currentPackageName != ctx.packageName) {
                scenario = ActivityScenario.launch(MainActivity::class.java)
                Thread.sleep(1_500)
            }
            // Home is the pet's room: a Settings gear and no Back key (every other screen has one).
            if (device.wait(Until.hasObject(By.desc("Settings")), 1_500) == true && !device.hasObject(appBack())) return
            if (device.hasObject(appBack())) device.findObject(appBack())?.click() else device.pressBack()
            Thread.sleep(600)
        }
    }

    private fun shot(name: String) {
        device.takeScreenshot(File(out, "%02d-%s.png".format(++shotCount, name.substringAfter('-'))))
    }

    private fun find(selector: BySelector, timeoutMs: Long = 15_000): UiObject2 {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            dismissSystemDialogs()
            fresh(selector)?.let { return it }
            if (System.currentTimeMillis() > end) throw AssertionError("not on screen: $selector")
            Thread.sleep(300)
        }
    }

    /**
     * Looks for [selector] in the screen as it is now. The accessibility cache can keep an old copy
     * of a scrolled page (the living pet keeps the app from ever going idle), so it's cleared first.
     */
    private fun fresh(selector: BySelector): UiObject2? {
        if (Build.VERSION.SDK_INT >= 34) runCatching { instr.uiAutomation.clearCache() }
        return device.findObject(selector)
    }

    /** CI emulators sometimes show "<some system app> isn't responding" over the app: wait it out. */
    private fun dismissSystemDialogs() {
        if (device.findObject(By.textContains("isn't responding")) != null) {
            device.findObject(By.text("Wait"))?.click()
            note("dismissed a system 'isn't responding' dialog")
            Thread.sleep(500)
        }
    }

    /**
     * Finds [selector], scrolling the screen down (then back up) to reach it. Scrolls the way a
     * screen reader does (the accessibility scroll action), so it never drags the face-square photo.
     */
    private fun scrollTo(selector: BySelector): UiObject2 = settled(scrollToRaw(selector))

    /** Waits until a scroll has stopped moving [obj] (a tap during the animation lands somewhere else). */
    private fun settled(obj: UiObject2): UiObject2 {
        var last = runCatching { obj.visibleBounds }.getOrNull() ?: return obj
        repeat(12) {
            Thread.sleep(150)
            val now = runCatching { obj.visibleBounds }.getOrNull() ?: return obj
            if (now == last) return obj
            last = now
        }
        return obj
    }

    private fun scrollToRaw(selector: BySelector): UiObject2 {
        runCatching { find(selector, 3_000) }.getOrNull()?.let { return it }
        var moved = 0
        for (forward in listOf(true, false)) {
            // Just after a dialog closes, the active window can still be the dialog's: wait for the
            // app's screen rather than giving up at once. Several failures in a row = the end.
            var stuck = 0
            for (i in 0 until 20) {
                fresh(selector)?.let { return it }
                if (accessibilityScroll(forward)) { stuck = 0; moved++ } else if (++stuck >= 8) break
                Thread.sleep(if (stuck > 0) 600L else 400L)
            }
        }
        // Then UiAutomator's own gestures on the scrolling page (the accessibility scroll sometimes
        // stops early on a pet's page while the pet moves).
        device.findObject(By.scrollable(true))?.let { page ->
            for (direction in listOf(androidx.test.uiautomator.Direction.DOWN, androidx.test.uiautomator.Direction.UP)) {
                runCatching { page.setGestureMargin(device.displayHeight / 8); page.scrollUntil(direction, Until.findObject(selector)) }
                    .getOrNull()?.let { note("found by scrolling the page after $moved accessibility scrolls: $selector"); return it }
            }
        }
        // Last resort: drag the page like a finger, down the middle.
        val x = device.displayWidth / 2
        // (A pet's page with its health section open is many screens long: enough drags to cross it.)
        for (up in listOf(true, false)) {
            repeat(30) {
                fresh(selector)?.let { note("found by dragging after $moved accessibility scrolls: $selector"); return it }
                val (from, to) = if (up) 0.8 to 0.25 else 0.25 to 0.8
                device.swipe(x, (device.displayHeight * from).toInt(), x, (device.displayHeight * to).toInt(), 50)
                Thread.sleep(500)
            }
        }
        val seen = device.findObjects(By.textContains(" ")).mapNotNull { runCatching { it.text }.getOrNull() }.take(12)
        return fresh(selector) ?: throw AssertionError("not found after scrolling ($moved scrolls): $selector; on screen: $seen")
    }

    /**
     * Scrolls the app's screen one page. False when it can't move, or when the active window isn't
     * the app's full screen yet (a dialog still closing).
     */
    /**
     * Scrolls a dialog's own list (which is smaller than the screen, so [accessibilityScroll] leaves
     * it alone) until [selector] is on screen.
     */
    private fun dialogScrollTo(selector: BySelector): UiObject2 {
        repeat(12) { i ->
            fresh(selector)?.let { return settled(it) }
            if (Build.VERSION.SDK_INT >= 34) runCatching { instr.uiAutomation.clearCache() }
            val root = instr.uiAutomation.rootInActiveWindow
            val queue = ArrayDeque(listOfNotNull(root))
            var scrolled = false
            while (queue.isNotEmpty() && !scrolled) {
                val node = queue.removeFirst()
                if (node.isScrollable) scrolled = node.performAction(if (i < 8) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                else for (c in 0 until node.childCount) node.getChild(c)?.let { queue.addLast(it) }
            }
            Thread.sleep(500)
        }
        return find(selector, 3_000)
    }

    private fun accessibilityScroll(forward: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= 34) runCatching { instr.uiAutomation.clearCache() } // see [fresh]
        val root = instr.uiAutomation.rootInActiveWindow ?: return false
        val bounds = Rect().also { root.getBoundsInScreen(it) }
        if (root.packageName != ctx.packageName || bounds.height() < device.displayHeight * 0.8) return false
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) {
                return node.performAction(
                    if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                )
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        return false
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(250)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    /** What the pinned widget shows now (its pet is the first one), from the file it reads. */
    private fun widgetFace(): com.pawpixel.core.WidgetFace? {
        val text = repo.platform.files.readText(com.pawpixel.core.WidgetSnapshot.FILE_NAME) ?: return null
        return com.pawpixel.core.WidgetSnapshot.face(com.pawpixel.core.Json.parse(text), repo.now())
    }

    /**
     * Draws the widget at [widthDp] x [heightDp] the way a launcher does (Glance's RemoteViews applied
     * to real views), on a wallpaper-blue backdrop, saves a screenshot, and returns its texts.
     */
    private fun renderWidget(name: String, widthDp: Int, heightDp: Int, dark: Boolean): List<String> {
        val config = android.content.res.Configuration(ctx.resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
        }
        val themed = ctx.createConfigurationContext(config)
        val remote = runBlocking {
            com.pawpixel.app.widget.PetWidget().compose(
                themed, size = androidx.compose.ui.unit.DpSize(widthDp.dp, heightDp.dp),
                state = androidx.datastore.preferences.core.emptyPreferences(),
            )
        }
        val density = ctx.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val w = (widthDp * density).toInt()
        val h = (heightDp * density).toInt()
        val texts = ArrayList<String>()
        instr.runOnMainSync {
            val frame = android.widget.FrameLayout(themed)
            val view = remote.apply(themed, frame)
            frame.addView(view, android.widget.FrameLayout.LayoutParams(w, h))
            frame.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, w, h)
            val bitmap = android.graphics.Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).apply {
                drawColor(if (dark) 0xFF1B2433.toInt() else 0xFF7FA7D9.toInt())
                translate(pad.toFloat(), pad.toFloat())
                frame.draw(this)
            }
            File(out, "widget-$name${if (dark) "-dark" else ""}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            fun collect(v: android.view.View) {
                if (v is android.widget.TextView && v.visibility == android.view.View.VISIBLE) texts += v.text.toString()
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
            }
            collect(frame)
        }
        note("widget $name${if (dark) " dark" else ""}: $texts")
        return texts
    }

    private fun choosers() = Intents.getIntents().count { it.action == Intent.ACTION_CHOOSER }

    /**
     * Scrolls the app's screen down about half a page with a finger drag, within the app's own
     * window (its size follows `wm size`, unlike the cached display size).
     */
    private fun pageDown() {
        val bounds = Rect().also { r -> instr.uiAutomation.rootInActiveWindow?.getBoundsInScreen(r) }
        if (bounds.height() <= 0) return
        val x = bounds.centerX()
        device.swipe(x, bounds.top + bounds.height() * 3 / 4, x, bounds.top + bounds.height() * 3 / 10, 30)
    }

    /** Runs a shell command as the shell user (settings, wm, dumpsys) and returns its output. */
    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instr.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    /** "120 frames, 3 janky (2.5%), p50 8 ms, p90 12 ms, p99 30 ms" from `dumpsys gfxinfo`. */
    private fun frameSummary(gfx: String): String {
        fun line(prefix: String) = gfx.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(prefix) }?.substringAfter(':')?.trim() ?: "?"
        return "${line("Total frames rendered")} frames, ${line("Janky frames")} janky, p50 ${line("50th percentile")}, " +
            "p90 ${line("90th percentile")}, p99 ${line("99th percentile")}"
    }

    /** Total, Java heap, native heap and graphics from `dumpsys meminfo`'s App Summary (kB). */
    private fun memorySummary(mem: String): String {
        // "Java Heap:   12345   23456" (the summary has colons; the table above it doesn't).
        fun kb(label: String) = mem.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$label:") }
            ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: "?"
        return "total PSS ${kb("TOTAL PSS")} kB, Java heap ${kb("Java Heap")} kB, native heap ${kb("Native Heap")} kB, graphics ${kb("Graphics")} kB"
    }
}
