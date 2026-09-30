package com.pawpixel.core

/**
 * PawPixel Pro: a one-time, non-consumable in-app purchase (no subscription), so it can be paid
 * with GCash, Maya, ShopeePay, load, or cash at 7-Eleven / ECPay. It adds more pets and a few Pro
 * outfits. Nothing the owner already has (pets, care, reminders, health, backups, household) is
 * ever behind it, and losing Pro (a refund) never takes a pet away.
 */
object Pro {
    /** The product id, the same in Google Play Console and App Store Connect. */
    const val PRODUCT_ID = "pawpixel_pro"
}

/** What the app store says about Pro for the signed-in store account. */
enum class Ownership {
    NONE,
    /** Bought, waiting for the payment (cash at a store, a bank's approval, Ask to Buy). */
    PENDING,
    OWNED,
}

/** Pro as this phone knows it. Kept in [Settings] ([Settings.pro], [Settings.proPending]), so it works offline. */
data class Entitlement(val owned: Boolean = false, val pending: Boolean = false)

/** Something the store told the app. */
sealed interface StoreEvent {
    /**
     * The store's full answer for this account (at app start, when the app comes back, or Restore).
     * It's the source of truth: a purchase refunded while the app was closed is gone from it.
     */
    data class Checked(val ownership: Ownership) : StoreEvent

    /**
     * A purchase just made, or one that changed later: a cash payment arrived ([Ownership.OWNED]),
     * or a payment that never came was cancelled ([Ownership.NONE]).
     */
    data class Purchase(val ownership: Ownership) : StoreEvent

    /** Refunded or revoked by the store. */
    data object Revoked : StoreEvent

    /** The store couldn't be reached (offline, no Play Store, signed out): keep what we knew. */
    data object Unreachable : StoreEvent
}

/** The entitlement state machine: pure, so every path (owned, pending, refunded, offline) is tested. */
object ProEntitlement {
    fun of(settings: Settings) = Entitlement(settings.pro, settings.proPending)

    fun next(e: Entitlement, event: StoreEvent): Entitlement = when (event) {
        is StoreEvent.Checked -> when (event.ownership) {
            Ownership.OWNED -> Entitlement(owned = true)
            Ownership.PENDING -> Entitlement(pending = true)
            Ownership.NONE -> Entitlement()
        }
        is StoreEvent.Purchase -> when (event.ownership) {
            Ownership.OWNED -> Entitlement(owned = true)
            Ownership.PENDING -> e.copy(pending = !e.owned)
            // Only the waiting purchase ends: this news isn't about Pro already owned.
            Ownership.NONE -> e.copy(pending = false)
        }
        StoreEvent.Revoked -> Entitlement()
        StoreEvent.Unreachable -> e
    }

    /**
     * Applies [event] to the cached entitlement in [state]'s settings. Pets and outfits are never
     * touched. While a test build's unlock switch is on, the store's news doesn't change Pro.
     */
    fun apply(state: AppState, event: StoreEvent): AppState {
        if (state.settings.proTestUnlock) return state
        val e = next(of(state.settings), event)
        if (e == of(state.settings)) return state
        return state.copy(settings = state.settings.copy(pro = e.owned, proPending = e.pending))
    }

    /**
     * Test builds: "Test build: unlock Pro features". On: Pro, whatever the store says. Off: no Pro
     * until the store is asked again (the app does that right after).
     */
    fun testUnlock(state: AppState, on: Boolean): AppState =
        state.copy(settings = state.settings.copy(pro = on, proPending = false, proTestUnlock = on))

    /**
     * Release builds never keep a test build's unlock (a phone that had a test build installed):
     * Pro goes back to what the store says, which the app asks right after.
     */
    fun forRelease(state: AppState): AppState =
        if (!state.settings.proTestUnlock) state else testUnlock(state, false)
}
