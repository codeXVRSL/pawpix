import UIKit
import PhotosUI
import Vision
import CoreVideo
import UserNotifications
import WidgetKit
import UniformTypeIdentifiers
import ComposeApp

/// Apple-framework services for the shared Kotlin app (see `IosHost` in composeApp/src/iosMain).
final class SwiftHost: NSObject, IosHost {
    static let shared = SwiftHost()
    static let appGroup = "group.com.pawpixel.app"
    static let careCategory = "CARE"
    static let doneAction = "DONE"

    private var pickerDelegate: PickerDelegate?

    // MARK: Files

    func sharedContainerPath() -> String {
        let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup)
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0] // no App Group = no widget, app still works
        let dir = base.appendingPathComponent("pawpixel", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.path
    }

    // MARK: Photos

    func pickPhoto(completion: DataCallback) {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = 1
        let picker = PHPickerViewController(configuration: config)
        let delegate = PickerDelegate { [weak self] data in
            completion.onResult(data: data)
            self?.pickerDelegate = nil
        }
        pickerDelegate = delegate
        picker.delegate = delegate
        Self.topViewController()?.present(picker, animated: true)
    }

    func decodePhoto(data: Data, maxSide: Int32) -> Data? {
        guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else { return nil }
        let longest = max(image.size.width, image.size.height)
        let scale = min(1.0, CGFloat(maxSide) / longest)
        let size = CGSize(width: max(1, (image.size.width * scale).rounded()), height: max(1, (image.size.height * scale).rounded()))
        // Drawing through UIImage applies EXIF orientation.
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let upright = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let cg = upright.cgImage else { return nil }
        return RawImageBytes.encode(cg)
    }

    // MARK: Pet cut-out (Vision, iOS 17+)

    func segmentPet(rawImage: Data, completion: DataCallback) {
        DispatchQueue.global(qos: .userInitiated).async {
            let mask = Self.foregroundMask(rawImage)
            DispatchQueue.main.async { completion.onResult(data: mask) }
        }
    }

    private static func foregroundMask(_ raw: Data) -> Data? {
        guard let decoded = RawImageBytes.decode(raw) else { return nil }
        let (cg, width, height) = decoded
        let request = VNGenerateForegroundInstanceMaskRequest()
        let handler = VNImageRequestHandler(cgImage: cg, options: [:])
        do {
            try handler.perform([request])
            guard let result = request.results?.first else { return nil }
            let buffer = try result.generateScaledMaskForImage(forInstances: result.allInstances, from: handler)
            return floatMask(buffer, width: width, height: height)
        } catch {
            return nil // Simulator or unsupported device: Kotlin falls back to its own segmenter.
        }
    }

    /// Reads a one-channel mask buffer (float32 or 8-bit) into little-endian Float32s.
    private static func floatMask(_ buffer: CVPixelBuffer, width: Int, height: Int) -> Data? {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard CVPixelBufferGetWidth(buffer) == width, CVPixelBufferGetHeight(buffer) == height,
              let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let rowBytes = CVPixelBufferGetBytesPerRow(buffer)
        let format = CVPixelBufferGetPixelFormatType(buffer)
        var values = [Float32](repeating: 0, count: width * height)
        for y in 0..<height {
            let row = base.advanced(by: y * rowBytes)
            for x in 0..<width {
                if format == kCVPixelFormatType_OneComponent32Float {
                    values[y * width + x] = row.assumingMemoryBound(to: Float32.self)[x]
                } else {
                    values[y * width + x] = Float32(row.assumingMemoryBound(to: UInt8.self)[x]) / 255
                }
            }
        }
        return values.withUnsafeBufferPointer { Data(buffer: $0) } // Apple platforms are little-endian
    }

    // MARK: Reminders

    func scheduleReminders(json: String) {
        let center = UNUserNotificationCenter.current()
        center.removeAllPendingNotificationRequests()
        guard let data = json.data(using: .utf8),
              let items = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return }
        let now = Date().timeIntervalSince1970
        for item in items {
            guard let id = item["id"] as? String, let taskId = item["taskId"] as? String,
                  let at = (item["at"] as? NSNumber)?.doubleValue, at > now + 1 else { continue }
            let content = UNMutableNotificationContent()
            content.title = item["title"] as? String ?? "PawPixel"
            content.body = item["body"] as? String ?? ""
            content.sound = .default
            content.categoryIdentifier = Self.careCategory
            content.userInfo = ["taskId": taskId]
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: at - now, repeats: false)
            center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
        }
    }

    func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    // MARK: Widgets, sharing, links

    func reloadWidgets() {
        WidgetCenter.shared.reloadAllTimelines()
    }

    func shareFile(data: Data, fileName: String) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        try? data.write(to: url)
        let sheet = UIActivityViewController(activityItems: [url, "Meet my pet in pixels! Made with PawPixel"], applicationActivities: nil)
        guard let top = Self.topViewController() else { return }
        sheet.popoverPresentationController?.sourceView = top.view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
        sheet.popoverPresentationController?.permittedArrowDirections = []
        top.present(sheet, animated: true)
    }

    func openUrl(url: String) {
        guard let u = URL(string: url) else { return }
        UIApplication.shared.open(u)
    }

    static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

