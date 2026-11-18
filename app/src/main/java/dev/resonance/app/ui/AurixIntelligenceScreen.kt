package dev.resonance.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.resonance.app.intelligence.SoundReality
import dev.resonance.app.playback.ResonanceVisualizerTap
import java.util.Locale

@Composable
fun AurixIntelligenceScreen() {
    val engine = ResonanceVisualizerTap.intelligence
    val insight by ResonanceVisualizerTap.insight.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Aurix Intelligence", style = MaterialTheme.typography.headlineSmall)
        Text("On-device sound intelligence · no cloud processing", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("MoodSync · Dynamic EQ", style = MaterialTheme.typography.titleMedium)
                        Text(if (engine.enabled) "Adapting to the live audio signal" else "DSP bypassed")
                    }
                    Switch(checked = engine.enabled, onCheckedChange = { engine.enabled = it })
                }
                Text("Detected mood: ${insight.mood.name.lowercase().replace('_', ' ')}")
                Text("EQ · Bass ${String.format(Locale.US, "%.1f", insight.gainsDb[0])} dB  ·  Mid ${String.format(Locale.US, "%.1f", insight.gainsDb[1])} dB  ·  Treble ${String.format(Locale.US, "%.1f", insight.gainsDb[2])} dB")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reality · Sound scenes", style = MaterialTheme.typography.titleMedium)
                Text("Select a local DSP curve. These are sound profiles, not room simulation.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SoundReality.entries.take(2).forEach { scene ->
                        Button(onClick = { engine.reality = scene }) { Text(scene.name) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SoundReality.entries.drop(2).forEach { scene ->
                        Button(onClick = { engine.reality = scene }) { Text(scene.name) }
                    }
                }
                Text("Active scene: ${insight.scene.name}")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("MusicDNA · Live fingerprint", style = MaterialTheme.typography.titleMedium)
                Text("Bass energy  ${(insight.bass * 100).toInt()}%")
                Text("Mid energy  ${(insight.mid * 100).toInt()}%")
                Text("Treble energy  ${(insight.treble * 100).toInt()}%")
                Text("Signal level  ${(insight.rms * 100).toInt()}%")
                Text("Track-level BPM, key and saved similarity profiles are planned next.")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SoundUniverse", style = MaterialTheme.typography.titleMedium)
                Text("The existing visualiser remains available in the Visualiser tab. This engine now publishes post-EQ audio insights for its next reactive visual layer.")
                Text("SmartMix and offline voice-command recognition are not enabled in this phase.")
            }
        }
    }
}
