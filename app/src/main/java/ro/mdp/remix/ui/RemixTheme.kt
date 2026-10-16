package ro.mdp.remix.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val Gold = Color(0xFFE6BF72)
internal val GoldDark = Color(0xFF6D5129)
internal val Background = Color(0xFF0C0D0F)
internal val Panel = Color(0xFF17191D)
internal val Muted = Color(0xFFA5A6AD)

private val RemixColors = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF251B0B),
    primaryContainer = Color(0xFF3E301C),
    onPrimaryContainer = Gold,
    secondary = Gold,
    onSecondary = Color(0xFF251B0B),
    background = Background,
    onBackground = Color(0xFFF4F2EC),
    surface = Panel,
    onSurface = Color(0xFFF4F2EC),
    surfaceVariant = Color(0xFF25272C),
    onSurfaceVariant = Muted,
    outline = Color(0xFF45464C),
    outlineVariant = Color(0xFF303137),
    error = Color(0xFFFFB4AB),
)

@Composable
fun RemixTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RemixColors, typography = Typography(), content = content)
}
