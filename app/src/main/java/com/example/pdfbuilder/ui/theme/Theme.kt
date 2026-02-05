package com.example.pdfbuilder.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class AppTheme {
    RETRO_PAPER,
    TECH_DARK,
    MINIMALIST // New Minimalist Theme
}

// Minimalist Theme (简约风格)
val minimalPrimary = Color(0xFF2196F3) // Blue 500 (Original)
val minimalOnPrimary = Color(0xFFFFFFFF)
val minimalPrimaryContainer = Color(0xFFBBDEFB)
val minimalOnPrimaryContainer = Color(0xFF0D47A1)
val minimalSecondary = Color(0xFF4CAF50) // Green 500 (Original)
val minimalOnSecondary = Color(0xFFFFFFFF)
val minimalSecondaryContainer = Color(0xFFC8E6C9)
val minimalOnSecondaryContainer = Color(0xFF1B5E20)
val minimalTertiary = Color(0xFFFF9800) // Orange 500 (Original)
val minimalOnTertiary = Color(0xFFFFFFFF)
val minimalBackground = Color(0xFFFFFFFF) // Pure White
val minimalOnBackground = Color(0xFF000000)
val minimalSurface = Color(0xFFF5F5F5) // Very Light Grey
val minimalOnSurface = Color(0xFF000000)
val minimalSurfaceVariant = Color(0xFFEEEEEE)
val minimalOnSurfaceVariant = Color(0xFF424242)
val minimalOutline = Color(0xFFBDBDBD)

// Retro Paper Theme (复古纸张风)
val primary = Color(0xFF8D6E63) // Brown 400 (Softer)
val onPrimary = Color(0xFFFFFFFF)
val primaryContainer = Color(0xFFD7CCC8)
val onPrimaryContainer = Color(0xFF3E2723)
val secondary = Color(0xFFA1887F) // Brown 300
val onSecondary = Color(0xFFFFFFFF)
val secondaryContainer = Color(0xFFD7CCC8)
val onSecondaryContainer = Color(0xFF3E2723)
val tertiary = Color(0xFF5D4037) // Brown 700 (Action Buttons)
val onTertiary = Color(0xFFFFFFFF)
val background = Color(0xFFF2E6D5)
val onBackground = Color(0xFF3E2723)
val surface = Color(0xFFFFF8F0)
val onSurface = Color(0xFF3E2723)
val surfaceVariant = Color(0xFFE6D6C4)
val onSurfaceVariant = Color(0xFF5D4037)
val outline = Color(0xFFA1887F)

// Tech Dark Theme (科技感深色)
val techPrimary = Color(0xFF4DD0E1) // Cyan 300 (Less Neon)
val techOnPrimary = Color(0xFF000000)
val techPrimaryContainer = Color(0xFF006064)
val techOnPrimaryContainer = Color(0xFFE0F7FA)
val techSecondary = Color(0xFFAED581) // Light Green 300 (Softer)
val techOnSecondary = Color(0xFF000000)
val techSecondaryContainer = Color(0xFF33691E)
val techOnSecondaryContainer = Color(0xFFDCEDC8)
val techTertiary = Color(0xFF00838F) // Cyan 800 (Action Buttons)
val techOnTertiary = Color(0xFFFFFFFF)
val techBackground = Color(0xFF121212) // Dark Grey/Black
val techOnBackground = Color(0xFFE0E0E0)
// Semi-transparent surface for Tech Theme
val techSurface = Color(0xCC1E1E1E) // 80% opacity dark surface
val techOnSurface = Color(0xFFE0E0E0)
val techSurfaceVariant = Color(0xCC2C2C2C) // 80% opacity variant
val techOnSurfaceVariant = Color(0xFFB0BEC5)
val techOutline = Color(0xFF4DD0E1)

@Composable
fun PdfSplitterTheme(
    appTheme: AppTheme = AppTheme.MINIMALIST, // Set Minimalist as default
    content: @Composable () -> Unit
) {
    val colorScheme = when (appTheme) {
        AppTheme.MINIMALIST -> lightColorScheme(
            primary = minimalPrimary,
            onPrimary = minimalOnPrimary,
            primaryContainer = minimalPrimaryContainer,
            onPrimaryContainer = minimalOnPrimaryContainer,
            secondary = minimalSecondary,
            onSecondary = minimalOnSecondary,
            secondaryContainer = minimalSecondaryContainer,
            onSecondaryContainer = minimalOnSecondaryContainer,
            tertiary = minimalTertiary,
            onTertiary = minimalOnTertiary,
            background = minimalBackground,
            onBackground = minimalOnBackground,
            surface = minimalSurface,
            onSurface = minimalOnSurface,
            surfaceVariant = minimalSurfaceVariant,
            onSurfaceVariant = minimalOnSurfaceVariant,
            outline = minimalOutline
        )
        AppTheme.RETRO_PAPER -> lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline
        )
        AppTheme.TECH_DARK -> darkColorScheme(
            primary = techPrimary,
            onPrimary = techOnPrimary,
            primaryContainer = techPrimaryContainer,
            onPrimaryContainer = techOnPrimaryContainer,
            secondary = techSecondary,
            onSecondary = techOnSecondary,
            secondaryContainer = techSecondaryContainer,
            onSecondaryContainer = techOnSecondaryContainer,
            tertiary = techTertiary,
            onTertiary = techOnTertiary,
            background = techBackground,
            onBackground = techOnBackground,
            surface = techSurface,
            onSurface = techOnSurface,
            surfaceVariant = techSurfaceVariant,
            onSurfaceVariant = techOnSurfaceVariant,
            outline = techOutline
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = (appTheme == AppTheme.RETRO_PAPER || appTheme == AppTheme.MINIMALIST)
        }
    }

    // Apply gradient background for Tech Theme
    if (appTheme == AppTheme.TECH_DARK) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF121212), // Top: Black/Dark Grey
                            Color(0xFF001F24)  // Bottom: Dark Cyan tint
                        )
                    )
                )
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                content = content
            )
        }
    } else {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
