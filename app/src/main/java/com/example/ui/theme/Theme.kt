package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val VoidShieldDarkColorScheme =
  darkColorScheme(
    primary = CyberEmerald,
    onPrimary = ObsidianBg,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = MintGlow,
    secondary = SlateMuted,
    onSecondary = IceWhite,
    tertiary = AmberSignal,
    background = ObsidianBg,
    onBackground = IceWhite,
    surface = CharcoalSurface,
    onSurface = IceWhite,
    surfaceVariant = CharcoalElevated,
    onSurfaceVariant = AshSecondary,
    outline = HairlineBorder,
    error = CrimsonAlert,
    onError = IceWhite,
  )

@Composable
fun VoidShieldTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = VoidShieldDarkColorScheme,
    typography = Typography,
    content = content,
  )
}
