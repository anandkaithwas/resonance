package dev.resonance.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ResonanceApp() }
    }
}

@Composable
private fun ResonanceApp() {
    var section by remember { mutableStateOf("Home") }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text("RESONANCE", style = MaterialTheme.typography.headlineLarge)
                Text("Your music. Your rules.", style = MaterialTheme.typography.titleMedium)
                Text("FOUNDATION BUILD · 0.1.0", style = MaterialTheme.typography.labelSmall)
                Text("Selected workspace: $section", style = MaterialTheme.typography.titleLarge)
                listOf("Home", "Player", "AI Curator", "AI DJ", "Visualiser", "Song Studio").forEach { item ->
                    Button(onClick = { section = item }) { Text(item) }
                }
                Text(
                    "Development shell only. Real playback, audio analysis, DJ mixing, visualisation and AI generation are not implemented yet.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