private final class PickerDelegate: NSObject, PHPickerViewControllerDelegate {
    let done: (Data?) -> Void
    init(done: @escaping (Data?) -> Void) { self.done = done }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let provider = results.first?.itemProvider, provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) else {
            done(nil); return
        }
        provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
            DispatchQueue.main.async { self.done(data) }
        }
    }
}

/// Mirrors Kotlin's `RawImage`: "PPX1", width, height (big-endian Int32), then ARGB Int32s (big-endian).
enum RawImageBytes {
    static func encode(_ cg: CGImage) -> Data? {
        let w = cg.width, h = cg.height
        var rgba = [UInt8](repeating: 0, count: w * h * 4)
        // The context must only use the buffer inside this closure.
        let drawn: Bool = rgba.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return nil }
        var argb = [UInt8](repeating: 255, count: 12 + w * h * 4)
        argb[0] = 0x50; argb[1] = 0x50; argb[2] = 0x58; argb[3] = 0x31
        writeInt(&argb, 4, w); writeInt(&argb, 8, h)
        for i in 0..<(w * h) {
            let o = 12 + i * 4 // A stays 255, then R, G, B
            argb[o + 1] = rgba[i * 4]; argb[o + 2] = rgba[i * 4 + 1]; argb[o + 3] = rgba[i * 4 + 2]
        }
        return Data(argb)
    }

    static func decode(_ data: Data) -> (CGImage, Int, Int)? {
        let b = [UInt8](data)
        guard b.count >= 12, b[0] == 0x50, b[1] == 0x50, b[2] == 0x58, b[3] == 0x31 else { return nil }
        let w = readInt(b, 4), h = readInt(b, 8)
        guard w > 0, h > 0, b.count == 12 + w * h * 4 else { return nil }
        var rgba = [UInt8](repeating: 0, count: w * h * 4)
        for i in 0..<(w * h) {
            let o = 12 + i * 4
            rgba[i * 4] = b[o + 1]; rgba[i * 4 + 1] = b[o + 2]; rgba[i * 4 + 2] = b[o + 3]; rgba[i * 4 + 3] = 255
        }
        guard let provider = CGDataProvider(data: Data(rgba) as CFData),
              let cg = CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4,
                               space: CGColorSpaceCreateDeviceRGB(),
                               bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                               provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
        else { return nil }
        return (cg, w, h)
    }

    private static func writeInt(_ b: inout [UInt8], _ o: Int, _ v: Int) {
        let u = UInt32(v)
        b[o] = UInt8(u >> 24 & 0xff); b[o + 1] = UInt8(u >> 16 & 0xff); b[o + 2] = UInt8(u >> 8 & 0xff); b[o + 3] = UInt8(u & 0xff)
    }

    private static func readInt(_ b: [UInt8], _ o: Int) -> Int {
        Int(b[o]) << 24 | Int(b[o + 1]) << 16 | Int(b[o + 2]) << 8 | Int(b[o + 3])
    }
}
