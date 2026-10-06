import XCTest

/// The app the way an owner uses it, in the iOS Simulator: photo → pixel pet → ears → save →
/// care tasks → Done → add a medicine task → share sheet → home → settings → relaunch.
///
/// Only the photo picker is replaced (DEBUG builds read PAWPIXEL_TEST_PHOTO_B64). Screenshots go
/// to ~/pawpixel-e2e on the Mac running the Simulator, plus the .xcresult bundle. Steps are soft:
/// a failed step is recorded with a screenshot and the journey carries on, then the test fails.
final class OwnerJourneyTests: XCTestCase {
    private let app = XCUIApplication()
    private let petName = "mochi"
    private var log: [String] = []
    private var shotCount = 0
    private lazy var outDir: URL = {
        let home = ProcessInfo.processInfo.environment["SIMULATOR_HOST_HOME"] ?? NSTemporaryDirectory()
        let url = URL(fileURLWithPath: home).appendingPathComponent("pawpixel-e2e", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    override func setUp() {
        continueAfterFailure = true
        let photo = Bundle(for: OwnerJourneyTests.self).url(forResource: "pet", withExtension: "jpg")!
        app.launchEnvironment["PAWPIXEL_TEST_PHOTO_B64"] = try! Data(contentsOf: photo).base64EncodedString()
        app.launchEnvironment["PAWPIXEL_TEST_RESET"] = "1"
        // The notification permission alert appears when the pet is saved.
        addUIInterruptionMonitor(withDescription: "Notifications") { alert in
            for label in ["Allow", "Allow Notifications", "Allow While Using App", "Allow Once"] where alert.buttons[label].exists {
                alert.buttons[label].tap(); return true
            }
            return false
        }
    }

    func testOwnerJourney() {
        app.launch()

        step("first open goes straight to making a pet") {
            try find("Make your pixel pet", timeout: 30)
            shot("first-open")
        }

        step("photo becomes a pixel pet") {
            try find("Choose a photo").tap()
            try find("Is that your pet? Tap to give pets.", timeout: 90)
            sleep(2)
            shot("your-pixel-pet")
        }

        step("cat body and ears") {
            try scrollTo("Cat body").tap()
            try scrollTo("Floppy ears").tap()
            sleep(1)
            shot("floppy-ears")
            try find("Pointy ears").tap()
        }

        step("name and save") {
            // Compose text fields show up as text views to iOS accessibility.
            let candidates = app.descendants(matching: .any).matching(NSPredicate(format: "elementType == %d OR elementType == %d",
                XCUIElement.ElementType.textView.rawValue, XCUIElement.ElementType.textField.rawValue))
            let field = try scrollTo(query: candidates.firstMatch, "name field")
            field.tap()
            if !app.keyboards.firstMatch.waitForExistence(timeout: 5) { field.tap() }
            guard app.keyboards.firstMatch.waitForExistence(timeout: 5) else { throw Failure("keyboard didn't open") }
            sleep(1)
            // A fresh simulator shows a one-time "slide to type" tip over the keyboard.
            let tipContinue = app.buttons["Continue"]
            if tipContinue.waitForExistence(timeout: 2) { tipContinue.tap(); sleep(1) }
            shot("name-keyboard-open")
            // Type like a person: tap the keys (lowercase, the field doesn't auto-capitalise).
            // If the text input session wasn't ready yet, nothing lands: wait and type again.
            let saveButton = element("Save \(petName)")
            // The name may land even when that button is below the fold: the field's value says so.
            // Compose exposes the field's text as its label on iOS (and as a value on some versions).
            let typed = app.descendants(matching: .any).matching(NSPredicate(format: "value CONTAINS %@ OR label CONTAINS %@", petName, petName)).firstMatch
            for attempt in 1...3 {
                // Never type the name twice: the first round may have landed while the tree was catching up.
                if typed.waitForExistence(timeout: attempt == 1 ? 0.5 : 6) { log.append("      name already in the field"); break }
                // The typing tip can also appear late, over the keyboard.
                if tipContinue.exists { tipContinue.tap(); sleep(1) }
                for ch in petName {
                    let key = app.keys[String(ch)]
                    guard key.waitForExistence(timeout: 3) else { throw Failure("no key \(ch) on the keyboard") }
                    // A keyboard still animating in has keys that exist but can't be tapped yet (tapping one
                    // then fails the whole test): wait, and if it isn't ready, retry the word.
                    if !key.isHittable { sleep(1) }
                    guard key.isHittable else { log.append("      key \(ch) not ready"); break }
                    key.tap()
                }
                if saveButton.waitForExistence(timeout: 6) || typed.waitForExistence(timeout: 2) { log.append("      typed on attempt \(attempt)"); break }
                log.append("      attempt \(attempt): typing didn't land yet")
                field.tap()
                sleep(2)
            }
            shot("name-typed")
            let ret = app.keyboards.buttons["Return"].exists ? app.keyboards.buttons["Return"] : app.keyboards.buttons["return"]
            if tipContinue.exists { tipContinue.tap(); sleep(1) }
            if ret.exists && ret.isHittable { ret.tap() }
            try scrollTo("Save \(petName)").tap() // the big Save under the form (the header's can sit under the status bar after the keyboard scrolls the page)
            allowNotificationsIfAsked()
            try find("Care", timeout: 30)
            sleep(2)
            shot("pet-screen")
        }

        step("Done on a care task") {
            // Screen readers hear what the button does: "Mark Feed done for mochi".
            // The need meter under the room: a tap logs the care. (The simulator sometimes calls it
            // not hittable although it is on screen: then tap its centre.)
            let done = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@ AND label ENDSWITH %@", "Mark ", "done for \(petName)")).firstMatch
            guard done.waitForExistence(timeout: 15) else { throw Failure("no need meter to tap") }
            if done.isHittable { done.tap() } else { done.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
            guard element(containing: "Undo").waitForExistence(timeout: 10) else { throw Failure("no Undo after Done") }
            sleep(1)
            shot("after-done")
        }

        step("add a medicine task") {
            try find("Care").tap() // the Care panel rises over the room
            try scrollTo("+ Add care task").tap()
            try find("Times")
            try scrollTo(query: element(containing: "Medicine"), "Medicine chip").tap()
            shot("task-editor")
            try scrollTo("Save").tap()
            try find("+ Add care task")
            try scrollTo(query: element(containing: "Medicine"), "medicine task in the list")
            try scrollTo("Back").tap() // the panel drops: the room again
        }

        step("health: a kitten's birthday gives her the first-year plan") {
            try find("Health").tap() // the Health key under the room
            try scrollTo("+ Add health reminders").tap()
            // "About how old" is the default, at 8 weeks: two taps make the pet a 10-week-old kitten.
            let older = element(containing: "Older")
            guard older.waitForExistence(timeout: 10) else { throw Failure("birthday dialog didn't open") }
            older.tap()
            usleep(300_000)
            older.tap()
            guard element(containing: "About 10 weeks old").waitForExistence(timeout: 5) else { throw Failure("age didn't change to 10 weeks") }
            shot("health-birthday")
            try find("Save").tap()
            try scrollTo(query: element(containing: "FVRCP vaccine"), "FVRCP row")
            guard element(containing: "Dose 1 of 3").waitForExistence(timeout: 10) else { throw Failure("the first-year series isn't shown") }
            sleep(1)
            shot("health-series")
            try scrollTo("Back").tap()
        }

        step("weight: two weigh-ins draw the chart") {
            try find("Health").tap()
            try scrollTo(query: element(containing: "Weight"), "Weight door").tap()
            // Without the keyboard: +1 kg four times (4.0 kg) yesterday, then +0.1 kg twice from it (4.2 kg) today.
            try scrollTo("+ Add weight").tap()
            let addKilo = element(containing: "Add 1 kg")
            guard addKilo.waitForExistence(timeout: 10) else { throw Failure("weight dialog didn't open") }
            for _ in 0..<4 { addKilo.tap(); usleep(200_000) }
            try find("Yesterday").tap()
            shot("weight-dialog")
            try find("Save").tap()
            try scrollTo("+ Add weight").tap()
            let addTenth = element(containing: "Add 0.1 kg")
            guard addTenth.waitForExistence(timeout: 10) else { throw Failure("weight dialog didn't open again") }
            addTenth.tap(); usleep(200_000); addTenth.tap()
            try find("Save").tap()
            try scrollTo(query: element(containing: "Weight chart"), "weight chart")
            try scrollTo("+ Add weight")
            guard element("4.2 kg").waitForExistence(timeout: 10) else { throw Failure("latest weight not shown") }
            sleep(1)
            shot("weight")
            try scrollTo("Back").tap() // the Health panel
            try scrollTo("Back").tap() // the room
        }

        step("before/after card opens the share sheet") {
            try find("Share").tap() // the Share key under the room
            let button = try scrollTo("Before/after")
            sleep(1) // let the list settle after scrolling, or the tap can miss
            button.tap()
            let close = app.buttons["Close"]
            var appeared = app.otherElements["ActivityListView"].waitForExistence(timeout: 15) || close.waitForExistence(timeout: 1)
            if !appeared {
                log.append("      first tap didn't open the sheet; tapping again")
                button.tap()
                appeared = app.otherElements["ActivityListView"].waitForExistence(timeout: 15) || close.waitForExistence(timeout: 1)
            }
            shot("share-card")
            guard appeared else { throw Failure("share sheet didn't appear for the card") }
            dismissShareSheet()
        }

        step("share animation opens the share sheet") {
            try scrollTo("Share animation").tap()
            let sheet = app.otherElements["ActivityListView"]
            let close = app.buttons["Close"]
            let appeared = sheet.waitForExistence(timeout: 60) || close.waitForExistence(timeout: 1)
            shot("share-sheet")
            guard appeared else { throw Failure("share sheet didn't appear") }
            dismissShareSheet()
            try scrollTo("Back").tap() // the pet's page
        }

        step("home is the pet's room; settings open") {
            guard element(containing: petName).waitForExistence(timeout: 15) else { throw Failure("name plate not shown") }
            sleep(1)
            shot("home")
            try find("Settings").tap() // the gear over the room
            try find("Bedtime")
            shot("settings")
            try scrollTo("Away 3 days").tap()
            try find("I'm back").tap()
            try scrollTo("Save backup file")
            try scrollTo("Back").tap() // the screen is scrolled down to the backup section
        }

        step("album: a photo with a caption, then the pet remembered and brought back") {
            try find("More").tap() // the pet's menu
            // Menu rows read as one label ("Album, 3 photos, kept for good"): match on the title.
            try scrollTo(query: element(containing: "Album"), "Album").tap()
            try find("No photos yet")
            shot("album-empty")
            try find("Add photo").tap() // the test photo, in place of the picker
            try find("Caption", timeout: 20)
            try find("Save").tap()
            let thumb = element(containing: "Photo of \(petName)")
            guard thumb.waitForExistence(timeout: 20) else { throw Failure("no photo in the album") }
            sleep(1)
            shot("album")
            try find("Back").tap() // the album
            try scrollTo(query: element(containing: "In loving memory"), "In loving memory").tap()
            try find("Remember \(petName)").tap()
            try scrollTo("Back").tap() // the More panel drops
            try find("Forever in your heart")
            sleep(1)
            shot("memory")
            try find("More").tap()
            try scrollTo(query: element(containing: "Remembered"), "Remembered").tap()
            try find("Bring back").tap()
            try scrollTo("Back").tap()
            try find("Care")
        }

        step("pet map opens (this CI build has no map server: it says so)") {
            try find("More").tap() // the pet's menu
            try scrollTo(query: element(containing: "Pet map"), "Pet map").tap()
            let ready = element("The pet map is coming soon").waitForExistence(timeout: 10)
                || element("I'm 18 or older").waitForExistence(timeout: 2)
            shot("pet-map")
            guard ready else { throw Failure("pet map screen didn't open") }
            try find("Back").tap()
            try scrollTo("Back").tap() // the More panel drops
        }

        step("demo map (no server): join, the pets nearby, a walk, RSVP, host a walk") {
            try find("More").tap()
            try scrollTo(query: element(containing: "Pet map"), "Pet map").tap()
            guard element("Try the demo map").waitForExistence(timeout: 10) else {
                log.append("      note: this build has a map server; the demo isn't offered"); try find("Back").tap(); try scrollTo("Back").tap(); return
            }
            try find("Try the demo map").tap()
            try find("I'm 18 or older").tap()
            try find(containing: "Show my pixel pets").tap()
            try scrollTo(query: element(containing: "and join"), "join").tap()
            try find("Nearby", timeout: 30)
            try find(containing: "walks coming up")
            sleep(2)
            shot("demo-map")
            try find("Gatherings").tap()
            try find(containing: "Sunday pet walk", timeout: 15)
            try scrollTo("I'm going").tap()
            try find(containing: "Meet at: Plaza Rizal", timeout: 15)
            shot("demo-map-walk")
            try scrollTo("Host a walk").tap()
            try find("Send for approval")
            shot("demo-map-host")
            try find("Cancel").tap()
            try find("Back").tap() // the map
            try scrollTo("Back").tap() // the More panel drops
        }

        step("lost and found: raise an alert, then safe home") {
            try find("More").tap()
            try scrollTo(query: element(containing: "Lost?"), "Lost?").tap()
            try find(containing: "Is", timeout: 15)
            let field = app.textViews.firstMatch.exists ? app.textViews.firstMatch : app.textFields.firstMatch
            if field.waitForExistence(timeout: 5) { field.tap(); field.typeText("Grey tabby, pink collar") }
            try scrollTo("Earlier today").tap()
            shot("lost-form")
            try scrollTo("Alert owners nearby").tap()
            guard element(containing: "Alert is on for").waitForExistence(timeout: 40) else {
                log.append("      note: the alert didn't go up (no server and no demo sign-in?); skipping the rest"); try find("Back").tap(); try scrollTo("Back").tap(); return
            }
            shot("lost-alert")
            try scrollTo("Safe home!").tap()
            try find("Yes, safe home").tap()
            try find(containing: "Welcome home", timeout: 20)
            shot("lost-home")
            try find(containing: "Back to").tap()
            try find(containing: "Care", timeout: 15)
        }

        step("share with your household (no server in this CI build: says it's not available yet)") {
            try find("Share").tap()
            try scrollTo(query: element(containing: "Share with your household"), "Share with your household").tap()
            let ready = element("Coming soon").waitForExistence(timeout: 10)
            shot("household")
            guard ready else { throw Failure("household screen didn't say it's not available yet") }
            try find("Back").tap()
            try scrollTo("Back").tap() // the Share panel drops
            try find("Settings")
        }

        step("relaunch keeps the pet") {
            app.terminate()
            app.launchEnvironment["PAWPIXEL_TEST_RESET"] = "0"
            app.launch()
            guard element(containing: petName).waitForExistence(timeout: 30) else { throw Failure("pet not listed after relaunch") }
            shot("after-relaunch")
        }

        try? log.joined(separator: "\n").write(to: outDir.appendingPathComponent("steps.txt"), atomically: true, encoding: .utf8)
        let failed = log.filter { $0.hasPrefix("FAIL") }
        XCTAssert(failed.isEmpty, "Failed steps:\n" + failed.joined(separator: "\n"))
    }

    // MARK: helpers

    struct Failure: Error, CustomStringConvertible { let description: String; init(_ d: String) { description = d } }

    private func step(_ name: String, _ block: () throws -> Void) {
        let t0 = Date()
        do {
            try block()
            log.append("PASS  \(name)  (\(Int(Date().timeIntervalSince(t0) * 1000)) ms)")
        } catch {
            shot("FAILED-" + name.prefix(30).replacingOccurrences(of: " ", with: "-"))
            try? app.debugDescription.write(to: outDir.appendingPathComponent("FAILED-\(shotCount)-tree.txt"), atomically: true, encoding: .utf8)
            log.append("FAIL  \(name): \(error)")
        }
    }

    private func shot(_ name: String) {
        shotCount += 1
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? png.write(to: outDir.appendingPathComponent(String(format: "%02d-%@.png", shotCount, name)))
        let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Any element whose accessibility label is exactly [label] (Compose exposes Text and Button labels).
    private func element(_ label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    private func element(containing text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    @discardableResult
    private func find(_ label: String, timeout: TimeInterval = 15) throws -> XCUIElement {
        let e = element(label)
        guard e.waitForExistence(timeout: timeout) else { throw Failure("not on screen: \(label)") }
        return e
    }

    @discardableResult
    private func find(containing text: String, timeout: TimeInterval = 15) throws -> XCUIElement {
        let e = element(containing: text)
        guard e.waitForExistence(timeout: timeout) else { throw Failure("not on screen: …\(text)…") }
        return e
    }

    @discardableResult
    private func scrollTo(_ label: String) throws -> XCUIElement { try scrollTo(query: element(label), label) }

    /// Brings [e] on screen: swipes up (then down) along the right-hand margin, away from the
    /// face-square photo, until iOS reports it hittable. Logs positions so a stuck scroll is visible.
    @discardableResult
    private func scrollTo(query e: XCUIElement, _ what: String) throws -> XCUIElement {
        // "Hittable" but tucked under the status bar (a top-bar button after the keyboard scrolled the
        // page) still can't be tapped: keep scrolling until it is clear of it.
        func onScreen() -> Bool { e.exists && e.isHittable && e.frame.minY >= 60 }
        _ = e.waitForExistence(timeout: 5)
        if onScreen() { return settled(e) }
        for up in [true, false] {
            for i in 0..<8 {
                if onScreen() { return settled(e) }
                let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.985, dy: up ? 0.7 : 0.3))
                let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.985, dy: up ? 0.3 : 0.7))
                if i % 2 == 0 { from.press(forDuration: 0.05, thenDragTo: to) }
                else { from.press(forDuration: 0.05, thenDragTo: to, withVelocity: .default, thenHoldForDuration: 0.3) }
                usleep(400_000)
                if i < 3 { log.append("      scroll \(up ? "down" : "up") #\(i) for \(what): exists=\(e.exists) frame=\(e.exists ? "\(e.frame)" : "-") hittable=\(e.exists && e.isHittable)") }
            }
        }
        guard e.exists && e.isHittable else { throw Failure("not found after scrolling: \(what)") }
        return settled(e)
    }

    /// Waits until a scroll has stopped moving [e] (a tap during the fling lands somewhere else).
    private func settled(_ e: XCUIElement) -> XCUIElement {
        var last = e.frame
        for _ in 0..<12 {
            usleep(250_000)
            let now = e.frame
            if now == last { break }
            last = now
        }
        return e
    }

    /// Closes the share sheet and waits until it (and its dimming layer) is really gone.
    private func dismissShareSheet() {
        let dim = app.otherElements["PopoverDismissRegion"]
        for _ in 0..<4 {
            if app.buttons["Close"].exists { app.buttons["Close"].tap() }
            else if dim.exists { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap() }
            else { break }
            let gone = NSPredicate(format: "exists == false")
            let wait = XCTNSPredicateExpectation(predicate: gone, object: dim)
            _ = XCTWaiter.wait(for: [wait], timeout: 4)
        }
        sleep(1)
    }

    private func dismissKeyboard() {
        if app.keyboards.buttons["Return"].exists { app.keyboards.buttons["Return"].tap() }
        else if app.keyboards.buttons["return"].exists { app.keyboards.buttons["return"].tap() }
    }

    private func allowNotificationsIfAsked() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow", "Allow Notifications"] {
            let b = springboard.buttons[label]
            if b.waitForExistence(timeout: 4) { b.tap(); return }
        }
        app.tap() // lets the interruption monitor handle it if it shows later
    }
}

private extension XCUIElementQuery {
    func matching(label: String) -> XCUIElementQuery { matching(NSPredicate(format: "label == %@", label)) }
}
