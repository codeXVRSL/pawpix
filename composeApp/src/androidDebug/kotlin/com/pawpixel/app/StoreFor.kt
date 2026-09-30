package com.pawpixel.app

import android.content.Context
import android.content.SharedPreferences
import com.pawpixel.core.Ownership
import com.pawpixel.core.StoreEvent
import kotlinx.coroutines.delay

/**
 * Debug builds only (this source set isn't in release builds): Google Play, or a pretend store
 * when it's switched on (Settings → "Test build: pretend store", or the end-to-end test). Play
 * Billing can't run on the CI emulator, which has no Play Store or account.
 */
internal fun storeFor(context: Context, play: Store): Store =
    DebugStore(play, FakeStore(context.getSharedPreferences("pawpixel-test-store", Context.MODE_PRIVATE)))

class DebugStore(private val play: Store, val fake: FakeStore) : Store {
    private val active: Store get() = if (fake.enabled) fake else play
    override val test: TestStore get() = fake
    override suspend fun price() = active.price()
    override suspend fun buy() = active.buy()
    override suspend fun owned(sync: Boolean) = active.owned(sync)
    override fun listen(onEvent: (StoreEvent) -> Unit) {
        play.listen { if (!fake.enabled) onEvent(it) }
        fake.listen { if (fake.enabled) onEvent(it) }
    }
}

/**
 * A pretend store account: buys at once, or (with [payLater]) waits like a cash payment until
 * [paymentArrives]. Kept in its own preferences, like a real store account, so "Delete all my
 * data" and restores behave as they would with Play.
 */
class FakeStore(private val prefs: SharedPreferences) : Store, TestStore {
    private var listener: ((StoreEvent) -> Unit)? = null

    override var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }
    override var payLater: Boolean
        get() = prefs.getBoolean("payLater", false)
        set(v) { prefs.edit().putBoolean("payLater", v).apply() }
    private var ownership: Ownership
        get() = runCatching { Ownership.valueOf(prefs.getString("ownership", null) ?: "") }.getOrDefault(Ownership.NONE)
        set(v) { prefs.edit().putString("ownership", v.name).apply() }

    override suspend fun price(): String = PRICE
    override suspend fun owned(sync: Boolean): Ownership = ownership
    override fun listen(onEvent: (StoreEvent) -> Unit) { listener = onEvent }

    override suspend fun buy(): BuyResult {
        delay(300) // the purchase sheet
        if (ownership != Ownership.OWNED) ownership = if (payLater) Ownership.PENDING else Ownership.OWNED
        return BuyResult.Done(ownership)
    }

    override fun paymentArrives() {
        if (ownership != Ownership.PENDING) return
        ownership = Ownership.OWNED
        listener?.invoke(StoreEvent.Purchase(Ownership.OWNED))
    }

    override fun refund() {
        ownership = Ownership.NONE
        listener?.invoke(StoreEvent.Revoked)
    }

    /** A new pretend account (for the end-to-end test). */
    fun reset() { prefs.edit().clear().apply() }

    companion object {
        /** What Play would show for a ₱199 product in the Philippines. */
        const val PRICE = "₱199.00"
    }
}
