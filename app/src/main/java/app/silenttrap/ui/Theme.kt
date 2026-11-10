package app.silenttrap.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF12110F)
private val Paper = Color(0xFF1C1A17)
private val Raised = Color(0xFF2A2622)
private val Amber = Color(0xFFE8A15A)
private val AmberDeep = Color(0xFF2A1C0E)
private val Cream = Color(0xFFF4EFE6)
private val Muted = Color(0xFFA39888)
private val Clay = Color(0xFFE07A64)

private val Scheme = darkColorScheme(
    primary = Amber,
    onPrimary = AmberDeep,
    secondary = Color(0xFFD7C4A3),
    onSecondary = AmberDeep,
    background = Ink,
    onBackground = Cream,
    surface = Paper,
    onSurface = Cream,
    surfaceVariant = Raised,
    onSurfaceVariant = Muted,
    error = Clay,
    onError = Ink,
)

@Composable
fun SilentTrapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
