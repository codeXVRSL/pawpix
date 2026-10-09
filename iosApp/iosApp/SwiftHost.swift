import UIKit
import PhotosUI
import Vision
import CoreVideo
import UserNotifications
import WidgetKit
import UniformTypeIdentifiers
import ImageIO
import CoreLocation
import AuthenticationServices
import ComposeApp

/// Apple-framework services for the shared Kotlin app (see `IosHost` in composeApp/src/iosMain).
final class SwiftHost: NSObject, IosHost {
    static let shared = SwiftHost()
    static let appGroup = "group.com.pawpixel.app"
    static let careCategory = "CARE"
    static let doneAction = "DONE"

    private var pickerDelegate: PickerDelegate?
    private var documentDelegate: DocumentDelegate?
    private var locationDelegate: ApproximateLocation?
    private var appleDelegate: AppleSignIn?

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
        #if DEBUG
        // UI tests (PawPixelUITests) hand in a photo instead of driving the system picker.
        if let b64 = ProcessInfo.processInfo.environment["PAWPIXEL_TEST_PHOTO_B64"], let data = Data(base64Encoded: b64) {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { completion.onResult(data: data) }
            return
        }
        #endif
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

    // MARK: Backups

    func pickFile(completion: DataCallback) {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.json, .plainText, .data], asCopy: true)
        let delegate = DocumentDelegate { [weak self] data in
            completion.onResult(data: data)
            self?.documentDelegate = nil
        }
        documentDelegate = delegate
        picker.delegate = delegate
        picker.allowsMultipleSelection = false
        Self.topViewController()?.present(picker, animated: true)
    }

    func decodePhoto(data: Data, maxSide: Int32) -> Data? {
        // ImageIO shrinks the photo while decoding it and turns it upright (EXIF orientation), so a
        // 48 MP photo never sits in memory at full size (UIImage would decode all of it first).
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(max(1, maxSide)),
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary), cg.width > 0, cg.height > 0 else { return nil }
        return RawImageBytes.encode(cg)
    }

    // MARK: Pet cut-out (Vision, iOS 17+)

    func encodeJpeg(rawImage: Data, quality: Double) -> Data? {
        guard let decoded = RawImageBytes.decode(rawImage) else { return nil }
        // A fresh UIImage carries no EXIF, so the card photo loses any location it had.
        return UIImage(cgImage: decoded.0).jpegData(compressionQuality: quality)
    }

    func segmentPet(rawImage: Data, completion: DataCallback) {
        DispatchQueue.global(qos: .userInitiated).async {
            let mask = Self.foregroundMask(rawImage)
            DispatchQueue.main.async { completion.onResult(data: mask) }
        }
    }

    /// Vision's built-in classifier: "cat" or "dog" when one of them is clearly in the picture.
    func classifyPet(rawImage: Data, completion: TokenCallback) {
        DispatchQueue.global(qos: .userInitiated).async {
            var answer: String? = nil
            if let decoded = RawImageBytes.decode(rawImage) {
                let request = VNClassifyImageRequest()
                let handler = VNImageRequestHandler(cgImage: decoded.0, options: [:])
                if (try? handler.perform([request])) != nil, let results = request.results {
                    func score(_ names: [String]) -> Float {
                        results.filter { r in names.contains(where: { r.identifier.lowercased() == $0 }) }.map { $0.confidence }.max() ?? 0
                    }
                    let cat = score(["cat", "kitten", "tabby", "domestic_cat"])
                    let dog = score(["dog", "puppy", "domestic_dog"])
                    let rabbit = score(["rabbit", "bunny"])
                    // The most confident of the three, when it's confident enough.
                    if cat >= 0.4 && cat >= dog && cat >= rabbit { answer = "CAT" }
                    else if dog >= 0.4 && dog > cat && dog >= rabbit { answer = "DOG" }
                    else if rabbit >= 0.4 && rabbit > cat && rabbit > dog { answer = "RABBIT" }
                }
            }
            DispatchQueue.main.async { completion.onResult(token: answer, error: nil) }
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
            // The Done button is left off heads-ups ("due in 3 days").
            if (item["quickDone"] as? Bool) != false { content.categoryIdentifier = Self.careCategory }
            content.userInfo = ["taskId": (item["taskIds"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? taskId]
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: at - now, repeats: false)
            center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
        }
    }

    func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            DispatchQueue.main.async { self.cachedNotificationStatus = granted ? 1 : 0 }
        }
    }

    /// iOS answers asynchronously: return the last known answer (-1 = not known yet) and refresh it.
    private var cachedNotificationStatus: Int32 = -1

    func notificationStatus() -> Int32 {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let allowed = [UNAuthorizationStatus.authorized, .provisional, .ephemeral].contains(settings.authorizationStatus)
            DispatchQueue.main.async { self.cachedNotificationStatus = allowed ? 1 : 0 }
        }
        return cachedNotificationStatus
    }

    // MARK: Widgets, sharing, links

    func reloadWidgets() {
        WidgetCenter.shared.reloadAllTimelines()
    }

    func shareFile(data: Data, fileName: String) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        try? data.write(to: url)
        // Pictures get a friendly caption; a backup file travels on its own.
        let items: [Any] = fileName.hasSuffix(".json") ? [url] : [url, "Meet my pet in pixels! Made with PawPixel"]
        let sheet = UIActivityViewController(activityItems: items, applicationActivities: nil)
        guard let top = Self.topViewController() else { NSLog("PawPixel: share failed, no view controller to present from"); return }
        NSLog("PawPixel: sharing %@ (%d bytes) from %@", fileName, data.count, String(describing: type(of: top)))
        sheet.popoverPresentationController?.sourceView = top.view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
        sheet.popoverPresentationController?.permittedArrowDirections = []
        top.present(sheet, animated: true)
    }

    func shareText(text: String) {
        guard let top = Self.topViewController() else { return }
        let sheet = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        sheet.popoverPresentationController?.sourceView = top.view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
        sheet.popoverPresentationController?.permittedArrowDirections = []
        top.present(sheet, animated: true)
    }

    func openUrl(url: String) {
        guard let u = URL(string: url) else { return }
        UIApplication.shared.open(u)
    }

    // MARK: Pet map

    /// Approximate location only: the app snaps it to a ~1 km square before anything leaves the phone.
    func approximateLocation(completion: LocationCallback) {
        let finder = ApproximateLocation { [weak self] location in
            if let l = location {
                completion.onResult(found: true, lat: l.coordinate.latitude, lng: l.coordinate.longitude)
            } else {
                completion.onResult(found: false, lat: 0, lng: 0)
            }
            self?.locationDelegate = nil
        }
        locationDelegate = finder
        finder.start()
    }

    func signInWithApple(hashedNonce: String, completion: TokenCallback) {
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = []   // no name or email needed
        request.nonce = hashedNonce
        let controller = ASAuthorizationController(authorizationRequests: [request])
        let delegate = AppleSignIn { [weak self] token, error in
            completion.onResult(token: token, error: error)
            self?.appleDelegate = nil
        }
        appleDelegate = delegate
        controller.delegate = delegate
        controller.presentationContextProvider = delegate
        controller.performRequests()
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

private final class DocumentDelegate: NSObject, UIDocumentPickerDelegate {
    let done: (Data?) -> Void
    init(done: @escaping (Data?) -> Void) { self.done = done }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { done(nil); return }
        // Backups are small; don't read anything huge into memory.
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        // Same limit as Backup.MAX_BYTES; empty data tells the app the file was too big.
        done(size > 40_000_000 ? Data() : try? Data(contentsOf: url))
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { done(nil) }
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

/// One approximate fix (reduced accuracy is fine), with a 15 s fallback to the last known location.
private final class ApproximateLocation: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let done: (CLLocation?) -> Void
    private var finished = false

    init(done: @escaping (CLLocation?) -> Void) {
        self.done = done
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyReduced
    }

    func start() {
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways: manager.requestLocation()
        default: finish(nil)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in self?.finish(self?.manager.location) }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: manager.requestLocation()
        case .denied, .restricted: finish(nil)
        default: break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) { finish(locations.last) }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) { finish(manager.location) }

    private func finish(_ location: CLLocation?) {
        guard !finished else { return }
        finished = true
        DispatchQueue.main.async { self.done(location) }
    }
}

/// Sign in with Apple for the pet map. Returns the identity token; the server checks the nonce.
private final class AppleSignIn: NSObject, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    private let done: (String?, String?) -> Void
    init(done: @escaping (String?, String?) -> Void) { self.done = done }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let data = credential.identityToken, let token = String(data: data, encoding: .utf8) else {
            done(nil, "Apple didn't return a sign-in token.")
            return
        }
        done(token, nil)
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        if let e = error as? ASAuthorizationError, e.code == .canceled { done(nil, nil) } else { done(nil, error.localizedDescription) }
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        SwiftHost.topViewController()?.view.window ?? ASPresentationAnchor()
    }
}
