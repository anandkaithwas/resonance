package dev.resonance.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AurixDarkBg = Color(0xFF11151C)
val AurixCyan = Color(0xFF22D3EE)
val AurixPurple = Color(0xFFA855F7)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AurixTopBar(
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = AurixDarkBg,
            titleContentColor = Color.White
        ),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(32.dp)) {
                    val w = size.width
                    val h = size.height
                    drawRoundRect(
                        brush = Brush.linearGradient(listOf(AurixCyan, AurixPurple)),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(w * 0.28f, h * 0.28f)
                    )
                    val barW = w * 0.11f
                    val heights = listOf(0.32f, 0.62f, 0.45f, 0.70f)
                    val startX = w * 0.22f
                    val gap = w * 0.16f
                    heights.forEachIndexed { index, ratio ->
                        val barH = h * ratio
                        drawRoundRect(
                            color = AurixDarkBg,
                            topLeft = Offset(startX + index * gap, (h - barH) / 2f),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            style = SpanStyle(
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        ) {
                            append("Aurix")
                        }
                        withStyle(
                            style = SpanStyle(
                                color = AurixCyan,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 20.sp
                            )
                        ) {
                            append(".studio")
                        }
                    }
                )
            }
        }
    )
}
