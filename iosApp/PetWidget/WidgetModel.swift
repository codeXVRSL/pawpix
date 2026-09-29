import Foundation
import AppIntents
import UIKit

private let appGroup = "group.com.pawpixel.app"

private func sharedDir() -> URL? {
    FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
        .appendingPathComponent("pawpixel", isDirectory: true)
}

// MARK: - widget.json (written by the Kotlin app; see core/WidgetSnapshot.kt, whose `face` this mirrors)

struct Snapshot: Decodable {
    let version: Int
    let generatedAt: Double
    /// The phone's offset from UTC when the file was written (ms), so care times follow a time zone change.
    let utcOffsetMs: Double?
    let pets: [SnapshotPet]
    /// The widget's own words in the owner's language (Done, the empty message, "Next: ...").
    let labels: [String: String]?
}

/// A pet's timeline, Done buttons and next care; each list is change points (the last one at or before now applies).
struct Track: Decodable {
    let timeline: [MoodPoint]
    let actions: [ActionPoint]
    let next: [NextPoint]
}

struct SnapshotPet: Decodable {
    let id: String
    let name: String
    let sprites: [String: String]
    /// "Chelsea is happy!", shown for a while after a Done tap.
    let happy: String?
    let timeline: [MoodPoint]
    let actions: [ActionPoint]
    let next: [NextPoint]

    var track: Track { Track(timeline: timeline, actions: actions, next: next) }
}

struct MoodPoint: Decodable {
    let at: Double      // epoch ms
    let mood: String
    let caption: String
}

/// The one-tap Done from `at` (none if `taskId` is nil), and how things go if it's tapped.
struct ActionPoint: Decodable {
    let at: Double
    let taskId: String?
    let label: String?
    let ifDone: Track?
}

struct NextPoint: Decodable {
    let at: Double
    let taskId: String?
    let dueAt: Double?
    let emoji: String?
    let title: String?
}

struct PendingTap: Codable {
    let taskId: String
    let at: Double
}

/// What a widget shows at one moment.
struct PetFace {
    let petId: String
    let name: String
    let mood: String
    let caption: String
    let sprite: String?
    let actionTaskId: String?
    let actionLabel: String?
    let nextEmoji: String?
    let nextTitle: String?
    let nextAt: Date?
}

extension Snapshot {
    /// A widget's pet choice: whichever pet needs the owner most right now.
    static let mostInNeed = "*"
    private static let priority = ["sad", "meds", "hungry", "restless", "content", "happy", "sleepy"]
    private static let recentCareMs = 90.0 * 60 * 1000

    /// What to show at `date`: the chosen pet (nil = the first), with the widget's own Done taps
    /// the app hasn't seen yet. Nil when there are no pets.
    func face(at date: Date, choice: String?, taps: [PendingTap], timeZone: TimeZone = .current) -> PetFace? {
        guard !pets.isEmpty else { return nil }
        let nowOffset = Double(timeZone.secondsFromGMT(for: date)) * 1000
        let shift = (utcOffsetMs ?? nowOffset) - nowOffset
        let now = date.timeIntervalSince1970 * 1000 - shift
        let fresh = taps.filter { $0.at >= generatedAt }.map { PendingTap(taskId: $0.taskId, at: $0.at - shift) }
        let faces = pets.map { face(of: $0, now: now, taps: fresh, shift: shift) }
        switch choice {
        case .some(Self.mostInNeed):
            return faces.min { (Self.priority.firstIndex(of: $0.mood) ?? 9) < (Self.priority.firstIndex(of: $1.mood) ?? 9) }
        case let id?:
            return faces.first { $0.petId == id } ?? faces[0]
        case nil:
            return faces[0]
        }
    }

