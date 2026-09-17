package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ZoyaDarkColorScheme = darkColorScheme(
    primary = ZoyaNeonRose,
    onPrimary = ZoyaTextPrimary,
    primaryContainer = ZoyaSurfaceVariant,
    onPrimaryContainer = ZoyaNeonRoseLight,
    secondary = ZoyaElectricCyan,
    onSecondary = ZoyaBackground,
    secondaryContainer = ZoyaSurfaceHighlight,
    onSecondaryContainer = ZoyaCyanLight,
    tertiary = ZoyaViolet,
    onTertiary = ZoyaTextPrimary,
    background = ZoyaBackground,
    onBackground = ZoyaTextPrimary,
    surface = ZoyaSurface,
    onSurface = ZoyaTextPrimary,
    surfaceVariant = ZoyaSurfaceVariant,
    onSurfaceVariant = ZoyaTextSecondary
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ZoyaDarkColorScheme,
        typography = Typography,
        content = content
    )
}
