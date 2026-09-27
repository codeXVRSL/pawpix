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
        IosGraph.shared.start(host: SwiftHost.shared)
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        let done = UNNotificationAction(identifier: SwiftHost.doneAction, title: "Done", options: [])
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
           let taskId = response.notification.request.content.userInfo["taskId"] as? String {
            IosGraph.shared.completeTask(taskId: taskId)
        }
        completionHandler()
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
