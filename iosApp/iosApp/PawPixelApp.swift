import SwiftUI
import UserNotifications
import ComposeApp

@main
struct PawPixelApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
                // A widget tap: "pawpixel://pet/<id>" opens that pet's page.
                .onOpenURL { url in IosGraph.shared.openLink(url: url.absoluteString) }
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            // Apply Done taps made on the widget and refresh reminders/widgets.
            if phase == .active { IosGraph.shared.onForeground() }
        }
    }
}

/** Hosts the shared Compose Multiplatform UI. */
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosPlatformKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        #if DEBUG
        if ProcessInfo.processInfo.environment["PAWPIXEL_TEST_RESET"] == "1" {
            try? FileManager.default.removeItem(atPath: SwiftHost.shared.sharedContainerPath())
        }
        #endif
        IosGraph.shared.start(host: SwiftHost.shared)
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        // In the owner's language (Settings → Language, or the phone's).
        let done = UNNotificationAction(identifier: SwiftHost.doneAction, title: IosGraph.shared.doneLabel(), options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: SwiftHost.careCategory, actions: [done], intentIdentifiers: [], options: [])
        ])
        return true
    }

    /** "Done" pressed on a reminder notification. */
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if response.actionIdentifier == SwiftHost.doneAction,
           let refs = response.notification.request.content.userInfo["taskId"] as? String {
            // Tell iOS we're finished only once the Done is saved (a bundle logs several tasks).
            IosGraph.shared.completeTask(refs: refs, done: NotificationDoneHandler(completionHandler))
        } else {
            completionHandler()
        }
    }

    /** Show reminders even while the app is open. */
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }
}

/// Hands iOS's completion handler to Kotlin, which calls it after saving.
final class NotificationDoneHandler: NSObject, NotificationDone {
    private let handler: () -> Void
    init(_ handler: @escaping () -> Void) { self.handler = handler }
    func finished() { DispatchQueue.main.async { self.handler() } }
}
