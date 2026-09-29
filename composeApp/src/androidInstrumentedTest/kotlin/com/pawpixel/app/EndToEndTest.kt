package com.pawpixel.app

import android.Manifest
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

        step("health reminders: add the usual set, record a shot given a month ago") {
            retrying { scrollTo(By.text("+ Add health reminders")).click() }
            find(By.textContains("born?"))
            shot("health-birthday")
            retrying { find(By.text("Adult / not sure")).click() }
            // An adult cat: anti-rabies, FVRCP booster, deworming, tick & flea, check-up.
            waitFor("health tasks added") { repo.state.value.tasksFor(petId).count { it.kind.health } == 5 }
            val vaccine = repo.state.value.tasksFor(petId).first { it.title == "Anti-rabies shot" }
            val row = scrollTo(By.text("💉 Anti-rabies shot"))
            find(By.text("Due today"))
            // The Done button in the same row (the nearest one vertically).
            val done = device.findObjects(By.text("Done")).minByOrNull { kotlin.math.abs(it.visibleBounds.centerY() - row.visibleBounds.centerY()) }
                ?: throw AssertionError("no Done button next to the vaccine")
            retrying { done.click() }
            find(By.text("When was it done?"))
            shot("health-when")
            retrying { find(By.text("A month ago")).click() }
            waitFor("shot recorded") { repo.state.value.completions.any { it.taskId == vaccine.id } }
            scrollTo(By.text("Due in 11 months"))
            Thread.sleep(500)
            shot("health-section")
            // Photo of the vaccination card (the stubbed picker returns the test photo).
            val cardRow = scrollTo(By.text("💉 Anti-rabies shot"))
            val add = device.findObjects(By.text("📷 Add card photo")).minByOrNull { kotlin.math.abs(it.visibleBounds.top - cardRow.visibleBounds.bottom) }
                ?: throw AssertionError("no card button")
            retrying { add.click() }
            waitFor("card saved", 20_000) { repo.card(vaccine) != null }
            check(repo.card(vaccine)!!.let { it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }) { "card isn't a JPEG" }
            retrying { scrollTo(By.text("📷 View card")).click() }
            find(By.text("Anti-rabies shot · Chelsea"))
            shot("health-card")
            retrying { find(By.text("Close")).click() }
            // Rabies rules and local help.
            retrying { scrollTo(By.text("Rabies rules and where to get shots")).click() }
            scrollTo(By.textContains("City Veterinary Office"))
            shot("health-local-help")
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
            device.pressBack(); device.pressBack()
            retrying { find(By.text("Chelsea")).click() }
            scrollTo(By.textStartsWith("Done by Jamaica"))
            Thread.sleep(500)
            shot("family-done-by")
            device.pressBack()
            find(By.text("Chelsea"))
        }

        step("reminder notification with a working Done button") {
            val task = repo.state.value.tasksFor(petId).first { it.kind.name == "WATER" }
            val before = completions()
            ctx.sendBroadcast(
                Intent(ctx, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_SHOW)
                    .putExtra(ReminderReceiver.EXTRA_TASK, task.id)
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
        for (forward in listOf(true, false)) {
            repeat(15) {
                device.findObject(selector)?.let { return it }
                if (!accessibilityScroll(forward)) return@repeat
                Thread.sleep(400)
            }
        }
        return device.findObject(selector) ?: throw AssertionError("not found after scrolling: $selector")
    }

    /** Scrolls the first scrollable container of the app one page. False when it can't move. */
    private fun accessibilityScroll(forward: Boolean): Boolean {
        val root = instr.uiAutomation.rootInActiveWindow ?: return false
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
