import XCTest

/// The app the way an owner uses it, in the iOS Simulator: photo → pixel pet → ears → save →
/// care tasks → Done → add a medicine task → share sheet → home → settings → relaunch.
///
/// Only the photo picker is replaced (DEBUG builds read PAWPIXEL_TEST_PHOTO_B64). Screenshots go
/// to ~/pawpixel-e2e on the Mac running the Simulator, plus the .xcresult bundle. Steps are soft:
/// a failed step is recorded with a screenshot and the journey carries on, then the test fails.
final class OwnerJourneyTests: XCTestCase {
    private let app = XCUIApplication()
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
            for label in ["Allow", "Allow Notifications"] where alert.buttons[label].exists {
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
            field.typeText("Chelsea\n") // the newline closes the keyboard
            try scrollTo("Save Chelsea").tap()
            allowNotificationsIfAsked()
            try find("+ Add care task", timeout: 30)
            sleep(2)
            shot("pet-screen")
        }

        step("Done on a care task") {
            try scrollTo(query: app.buttons.matching(label: "Done").firstMatch, "Done").tap()
            try find("Undo", timeout: 10)
            sleep(1)
            shot("after-done")
        }

        step("add a medicine task") {
            try scrollTo("+ Add care task").tap()
            try find("Times")
            try scrollTo(query: element(containing: "Medicine"), "Medicine chip").tap()
            shot("task-editor")
            try scrollTo("Save").tap()
            try find("+ Add care task")
            try scrollTo(query: element(containing: "Medicine"), "medicine task in the list")
        }

        step("share animation opens the share sheet") {
            try scrollTo("Share animation").tap()
            let sheet = app.otherElements["ActivityListView"]
            let close = app.buttons["Close"]
            let appeared = sheet.waitForExistence(timeout: 60) || close.waitForExistence(timeout: 1)
            shot("share-sheet")
            guard appeared else { throw Failure("share sheet didn't appear") }
            if close.exists { close.tap() } else { app.swipeDown(velocity: .fast) }
            sleep(1)
        }

        step("home lists the pet, settings open") {
            try find("‹ Back").tap()
            try find("PawPixel")
            try find("Chelsea")
            sleep(1)
            shot("home")
            try find("Settings").tap()
            try find("Bedtime")
            shot("settings")
            try find("‹ Back").tap()
        }

        step("relaunch keeps the pet") {
            app.terminate()
            app.launchEnvironment["PAWPIXEL_TEST_RESET"] = "0"
            app.launch()
            try find("Chelsea", timeout: 30)
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
    private func scrollTo(_ label: String) throws -> XCUIElement { try scrollTo(query: element(label), label) }

    /// Returns [e] once it exists. Compose exposes the whole (non-lazy) screen to accessibility, and
    /// tapping an off-screen element makes iOS scroll it into view first; swipes are a fallback.
    @discardableResult
    private func scrollTo(query e: XCUIElement, _ what: String) throws -> XCUIElement {
        if e.waitForExistence(timeout: 5) { return e }
        let scroller = app.scrollViews.firstMatch
        for _ in 0..<8 {
            if e.exists { return e }
            if scroller.exists { scroller.swipeUp(velocity: .slow) } else { app.swipeUp(velocity: .slow) }
        }
        guard e.exists else { throw Failure("not found after scrolling: \(what)") }
        return e
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
