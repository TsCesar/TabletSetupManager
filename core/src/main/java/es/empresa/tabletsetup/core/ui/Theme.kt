package es.empresa.tabletsetup.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Color de acento de cada aplicación (mismo diseño, distinta identidad). */
enum class SuiteAccent { INSTALLER, UPDATER }

/** Colores semánticos de estado, ajustados para modo claro y oscuro. */
@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val neutralContainer: Color,
)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(
        Color(0xFF1B6C3A), Color.White, Color(0xFFD3F0DC), Color(0xFF8A5300), Color(0xFFFFE2B8),
        Color(0xFF1B5FBF), Color(0xFFD9E5FF), Color(0xFFE6E8EE),
    )
}

private fun scheme(accent: SuiteAccent, dark: Boolean): ColorScheme = when (accent) {
    SuiteAccent.INSTALLER -> if (dark) {
        darkColorScheme(
            primary = Color(0xFFAAC7FF), onPrimary = Color(0xFF002F65), primaryContainer = Color(0xFF00458E),
            onPrimaryContainer = Color(0xFFD7E3FF), secondary = Color(0xFFBEC6DC), secondaryContainer = Color(0xFF3E4759),
            onSecondaryContainer = Color(0xFFDAE2F9), background = Color(0xFF111318), surface = Color(0xFF111318),
            surfaceVariant = Color(0xFF44474F), onSurfaceVariant = Color(0xFFC4C6D0), outline = Color(0xFF8E9099),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1B5FBF), onPrimary = Color.White, primaryContainer = Color(0xFFD7E3FF),
            onPrimaryContainer = Color(0xFF001A40), secondary = Color(0xFF565E71), secondaryContainer = Color(0xFFDAE2F9),
            onSecondaryContainer = Color(0xFF131C2B), background = Color(0xFFF8F9FF), surface = Color(0xFFF8F9FF),
            surfaceVariant = Color(0xFFE0E2EC), onSurfaceVariant = Color(0xFF44474F), outline = Color(0xFF74777F),
        )
    }
    SuiteAccent.UPDATER -> if (dark) {
        darkColorScheme(
            primary = Color(0xFF4CDADB), onPrimary = Color(0xFF003738), primaryContainer = Color(0xFF004F50),
            onPrimaryContainer = Color(0xFF6FF6F7), secondary = Color(0xFFB0CCCB), secondaryContainer = Color(0xFF324B4B),
            onSecondaryContainer = Color(0xFFCCE8E7), background = Color(0xFF0E1415), surface = Color(0xFF0E1415),
            surfaceVariant = Color(0xFF3F4948), onSurfaceVariant = Color(0xFFBEC9C8), outline = Color(0xFF889392),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF00696B), onPrimary = Color.White, primaryContainer = Color(0xFF6FF6F7),
            onPrimaryContainer = Color(0xFF002020), secondary = Color(0xFF4A6363), secondaryContainer = Color(0xFFCCE8E7),
            onSecondaryContainer = Color(0xFF051F1F), background = Color(0xFFF4FBFA), surface = Color(0xFFF4FBFA),
            surfaceVariant = Color(0xFFDAE5E4), onSurfaceVariant = Color(0xFF3F4948), outline = Color(0xFF6F7979),
        )
    }
}

private fun statusColors(dark: Boolean) = if (dark) {
    StatusColors(
        success = Color(0xFF8FD8A4), onSuccess = Color(0xFF00391A), successContainer = Color(0xFF0F5229),
        warning = Color(0xFFFFB95C), warningContainer = Color(0xFF5C3B00),
        info = Color(0xFFAAC7FF), infoContainer = Color(0xFF00458E), neutralContainer = Color(0xFF2E3036),
    )
} else {
    StatusColors(
        success = Color(0xFF1B6C3A), onSuccess = Color.White, successContainer = Color(0xFFD3F0DC),
        warning = Color(0xFF8A5300), warningContainer = Color(0xFFFFE2B8),
        info = Color(0xFF1B5FBF), infoContainer = Color(0xFFD9E5FF), neutralContainer = Color(0xFFE6E8EE),
    )
}

@Composable
fun SuiteTheme(accent: SuiteAccent, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides statusColors(dark)) {
        MaterialTheme(colorScheme = scheme(accent, dark), typography = Typography(), content = content)
    }
}
