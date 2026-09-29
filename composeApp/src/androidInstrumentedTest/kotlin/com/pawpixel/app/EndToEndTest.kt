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
            find(By.text("Care"), 30_000)
            check(repo.state.value.pets.singleOrNull()?.name == "Chelsea") { "pet not saved: ${repo.state.value.pets}" }
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
            retrying { scrollTo(By.text("Done")).click() }
            waitFor("completion recorded") { completions() == before + 1 }
            find(By.text("Undo"))
            Thread.sleep(1_200) // the eating reaction
            shot("06-after-done")
        }

        step("add a medicine task") {
            retrying { scrollTo(By.text("+ Add care task")).click() }
            find(By.text("Times"))
            retrying { scrollTo(By.textContains("Medicine")).click() }
            shot("07-task-editor")
            retrying { scrollTo(By.text("Save")).click() }
            waitFor("medicine task saved") { repo.state.value.tasksFor(petId).any { it.kind.name == "MEDS" } }
            find(By.text("Care"))
        }

        /** The Done button in the same card as [row] (the nearest one vertically). */
        fun doneNextTo(row: UiObject2): UiObject2 =
            device.findObjects(By.text("Done")).minByOrNull { kotlin.math.abs(it.visibleBounds.centerY() - row.visibleBounds.centerY()) }
                ?: throw AssertionError("no Done button next to ${row.text}")

        step("health: a kitten's birthday gives her the first-year plan") {
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
            scrollTo(By.text("10 weeks old")) // her age, under her name
            scrollTo(By.text("💉 FVRCP vaccine"))
            find(By.textStartsWith("Dose 1 of 3"))
            find(By.textStartsWith("Typical schedule"))
            Thread.sleep(500)
            shot("health-series")
        }

        step("health: record a dose with a photo of the vaccination card") {
            val fvrcp = repo.state.value.tasksFor(petId).first { it.title == "FVRCP vaccine" }
            retrying { doneNextTo(scrollTo(By.text("💉 FVRCP vaccine"))).click() }
            find(By.text("When was it done?"))
            // The stubbed photo picker returns the test photo.
            retrying { find(By.text("📷 Add photo")).click() }
            find(By.text("Photo added"), 20_000)
            shot("health-record")
            retrying { find(By.text("Save")).click() }
            waitFor("dose recorded") { repo.state.value.completions.any { it.taskId == fvrcp.id } }
            val record = repo.state.value.completions.last { it.taskId == fvrcp.id }
            waitFor("photo saved with it", 20_000) { repo.recordPhoto(petId, record.id) != null }
            check(repo.recordPhoto(petId, record.id)!!.let { it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }) { "record photo isn't a JPEG" }
            scrollTo(By.text("💉 FVRCP vaccine"))
            find(By.textStartsWith("Dose 2 of 3"))
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
            retrying { doneNextTo(scrollTo(By.text("💉 Anti-rabies shot"))).click() }
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
        }

        step("weight: two weigh-ins draw the chart; the list opens") {
            val today = repo.clock.dayIndex(repo.now())
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
            scrollTo(By.text("+ Add weight"))
            find(By.text("4.2 kg"))
            Thread.sleep(500)
            shot("weight")
            retrying { scrollTo(By.text("All weigh-ins (2)")).click() }
            scrollTo(By.text("Hide weigh-ins"))
            shot("weight-list")
            retrying { find(By.text("Hide weigh-ins")).click() }
        }

        step("milestone: 7 days of care is celebrated and shareable") {
            // A week of care, as if logged over the past days.
            val today = repo.clock.dayIndex(repo.now())
            runBlocking { repo.update { s -> com.pawpixel.core.StateOps.markCareDays(s, petId, (today - 6..today).toList()) } }
            val before = choosers()
            retrying { scrollTo(By.text("Share the card")).click() }
            waitFor("milestone share sheet") { choosers() == before + 1 }
            find(By.textStartsWith("🎉 7 days of care"))
            shot("milestone")
            retrying { find(By.text("Nice!")).click() }
            waitFor("celebrated once") { repo.state.value.pet(petId)?.milestoneSeen == 7 }
        }

        step("outfits: a week of care earns a bandana, and it shows on the pet") {
            retrying { scrollTo(By.text("Bandana")).click() }
            waitFor("wearing it") { repo.state.value.pet(petId)?.accessory == "BANDANA" }
            scrollTo(By.textStartsWith("🔒 Crown"))
            shot("outfits")
            retrying { scrollTo(By.text("None")).click() }
            waitFor("took it off") { repo.state.value.pet(petId)?.accessory == null }
        }

        step("share animation and before/after card open the share sheet") {
            val before = choosers()
            retrying { scrollTo(By.text("Share animation")).click() }
            waitFor("GIF share sheet", 60_000) { choosers() == before + 1 }
            retrying { scrollTo(By.text("Before/after")).click() }
            waitFor("card share sheet") { choosers() == before + 2 }
            shot("08-share-section")
        }

        step("home screen lists the pet") {
            device.pressBack()
            find(By.text("PawPixel"))
            find(By.text("Chelsea"))
            Thread.sleep(800)
            shot("09-home")
        }

        step("settings open and close") {
            retrying { find(By.text("Settings")).click() }
            find(By.text("Bedtime"))
            shot("10-settings")
            device.pressBack()
            find(By.text("Chelsea"))
        }

        step("away mode pauses care and comes back") {
            retrying { find(By.text("Settings")).click() }
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
            retrying { find(By.text("Settings")).click() }
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

            retrying { find(By.text("More")).click() }
            retrying { find(By.text("Leave the map")).click() }
            retrying { find(By.text("Leave")).click() }
            find(By.text("I'm 18 or older"), 20_000)
            check(!runBlocking { repo.map.client.hasJoined() }) { "still on the map after leaving" }
            device.pressBack()
            find(By.text("Chelsea"))
        }

        step("family sharing: start a family, a partner joins, her Done shows on this phone") {
            if (!repo.map.settings.isConfigured) { note("server not set up in this build; skipped"); return@step }
            retrying { find(By.text("Settings")).click() }
            retrying { scrollTo(By.text("Open family sharing")).click() }
            if (device.hasObject(By.text(repo.map.signInLabel))) retrying { find(By.text(repo.map.signInLabel)).click() }
            val nameField = find(By.clazz("android.widget.EditText"), 20_000)
            retrying { nameField.click() }
            retrying { find(By.clazz("android.widget.EditText")).text = "Save" }
            device.pressBack()
            retrying { scrollTo(By.text("Start a family")).click() }
            val code = find(By.text(Pattern.compile("[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}")), 20_000).text
            shot("family-invite")
            // Jamaica, on her own phone (here: the app's own client, signed in as the seeded partner).
            val partnerApi = com.pawpixel.map.SupabaseApi(repo.map.settings, repo.platform.http, object : com.pawpixel.map.SessionStore {
                var s: String? = null; override fun load() = s; override fun save(json: String?) { s = json }
            }, repo.platform::nowMs)
            val partner = com.pawpixel.map.HouseholdClient(partnerApi)
            val hid = runBlocking { partnerApi.signInWithPassword("partner@test.pawpixel", "partner-pass-123"); partner.join(code, "Jamaica") }
            // Share Chelsea.
            retrying { scrollTo(By.checkable(true)).click() }
            waitFor("Chelsea reaches the family", 30_000) { runBlocking { partner.pull(hid) }.pets.any { it.name == "Chelsea" } }
            val shared = runBlocking { partner.pull(hid) }
            // Litter, not water: the reminder step after this one uses the water task.
            val litter = shared.tasks.first { it.kind.name == "LITTER" }
            check(shared.pets.single().lookCode != null) { "no pixel look shared" }
            // Jamaica cleans the litter.
            val now = repo.now()
            runBlocking {
                partner.push(hid, com.pawpixel.core.SyncPush(addCompletions = listOf(com.pawpixel.core.Completion(
                    litter.id, now, repo.clock.minuteOfDay(now), repo.clock.dayIndex(now), id = "partnerlitter1"))))
            }
            retrying { find(By.text("Sync now")).click() }
            waitFor("her Done arrives", 30_000) { repo.state.value.completions.any { it.id == "partnerlitter1" && it.by == partnerApi.userId } }
            find(By.textContains("Jamaica"))
            shot("family-members")
            // Back to Home (wait for it: the family screen also lists "Chelsea"), then her page.
            goHome()
            find(By.text("Pet map"))
            retrying { find(By.text("Chelsea")).click() }
            scrollTo(By.text("+ Add care task")) // her page is open
            val mine = repo.state.value
            val today = repo.clock.dayIndex(repo.now())
            note("litter records: " + mine.completions.filter { it.taskId == litter.id }.joinToString { "${it.id} by=${it.by} day=${it.localDay}" } +
                " today=$today me=${repo.family.myUserId} name=${repo.family.nameOf(partnerApi.userId)}")
            scrollTo(By.textStartsWith("Done by Jamaica"))
            Thread.sleep(500)
            shot("family-done-by")
            device.pressBack()
            find(By.text("Chelsea"))
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
            // The home screen offers the widget until one is added.
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

    /** Back to the home screen from wherever an earlier (failed) step left the app. */
    private fun goHome() {
        repeat(6) {
            // Pressed back once too often (the screen was still settling): open the app again.
            if (device.currentPackageName != ctx.packageName) {
                scenario = ActivityScenario.launch(MainActivity::class.java)
                Thread.sleep(1_500)
            }
            if (device.wait(Until.hasObject(By.text("Settings")), 1_500) == true && device.hasObject(By.text("PawPixel"))) return
            device.pressBack(); Thread.sleep(600)
        }
    }

    private fun shot(name: String) {
        device.takeScreenshot(File(out, "%02d-%s.png".format(++shotCount, name.substringAfter('-'))))
    }

    private fun find(selector: BySelector, timeoutMs: Long = 15_000): UiObject2 {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            dismissSystemDialogs()
            device.findObject(selector)?.let { return it }
            if (System.currentTimeMillis() > end) throw AssertionError("not on screen: $selector")
            Thread.sleep(300)
        }
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
    private fun scrollTo(selector: BySelector): UiObject2 {
        runCatching { find(selector, 3_000) }.getOrNull()?.let { return it }
        var moved = 0
        for (forward in listOf(true, false)) {
            // Just after a dialog closes, the active window can still be the dialog's: wait for the
            // app's screen rather than giving up at once. Several failures in a row = the end.
            var stuck = 0
            for (i in 0 until 20) {
                device.findObject(selector)?.let { return it }
                if (accessibilityScroll(forward)) { stuck = 0; moved++ } else if (++stuck >= 8) break
                Thread.sleep(if (stuck > 0) 600L else 400L)
            }
        }
        // Last resort: drag the page like a finger, down the middle.
        val x = device.displayWidth / 2
        for (up in listOf(true, false)) {
            repeat(12) {
                device.findObject(selector)?.let { note("found by dragging after $moved accessibility scrolls: $selector"); return it }
                val (from, to) = if (up) 0.75 to 0.35 else 0.35 to 0.75
                device.swipe(x, (device.displayHeight * from).toInt(), x, (device.displayHeight * to).toInt(), 25)
                Thread.sleep(500)
            }
        }
        val seen = device.findObjects(By.textContains(" ")).mapNotNull { runCatching { it.text }.getOrNull() }.take(12)
        return device.findObject(selector) ?: throw AssertionError("not found after scrolling ($moved scrolls): $selector; on screen: $seen")
    }

    /**
     * Scrolls the app's screen one page. False when it can't move, or when the active window isn't
     * the app's full screen yet (a dialog still closing).
     */
    private fun accessibilityScroll(forward: Boolean): Boolean {
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

    private fun choosers() = Intents.getIntents().count { it.action == Intent.ACTION_CHOOSER }
}
