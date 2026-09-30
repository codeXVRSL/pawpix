package com.pawpixel

import com.pawpixel.core.AppState
import com.pawpixel.core.Entitlement
import com.pawpixel.core.HouseholdSync
import com.pawpixel.core.Milestones
import com.pawpixel.core.Ownership
import com.pawpixel.core.Pet
import com.pawpixel.core.ProEntitlement
import com.pawpixel.core.Settings
import com.pawpixel.core.SharedData
import com.pawpixel.core.Species
import com.pawpixel.core.StateCodec
import com.pawpixel.core.StateOps
import com.pawpixel.core.StoreEvent
import com.pawpixel.sprite.Accessory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProTest {
    private val none = Entitlement()
    private val owned = Entitlement(owned = true)
    private val pending = Entitlement(pending = true)

    @Test fun buyingNowOwnsPro() {
        assertEquals(owned, ProEntitlement.next(none, StoreEvent.Purchase(Ownership.OWNED)))
    }

    @Test fun cashAtSevenElevenWaitsThenOwns() {
        var e = ProEntitlement.next(none, StoreEvent.Purchase(Ownership.PENDING))
        assertEquals(pending, e)
        // The app restarts before the owner pays: the store still says pending.
        e = ProEntitlement.next(e, StoreEvent.Checked(Ownership.PENDING))
        assertEquals(pending, e)
        // Paid at the counter: the store tells the app.
        e = ProEntitlement.next(e, StoreEvent.Purchase(Ownership.OWNED))
        assertEquals(owned, e)
    }

    @Test fun anUnpaidPurchaseThatExpiresEndsTheWait() {
        assertEquals(none, ProEntitlement.next(pending, StoreEvent.Purchase(Ownership.NONE)))
        // The same news never takes away Pro already owned.
        assertEquals(owned, ProEntitlement.next(owned, StoreEvent.Purchase(Ownership.NONE)))
        assertEquals(owned, ProEntitlement.next(owned, StoreEvent.Purchase(Ownership.PENDING)))
    }

    @Test fun refundsAndRevocationsDropPro() {
        assertEquals(none, ProEntitlement.next(owned, StoreEvent.Revoked))
        // A refund while the app was closed: the next check no longer lists it.
        assertEquals(none, ProEntitlement.next(owned, StoreEvent.Checked(Ownership.NONE)))
    }

    @Test fun restoreOnANewPhoneOwnsPro() {
        assertEquals(owned, ProEntitlement.next(none, StoreEvent.Checked(Ownership.OWNED)))
        assertEquals(owned, ProEntitlement.next(pending, StoreEvent.Checked(Ownership.OWNED)))
    }

    @Test fun offlineKeepsTheCachedAnswer() {
        for (e in listOf(none, owned, pending)) assertEquals(e, ProEntitlement.next(e, StoreEvent.Unreachable))
        // The cache is the saved settings: it survives a restart.
        val s = ProEntitlement.apply(AppState(), StoreEvent.Purchase(Ownership.OWNED))
        val reloaded = StateCodec.decode(StateCodec.encode(s))
        assertTrue(reloaded.settings.pro)
        assertEquals(s, ProEntitlement.apply(reloaded, StoreEvent.Unreachable))
        val waiting = StateCodec.decode(StateCodec.encode(ProEntitlement.apply(AppState(), StoreEvent.Purchase(Ownership.PENDING))))
        assertTrue(waiting.settings.proPending)
        assertFalse(waiting.settings.pro)
    }

    @Test fun nothingChangedIsTheSameState() {
        val s = AppState(settings = Settings(pro = true))
        assertSame(s, ProEntitlement.apply(s, StoreEvent.Checked(Ownership.OWNED)))
    }

    @Test fun losingProNeverTakesPetsOrOutfitsAway() {
        val pets = listOf(Pet("a", "Mochi", Species.CAT, 0, accessory = "SALAKOT"), Pet("b", "Bantay", Species.DOG, 0))
        val s = AppState(pets = pets, settings = Settings(pro = true))
        val refunded = ProEntitlement.apply(s, StoreEvent.Revoked)
        assertFalse(refunded.settings.pro)
        assertEquals(pets, refunded.pets)
        // No new pets without Pro, but everyone already here stays.
        assertFalse(StateOps.canAddPet(refunded))
    }

    @Test fun theFreeLimitCountsOnlyThisPhonesOwnPets() {
        val mine = Pet("a", "Mochi", Species.CAT, 0)
        val family = Pet("b", "Bantay", Species.DOG, 0, shared = true, fromHousehold = true)
        assertTrue(StateOps.canAddPet(AppState()))
        assertTrue(StateOps.canAddPet(AppState(pets = listOf(family))), "a family's pet is never behind Pro")
        assertEquals(1, AppState.FREE_PET_LIMIT)
        assertFalse(StateOps.canAddPet(AppState(pets = listOf(mine, family))))
        assertTrue(StateOps.canAddPet(AppState(pets = listOf(mine, family), settings = Settings(pro = true))))
        assertEquals(family, StateCodec.decode(StateCodec.encode(AppState(pets = listOf(family)))).pets.single())
    }

    @Test fun petsFromTheHouseholdAreMarkedAndTheMarkIsNeverShared() {
        val theirs = Pet("b", "Bantay", Species.DOG, 0, shared = true, lookCode = "1;b0703c,f4f1ea;" + "0".repeat(64))
        val merged = HouseholdSync.merge(AppState(), SharedData(), SharedData(pets = listOf(theirs)))
        val arrived = merged.state.pets.single()
        assertTrue(arrived.fromHousehold)
        assertTrue(merged.push.isEmpty, "the mark isn't sent back")
        assertTrue(StateOps.canAddPet(merged.state))
        // Someone renames it: it stays marked here.
        val again = HouseholdSync.merge(merged.state, merged.base, SharedData(pets = listOf(theirs.copy(name = "Bantay Jr", editedAtMs = 5))))
        assertTrue(again.state.pets.single().fromHousehold)
        assertEquals("Bantay Jr", again.state.pets.single().name)
        // A pet made on this phone and shared isn't marked.
        val own = Pet("a", "Mochi", Species.CAT, 0, shared = true)
        val shared = HouseholdSync.merge(AppState(pets = listOf(own)), SharedData(), SharedData())
        assertFalse(shared.state.pets.single().fromHousehold)
        assertFalse(shared.push.upsertPets.single().fromHousehold)
    }

    @Test fun proOutfitsNeedProAndStayOn() {
        val pet = Pet("a", "Mochi", Species.CAT, 0)
        var s = AppState(pets = listOf(pet))
        assertEquals(listOf("SALAKOT", "SAMPAGUITA", "PAROL"), Milestones.proOutfits.map { it.name })
        assertTrue(Milestones.unlocked(pet).none { it.pro }, "never earned with days of care")
        s = Milestones.wear(s, "a", Accessory.SALAKOT)
        assertNull(s.pets[0].accessory, "not without Pro")
        s = s.copy(settings = s.settings.copy(pro = true))
        s = Milestones.wear(s, "a", Accessory.SALAKOT)
        assertEquals("SALAKOT", s.pets[0].accessory)
        assertEquals(2, s.pets[0].spriteVersion)
        // A refund doesn't take it off; it just can't be put back on after changing.
        s = ProEntitlement.apply(s, StoreEvent.Revoked)
        assertEquals("SALAKOT", s.pets[0].accessory)
        assertEquals(s, StateCodec.decode(StateCodec.encode(s)))
        // Each one is drawn on the pet.
        val look = com.pawpixel.sprite.PetLook.decode("1;b0703c,f4f1ea;" + "0".repeat(40) + "1".repeat(24))!!
        val plain = com.pawpixel.sprite.PetArt(look, Species.CAT).still
        for (a in Milestones.proOutfits) {
            val worn = com.pawpixel.sprite.PetArt(look, Species.DOG, null, a)
            assertFalse(plain.pixels.contentEquals(com.pawpixel.sprite.PetArt(look, Species.CAT, null, a).still.pixels), a.name)
            assertFalse(com.pawpixel.sprite.Chibi.sleeping(com.pawpixel.sprite.PetArt(look, Species.DOG)).pixels
                .contentEquals(com.pawpixel.sprite.Chibi.sleeping(worn).pixels), "${a.name} asleep")
        }
    }

    @Test fun restoreThenRefundThenRestoreAgain() {
        var s = AppState(pets = listOf(Pet("a", "Mochi", Species.CAT, 0)))
        s = ProEntitlement.apply(s, StoreEvent.Checked(Ownership.OWNED))
        assertTrue(s.settings.pro)
        s = ProEntitlement.apply(s, StoreEvent.Revoked)
        assertFalse(s.settings.pro)
        assertEquals(1, s.pets.size)
        s = ProEntitlement.apply(s, StoreEvent.Checked(Ownership.OWNED))
        assertTrue(s.settings.pro)
        assertFalse(s.settings.proPending)
    }

    @Test fun aPendingPaymentThatNeverCameIsGoneAtTheNextCheck() {
        assertEquals(none, ProEntitlement.next(pending, StoreEvent.Checked(Ownership.NONE)))
        // Offline while waiting: still waiting.
        assertEquals(pending, ProEntitlement.next(pending, StoreEvent.Unreachable))
    }

    @Test fun settingsScreensNeverTurnProBack() {
        val stale = Settings() // drawn before the store's answer
        val owned = AppState(settings = Settings(pro = true))
        val after = StateOps.setSettings(owned, stale.copy(nightStart = 21 * 60))
        assertTrue(after.settings.pro)
        assertEquals(21 * 60, after.settings.nightStart)
        val waiting = AppState(settings = Settings(proPending = true))
        assertTrue(StateOps.setSettings(waiting, Settings(pro = true)).settings.proPending)
        assertFalse(StateOps.setSettings(waiting, Settings(pro = true)).settings.pro, "only the store turns Pro on")
    }

    @Test fun theTestBuildUnlockHoldsUntilSwitchedOffAndNeverReachesARelease() {
        var s = ProEntitlement.testUnlock(AppState(), true)
        assertTrue(s.settings.pro)
        assertTrue(s.settings.proTestUnlock)
        // The (missing) store says no: the test unlock stays.
        assertSame(s, ProEntitlement.apply(s, StoreEvent.Checked(Ownership.NONE)))
        assertSame(s, ProEntitlement.apply(s, StoreEvent.Revoked))
        s = StateCodec.decode(StateCodec.encode(s))
        assertTrue(s.settings.proTestUnlock)
        // Off: no Pro until the store says so.
        val off = ProEntitlement.testUnlock(s, false)
        assertFalse(off.settings.pro)
        assertTrue(ProEntitlement.apply(off, StoreEvent.Checked(Ownership.OWNED)).settings.pro)
        // A release build clears it.
        val release = ProEntitlement.forRelease(s)
        assertFalse(release.settings.pro)
        assertFalse(release.settings.proTestUnlock)
        val bought = AppState(settings = Settings(pro = true))
        assertSame(bought, ProEntitlement.forRelease(bought), "a real purchase is untouched")
    }
}
