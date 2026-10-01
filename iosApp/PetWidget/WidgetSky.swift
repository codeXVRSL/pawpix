import SwiftUI

/// The sky over the pet, by the time of day (mirrors core/Sky.kt: the same hours and colours as the
/// app's stage and the Android widget): peach at dawn, soft blue by day, apricot to lavender at dusk,
/// and deep indigo with stars during the owner's set night.
enum SkyPhase: String {
    case night, dawn, day, dusk

    /// Light text reads on the night sky; dark ink on the others.
    var dark: Bool { self == .night }

    var top: Color {
        switch self {
        case .night: return Color(red: 0.106, green: 0.141, blue: 0.251)   // 1B2440
        case .dawn: return Color(red: 1.0, green: 0.82, blue: 0.761)       // FFD1C2
        case .day: return Color(red: 0.812, green: 0.902, blue: 0.98)      // CFE6FA
        case .dusk: return Color(red: 1.0, green: 0.769, blue: 0.604)      // FFC49A
        }
    }

    var bottom: Color {
        switch self {
        case .night: return Color(red: 0.227, green: 0.169, blue: 0.29)    // 3A2B4A
        case .dawn, .day: return Color(red: 1.0, green: 0.945, blue: 0.878) // FFF1E0
        case .dusk: return Color(red: 0.851, green: 0.784, blue: 0.925)    // D9C8EC
        }
    }

    var floor: Color {
        self == .night ? Color(red: 0.184, green: 0.153, blue: 0.259) : Color(red: 0.863, green: 0.922, blue: 0.788) // 2F2742 / DCEBC9
    }

    var floorLine: Color {
        self == .night ? Color(red: 0.29, green: 0.247, blue: 0.388) : Color(red: 0.725, green: 0.824, blue: 0.608) // 4A3F63 / B9D29B
    }

    private static let dawnStart = 5 * 60 + 30
    private static let dayStart = 8 * 60
    private static let duskStart = 16 * 60 + 30
    private static let duskEnd = 19 * 60

    static func isNight(_ minute: Int, nightStart: Int, nightEnd: Int) -> Bool {
        nightStart <= nightEnd ? (minute >= nightStart && minute < nightEnd) : (minute >= nightStart || minute < nightEnd)
    }

    static func at(minuteOfDay minute: Int, nightStart: Int, nightEnd: Int) -> SkyPhase {
        if isNight(minute, nightStart: nightStart, nightEnd: nightEnd) { return .night }
        if minute < dawnStart { return .night }
        if minute < dayStart { return .dawn }
        if minute < duskStart { return .day }
        if minute < duskEnd { return .dusk }
        return .night
    }

    static func at(_ date: Date, nightStart: Int, nightEnd: Int, calendar: Calendar = .current) -> SkyPhase {
        let c = calendar.dateComponents([.hour, .minute], from: date)
        return at(minuteOfDay: (c.hour ?? 0) * 60 + (c.minute ?? 0), nightStart: nightStart, nightEnd: nightEnd)
    }

    /// The moments the sky may change over the next days (the fixed hours and the owner's night),
    /// for timeline entries.
    static func changeDates(after date: Date, nightStart: Int, nightEnd: Int, days: Int = 2, calendar: Calendar = .current) -> [Date] {
        let minutes = Set([dawnStart, dayStart, duskStart, duskEnd, nightStart, nightEnd])
        var out: [Date] = []
        for day in 0...days {
            guard let base = calendar.date(byAdding: .day, value: day, to: calendar.startOfDay(for: date)) else { continue }
            for m in minutes {
                if let d = calendar.date(byAdding: .minute, value: m, to: base), d > date { out.append(d) }
            }
        }
        return out.sorted()
    }
}

/// The widget's background: the sky as a gradient, a strip of ground, and stars at night.
struct SkyBackground: View {
    let phase: SkyPhase

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .bottom) {
                LinearGradient(colors: [phase.top, phase.bottom], startPoint: .top, endPoint: .bottom)
                if phase == .night {
                    ForEach(Array(SkyBackground.stars.enumerated()), id: \.offset) { star in
                        Circle()
                            .fill(Color(red: 1.0, green: 0.965, blue: 0.835).opacity(star.element.2 ? 0.9 : 0.6))
                            .frame(width: star.element.2 ? 3 : 2, height: star.element.2 ? 3 : 2)
                            .position(x: star.element.0 * geo.size.width, y: star.element.1 * geo.size.height * 0.7)
                    }
                    Circle().fill(Color(red: 1.0, green: 0.945, blue: 0.788))
                        .frame(width: 18, height: 18)
                        .overlay(Circle().fill(phase.top).frame(width: 15, height: 15).offset(x: 6, y: -3))
                        .position(x: geo.size.width - 22, y: 20)
                } else {
                    Circle().fill((phase == .day ? Color(red: 1.0, green: 0.957, blue: 0.761) : Color(red: 1.0, green: 0.851, blue: 0.541)).opacity(0.9))
                        .frame(width: 26, height: 26)
                        .position(x: geo.size.width - 24, y: 22)
                }
                VStack(spacing: 0) {
                    Rectangle().fill(phase.floorLine).frame(height: 2)
                    Rectangle().fill(phase.floor)
                }
                .frame(height: max(14, geo.size.height * 0.16))
            }
        }
    }

    /// A handful of stars at fixed places (fractions of the sky; the third is "bigger").
    private static let stars: [(CGFloat, CGFloat, Bool)] = [
        (0.08, 0.12, false), (0.21, 0.42, true), (0.33, 0.08, false), (0.47, 0.3, false), (0.58, 0.14, true),
        (0.7, 0.5, false), (0.82, 0.62, false), (0.15, 0.7, true), (0.4, 0.6, false), (0.92, 0.3, false),
    ]
}
