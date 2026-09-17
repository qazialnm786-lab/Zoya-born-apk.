package com.example.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.AssistantMode
import com.example.ui.theme.ZoyaElectricCyan
import com.example.ui.theme.ZoyaNeonRose
import com.example.ui.theme.ZoyaViolet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ZoyaOrbCanvas(
    mode: AssistantMode,
    micAmplitude: Float,
    speakerAmplitude: Float,
    onOrbClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_transition")

    // Slow subtle breathing glow for IDLE
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingScale"
    )

    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingAlpha"
    )

    // Rotational sweep for THINKING
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotationAngle"
    )

    // Pulsing ring for SPEAKING / LISTENING
    val pulseWave by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseWave"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .testTag("zoya_orb_button")
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = 140.dp),
                onClick = onOrbClick
            )
    ) {
        Canvas(modifier = Modifier.size(280.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension / 3.4f

            when (mode) {
                AssistantMode.IDLE -> {
                    // IDLE: Slow subtle breathing glow & concentric faint rings
                    val currentRadius = baseRadius * breathingScale

                    // Outer ambient glow
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ZoyaNeonRose.copy(alpha = breathingAlpha * 0.45f),
                                ZoyaViolet.copy(alpha = breathingAlpha * 0.2f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = currentRadius * 1.5f
                        ),
                        center = center,
                        radius = currentRadius * 1.5f
                    )

                    // Faint celestial guide rings
                    drawCircle(
                        color = ZoyaElectricCyan.copy(alpha = 0.15f * breathingAlpha),
                        radius = currentRadius * 1.25f,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )

                    // Core luminous orb
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.9f),
                                ZoyaNeonRose,
                                ZoyaViolet
                            ),
                            center = center,
                            radius = currentRadius
                        ),
                        center = center,
                        radius = currentRadius
                    )
                }

                AssistantMode.LISTENING -> {
                    // LISTENING: Active listening waveform responding to mic input frequency & amplitude
                    val responsiveAmp = micAmplitude.coerceIn(0.05f, 1f)
                    val activeRadius = baseRadius * (1f + responsiveAmp * 0.35f)

                    // Outer responsive glow
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ZoyaElectricCyan.copy(alpha = 0.5f + responsiveAmp * 0.4f),
                                ZoyaNeonRose.copy(alpha = 0.2f + responsiveAmp * 0.3f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = activeRadius * 1.6f
                        ),
                        center = center,
                        radius = activeRadius * 1.6f
                    )

                    // Radial waveform bars around the orb
                    val barCount = 48
                    for (i in 0 until barCount) {
                        val angle = (i * 2 * PI / barCount).toFloat()
                        // Sinusoidal frequency simulation modulated by mic amplitude
                        val waveOffset = sin((i * 4f) + (pulseWave * 2 * PI.toFloat())) * 24.dp.toPx() * responsiveAmp
                        val innerR = activeRadius * 1.05f
                        val outerR = activeRadius * 1.15f + waveOffset.coerceAtLeast(4f)

                        val start = Offset(center.x + innerR * cos(angle), center.y + innerR * sin(angle))
                        val end = Offset(center.x + outerR * cos(angle), center.y + outerR * sin(angle))

                        val barColor = if (i % 2 == 0) ZoyaElectricCyan else ZoyaNeonRose
                        drawLine(
                            color = barColor.copy(alpha = 0.85f),
                            start = start,
                            end = end,
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    // Dynamic core orb
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White,
                                ZoyaElectricCyan,
                                ZoyaViolet
                            ),
                            center = center,
                            radius = activeRadius
                        ),
                        center = center,
                        radius = activeRadius
                    )
                }

                AssistantMode.THINKING -> {
                    // THINKING/PROCESSING: Pulsing neon ring with rotating sweep
                    val pulseRadius = baseRadius * (0.95f + 0.1f * sin(pulseWave * 2 * PI.toFloat()))

                    // Ambient glow
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ZoyaViolet.copy(alpha = 0.45f),
                                ZoyaNeonRose.copy(alpha = 0.25f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = pulseRadius * 1.5f
                        ),
                        center = center,
                        radius = pulseRadius * 1.5f
                    )

                    // Rotating segmented neon ring
                    val sweepRingRadius = pulseRadius * 1.25f
                    val sweepAngleRad = Math.toRadians(rotationAngle.toDouble()).toFloat()

                    for (j in 0..5) {
                        val segAngle = sweepAngleRad + (j * PI.toFloat() / 3f)
                        val start = Offset(
                            center.x + sweepRingRadius * cos(segAngle),
                            center.y + sweepRingRadius * sin(segAngle)
                        )
                        val end = Offset(
                            center.x + (sweepRingRadius + 14.dp.toPx()) * cos(segAngle),
                            center.y + (sweepRingRadius + 14.dp.toPx()) * sin(segAngle)
                        )
                        drawLine(
                            color = ZoyaElectricCyan,
                            start = start,
                            end = end,
                            strokeWidth = 3.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    // Rotating continuous glowing stroke
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(
                                ZoyaNeonRose,
                                ZoyaViolet,
                                ZoyaElectricCyan,
                                ZoyaNeonRose
                            ),
                            center = center
                        ),
                        radius = sweepRingRadius,
                        center = center,
                        style = Stroke(width = 3.5.dp.toPx())
                    )

                    // Center core
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.95f),
                                ZoyaViolet,
                                ZoyaNeonRose
                            ),
                            center = center,
                            radius = pulseRadius
                        ),
                        center = center,
                        radius = pulseRadius
                    )
                }

                AssistantMode.SPEAKING -> {
                    // SPEAKING: Dynamic audio wave matching Zoya's output stream
                    val responsiveAmp = speakerAmplitude.coerceIn(0.1f, 1f)
                    val speakRadius = baseRadius * (1f + responsiveAmp * 0.4f)

                    // Concentric expanding audio ripples
                    val rippleR1 = baseRadius * (1.1f + pulseWave * 0.6f * (1f + responsiveAmp))
                    val rippleAlpha1 = ((1f - pulseWave) * 0.7f).coerceIn(0f, 1f)
                    drawCircle(
                        color = ZoyaNeonRose.copy(alpha = rippleAlpha1),
                        radius = rippleR1,
                        center = center,
                        style = Stroke(width = 2.5.dp.toPx())
                    )

                    val rippleR2 = baseRadius * (1.1f + ((pulseWave + 0.5f) % 1f) * 0.6f * (1f + responsiveAmp))
                    val rippleAlpha2 = ((1f - ((pulseWave + 0.5f) % 1f)) * 0.7f).coerceIn(0f, 1f)
                    drawCircle(
                        color = ZoyaElectricCyan.copy(alpha = rippleAlpha2),
                        radius = rippleR2,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // Outer voice aura
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ZoyaNeonRose.copy(alpha = 0.55f),
                                ZoyaElectricCyan.copy(alpha = 0.25f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = speakRadius * 1.55f
                        ),
                        center = center,
                        radius = speakRadius * 1.55f
                    )

                    // Core expressive voice orb
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White,
                                ZoyaNeonRose,
                                ZoyaViolet
                            ),
                            center = center,
                            radius = speakRadius
                        ),
                        center = center,
                        radius = speakRadius
                    )
                }
            }
        }

        // Center Icon reflecting current active mode
        val icon = when (mode) {
            AssistantMode.IDLE -> Icons.Default.Mic
            AssistantMode.LISTENING -> Icons.Default.Mic
            AssistantMode.THINKING -> Icons.Default.Psychology
            AssistantMode.SPEAKING -> Icons.Default.VolumeUp
        }

        Icon(
            imageVector = icon,
            contentDescription = "Zoya Assistant State",
            tint = Color.White,
            modifier = Modifier.size(44.dp)
        )
    }
}
