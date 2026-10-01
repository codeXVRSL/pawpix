import XCTest
import SwiftUI
import WidgetKit

/// The widget extension can't be placed on a Home Screen by a UI test, so this checks its Swift reader
/// of widget.json against the same file the Kotlin tests use (core WidgetFixtureTest), and renders every
/// widget family to images in ~/pawpixel-e2e/widgets (published with the CI results) to look at.
final class WidgetRenderTests: XCTestCase {
    private let manila = TimeZone(identifier: "Asia/Manila")!
    /// 6:00 AM in Manila on the fixture's day, when it was written.
    private let t0 = 1_779_832_800_000.0

    private func at(_ hours: Double) -> Date { Date(timeIntervalSince1970: (t0 + hours * 3_600_000) / 1000) }

    private func fixture() throws -> Snapshot {
        let url = try XCTUnwrap(Bundle(for: WidgetRenderTests.self).url(forResource: "widget-fixture", withExtension: "json"))
        return try JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: url))
    }

    func testReaderMatchesKotlin() throws {
        let snap = try fixture()
        XCTAssertEqual(snap.generatedAt, t0)
        // 9 AM: breakfast is two hours late.
        let nine = try XCTUnwrap(snap.face(at: at(3), choice: nil, taps: [], timeZone: manila))
        XCTAssertEqual(nine.petId, "mochi")
        XCTAssertEqual(nine.mood, "hungry")
        XCTAssertEqual(nine.actionTaskId, "feed")
        XCTAssertEqual(nine.actionEmoji, "🍖")
        XCTAssertEqual(nine.actionTitle, "Feed")
        // Done on the widget at 9:10: happy at once, no button; dinner's button comes back at 4 PM.
        let taps = [PendingTap(taskId: "feed", at: t0 + 3.2 * 3_600_000)]
        let after = try XCTUnwrap(snap.face(at: at(3.25), choice: nil, taps: taps, timeZone: manila))
        XCTAssertEqual(after.mood, "happy")
        XCTAssertEqual(after.caption, "Mochi is happy!")
        XCTAssertNil(after.actionTaskId)
        XCTAssertEqual(snap.face(at: at(10.5), choice: nil, taps: taps, timeZone: manila)?.actionTaskId, "feed")
        // Which pet: chosen, most in need, or the first when the chosen one is gone.
        XCTAssertEqual(snap.face(at: at(3), choice: "kiko", taps: [], timeZone: manila)?.petId, "kiko")
        XCTAssertEqual(snap.face(at: at(3), choice: "gone", taps: [], timeZone: manila)?.petId, "mochi")
        XCTAssertEqual(snap.face(at: at(3), choice: Snapshot.mostInNeed, taps: [], timeZone: manila)?.petId, "mochi")
        XCTAssertEqual(snap.face(at: at(5), choice: Snapshot.mostInNeed, taps: taps, timeZone: manila)?.petId, "kiko")
        // Next care, and care times that follow the owner to Tokyo.
        let early = try XCTUnwrap(snap.face(at: at(0.5), choice: nil, taps: [], timeZone: manila))
        XCTAssertEqual(early.nextAt, at(1))
        XCTAssertEqual(early.nextTitle, "Feed")
        let tokyo = TimeZone(identifier: "Asia/Tokyo")!
        XCTAssertEqual(snap.face(at: at(0.8333), choice: nil, taps: [], timeZone: tokyo)?.mood, "hungry")
        XCTAssertEqual(snap.face(at: at(0.8333), choice: nil, taps: [], timeZone: manila)?.mood, "content")
        // Timeline entries: every change for two days, soonest first.
        let dates = snap.changeDates(after: at(0), taps: [])
        XCTAssertEqual(dates.first, at(1)) // 7 AM: breakfast's "next" is past
        XCTAssertGreaterThan(dates.last ?? .distantPast, at(40))
    }

    @MainActor
    func testRenderEveryFamily() throws {
        let snap = try fixture()
        let dir = outDir()
        func entry(_ hours: Double, taps: [PendingTap] = []) -> PetEntry {
            let face = snap.face(at: at(hours), choice: nil, taps: taps, timeZone: manila)
            var cal = Calendar.current
            cal.timeZone = manila
            let sky = SkyPhase.at(at(hours), nightStart: snap.nightStart, nightEnd: snap.nightEnd, calendar: cal)
            return PetEntry(date: at(hours), face: face, image: WidgetImages.sample, labels: snap.labels ?? [:], sky: sky)
        }
        let hungry = entry(3)
        let fine = entry(0.5)
        let night = entry(17) // 11 PM: asleep under the stars
        let empty = PetEntry.empty(snap.labels)
        XCTAssertEqual(night.sky, .night)
        XCTAssertEqual(hungry.sky, .day)
        // Point sizes on a 6.7" iPhone.
        let home: [(String, WidgetFamily, CGSize)] = [
            ("small", .systemSmall, CGSize(width: 170, height: 170)),
            ("medium", .systemMedium, CGSize(width: 364, height: 170)),
            ("large", .systemLarge, CGSize(width: 364, height: 382)),
        ]
        let lock: [(String, WidgetFamily, CGSize)] = [
            ("circular", .accessoryCircular, CGSize(width: 76, height: 76)),
            ("rectangular", .accessoryRectangular, CGSize(width: 172, height: 76)),
            ("inline", .accessoryInline, CGSize(width: 257, height: 26)),
        ]
        var rendered = 0
        for (name, family, size) in home {
            for (state, e) in [("hungry", hungry), ("fine", fine), ("night", night), ("empty", empty)] {
                for scheme in [ColorScheme.light, .dark] {
                    let view = PetWidgetView(entry: e, family: family)
                        .padding(16)
                        .frame(width: size.width, height: size.height)
                        .background {
                            Group { if let sky = e.sky { SkyBackground(phase: sky) } else { Palette.paper } }
                                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                        }
                        .environment(\.colorScheme, scheme)
                    rendered += save(view, dir, "home-\(name)-\(state)-\(scheme == .dark ? "dark" : "light")")
                }
            }
        }
        // StandBy (a small widget on black, no background of its own).
        let standBy = PetWidgetView(entry: hungry, family: .systemSmall, showsBackground: false)
            .padding(16).frame(width: 170, height: 170).background(.black).environment(\.colorScheme, .dark)
        rendered += save(standBy, dir, "standby-small-hungry")
        for (name, family, size) in lock {
            for (state, e) in [("hungry", hungry), ("fine", fine)] {
                let view = PetWidgetView(entry: e, family: family, showsBackground: false)
                    .frame(width: size.width, height: size.height)
                    .foregroundStyle(.white)
                    .background(Color(white: 0.25))
                    .environment(\.colorScheme, .dark)
                rendered += save(view, dir, "lock-\(name)-\(state)")
            }
        }
        XCTAssertEqual(rendered, home.count * 6 + 1 + lock.count * 2)
    }

    @MainActor
    private func save(_ view: some View, _ dir: URL, _ name: String) -> Int {
        let renderer = ImageRenderer(content: view)
        renderer.scale = 3
        guard let png = renderer.uiImage?.pngData() else { XCTFail("couldn't render \(name)"); return 0 }
        try? png.write(to: dir.appendingPathComponent("widget-\(name).png"))
        return 1
    }

    private func outDir() -> URL {
        let home = ProcessInfo.processInfo.environment["SIMULATOR_HOST_HOME"] ?? NSTemporaryDirectory()
        let url = URL(fileURLWithPath: home).appendingPathComponent("pawpixel-e2e/widgets", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
