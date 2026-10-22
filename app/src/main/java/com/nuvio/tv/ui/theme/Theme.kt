package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme

data class NuvioExtendedColors(
    val backgroundElevated: Color,
    val backgroundCard: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val focusRing: Color,
    val focusBackground: Color,
    val rating: Color
)

val LocalNuvioColors = staticCompositionLocalOf {
    NuvioColorScheme(ThemeColors.Ocean)
}

val LocalNuvioExtendedColors = staticCompositionLocalOf {
    NuvioExtendedColors(
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF242424),
        textSecondary = Color(0xFFB3B3B3),
        textTertiary = Color(0xFF808080),
        focusRing = ThemeColors.Ocean.focusRing,
        focusBackground = ThemeColors.Ocean.focusBackground,
        rating = Color(0xFFFFD700)
    )
}

val LocalAppTheme = staticCompositionLocalOf { AppTheme.WHITE }

@OptIn(ExperimentalTvMaterial3Api::class)
fun themeColorScheme(
    theme: AppTheme,
    amoledMode: Boolean = false,
    amoledSurfacesMode: Boolean = false,
    customHue: Float? = null,
    customAccent: Color? = null
): ColorScheme {
    val palette = ThemeColors.getColorPalette(theme, customHue, customAccent)
    val colors = NuvioColorScheme(palette, amoledMode, amoledSurfacesMode)
    return darkColorScheme(
        primary = palette.focusRing,
        onPrimary = palette.onSecondary,
        secondary = colors.Secondary,
        onSecondary = colors.OnSecondary,
        background = colors.Background,
        surface = colors.Surface,
        surfaceVariant = colors.SurfaceVariant,
        onBackground = colors.TextPrimary,
        onSurface = colors.TextPrimary,
        onSurfaceVariant = colors.TextSecondary,
        error = colors.Error
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NuvioTheme(
    appTheme: AppTheme = AppTheme.WHITE,
    appFont: AppFont = AppFont.INTER,
    amoledMode: Boolean = false,
    amoledSurfacesMode: Boolean = false,
    customHue: Float? = null,
    customAccent: Color? = null,
    content: @Composable () -> Unit
) {
    val palette = ThemeColors.getColorPalette(appTheme, customHue, customAccent)
    val colorScheme = NuvioColorScheme(palette, amoledMode, amoledSurfacesMode)

    val materialColorScheme = themeColorScheme(
        theme = appTheme,
        amoledMode = amoledMode,
        amoledSurfacesMode = amoledSurfacesMode,
        customHue = customHue,
        customAccent = customAccent
    )

    val extendedColors = NuvioExtendedColors(
        backgroundElevated = colorScheme.BackgroundElevated,
        backgroundCard = colorScheme.BackgroundCard,
        textSecondary = colorScheme.TextSecondary,
        textTertiary = colorScheme.TextTertiary,
        focusRing = colorScheme.FocusRing,
        focusBackground = colorScheme.FocusBackground,
        rating = colorScheme.Rating
    )

    CompositionLocalProvider(
        LocalNuvioColors provides colorScheme,
        LocalNuvioExtendedColors provides extendedColors,
        LocalAppTheme provides appTheme
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = buildNuvioTypography(getFontFamily(appFont)),
            content = content
        )
    }
}

object NuvioTheme {
    val colors: NuvioColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioColors.current

    val extendedColors: NuvioExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioExtendedColors.current

    val currentTheme: AppTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTheme.current
}
