package com.nuvio.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.nuvio.tv.domain.model.AppTheme

/**
 * Color palette for each theme.
 * Surfaces deliberately remain neutral: a theme contributes a single accent,
 * never a second tinted background colour.
 */
data class ThemeColorPalette(
    val secondary: Color,
    val secondaryVariant: Color,
    val onSecondary: Color = Color.White,
    val onSecondaryVariant: Color = Color.White,
    val focusRing: Color,
    val focusBackground: Color,
    // Background colors with subtle theme tinting
    val background: Color = Color(0xFF0D0D0D),
    val backgroundElevated: Color = Color(0xFF1A1A1A),
    val backgroundCard: Color = Color(0xFF242424)
)

object ThemeColors {

    val Crimson = ThemeColorPalette(
        secondary = Color(0xFFFF5252),
        secondaryVariant = Color(0xFFFF5252),
        focusRing = Color(0xFFFF5252),
        focusBackground = Color(0xFF242424)
    )

    val Ocean = ThemeColorPalette(
        secondary = Color(0xFF42A5F5),
        secondaryVariant = Color(0xFF42A5F5),
        focusRing = Color(0xFF42A5F5),
        focusBackground = Color(0xFF242424)
    )

    val Violet = ThemeColorPalette(
        secondary = Color(0xFFAB47BC),
        secondaryVariant = Color(0xFFAB47BC),
        focusRing = Color(0xFFAB47BC),
        focusBackground = Color(0xFF242424)
    )

    val Emerald = ThemeColorPalette(
        secondary = Color(0xFF66BB6A),
        secondaryVariant = Color(0xFF66BB6A),
        focusRing = Color(0xFF66BB6A),
        focusBackground = Color(0xFF242424)
    )

    val Amber = ThemeColorPalette(
        secondary = Color(0xFFFFA726),
        secondaryVariant = Color(0xFFFFA726),
        focusRing = Color(0xFFFFA726),
        focusBackground = Color(0xFF242424)
    )

    val Rose = ThemeColorPalette(
        secondary = Color(0xFFEC407A),
        secondaryVariant = Color(0xFFEC407A),
        focusRing = Color(0xFFEC407A),
        focusBackground = Color(0xFF242424)
    )

    val White = ThemeColorPalette(
        secondary = Color(0xFFF5F5F5),
        secondaryVariant = Color(0xFFE0E0E0),
        // The selected/focused controls use this light accent as their
        // background. Keep their labels and icons readable.
        onSecondary = Color(0xFF000000),
        onSecondaryVariant = Color(0xFF000000),
        focusRing = Color(0xFFFFFFFF),
        focusBackground = Color(0xFF242424)
    )

    val Gold = ThemeColorPalette(
        secondary = Color(0xFFD3AF37),
        secondaryVariant = Color(0xFFD3AF37),
        onSecondary = Color(0xFF111111),
        onSecondaryVariant = Color(0xFF111111),
        focusRing = Color(0xFFD3AF37),
        focusBackground = Color(0xFF242424)
    )

    fun getColorPalette(theme: AppTheme, customHue: Float? = null, customAccent: Color? = null): ThemeColorPalette {
        if (customAccent != null) {
            return ThemeColorPalette(
                secondary = customAccent,
                secondaryVariant = customAccent,
                onSecondary = if (customAccent.luminance() > 0.58f) Color(0xFF111111) else Color.White,
                onSecondaryVariant = if (customAccent.luminance() > 0.58f) Color(0xFF111111) else Color.White,
                focusRing = customAccent,
                focusBackground = Color(0xFF242424)
            )
        }
        if (customHue != null && customHue in 0f..1f) {
            val accent = Color.hsv(customHue * 360f, 0.82f, 1f)
            return ThemeColorPalette(
                secondary = accent,
                secondaryVariant = accent,
                focusRing = accent,
                focusBackground = Color(0xFF242424)
            )
        }
        return when (theme) {
            AppTheme.CRIMSON -> Crimson
            AppTheme.OCEAN -> Ocean
            AppTheme.VIOLET -> Violet
            AppTheme.EMERALD -> Emerald
            AppTheme.AMBER -> Amber
            AppTheme.GOLD -> Gold
            AppTheme.ROSE -> Rose
            AppTheme.WHITE -> White
        }
    }
}
