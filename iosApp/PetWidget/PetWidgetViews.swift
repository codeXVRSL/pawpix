import SwiftUI
import WidgetKit
import AppIntents

// MARK: - Entry

struct PetEntry: TimelineEntry {
    let date: Date
    /// Nil: no pet yet (the empty state invites the owner to make one).
    let face: PetFace?
    let image: UIImage?
    /// The widget's words in the owner's language (see WidgetSnapshot.build).
    let labels: [String: String]
    /// The sky behind the pet at this moment, or nil for plain paper.
    var sky: SkyPhase? = .day
    /// Whether the pet's name is written on the widget.
    var showName: Bool = true

    /// Taps open the pet's page, or the pet maker.
    var url: URL { URL(string: face.map { "pawpixel://pet/\($0.petId)" } ?? "pawpixel://new")! }

    /// Smart Stacks rotate PawPixel up when care is due.
    var relevance: TimelineEntryRelevance? { TimelineEntryRelevance(score: face?.actionTaskId != nil ? 1 : 0.1) }

    func label(_ key: String, _ english: String) -> String { labels[key] ?? english }

    /// A happy sample pet, for the widget gallery before the app has run.
    static let sample = PetEntry(
        date: .now,
        face: PetFace(petId: "", name: "Mochi", mood: "happy", caption: "Mochi is happy!", sprite: nil,
                      actionTaskId: nil, actionEmoji: nil, actionTitle: nil, nextEmoji: "🍖", nextTitle: "Feed",
                      nextAt: Calendar.current.date(bySettingHour: 17, minute: 30, second: 0, of: .now)),
        image: WidgetImages.sample, labels: [:])

    static func empty(_ labels: [String: String]?) -> PetEntry {
        PetEntry(date: .now, face: nil, image: WidgetImages.sample, labels: labels ?? [:])
    }
}

enum WidgetImages {
    private final class Token {}
    /// The bundled sample pet (an orange cat), also used for the empty state.
    static let sample = UIImage(named: "sample-pet", in: Bundle(for: Token.self), with: nil)
}

/// PawPixel's warm paper, with a night version; the Done button's berry.
enum Palette {
    private static func dynamic(_ light: (CGFloat, CGFloat, CGFloat), _ dark: (CGFloat, CGFloat, CGFloat)) -> Color {
        Color(UIColor { traits in
            let c = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: c.0, green: c.1, blue: c.2, alpha: 1)
        })
    }
    // The app's palette (see the shared Theme.kt): cream #FFF7EC / plum #1F1930, ink #2B2135, coral #D9364F.
    static let paper = dynamic((1.0, 0.969, 0.925), (0.122, 0.098, 0.188))
    static let ink = dynamic((0.17, 0.13, 0.21), (0.969, 0.933, 0.894))
    static let soft = dynamic((0.416, 0.361, 0.471), (0.788, 0.741, 0.839))
    static let berry = dynamic((0.851, 0.212, 0.31), (1.0, 0.502, 0.576))
    static let onBerry = dynamic((1, 1, 1), (0.29, 0.04, 0.094))
    /// Ink over the night sky, whatever the phone's appearance.
    static let nightInk = Color(red: 0.969, green: 0.933, blue: 0.894)
    static let nightSoft = Color(red: 0.788, green: 0.741, blue: 0.839)
}

// MARK: - Views

/// Every widget family. [showsBackground] is false in StandBy and on clear/tinted home screens, where
/// the system draws no background, so the text uses the system's colours instead of PawPixel's ink.
struct PetWidgetView: View {
    let entry: PetEntry
    let family: WidgetFamily
    var showsBackground: Bool = true

    private var night: Bool { showsBackground && entry.sky?.dark == true }
    private var ink: Color { night ? Palette.nightInk : (showsBackground ? Palette.ink : .primary) }
    private var soft: Color { night ? Palette.nightSoft : (showsBackground ? Palette.soft : .secondary) }
    /// The caption's pill over the sky, so it reads on any gradient.
    private var pill: Color { night ? Color.black.opacity(0.35) : Color.white.opacity(0.75) }

    var body: some View {
        content.widgetURL(entry.url)
    }

    @ViewBuilder private var content: some View {
        switch family {
        case .accessoryCircular: circular
        case .accessoryRectangular: rectangular
        case .accessoryInline: Text(inlineText)
        case .systemSmall: small
        case .systemLarge, .systemExtraLarge: large
        default: medium
        }
    }

    // MARK: Home Screen and StandBy

    private var small: some View {
        VStack(spacing: 4) {
            sprite
            if let face = entry.face {
                Text(face.caption).font(.caption.bold()).foregroundStyle(ink)
                    .lineLimit(2).multilineTextAlignment(.center).minimumScaleFactor(0.8)
                    .padding(.horizontal, 8).padding(.vertical, 2)
                    .background(entry.sky == nil || !showsBackground ? Color.clear : pill, in: Capsule())
                if let action = face.actionTaskId {
                    doneButton(action, face)
                } else if let next = nextLine(face) {
                    Text(next).font(.caption2).foregroundStyle(soft).lineLimit(1).minimumScaleFactor(0.7)
                }
            } else {
                emptyText
            }
        }
    }

