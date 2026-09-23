package com.water.widget.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.water.widget.AppThemeMode

/** Theme-aware colors for water status, feedback, and layered surfaces. */
data class WaterSemanticColors(
    val waterStrong: Color,
    val danger: Color,
    val onDanger: Color,
    val surfaceRaised: Color,
    val outlineSoft: Color,
    val scrim: Color
)

private val LightSemanticColors = WaterSemanticColors(
    waterStrong = Color(0xFF00596C),
    danger = Color(0xFFB3261E),
    onDanger = Color.White,
    surfaceRaised = Color(0xFFFCFEFE),
    outlineSoft = Color(0xFFC6D5D9),
    scrim = Color(0x990A1D22)
)

private val DarkSemanticColors = WaterSemanticColors(
    waterStrong = Color(0xFFC6F4FD),
    danger = Color(0xFFFFB4AB),
    onDanger = Color(0xFF690005),
    surfaceRaised = Color(0xFF122329),
    outlineSoft = Color(0xFF395058),
    scrim = Color(0xB300090C)
)

private val LocalWaterSemanticColors = staticCompositionLocalOf { LightSemanticColors }

/** Shared semantic tokens. Read [colors] inside a [WaterTheme]. */
object WaterThemeTokens {
    val colors: WaterSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalWaterSemanticColors.current
}

/** Consistent rounded geometry for cards, controls, sheets, and feature artwork. */
object WaterShapeTokens {
    val compact = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(20.dp)
    val prominent = RoundedCornerShape(28.dp)

    internal val material = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = compact,
        medium = card,
        large = prominent,
        extraLarge = RoundedCornerShape(32.dp)
    )
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B82),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0F4FA),
    onPrimaryContainer = Color(0xFF003640),
    secondary = Color(0xFF38656E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9F0F3),
    onSecondaryContainer = Color(0xFF001F25),
    tertiary = Color(0xFF8A6200),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE39C),
    onTertiaryContainer = Color(0xFF2B1B00),
    error = LightSemanticColors.danger,
    onError = LightSemanticColors.onDanger,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFEEF4F5),
    onBackground = Color(0xFF14262B),
    surface = Color(0xFFFCFEFE),
    surfaceContainerHigh = LightSemanticColors.surfaceRaised,
    onSurface = Color(0xFF14262B),
    surfaceVariant = Color(0xFFE4ECEE),
    onSurfaceVariant = Color(0xFF405258),
    outline = Color(0xFF708187),
    outlineVariant = LightSemanticColors.outlineSoft,
    scrim = LightSemanticColors.scrim
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BDFEF),
    onPrimary = Color(0xFF003640),
    primaryContainer = Color(0xFF174F5B),
    onPrimaryContainer = Color(0xFFD0F4FA),
    secondary = Color(0xFFB9DDE3),
    onSecondary = Color(0xFF223238),
    secondaryContainer = Color(0xFF31484E),
    onSecondaryContainer = Color(0xFFD9F0F3),
    tertiary = Color(0xFFFFBF56),
    onTertiary = Color(0xFF482900),
    tertiaryContainer = Color(0xFF624400),
    onTertiaryContainer = Color(0xFFFFE39C),
    error = DarkSemanticColors.danger,
    onError = DarkSemanticColors.onDanger,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF081519),
    onBackground = Color(0xFFE5F0F2),
    surface = Color(0xFF102025),
    surfaceContainerHigh = DarkSemanticColors.surfaceRaised,
    onSurface = Color(0xFFE5F0F2),
    surfaceVariant = Color(0xFF22343A),
    onSurfaceVariant = Color(0xFFB7C8CC),
    outline = Color(0xFF899CA1),
    outlineVariant = DarkSemanticColors.outlineSoft,
    scrim = DarkSemanticColors.scrim
)

@Composable
fun WaterTheme(
    mode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (mode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors
    val colorScheme = if (darkTheme) DarkColors else LightColors

    CompositionLocalProvider(
        LocalWaterSemanticColors provides semanticColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTheme.typography,
            shapes = WaterShapeTokens.material,
            content = content
        )
    }
}
