package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.domain.ThemeMode

@Immutable
data class SemanticColors(
    val positive: Color,
    val positiveContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val info: Color,
    val border: Color,
    val isDark: Boolean
)

val LocalSemanticColors = staticCompositionLocalOf {
    SemanticColors(
        positive = MintEmerald,
        positiveContainer = MintEmeraldMuted,
        warning = AmberGold,
        warningContainer = AmberGoldMuted,
        danger = CoralRed,
        dangerContainer = CoralRedMuted,
        info = SkyInfo,
        border = SlateBorder,
        isDark = true
    )
}

private val PawDarkColorScheme = darkColorScheme(
    primary = AmberGold,
    onPrimary = ObsidianBg,
    primaryContainer = AmberGoldMuted,
    onPrimaryContainer = AmberGold,
    secondary = MintEmerald,
    onSecondary = ObsidianBg,
    secondaryContainer = MintEmeraldMuted,
    onSecondaryContainer = MintEmerald,
    tertiary = SkyInfo,
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = SlateSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    outline = SlateBorder,
    error = CoralRed,
    onError = ObsidianBg,
    errorContainer = CoralRedMuted,
    onErrorContainer = CoralRed
)

private val PawLightColorScheme = lightColorScheme(
    primary = RichOchre,
    onPrimary = Color.White,
    primaryContainer = RichOchreMuted,
    onPrimaryContainer = Color(0xFF78350F),
    secondary = ForestEmerald,
    onSecondary = Color.White,
    secondaryContainer = ForestEmeraldMuted,
    onSecondaryContainer = ForestEmerald,
    tertiary = CobaltInfo,
    background = AlabasterBg,
    onBackground = TextPrimaryLight,
    surface = PureWhiteSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = SoftGraySurface,
    onSurfaceVariant = TextSecondaryLight,
    outline = LightBorder,
    error = CrimsonRed,
    onError = Color.White,
    errorContainer = CrimsonRedMuted,
    onErrorContainer = CrimsonRed
)

private val PawShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun PawLedgerTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = if (useDark) PawDarkColorScheme else PawLightColorScheme
    val semanticColors = if (useDark) {
        SemanticColors(
            positive = MintEmerald,
            positiveContainer = MintEmeraldMuted,
            warning = AmberGold,
            warningContainer = AmberGoldMuted,
            danger = CoralRed,
            dangerContainer = CoralRedMuted,
            info = SkyInfo,
            border = SlateBorder,
            isDark = true
        )
    } else {
        SemanticColors(
            positive = ForestEmerald,
            positiveContainer = ForestEmeraldMuted,
            warning = RichOchre,
            warningContainer = RichOchreMuted,
            danger = CrimsonRed,
            dangerContainer = CrimsonRedMuted,
            info = CobaltInfo,
            border = LightBorder,
            isDark = false
        )
    }

    CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PawLedgerTypography,
            shapes = PawShapes,
            content = content
        )
    }
}
