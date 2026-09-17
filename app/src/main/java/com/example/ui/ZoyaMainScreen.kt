package com.example.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AssistantMode
import com.example.ui.theme.ZoyaAmber
import com.example.ui.theme.ZoyaBackground
import com.example.ui.theme.ZoyaElectricCyan
import com.example.ui.theme.ZoyaEmerald
import com.example.ui.theme.ZoyaNeonRose
import com.example.ui.theme.ZoyaSurface
import com.example.ui.theme.ZoyaSurfaceHighlight
import com.example.ui.theme.ZoyaSurfaceVariant
import com.example.ui.theme.ZoyaTextMuted
import com.example.ui.theme.ZoyaTextPrimary
import com.example.ui.theme.ZoyaTextSecondary
import com.example.ui.theme.ZoyaViolet
import com.example.viewmodel.ZoyaViewModel

@Composable
fun ZoyaMainScreen(viewModel: ZoyaViewModel) {
    val state by viewModel.uiState.collectAsState()

    // Runtime Permission Launcher
    val permissionsToRequest = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onPermissionsResult()
    }

    // Permission Onboarding Dialog
    if (state.showPermissionsSheet) {
        PermissionsOnboardingDialog(
            permissions = state.permissions,
            onRequestPermissions = {
                permissionLauncher.launch(permissionsToRequest)
            },
            onDismiss = {
                viewModel.dismissPermissionsSheet()
            }
        )
    }

    Scaffold(
        containerColor = ZoyaBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.fillMaxSize()
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            ZoyaSurfaceVariant.copy(alpha = 0.45f),
                            ZoyaSurface.copy(alpha = 0.8f),
                            ZoyaBackground
                        ),
                        radius = 1600f
                    )
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Navigation / Status Header
                TopHeaderBar(
                    isBackgroundRunning = state.isBackgroundServiceRunning,
                    onToggleBackground = { viewModel.toggleBackgroundService() },
                    onOpenSecurity = { viewModel.showPermissionsSheet() }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Wake-word & Live Status Badge
                WakeWordStatusBar(
                    mode = state.mode,
                    isBackgroundRunning = state.isBackgroundServiceRunning
                )

                Spacer(modifier = Modifier.weight(0.7f))

                // Central Animated Mic/Orb
                ZoyaOrbCanvas(
                    mode = state.mode,
                    micAmplitude = state.micAmplitude,
                    speakerAmplitude = state.speakerAmplitude,
                    onOrbClick = { viewModel.toggleOrbTap() },
                    modifier = Modifier.padding(16.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // State Indicator Pill
                StateBadge(mode = state.mode)

                Spacer(modifier = Modifier.height(18.dp))

                // Sassy Voice Dialogue Pill
                SassySpeechBubble(
                    sassyQuote = state.sassyQuote,
                    statusText = state.statusText,
                    mode = state.mode
                )

                Spacer(modifier = Modifier.weight(1f))

                // Quick Voice Intent Suggestions Bar
                QuickVoiceActionChips(
                    onCommandSelected = { cmd ->
                        viewModel.executeQuickVoiceCommand(cmd)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TopHeaderBar(
    isBackgroundRunning: Boolean,
    onToggleBackground: () -> Unit,
    onOpenSecurity: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // App Title & Brand
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isBackgroundRunning) ZoyaElectricCyan else ZoyaNeonRose)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ZOYA",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = ZoyaTextPrimary,
                    letterSpacing = 2.sp
                )
            }
            Text(
                text = "VOICE-DRIVEN AI COMPANION",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = ZoyaElectricCyan,
                letterSpacing = 1.sp
            )
        }

        // Action Buttons
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Background service toggle button
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isBackgroundRunning) ZoyaElectricCyan.copy(alpha = 0.15f) else ZoyaSurfaceVariant,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isBackgroundRunning) ZoyaElectricCyan.copy(alpha = 0.6f) else Color.Transparent
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onToggleBackground() }
                    .testTag("toggle_background_service_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Background Listening",
                        tint = if (isBackgroundRunning) ZoyaElectricCyan else ZoyaTextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBackgroundRunning) "Background ON" else "Background OFF",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isBackgroundRunning) ZoyaElectricCyan else ZoyaTextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Permissions / Guardrails Button
            IconButton(
                onClick = onOpenSecurity,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(ZoyaSurfaceVariant)
                    .testTag("permissions_sheet_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Permissions Onboarding",
                    tint = ZoyaTextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun WakeWordStatusBar(
    mode: AssistantMode,
    isBackgroundRunning: Boolean
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ZoyaSurfaceVariant.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        ZoyaNeonRose.copy(alpha = 0.3f),
                        ZoyaViolet.copy(alpha = 0.2f),
                        ZoyaElectricCyan.copy(alpha = 0.3f)
                    )
                ),
                RoundedCornerShape(16.dp)
            )
            .testTag("wake_word_status_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = "Wake-word",
                    tint = ZoyaElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Wake-word: \"Zoya\"",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZoyaTextPrimary
                    )
                    Text(
                        text = if (isBackgroundRunning) "Always listening in background" else "Foreground listening active",
                        fontSize = 10.sp,
                        color = ZoyaTextSecondary
                    )
                }
            }

            // Pulsing live indicator
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (mode != AssistantMode.IDLE) ZoyaNeonRose.copy(alpha = 0.2f) else ZoyaEmerald.copy(
                            alpha = 0.2f
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (mode != AssistantMode.IDLE) "ACTIVE" else "READY",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = if (mode != AssistantMode.IDLE) ZoyaNeonRose else ZoyaEmerald,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun StateBadge(mode: AssistantMode) {
    val (label, color) = when (mode) {
        AssistantMode.IDLE -> "STANDBY • TAP ORB TO SPEAK" to ZoyaTextSecondary
        AssistantMode.LISTENING -> "LISTENING..." to ZoyaElectricCyan
        AssistantMode.THINKING -> "THINKING & PROCESSING..." to ZoyaViolet
        AssistantMode.SPEAKING -> "ZOYA SPEAKING" to ZoyaNeonRose
    }

    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(400),
        label = "badgeColor"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(animatedColor.copy(alpha = 0.15f))
            .border(1.dp, animatedColor.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = animatedColor,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun SassySpeechBubble(
    sassyQuote: String,
    statusText: String,
    mode: AssistantMode
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = ZoyaSurface.copy(alpha = 0.95f)),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        ZoyaNeonRose.copy(alpha = 0.4f),
                        ZoyaElectricCyan.copy(alpha = 0.3f)
                    )
                ),
                RoundedCornerShape(22.dp)
            )
            .testTag("sassy_quote_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "\"$sassyQuote\"",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = ZoyaTextPrimary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = statusText,
                fontSize = 11.sp,
                color = ZoyaTextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun QuickVoiceActionChips(
    onCommandSelected: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "QUICK VOICE INTENTS",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = ZoyaTextMuted,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionChip("📺 Open YouTube") { onCommandSelected("Open YouTube") }
            ActionChip("📸 Open Instagram") { onCommandSelected("Open Instagram") }
            ActionChip("📞 Call Contact") { onCommandSelected("Call Mom") }
            ActionChip("💬 WhatsApp Message") { onCommandSelected("Send WhatsApp to Sarah") }
            ActionChip("✉️ Send Gmail") { onCommandSelected("Send Gmail to team") }
            ActionChip("✨ Who are you?") { onCommandSelected("Who are you, Zoya?") }
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ZoyaSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, ZoyaSurfaceHighlight),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = ZoyaTextPrimary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
