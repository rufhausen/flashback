package dev.gtaylor.flashback.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gtaylor.flashback.KarooLight
import dev.gtaylor.flashback.data.AfterAlert
import dev.gtaylor.flashback.data.FlashbackSettings
import dev.gtaylor.flashback.karoo.AntLightMode

private val FALLBACK_ALERT_MODES = listOf("FAST_FLASH", "SLOW_FLASH", "RANDOM_FLASH")

@Composable
fun SettingsScreen(
    settings: FlashbackSettings,
    lights: List<KarooLight>,
    supportedModes: Map<String, Set<String>>,
    radarStatus: String,
    onChange: ((FlashbackSettings) -> FlashbackSettings) -> Unit,
    onTest: (deviceId: String, mode: String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Flashback", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Flashes your rear light only while the radar sees a vehicle. Otherwise Karoo stays in charge.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Radar: $radarStatus", style = MaterialTheme.typography.bodySmall)

        LabeledSwitch("Enabled", settings.enabled) { on -> onChange { it.copy(enabled = on) } }
        HorizontalDivider()

        SectionTitle("Lights to flash")
        if (lights.isEmpty()) {
            Text(
                "No ANT+ lights found. Pair your Varia under Karoo Settings › Sensors, make sure it's on, " +
                    "then reopen this screen.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        lights.forEach { light ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = light.id in settings.lightIds,
                    onCheckedChange = { checked ->
                        onChange {
                            it.copy(lightIds = if (checked) it.lightIds + light.id else it.lightIds - light.id)
                        }
                    },
                )
                Column {
                    Text(light.name)
                    light.manufacturer?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        HorizontalDivider()

        SectionTitle("Flash mode")
        val selected = settings.lightIds.firstOrNull { id -> lights.any { it.id == id } }
        val modes =
            selected
                ?.let { supportedModes[it] }
                ?.filter { it != "OFF" }
                ?.sortedBy { AntLightMode.fromKarooName(it)?.modeNumber ?: Int.MAX_VALUE }
                ?.takeIf { it.isNotEmpty() }
                ?: FALLBACK_ALERT_MODES
        ChipGroup(
            options = modes.map { it to (AntLightMode.fromKarooName(it)?.displayName ?: it) },
            selected = settings.alertMode,
        ) { mode -> onChange { it.copy(alertMode = mode) } }
        OutlinedButton(enabled = selected != null, onClick = { selected?.let { onTest(it, settings.alertMode) } }) {
            Text("Test flash (3s)")
        }
        HorizontalDivider()

        SectionTitle("Trigger on")
        ChipGroup(
            options = listOf(1 to "Any vehicle", 2 to "Fast approach only"),
            selected = settings.minThreatLevel,
        ) { level -> onChange { it.copy(minThreatLevel = level) } }

        SectionTitle("After the vehicle passes")
        ChipGroup(
            options = listOf(AfterAlert.RESTORE_PREVIOUS to "Restore previous", AfterAlert.OFF to "Turn off"),
            selected = settings.afterAlert,
        ) { after -> onChange { it.copy(afterAlert = after) } }

        SectionTitle("Keep flashing after clear")
        ChipGroup(
            options = listOf(0, 3, 5, 10).map { it to "${it}s" },
            selected = settings.clearHoldSeconds,
        ) { secs -> onChange { it.copy(clearHoldSeconds = secs) } }
        HorizontalDivider()

        Text(
            "Tip: if you want the light dark until a car shows up, set the Varia's light to Off " +
                "(or Auto Control off) in Karoo's sensor settings. Flashback only acts during a ride.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.padding(8.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}
