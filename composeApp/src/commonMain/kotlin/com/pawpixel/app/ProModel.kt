package com.pawpixel.app

import com.pawpixel.core.Ownership
import com.pawpixel.core.ProEntitlement
import com.pawpixel.core.StoreEvent
import com.pawpixel.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Pro screen's state: the store's price and how the last purchase or restore went. */
data class ProUi(
    /** The store's price for Pro, once it answered. Never written in the app. */
    val price: String? = null,
    /** Why there's no price (offline, no store), for the owner. */
    val priceProblem: String? = null,
    /** Waiting for the store (a purchase or a restore). */
    val busy: Boolean = false,
    /** How the last purchase or restore went, for the owner. */
    val message: String? = null,
)

/**
 * PawPixel Pro on this phone: asks the [Store], and keeps the answer in the saved settings (see
 * [ProEntitlement]) so Pro works offline. The store is checked at every start and whenever the app
 * comes back, so a refund drops Pro and a cash payment made at 7-Eleven turns it on.
 */
class ProModel(private val repo: PawRepository) {
    private val store get() = repo.platform.store
    /** Store news arriving outside a screen (a payment that came through) is saved here. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @kotlin.concurrent.Volatile
    private var listening = false

    private val _ui = MutableStateFlow(ProUi())
    val ui: StateFlow<ProUi> = _ui.asStateFlow()

    private fun listen() {
        if (listening) return
        listening = true
        store.listen { event ->
            scope.launch { runCatching { apply(event) }.onFailure { repo.platform.log("Store news not saved: $it") } }
        }
    }

    private suspend fun apply(event: StoreEvent) {
        // Most checks change nothing: don't save or redraw the widgets for those.
        if (ProEntitlement.apply(repo.state.value, event) === repo.state.value) return
        repo.update(stamp = false) { ProEntitlement.apply(it, event) }
    }

    /**
     * At start and when the app comes back: the store's answer wins; offline, the saved copy stays.
     * Never throws: whatever the store does, the rest of the app carries on.
     */
    suspend fun refresh() {
        if (!repo.platform.isDebugBuild && repo.state.value.settings.proTestUnlock) repo.update(stamp = false) { ProEntitlement.forRelease(it) }
        try {
            listen()
            apply(
                try {
                    kotlinx.coroutines.withTimeoutOrNull(STORE_TIMEOUT_MS) { StoreEvent.Checked(store.owned()) } ?: StoreEvent.Unreachable
                } catch (e: StoreException) {
                    repo.platform.log("Store check failed: ${e.problem}")
                    StoreEvent.Unreachable
                },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            repo.platform.log("Store check failed: $e")
        }
    }

    /** Test builds: "Test build: unlock Pro features" (off: the store is asked again). */
    suspend fun testUnlock(on: Boolean) {
        if (!repo.platform.isDebugBuild) return
        repo.update(stamp = false) { ProEntitlement.testUnlock(it, on) }
        if (!on) refresh()
    }

    /** The Pro screen opened (or "Try again"): ask the store for the price. */
    suspend fun loadPrice() {
        if (_ui.value.price != null) return
        _ui.update { it.copy(priceProblem = null) }
        try {
            // A store that never answers (StoreKit with no product, a stuck connection) mustn't leave
            // the screen at "Getting the price…": after a while, say so and offer Try again.
            val price = kotlinx.coroutines.withTimeoutOrNull(STORE_TIMEOUT_MS) { store.price() }
                ?: throw StoreException(StoreProblem.NOT_SET_UP)
            _ui.update { it.copy(price = price) }
        } catch (e: StoreException) {
            _ui.update { it.copy(priceProblem = problemText(e.problem)) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            repo.platform.log("Store price failed: $e")
            _ui.update { it.copy(priceProblem = problemText(StoreProblem.ERROR)) }
        }
    }

    suspend fun buy() = busy {
        listen()
        when (val r = store.buy()) {
            is BuyResult.Done -> {
                apply(StoreEvent.Purchase(r.ownership))
                if (r.ownership == Ownership.OWNED) tr("Thank you! PawPixel Pro is yours.") else null
            }
            BuyResult.Cancelled -> tr("No problem: nothing was charged.")
            is BuyResult.Failed -> problemText(r.problem)
        }
    }

    /** "Restore purchase": asks the store again (it may ask the owner to sign in). */
    suspend fun restore() = busy {
        listen()
        try {
            val ownership = store.owned(sync = true)
            apply(StoreEvent.Checked(ownership))
            when (ownership) {
                Ownership.OWNED -> tr("PawPixel Pro is restored. Thank you!")
                Ownership.PENDING -> null
                Ownership.NONE -> tr("This store account hasn't bought PawPixel Pro.")
            }
        } catch (e: StoreException) {
            problemText(e.problem)
        }
    }

    /** Leaving the Pro screen: the next visit starts without an old message. */
    fun clearMessage() = _ui.update { it.copy(message = null) }

    /** Test builds: the pretend store was switched on or off, so ask the (other) store again. */
    suspend fun storeSwitched() {
        _ui.value = ProUi()
        refresh()
    }

    private suspend fun busy(block: suspend () -> String?) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, message = null) }
        var message: String? = null
        try {
            message = block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A store library surprise never takes the app down: say so and let them try again.
            repo.platform.log("Store failed: $e")
            message = problemText(StoreProblem.ERROR)
        } finally {
            _ui.update { it.copy(busy = false, message = message) }
        }
    }

    private fun problemText(problem: StoreProblem): String = when (problem) {
        StoreProblem.OFFLINE -> tr("You seem to be offline. Connect to the internet and try again.")
        StoreProblem.UNAVAILABLE -> tr("The app store isn't available on this phone right now.")
        StoreProblem.NOT_SET_UP -> tr("PawPixel Pro isn't available yet. Everything else in PawPixel works as usual.")
        StoreProblem.ERROR -> tr("The store couldn't finish that. Please try again in a moment.")
    }

    private companion object {
        /** How long a quiet check (price, what's owned) waits for the store. Purchases wait for the owner. */
        const val STORE_TIMEOUT_MS = 20_000L
    }
}
