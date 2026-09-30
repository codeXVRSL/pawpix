import StoreKit
import ComposeApp

/// PawPixel Pro through StoreKit 2: a non-consumable, one-time purchase (see `IosStore` in
/// composeApp/src/iosMain). Answers go back to Kotlin as a status word: owned, pending, none,
/// revoked, cancelled, ok (with the price), offline, unavailable, notfound (Pro isn't set up in
/// App Store Connect yet) or error.
///
/// Only verified transactions count. `Transaction.updates` runs for the app's whole life, so
/// purchases approved later (Ask to Buy) and refunds reach the app while it's open; the app also
/// reads `Transaction.currentEntitlements` at every start, which works offline.
final class ProStore {
    static let shared = ProStore()
    private var updates: Task<Void, Never>?

    func price(_ id: String, _ done: StoreCallback) {
        Task {
            do {
                guard let product = try await Product.products(for: [id]).first else { return Self.reply(done, "notfound") }
                Self.reply(done, "ok", product.displayPrice)
            } catch {
                Self.reply(done, Self.status(of: error))
            }
        }
    }

    func buy(_ id: String, _ done: StoreCallback) {
        Task { @MainActor in
            do {
                guard let product = try await Product.products(for: [id]).first else { return Self.reply(done, "notfound") }
                switch try await product.purchase() {
                case .success(let verification):
                    guard case .verified(let transaction) = verification else { return Self.reply(done, "error") }
                    await transaction.finish()
                    Self.reply(done, "owned")
                case .pending:
                    // Ask to Buy, or the bank wants to confirm: it arrives later through Transaction.updates.
                    Self.reply(done, "pending")
                case .userCancelled:
                    Self.reply(done, "cancelled")
                @unknown default:
                    Self.reply(done, "error")
                }
            } catch {
                Self.reply(done, Self.status(of: error))
            }
        }
    }

    /// What this Apple ID owns. With `sync` (the owner tapped Restore purchase), asks the App Store
    /// first, which may ask them to sign in; if that fails or is cancelled, the local answer stands.
    func owned(_ id: String, sync: Bool, _ done: StoreCallback) {
        Task {
            if sync { try? await AppStore.sync() }
            Self.reply(done, await Self.isEntitled(id) ? "owned" : "none")
        }
    }

    func listen(_ id: String, _ listener: StoreCallback) {
        updates?.cancel()
        updates = Task.detached {
            for await result in Transaction.updates {
                guard case .verified(let transaction) = result, transaction.productID == id else { continue }
                await transaction.finish()
                listener.onResult(status: transaction.revocationDate == nil ? "owned" : "revoked", price: nil)
            }
        }
    }

    private static func isEntitled(_ id: String) async -> Bool {
        for await result in Transaction.currentEntitlements {
            if case .verified(let transaction) = result, transaction.productID == id, transaction.revocationDate == nil { return true }
        }
        return false
    }

    private static func reply(_ done: StoreCallback, _ status: String, _ price: String? = nil) {
        done.onResult(status: status, price: price)
    }

    private static func status(of error: Error) -> String {
        if let e = error as? StoreKitError {
            switch e {
            case .networkError: return "offline"
            case .userCancelled: return "cancelled"
            case .notAvailableInStorefront, .notEntitled: return "unavailable"
            default: return "error"
            }
        }
        if let e = error as? Product.PurchaseError {
            switch e {
            case .productUnavailable, .purchaseNotAllowed: return "unavailable"
            default: return "error"
            }
        }
        if (error as NSError).domain == NSURLErrorDomain { return "offline" }
        return "error"
    }
}
