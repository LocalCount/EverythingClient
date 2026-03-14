package com.everythingclient.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val LocalAmoledOutlineWidth = compositionLocalOf { 0.dp }
val LocalAmoledOutlineColor = compositionLocalOf { Color.Transparent }
val LocalIsAmoled = compositionLocalOf { false }

private val DarkColorScheme = darkColorScheme(
    primary              = BrandOrange,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFF3D1A08),
    onPrimaryContainer   = BrandOrangeLight,
    secondary            = OnDarkSecondary,
    onSecondary          = SurfaceDark0,
    secondaryContainer   = SurfaceDark3,
    onSecondaryContainer = Color(0xFFE8E8EE),
    tertiary             = AccentBlue,
    onTertiary           = Color.White,
    background           = SurfaceDark1,
    onBackground         = Color(0xFFE8E8EE),
    surface              = SurfaceDark2,
    onSurface            = Color(0xFFE8E8EE),
    surfaceVariant       = SurfaceDark3,
    onSurfaceVariant     = OnDarkSecondary,
    outline              = Color(0xFF44444C),
    outlineVariant       = Color(0xFF2A2A30),
    error                = AccentRed,
    onError              = Color.White,
    errorContainer       = Color(0xFF3D0A0A),
    onErrorContainer     = AccentRed,
    surfaceContainer         = SurfaceDark2,
    surfaceContainerLow      = SurfaceDark1,
    surfaceContainerHigh     = SurfaceDark3,
    surfaceContainerLowest   = SurfaceDark0,
    surfaceContainerHighest  = SurfaceDark4,
)

private val AmoledColorScheme = darkColorScheme(
    primary              = BrandOrange,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFF2A1000),
    onPrimaryContainer   = BrandOrangeLight,
    secondary            = Color(0xFFAAAAAA),
    onSecondary          = Color.Black,
    secondaryContainer   = Color(0xFF1A1A1A),
    onSecondaryContainer = Color.White,
    tertiary             = AccentBlue,
    onTertiary           = Color.White,
    background           = Color.Black,
    onBackground         = Color.White,
    surface              = Color.Black,
    onSurface            = Color.White,
    surfaceVariant       = Color(0xFF111114),
    onSurfaceVariant     = Color(0xFFCCCCCC),
    outline              = Color(0xFF666666),
    outlineVariant       = Color(0xFF333333),
    error                = AccentRed,
    onError              = Color.White,
    errorContainer       = Color(0xFF2A0505),
    onErrorContainer     = Color(0xFFFF9999),
    surfaceContainer         = Color.Black,
    surfaceContainerLow      = Color.Black,
    surfaceContainerHigh     = Color(0xFF161618),
    surfaceContainerLowest   = Color.Black,
    surfaceContainerHighest  = Color(0xFF202024),
)

private val LightColorScheme = lightColorScheme(
    primary              = BrandOrangeDark,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFFFE4D4),
    onPrimaryContainer   = Color(0xFF6B1C00),
    secondary            = Color(0xFF55555F),
    onSecondary          = Color.White,
    secondaryContainer   = SurfaceLight3,
    onSecondaryContainer = Color(0xFF111118),
    tertiary             = AccentBlue,
    onTertiary           = Color.White,
    background           = Color(0xFFF7F7FA),
    onBackground         = Color(0xFF111118),
    surface              = SurfaceLight0,
    onSurface            = Color(0xFF111118),
    surfaceVariant       = SurfaceLight2,
    onSurfaceVariant     = Color(0xFF55555F),
    outline              = Color(0xFFBBBBCC),
    outlineVariant       = Color(0xFFDDDDEE),
    error                = Color(0xFFCC2222),
    onError              = Color.White,
)

enum class AppTheme { SYSTEM, LIGHT, DARK, AMOLED }

@Composable
fun EverythingClientTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (appTheme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT  -> false
        AppTheme.DARK, AppTheme.AMOLED -> true
    }

    val colorScheme = when (appTheme) {
        AppTheme.AMOLED -> AmoledColorScheme
        AppTheme.DARK   -> DarkColorScheme
        AppTheme.LIGHT  -> LightColorScheme
        AppTheme.SYSTEM -> if (darkTheme) DarkColorScheme else LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    val outlineWidth: Dp = 1.dp
    val outlineColor: Color = when (appTheme) {
        AppTheme.AMOLED -> if (isSystemInDarkTheme()) Color(0xFF666666) else Color(0xFFCCCCDD)
        AppTheme.DARK   -> Color(0xFF3A3A40)
        AppTheme.LIGHT  -> Color(0xFFCCCCDD)
        AppTheme.SYSTEM -> if (darkTheme) Color(0xFF3A3A40) else Color(0xFFCCCCDD)
    }

    CompositionLocalProvider(
        LocalAmoledOutlineWidth provides outlineWidth,
        LocalAmoledOutlineColor provides outlineColor,
        LocalIsAmoled provides (appTheme == AppTheme.AMOLED && isSystemInDarkTheme()),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = Typography,
            shapes      = Shapes,
            content     = content
        )
    }
}
