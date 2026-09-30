package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AppState
import com.pawpixel.core.Milestones
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.Ears
import com.pawpixel.sprite.PetArt

/**
 * PawPixel Pro, plainly: what it adds, the store's price, "one-time, no subscription", Buy and
 * Restore. Opened only when the owner tries a Pro thing or picks it in Settings; never on its own.
 */
@Composable
fun ProScreen(app: AppScope, state: AppState) {
    val pro = app.repo.pro
    val ui by pro.ui.collectAsState()
    val owned = state.settings.pro
    val pending = state.settings.proPending && !owned
    LaunchedEffect(Unit) { pro.loadPrice() }
    DisposableEffect(Unit) { onDispose { pro.clearMessage() } }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(app)
            ScreenTitle(tr("PawPixel Pro"), Modifier.weight(1f))
        }

        ProOutfitPreview(app, state)

        PixelCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(tr("What you get"))
                Text(tr("🐾 More pets: care for all your pets here, not just your first."))
                Text(tr("👒 Pro outfits: a salakot, a sampaguita garland and a parol, for every pet."))
                Text(tr("💛 Support a solo developer in Naga City."))
            }
        }
        Text(
            tr("Always free, with or without Pro: care, reminders, health records, backups and household sharing. Nothing you've made is ever locked."),
            style = MaterialTheme.typography.bodySmall,
        )

        when {
            owned -> PixelCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(tr("✅ You have PawPixel Pro. Thank you for supporting PawPixel!"), fontWeight = FontWeight.Bold)
            }
            pending -> PixelCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("⏳ Waiting for your payment"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(tr("Paying with cash at 7-Eleven or ECPay? Pay with the code the store gave you. Pro turns on by itself once your payment arrives."))
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val price = ui.price
                when {
                    price != null -> Text(price, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    ui.priceProblem != null -> Text(ui.priceProblem!!, color = MaterialTheme.colorScheme.error)
                    else -> Text(tr("Getting the price…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(tr("One-time purchase. No subscription."), fontWeight = FontWeight.Bold)
                if (price == null && ui.priceProblem != null) {
                    OutlinedButton(onClick = { app.launch { pro.loadPrice() } }) { Text(tr("Try again")) }
                } else {
                    Button(
                        onClick = { app.launch { pro.buy() } },
                        enabled = price != null && !ui.busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (price != null) tr("Buy PawPixel Pro · {0}", price) else tr("Buy PawPixel Pro")) }
                }
            }
        }

        if (ui.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(tr("Waiting for the store…"), style = MaterialTheme.typography.bodySmall)
            }
        }
        ui.message?.let {
            Text(it, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }

        OutlinedButton(onClick = { app.launch { pro.restore() } }, enabled = !ui.busy) { Text(tr("Restore purchase")) }
        Text(
            tr("Bought Pro before, on this phone or another one with the same store account? Restore it here, free."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The owner's own pet in each Pro outfit (nothing when there's no pet yet). */
@Composable
private fun ProOutfitPreview(app: AppScope, state: AppState) {
    val pet = state.pets.firstOrNull() ?: return
    val looks = remember(pet.lookKey) {
        app.repo.art(pet)?.let { art -> Milestones.proOutfits.map { it to PetArt(art.look, pet.species, Ears.of(pet.ears), it).still } }
    } ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for ((outfit, image) in looks) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SpriteView(image, Modifier.width(96.dp).height(80.dp), animate = false, description = tr("{0} in a {1}", pet.name, tr(outfit.label)))
                Text(tr(outfit.label), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
