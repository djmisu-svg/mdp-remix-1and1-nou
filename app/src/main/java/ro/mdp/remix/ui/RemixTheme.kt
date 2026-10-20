package ro.mdp.remix.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Background = Color(0xFF09090B)
internal val Panel = Background
internal val Red = Color(0xFFA60920)
internal val Muted = Color.White.copy(alpha = 0.60f)
internal val Hairline = Color.White.copy(alpha = 0.12f)
val LocalEnglish = staticCompositionLocalOf { false }
val LocalPanelTransparency = staticCompositionLocalOf { 0 }

@Composable
internal fun panelColor(base: Color = Background): Color =
    base.copy(alpha = base.alpha * (1f - LocalPanelTransparency.current.coerceIn(0, 100) / 100f))

@Composable
fun uiText(romanian: String, english: String): String = if (LocalEnglish.current) english else romanian

private val RemixColors = darkColorScheme(
    primary = Red, onPrimary = Color.White, primaryContainer = Red.copy(alpha = 0.24f), onPrimaryContainer = Color.White,
    secondary = Red, onSecondary = Color.White, secondaryContainer = Red.copy(alpha = 0.18f), onSecondaryContainer = Color.White,
    tertiary = Red, onTertiary = Color.White, tertiaryContainer = Red.copy(alpha = 0.18f), onTertiaryContainer = Color.White,
    background = Background, onBackground = Color.White,
    surface = Background, onSurface = Color.White, surfaceVariant = Color.White.copy(alpha = 0.06f), onSurfaceVariant = Muted,
    surfaceTint = Color.Transparent, inverseSurface = Color.White, inverseOnSurface = Background, inversePrimary = Red,
    outline = Color.White.copy(alpha = 0.28f), outlineVariant = Hairline,
    error = Red, onError = Color.White, errorContainer = Red.copy(alpha = 0.2f), onErrorContainer = Color.White,
    scrim = Color.Black, surfaceDim = Background, surfaceBright = Background,
    surfaceContainerLowest = Background, surfaceContainerLow = Background, surfaceContainer = Background,
    surfaceContainerHigh = Background, surfaceContainerHighest = Background,
)
private val RemixTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.3).sp),
    headlineLarge = TextStyle(fontSize = 31.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.9).sp),
    headlineMedium = TextStyle(fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 13.sp, letterSpacing = 1.2.sp),
)

@Composable
fun RemixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RemixColors, typography = RemixTypography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(30.dp)),
        content = content,
    )
}