    private var medium: some View {
        HStack(spacing: 12) {
            sprite.frame(maxWidth: 140)
            VStack(alignment: .leading, spacing: 4) { details }
            Spacer(minLength: 0)
        }
    }

    private var large: some View {
        VStack(spacing: 10) {
            sprite
            VStack(spacing: 4) { details }.multilineTextAlignment(.center)
        }
    }

    @ViewBuilder private var details: some View {
        if let face = entry.face {
            if entry.showName { Text(face.name).font(.headline).foregroundStyle(ink).lineLimit(1) }
            Text(face.caption).font(.subheadline.weight(.medium)).foregroundStyle(ink).lineLimit(2)
                .padding(.horizontal, 8).padding(.vertical, 2)
                .background(entry.sky == nil || !showsBackground ? Color.clear : pill, in: Capsule())
            if let action = face.actionTaskId {
                doneButton(action, face).padding(.top, 2)
            } else if let next = nextLine(face) {
                Text(next).font(.caption).foregroundStyle(soft).lineLimit(2)
            }
        } else {
            emptyText
        }
    }

    private var emptyText: some View {
        Text(entry.label("makePet", "Make your pixel pet")).font(.caption.bold()).foregroundStyle(ink).multilineTextAlignment(.center)
    }

    // MARK: Lock Screen

    private var circular: some View {
        ZStack {
            AccessoryWidgetBackground()
            VStack(spacing: 0) {
                sprite.frame(maxHeight: badge == nil ? 44 : 30)
                if let badge { Text(badge).font(.system(size: 12, weight: .semibold)).minimumScaleFactor(0.6).lineLimit(1) }
            }
            .padding(4)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(entry.face?.caption ?? entry.label("makePet", "Make your pixel pet"))
    }

    private var rectangular: some View {
        HStack(spacing: 6) {
            sprite.frame(width: 44)
            VStack(alignment: .leading, spacing: 0) {
                if let face = entry.face {
                    if entry.showName { Text(face.name).font(.headline).widgetAccentable().lineLimit(1) }
                    Text(face.caption).font(.caption).lineLimit(1)
                    if let label = dueLine(face) ?? nextLine(face) { Text(label).font(.caption2).lineLimit(1) }
                } else {
                    Text(entry.label("makePet", "Make your pixel pet")).font(.caption).lineLimit(2)
                }
            }
            Spacer(minLength: 0)
        }
    }

    /// One line beside the clock: the mood, and what's due or next.
    private var inlineText: String {
        guard let face = entry.face else { return entry.label("makePet", "Make your pixel pet") }
        if let due = dueLine(face) { return "\(face.caption) · \(due)" }
        if let at = face.nextAt, let emoji = face.nextEmoji { return "\(face.caption) · \(emoji) \(Self.shortTime(at))" }
        return face.caption
    }

    /// What's due now (its emoji) or the next care's time, under the pet in the circle.
    private var badge: String? {
        guard let face = entry.face else { return nil }
        if face.actionTaskId != nil { return face.actionEmoji }
        return face.nextAt.map(Self.shortTime)
    }

    // MARK: Parts

    @ViewBuilder private var sprite: some View {
        if let image = entry.image {
            Image(uiImage: image)
                .interpolation(.none) // keep pixels crisp
                .resizable()
                .scaledToFit()
                .accessibilityLabel(entry.face.map { "\($0.name): \($0.caption)" } ?? "")
        }
    }

    /// "🍖 Done"
    private func doneButton(_ taskId: String, _ face: PetFace) -> some View {
        Button(intent: DoneIntent(taskId: taskId)) {
            Text("\(face.actionEmoji ?? "") \(entry.label("done", "Done"))").font(.caption.bold()).lineLimit(1).minimumScaleFactor(0.8).frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(showsBackground ? Palette.berry : .accentColor)
        .foregroundStyle(showsBackground ? Palette.onBerry : .white)
    }

    /// What's due now: "🍖 Feed".
    private func dueLine(_ face: PetFace) -> String? {
        guard face.actionTaskId != nil, let emoji = face.actionEmoji, let title = face.actionTitle else { return nil }
        return "\(emoji) \(title)"
    }

    /// "Next: 🍖 Feed · 5:30 PM"
    private func nextLine(_ face: PetFace) -> String? {
        guard let at = face.nextAt, let emoji = face.nextEmoji, let title = face.nextTitle else { return nil }
        return entry.label("next", "Next: {0} {1} · {2}")
            .replacingOccurrences(of: "{0}", with: emoji)
            .replacingOccurrences(of: "{1}", with: title)
            .replacingOccurrences(of: "{2}", with: at.formatted(date: .omitted, time: .shortened))
    }

    /// "5:30" or "17:30", without AM/PM, to fit a small circle.
    static func shortTime(_ date: Date) -> String {
        let twelveHour = DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current)?.contains("a") ?? true
        let f = DateFormatter()
        f.dateFormat = twelveHour ? "h:mm" : "HH:mm"
        return f.string(from: date)
    }
}