    private func face(of pet: SnapshotPet, now: Double, taps: [PendingTap], shift: Double) -> PetFace {
        // Follow Done taps: a tapped button switches to its "if done" track (one level is precomputed;
        // a further tap before the app runs just hides its button).
        var track = pet.track
        var unused = taps.filter { $0.at <= now }
        var hidden: [Double] = []   // `at` of tapped buttons in the current track
        var lastTap: Double?
        while let hit = Self.tappedButton(track.actions, unused) {
            let (index, tapIndex) = hit
            let button = track.actions[index]
            lastTap = max(lastTap ?? unused[tapIndex].at, unused[tapIndex].at)
            unused.remove(at: tapIndex)
            guard let deeper = button.ifDone else { hidden.append(button.at); break }
            track = deeper
            hidden = []
        }
        let point = Self.current(track.timeline, now)
        let action = Self.current(track.actions, now).flatMap { $0.taskId != nil && !hidden.contains($0.at) ? $0 : nil }
        let next = Self.current(track.next, now).flatMap { ($0.dueAt ?? 0) > now ? $0 : nil }
        var mood = point?.mood ?? "content"
        var caption = point?.caption ?? ""
        // "If done" assumed the tap came when the button appeared; the pet is happy for a while after the real one.
        if let lastTap, mood == "content", now - lastTap <= Self.recentCareMs {
            mood = "happy"
            caption = pet.happy ?? caption
        }
        return PetFace(
            petId: pet.id, name: pet.name, mood: mood, caption: caption, sprite: pet.sprites[mood],
            actionTaskId: action?.taskId, actionLabel: action?.label,
            nextEmoji: next?.emoji, nextTitle: next?.title,
            nextAt: next?.dueAt.map { Date(timeIntervalSince1970: ($0 + shift) / 1000) })
    }

    /// The earliest Done button a tap was made on while it showed: (button index, tap index).
    private static func tappedButton(_ buttons: [ActionPoint], _ taps: [PendingTap]) -> (Int, Int)? {
        for (i, b) in buttons.enumerated() {
            guard let id = b.taskId else { continue }
            let until = i + 1 < buttons.count ? buttons[i + 1].at : .infinity
            if let t = taps.firstIndex(where: { $0.taskId == id && $0.at >= b.at && $0.at < until }) { return (i, t) }
        }
        return nil
    }

    private static func current<T>(_ points: [T], _ now: Double) -> T? where T: TimedPoint {
        points.last { $0.at <= now } ?? points.first
    }

    /// Every moment anything shown may change (with or without taps), for WidgetKit timeline entries.
    func changeDates(after date: Date, taps: [PendingTap]) -> [Date] {
        func times(_ t: Track) -> [Double] {
            t.timeline.map(\.at) + t.next.map(\.at) + t.actions.flatMap { a in [a.at] + (a.ifDone.map(times) ?? []) }
        }
        let now = date.timeIntervalSince1970 * 1000
        let all = Set(pets.flatMap { times($0.track) } + taps.map { $0.at + Self.recentCareMs }).filter { $0 > now }
        return all.sorted().map { Date(timeIntervalSince1970: $0 / 1000) }
    }
}

protocol TimedPoint { var at: Double { get } }
extension MoodPoint: TimedPoint {}
extension ActionPoint: TimedPoint {}
extension NextPoint: TimedPoint {}

enum WidgetStore {
    static func snapshot() -> Snapshot? {
        guard let url = sharedDir()?.appendingPathComponent("widget.json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Snapshot.self, from: data)
    }

    static func pendingTaps() -> [PendingTap] {
        guard let url = sharedDir()?.appendingPathComponent("pending_done.json"),
              let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([PendingTap].self, from: data)) ?? []
    }

    /// The app ingests this file on its next launch (PawRepository.ingestWidgetTaps), which also
    /// sends the taps to the household.
    static func recordTap(taskId: String) {
        guard let dir = sharedDir() else { return }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var taps = pendingTaps()
        taps.append(PendingTap(taskId: taskId, at: Date().timeIntervalSince1970 * 1000))
        if let data = try? JSONEncoder().encode(taps) {
            try? data.write(to: dir.appendingPathComponent("pending_done.json"), options: .atomic)
        }
    }

    static func image(_ relative: String?) -> UIImage? {
        guard let relative, let url = sharedDir()?.appendingPathComponent(relative) else { return nil }
        return UIImage(contentsOfFile: url.path)
    }
}

// MARK: - One-tap Done (iOS 17 interactive widgets)

struct DoneIntent: AppIntent {
    static var title: LocalizedStringResource = "Mark care task done"

    @Parameter(title: "Task") var taskId: String

    init() {}
    init(taskId: String) { self.taskId = taskId }

    func perform() async throws -> some IntentResult {
        WidgetStore.recordTap(taskId: taskId)
        return .result() // WidgetKit reloads the timeline after an intent runs, showing the "if done" track.
    }
}
