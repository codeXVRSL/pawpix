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

struct SelectPetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Choose a pet"
    static var description = IntentDescription("Which pet this widget shows.")

    /// Unset: the first pet.
    @Parameter(title: "Pet") var pet: PetEntity?
}

// MARK: - Timeline

struct Provider: AppIntentTimelineProvider {
    /// widget.json covers two days; entries go no further (the app refreshes it whenever it runs).
    private static let maxEntries = 200

    func placeholder(in context: Context) -> PetEntry { .sample }

    func snapshot(for configuration: SelectPetIntent, in context: Context) async -> PetEntry {
        guard let snap = WidgetStore.snapshot(), !snap.pets.isEmpty else { return .sample }
        return entry(snap, at: .now, choice: configuration.pet?.id, taps: WidgetStore.pendingTaps(), images: ImageCache())
    }

    func timeline(for configuration: SelectPetIntent, in context: Context) async -> Timeline<PetEntry> {
        // No file yet: the app reloads widgets when it writes one.
        guard let snap = WidgetStore.snapshot() else { return Timeline(entries: [.empty(nil)], policy: .never) }
        let now = Date()
        let taps = WidgetStore.pendingTaps()
        let images = ImageCache()
        let dates = [now] + snap.changeDates(after: now, taps: taps).prefix(Self.maxEntries)
        let entries = dates.map { entry(snap, at: $0, choice: configuration.pet?.id, taps: taps, images: images) }
        return Timeline(entries: entries, policy: .atEnd)
    }

    private func entry(_ snap: Snapshot, at date: Date, choice: String?, taps: [PendingTap], images: ImageCache) -> PetEntry {
        guard let face = snap.face(at: date, choice: choice, taps: taps) else { return .empty(snap.labels) }
        return PetEntry(date: date, face: face, image: images.image(face.sprite), labels: snap.labels ?? [:])
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
                default: Palette.paper
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
