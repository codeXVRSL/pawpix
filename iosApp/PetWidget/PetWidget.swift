import WidgetKit
import SwiftUI
import AppIntents

private let appGroup = "group.com.pawpixel.app"

private func sharedDir() -> URL? {
    FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
        .appendingPathComponent("pawpixel", isDirectory: true)
}

// MARK: - widget.json (written by the Kotlin app; see core/WidgetSnapshot.kt)

struct Snapshot: Decodable {
    let version: Int
    let generatedAt: Double
    let pets: [SnapshotPet]
}

struct SnapshotPet: Decodable {
    let id: String
    let name: String
    let sprites: [String: String]
    let timeline: [MoodPoint]
    let action: DoneOption?
}

struct MoodPoint: Decodable {
    let at: Double      // epoch ms
    let mood: String
    let caption: String
}

struct DoneOption: Decodable {
    let taskId: String
    let label: String
    let timelineIfDone: [MoodPoint]
}

struct PendingTap: Codable {
    let taskId: String
    let at: Double
}

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

    /// The app ingests this file on its next launch (PawRepository.ingestWidgetTaps).
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

// MARK: - Timeline

struct PetEntry: TimelineEntry {
    let date: Date
    let name: String
    let caption: String
    let mood: String
    let image: UIImage?
    let action: DoneOption?
    let empty: Bool

    static let placeholder = PetEntry(date: .now, name: "Your pet", caption: "Open PawPixel to make your pixel pet",
                                      mood: "content", image: nil, action: nil, empty: true)
}

struct Provider: TimelineProvider {
    /// Worst first: with several pets the widget shows the one that needs you most.
    private static let priority = ["sad", "meds", "hungry", "restless", "content", "happy", "sleepy"]

    func placeholder(in context: Context) -> PetEntry { .placeholder }

    func getSnapshot(in context: Context, completion: @escaping (PetEntry) -> Void) {
        completion(makeEntries(now: .now).first ?? .placeholder)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<PetEntry>) -> Void) {
        let entries = makeEntries(now: .now)
        let refresh = entries.last.map { $0.date.addingTimeInterval(3600) } ?? Date().addingTimeInterval(3600)
        completion(Timeline(entries: entries.isEmpty ? [.placeholder] : entries, policy: .after(refresh)))
    }

    private func current(_ points: [MoodPoint], nowMs: Double) -> MoodPoint? {
        points.last { $0.at <= nowMs } ?? points.first
    }

    func makeEntries(now: Date) -> [PetEntry] {
        guard let snap = WidgetStore.snapshot(), !snap.pets.isEmpty else { return [] }
        let nowMs = now.timeIntervalSince1970 * 1000
        let taps = WidgetStore.pendingTaps().filter { $0.at >= snap.generatedAt }

        // If the owner tapped Done on the widget since the app last ran, show the "after" timeline.
        func timeline(for pet: SnapshotPet) -> (points: [MoodPoint], action: DoneOption?) {
            if let action = pet.action, taps.contains(where: { $0.taskId == action.taskId }) {
                return (action.timelineIfDone, nil)
            }
            return (pet.timeline, pet.action)
        }

        let ranked = snap.pets.min { a, b in
            let ma = current(timeline(for: a).points, nowMs: nowMs)?.mood ?? "content"
            let mb = current(timeline(for: b).points, nowMs: nowMs)?.mood ?? "content"
            return (Self.priority.firstIndex(of: ma) ?? 9) < (Self.priority.firstIndex(of: mb) ?? 9)
        }!
        let (points, action) = timeline(for: ranked)
        var result: [PetEntry] = []
        for (i, p) in points.enumerated() {
            let isLast = i == points.count - 1
            if !isLast && points[i + 1].at <= nowMs { continue } // superseded before now
            let date = max(now, Date(timeIntervalSince1970: p.at / 1000))
            result.append(PetEntry(date: date, name: ranked.name, caption: p.caption, mood: p.mood,
                                   image: WidgetStore.image(ranked.sprites[p.mood]),
                                   action: (p.mood == "happy" || p.mood == "sleepy") ? nil : action, empty: false))
        }
        return result
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
        return .result() // WidgetKit reloads the timeline after an intent runs.
    }
}

// MARK: - Views

struct PetWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: PetEntry

    private let ink = Color(red: 0.17, green: 0.13, blue: 0.21)
    private let berry = Color(red: 0.91, green: 0.22, blue: 0.31)

    var body: some View {
        Group {
            if entry.empty {
                Text(entry.caption).font(.caption).multilineTextAlignment(.center).foregroundStyle(ink)
            } else if family == .systemSmall {
                VStack(spacing: 2) {
                    sprite
                    Text(entry.name).font(.caption.bold()).foregroundStyle(ink).lineLimit(1)
                    if let action = entry.action { doneButton(action, compact: true) }
                }
            } else {
                HStack(spacing: 12) {
                    sprite
                    VStack(alignment: .leading, spacing: 6) {
                        Text(entry.name).font(.headline).foregroundStyle(ink)
                        Text(entry.caption).font(.subheadline).foregroundStyle(ink.opacity(0.8)).lineLimit(2)
                        if let action = entry.action { doneButton(action, compact: false) }
                    }
                    Spacer(minLength: 0)
                }
            }
        }
        .containerBackground(for: .widget) { Color(red: 1.0, green: 0.957, blue: 0.878) }
    }

    @ViewBuilder private var sprite: some View {
        if let image = entry.image {
            Image(uiImage: image)
                .interpolation(.none) // keep pixels crisp
                .resizable()
                .scaledToFit()
                .accessibilityLabel("\(entry.name): \(entry.caption)")
        }
    }

    private func doneButton(_ action: DoneOption, compact: Bool) -> some View {
        Button(intent: DoneIntent(taskId: action.taskId)) {
            Text(compact ? "Done" : action.label).font(.caption.bold())
        }
        .tint(berry)
    }
}

@main
struct PetWidgetBundle: WidgetBundle {
    var body: some Widget { PetWidget() }
}

struct PetWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "PetWidget", provider: Provider()) { entry in
            PetWidgetView(entry: entry)
        }
        .configurationDisplayName("PawPixel")
        .description("Your pixel pet and how they feel right now.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
