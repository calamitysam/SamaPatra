package com.samapatra.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.samapatra.app.data.StudentSettings

// Warm "paper and ink" palette: cream paper, deep teal ink, marigold and coral accents.
private val LightColors = lightColorScheme(
    primary = Color(0xFF14504F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE6E1),
    onPrimaryContainer = Color(0xFF0B2F2E),
    secondary = Color(0xFFF4B13E),
    onSecondary = Color(0xFF1E2A2B),
    secondaryContainer = Color(0xFFFFE3AD),
    onSecondaryContainer = Color(0xFF3A2800),
    tertiary = Color(0xFFC23B1F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9D0),
    onTertiaryContainer = Color(0xFF5A1507),
    background = Color(0xFFFBF7EF),
    onBackground = Color(0xFF1E2A2B),
    surface = Color(0xFFFBF7EF),
    onSurface = Color(0xFF1E2A2B),
    surfaceVariant = Color(0xFFEFE7D6),
    onSurfaceVariant = Color(0xFF4A5557),
    outline = Color(0xFF6B7776),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1C4),
    onPrimary = Color(0xFF00322D),
    primaryContainer = Color(0xFF1D4A47),
    onPrimaryContainer = Color(0xFFCFF0E9),
    secondary = Color(0xFFF4B13E),
    onSecondary = Color(0xFF2E1F00),
    secondaryContainer = Color(0xFF5A4000),
    onSecondaryContainer = Color(0xFFFFE3AD),
    tertiary = Color(0xFFFFB4A2),
    onTertiary = Color(0xFF561100),
    tertiaryContainer = Color(0xFF7B2410),
    onTertiaryContainer = Color(0xFFFFDAD2),
    background = Color(0xFF121A1B),
    onBackground = Color(0xFFEDE8DC),
    surface = Color(0xFF121A1B),
    onSurface = Color(0xFFEDE8DC),
    surfaceVariant = Color(0xFF263233),
    onSurfaceVariant = Color(0xFFC5CFCC),
    outline = Color(0xFF8A9694),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

// Maximum contrast: pure white or black, black or white text, strong outlines
private val HighContrastLight = lightColorScheme(
    primary = Color(0xFF0A3D3C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EFEA),
    onPrimaryContainer = Color.Black,
    secondary = Color(0xFFFFC857),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFFFFE08A),
    onSecondaryContainer = Color.Black,
    tertiary = Color(0xFF9A1B00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD0C4),
    onTertiaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE6E6E6),
    onSurfaceVariant = Color.Black,
    outline = Color.Black,
    error = Color(0xFF8C0009),
    onError = Color.White,
)

private val HighContrastDark = darkColorScheme(
    primary = Color(0xFFA8F0E4),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF003F3A),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFFFD166),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF4D3A00),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFFB4A2),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF5A1507),
    onTertiaryContainer = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color.White,
    outline = Color.White,
    error = Color(0xFFFFB4AB),
    onError = Color.Black,
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

/** The settings of the signed-in student. Screens read this to adapt themselves. */
val LocalStudentSettings = staticCompositionLocalOf { StudentSettings() }

@Composable
fun SamaPatraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

private fun Typography.forLook(spacing: Float, tracking: TextUnit?): Typography {
    fun TextStyle.fix(): TextStyle = copy(
        lineHeight = if (lineHeight.isSpecified) lineHeight * spacing else lineHeight,
        letterSpacing = tracking ?: letterSpacing,
    )
    return copy(
        displayLarge = displayLarge.fix(),
        displayMedium = displayMedium.fix(),
        displaySmall = displaySmall.fix(),
        headlineLarge = headlineLarge.fix(),
        headlineMedium = headlineMedium.fix(),
        headlineSmall = headlineSmall.fix(),
        titleLarge = titleLarge.fix(),
        titleMedium = titleMedium.fix(),
        titleSmall = titleSmall.fix(),
        bodyLarge = bodyLarge.fix(),
        bodyMedium = bodyMedium.fix(),
        bodySmall = bodySmall.fix(),
        labelLarge = labelLarge.fix(),
        labelMedium = labelMedium.fix(),
        labelSmall = labelSmall.fix(),
    )
}

/** Dresses everything inside it the way this student has asked for. */
@Composable
fun StudentLook(settings: StudentSettings, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors: ColorScheme = when {
        settings.highContrast -> if (dark) HighContrastDark else HighContrastLight
        settings.tintedBackground && !dark -> LightColors.copy(
            background = Color(0xFFF7EBC8),
            surface = Color(0xFFF7EBC8),
            surfaceVariant = Color(0xFFEBDDB0),
        )
        dark -> DarkColors
        else -> LightColors
    }

    val spacing = if (settings.lineSpacing == "wide") 1.3f else 1f
    val tracking = if (settings.readableFont) 0.6.sp else null
    val typography = AppTypography.forLook(spacing, tracking)

    val density = LocalDensity.current
    val scaled = Density(density.density, density.fontScale * settings.textScale.toFloat())

    CompositionLocalProvider(
        LocalStudentSettings provides settings,
        LocalDensity provides scaled,
    ) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = AppShapes) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                content = content,
            )
        }
    }
}