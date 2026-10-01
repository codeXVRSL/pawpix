import WidgetKit
import SwiftUI
import AppIntents

// The model and reader (widget.json, taps, DoneIntent) are in WidgetModel.swift, the views in
// PetWidgetViews.swift (both also compiled into the UI tests, which render them to images).

// MARK: - Which pet (widget configuration)

struct PetEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Pet"
    static var defaultQuery = PetQuery()
    let id: String
    let name: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

/// The pets in widget.json, and "whoever needs you most".
struct PetQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [PetEntity] { all().filter { identifiers.contains($0.id) } }
    func suggestedEntities() async throws -> [PetEntity] { all() }
    func defaultResult() async -> PetEntity? { all().first }

    private func all() -> [PetEntity] {
        guard let snap = WidgetStore.snapshot() else { return [] }
        return snap.pets.map { PetEntity(id: $0.id, name: $0.name) }
            + [PetEntity(id: Snapshot.mostInNeed, name: snap.labels?["mostInNeed"] ?? "Whoever needs you most")]
    }
}

/// The sky behind the pet: the hour's, a fixed one, or plain paper.
enum SkyChoice: String, AppEnum {
    case auto, day, night, paper

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Sky"
    static var caseDisplayRepresentations: [SkyChoice: DisplayRepresentation] = [
        .auto: "Follows the time of day", .day: "Always day", .night: "Always night", .paper: "Plain",
    ]

    func phase(at date: Date, nightStart: Int, nightEnd: Int) -> SkyPhase? {
        switch self {
        case .auto: return SkyPhase.at(date, nightStart: nightStart, nightEnd: nightEnd)
        case .day: return .day
        case .night: return .night
        case .paper: return nil
        }
    }
}

struct SelectPetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Widget settings"
    static var description = IntentDescription("Which pet this widget shows, the sky behind it, and whether its name is written.")

    /// Unset: the first pet.
    @Parameter(title: "Pet") var pet: PetEntity?
    @Parameter(title: "Sky", default: .auto) var sky: SkyChoice
    @Parameter(title: "Show the name", default: true) var showName: Bool
}

// MARK: - Timeline

struct Provider: AppIntentTimelineProvider {
    /// widget.json covers two days; entries go no further (the app refreshes it whenever it runs).
    private static let maxEntries = 200

    func placeholder(in context: Context) -> PetEntry { .sample }

    func snapshot(for configuration: SelectPetIntent, in context: Context) async -> PetEntry {
        guard let snap = WidgetStore.snapshot(), !snap.pets.isEmpty else { return .sample }
        return entry(snap, at: .now, configuration: configuration, taps: WidgetStore.pendingTaps(), images: ImageCache())
    }

    func timeline(for configuration: SelectPetIntent, in context: Context) async -> Timeline<PetEntry> {
        // No file yet: the app reloads widgets when it writes one.
        guard let snap = WidgetStore.snapshot() else { return Timeline(entries: [.empty(nil)], policy: .never) }
        let now = Date()
        let taps = WidgetStore.pendingTaps()
        let images = ImageCache()
        // Entries whenever the pet changes, and (with the hour's sky) whenever the sky does.
        var changes = snap.changeDates(after: now, taps: taps)
        if configuration.sky == .auto { changes = Array(Set(changes + SkyPhase.changeDates(after: now, nightStart: snap.nightStart, nightEnd: snap.nightEnd))).sorted() }
        let dates = [now] + changes.prefix(Self.maxEntries)
        let entries = dates.map { entry(snap, at: $0, configuration: configuration, taps: taps, images: images) }
        return Timeline(entries: entries, policy: .atEnd)
    }

    private func entry(_ snap: Snapshot, at date: Date, choice: String?, taps: [PendingTap], images: ImageCache) -> PetEntry {
        guard let face = snap.face(at: date, choice: choice, taps: taps) else { return .empty(snap.labels) }
        return PetEntry(date: date, face: face, image: images.image(face.sprite), labels: snap.labels ?? [:])
    }

    private func entry(_ snap: Snapshot, at date: Date, configuration: SelectPetIntent, taps: [PendingTap], images: ImageCache) -> PetEntry {
        var e = entry(snap, at: date, choice: configuration.pet?.id, taps: taps, images: images)
        e.sky = configuration.sky.phase(at: date, nightStart: snap.nightStart, nightEnd: snap.nightEnd)
        e.showName = configuration.showName
        return e
    }
}

/// Each mood's pose is read once per timeline.
private final class ImageCache {
    private var images: [String: UIImage] = [:]
    func image(_ path: String?) -> UIImage? {
        guard let path else { return nil }
        if let hit = images[path] { return hit }
        let image = WidgetStore.image(path)
        images[path] = image
        return image
    }
}

// MARK: - Widget

struct PetWidgetEntryView: View {
    @Environment(\.widgetFamily) private var family
    @Environment(\.showsWidgetContainerBackground) private var showsBackground
    let entry: PetEntry

    var body: some View {
        PetWidgetView(entry: entry, family: family, showsBackground: showsBackground)
            .containerBackground(for: .widget) {
                switch family {
                case .accessoryCircular, .accessoryRectangular, .accessoryInline: Color.clear
                default: if let sky = entry.sky { SkyBackground(phase: sky) } else { Palette.paper }
                }
            }
    }
}

@main
struct PetWidgetBundle: WidgetBundle {
    var body: some Widget { PetWidget() }
}

struct PetWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "PetWidget", intent: SelectPetIntent.self, provider: Provider()) { entry in
            PetWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("PawPixel")
        .description("Your pixel pet, how they feel right now, and a Done button when care is due.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

#Preview(as: .systemSmall) { PetWidget() } timeline: { PetEntry.sample }
#Preview(as: .accessoryRectangular) { PetWidget() } timeline: { PetEntry.sample }
