package com.pawpixel.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.pawpixel.i18n.tr

/**
 * What the law asks and where to go, for dog and cat owners in the Philippines (the pilot is in
 * Naga City). Checked as of 2026; the card says so, since fees and rules change.
 *
 * Sources:
 * - RA 9482, the Anti-Rabies Act of 2007, Section 5 (owners' duties):
 *   https://lawphil.net/statutes/repacts/ra2007/ra_9482_2007.html
 * - Naga City Veterinary Office, services and fees (anti-rabies ₱75 for pets 3 months and older,
 *   free consultation, deworming, castration): https://www2.naga.gov.ph/2023-city-service/veterinary-services/
 *   and its citizen's charter: https://www2.naga.gov.ph/wp-content/uploads/2022/02/CC-5E-CVO-Accessing-Veterinary-Services.pdf
 * - Wound care after a bite or scratch (wash 15 minutes with soap and running water), WHO:
 *   https://www.who.int/news-room/fact-sheets/detail/rabies ; the national rabies program (BAI):
 *   https://bai.gov.ph/index.php/component/k2/item/504
 * - March as Rabies Awareness Month: Executive Order No. 84 (1999), https://lawphil.net/executive/execord/eo1999/eo_84_1999.html
 */
@Composable
fun PhilippineInfoCard(app: AppScope) {
    var open by rememberSaveable { mutableStateOf(false) }
    PixelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = if (open) tr("Hide") else tr("Show"), role = Role.Button) { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("Rabies rules and where to get shots"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (open) "▲" else "▼")
            }
            if (open) {
                Text(
                    tr("For the Philippines, as of 2026. Rules and fees can change: ask your city or municipal vet."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(tr("The Anti-Rabies Act (RA 9482) asks every dog owner to:"), style = MaterialTheme.typography.bodyMedium)
                Bullets(
                    tr("register the dog with the city or municipality"),
                    tr("have it vaccinated against rabies regularly, and keep the card"),
                    tr("keep it on a leash in public"),
                    tr("report a bite within 24 hours"),
                )
                Text(tr("Cats need anti-rabies shots too."), style = MaterialTheme.typography.bodySmall)

                Text(tr("Bitten or scratched?"), fontWeight = FontWeight.Bold)
                Text(
                    tr("Wash the wound with soap and running water for 15 minutes and go to the nearest Animal Bite Treatment Center today."),
                    style = MaterialTheme.typography.bodySmall,
                )

                Text(tr("Naga City Veterinary Office"), fontWeight = FontWeight.Bold)
                val linkColor = MaterialTheme.colorScheme.primary
                // Selectable, so the address can be copied into a map or a message.
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Maharlika Hwy, Del Rosario, Naga City", style = MaterialTheme.typography.bodySmall)
                        Text(
                            buildAnnotatedString {
                                withLink(LinkAnnotation.Clickable(
                                    tag = "mail",
                                    styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                                    linkInteractionListener = { app.repo.platform.openUrl("mailto:$VET_EMAIL") },
                                )) { append(VET_EMAIL) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Bullets(
                            tr("Anti-rabies shot: ₱75, walk-in, for healthy pets 3 months and older"),
                            tr("Free consultation"),
                            tr("Deworming and castration are offered too"),
                        )
                    }
                }
                Text(
                    tr("March is Rabies Awareness Month: free anti-rabies shots are often offered. Check your barangay."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Bullets(vararg lines: String) {
    Text(lines.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall)
}

private const val VET_EMAIL = "cvo@naga.gov.ph"
