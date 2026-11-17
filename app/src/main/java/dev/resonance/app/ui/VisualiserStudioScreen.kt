package dev.resonance.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.resonance.app.playback.ResonanceVisualizerTap
import kotlin.math.cos
import kotlin.math.sin

enum class VisualizerTheme(val label: String) {
    NEON_BARS("Neon Bars"), WAVE_HALO("Waveform Halo"), MIRROR_PULSE("Mirror Pulse")
}

@Composable
fun VisualiserStudioScreen(modifier: Modifier = Modifier) {
    val amplitudes by ResonanceVisualizerTap.amplitudes.collectAsState()
    var theme by remember { mutableStateOf(VisualizerTheme.NEON_BARS) }
    var sensitivity by remember { mutableFloatStateOf(1.4f) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Visualiser Studio", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VisualizerTheme.entries.forEach { item ->
                FilterChip(selected = theme == item, onClick = { theme = item }, label = { Text(item.label) })
            }
        }
        Card(modifier = Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1117))) {
            Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                val primary = Color(0xFF00E5FF)
                val secondary = Color(0xFF7C4DFF)
                when (theme) {
                    VisualizerTheme.NEON_BARS -> {
                        val spacing = 8f
                        val barWidth = (size.width - spacing * (amplitudes.size - 1)) / amplitudes.size
                        amplitudes.forEachIndexed { idx, raw ->
                            val amp = (raw * sensitivity).coerceIn(0.05f, 1f)
                            val h = size.height * 0.8f * amp
                            drawRoundRect(
                                brush = Brush.verticalGradient(listOf(primary, secondary)),
                                topLeft = Offset(idx * (barWidth + spacing), (size.height - h) / 2f),
                                size = Size(barWidth, h),
                                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                            )
                        }
                    }
                    VisualizerTheme.WAVE_HALO -> {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val baseRadius = size.minDimension * 0.24f
                        drawCircle(color = secondary.copy(alpha = 0.35f), radius = baseRadius, center = center, style = Stroke(width = 4f))
                        amplitudes.forEachIndexed { idx, raw ->
                            val angle = (2.0 * Math.PI * idx) / amplitudes.size
                            val spike = (raw * sensitivity).coerceIn(0.05f, 1f) * (size.minDimension * 0.2f)
                            drawLine(
                                color = primary,
                                start = Offset(center.x + (cos(angle) * baseRadius).toFloat(), center.y + (sin(angle) * baseRadius).toFloat()),
                                end = Offset(center.x + (cos(angle) * (baseRadius + spike)).toFloat(), center.y + (sin(angle) * (baseRadius + spike)).toFloat()),
                                strokeWidth = 6f, cap = StrokeCap.Round
                            )
                        }
                    }
                    VisualizerTheme.MIRROR_PULSE -> {
                        val midY = size.height / 2f
                        val stepX = size.width / amplitudes.size
                        amplitudes.forEachIndexed { idx, raw ->
                            val amp = (raw * sensitivity).coerceIn(0.05f, 1f) * midY * 0.85f
                            val x = idx * stepX + stepX / 2f
                            drawLine(
                                brush = Brush.verticalGradient(listOf(primary, secondary)),
                                start = Offset(x, midY - amp), end = Offset(x, midY + amp),
                                strokeWidth = stepX * 0.6f, cap = StrokeCap.Round
                            )
                        }
                    }
                }
            }
        }
        Text("Reactivity Gain: ${"%.1f".format(sensitivity)}x")
        Slider(value = sensitivity, onValueChange = { sensitivity = it }, valueRange = 0.5f..2.5f)
    }
}
