package ro.mdp.remix.background

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ro.mdp.remix.ui.Background
import ro.mdp.remix.ui.Hairline
import ro.mdp.remix.ui.Muted
import ro.mdp.remix.ui.Red
import ro.mdp.remix.ui.uiText
import ro.mdp.remix.ui.panelColor
import kotlin.math.roundToInt

/** An opaque control panel keeps media customization readable over any image. */
@Composable
fun BackgroundSettingsPanel(
    settings: BackgroundSettings,
    loading: Boolean,
    originalAvailable: Boolean,
    error: String?,
    onChange: () -> Unit,
    onRestore: () -> Unit,
    onScale: (BackgroundScale) -> Unit,
    onDimming: (Float) -> Unit,
    onAnimation: (Boolean) -> Unit,
    onClearError: () -> Unit,
    onDimmingFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val status = when {
        loading -> "loading"
        settings.kind == BackgroundKind.ORIGINAL && !originalAvailable -> "original_missing"
        settings.kind != BackgroundKind.ORIGINAL -> "custom"
        originalAvailable -> "original_available"
        else -> "original_missing"
    }
    val source = when (settings.kind) {
        BackgroundKind.ORIGINAL -> uiText("Fundal original", "Original background")
        BackgroundKind.IMAGE -> uiText("Imagine personală", "Personal image")
        BackgroundKind.GIF -> uiText("Animație GIF personală", "Personal GIF animation")
        BackgroundKind.VIDEO -> uiText("Video personal", "Personal video")
    }
    val statusText = when {
        loading -> uiText("Se verifică fundalul salvat…", "Checking the saved background…")
        settings.kind == BackgroundKind.ORIGINAL && !originalAvailable -> uiText(
            "Fișierul original cu cuplul nu este inclus în această versiune. Se afișează fundalul negru al aplicației.",
            "The original couple file is not included in this build. The app’s black background is shown.",
        )
        settings.kind == BackgroundKind.ORIGINAL -> uiText("Animația originală este selectată.", "The original animation is selected.")
        else -> uiText("Alegerea este salvată pe acest dispozitiv.", "Your selection is saved on this device.")
    }
    val dimLabel = uiText("Întunecarea fundalului", "Background dimming")
    val animationLabel = uiText("Animație GIF și video", "GIF and video animation")
    Surface(
        modifier = modifier.fillMaxWidth().testTag("background_settings"),
        color = panelColor(), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Hairline),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Wallpaper, null, tint = Red, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Text(uiText("Fundalul tău", "Your background"), style = MaterialTheme.typography.titleLarge)
            }
            Text(uiText("O imagine, un GIF sau un video în spatele interfeței. Recorderul și comenzile își păstrează stilul.",
                "An image, GIF or video behind the interface. Your recorder and controls keep their style."),
                color = Muted, style = MaterialTheme.typography.bodyMedium)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(source, color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("background_source").semantics { stateDescription = settings.kind.name })
                Text(statusText, color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.testTag("background_status").semantics { stateDescription = status })
            }
            if (error != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.weight(1f).testTag("background_error"))
                    IconButton(onClick = onClearError) { Icon(Icons.Default.Close, uiText("Închide mesajul", "Dismiss message"), tint = Muted) }
                }
            }
            Button(onClick = onChange, enabled = !loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("background_change"),
                shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.PhotoLibrary, null, Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Text(uiText("Schimbă fundalul", "Change background"))
            }
            OutlinedButton(onClick = onRestore, enabled = !loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("background_restore"),
                shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Restore, null, Modifier.size(19.dp), tint = Color.White)
                Spacer(Modifier.width(9.dp))
                Text(uiText("Restaurează originalul", "Restore original"), color = Color.White)
            }
            HorizontalDivider(color = Hairline)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(uiText("Încadrare", "Framing"), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = settings.scale == BackgroundScale.FIT, onClick = { onScale(BackgroundScale.FIT) },
                        enabled = !loading, modifier = Modifier.weight(1f).testTag("background_fit"),
                        label = { Text(uiText("Încadrează", "Fit"), fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.FitScreen, null, Modifier.size(18.dp)) })
                    FilterChip(selected = settings.scale == BackgroundScale.FILL, onClick = { onScale(BackgroundScale.FILL) },
                        enabled = !loading, modifier = Modifier.weight(1f).testTag("background_fill"),
                        label = { Text(uiText("Umple", "Fill"), fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Crop, null, Modifier.size(18.dp)) })
                }
                Text(uiText("Încadrează păstrează întreaga imagine. Umple decupează marginile pentru a acoperi ecranul.",
                    "Fit keeps the whole image. Fill crops the edges to cover the screen."), color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(uiText("Întunecare", "Dimming"), style = MaterialTheme.typography.titleMedium)
                    Text("${(settings.dimming.coerceIn(0f, 1f) * 100).roundToInt()}%", color = Muted)
                }
                Slider(value = settings.dimming.coerceIn(0f, 1f), onValueChange = onDimming,
                    onValueChangeFinished = onDimmingFinished, enabled = !loading,
                    modifier = Modifier.fillMaxWidth().testTag("background_dimming").semantics { contentDescription = dimLabel })
                Text(uiText("Mai multă întunecare păstrează textul și comenzile clare.", "More dimming keeps text and controls clear."),
                    color = Muted, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(uiText("Animație", "Animation"), style = MaterialTheme.typography.titleMedium)
                    Text(uiText("GIF-urile și videourile se opresc când aplicația nu este vizibilă. Videoul de fundal nu redă sunet.",
                        "GIFs and videos pause when the app is hidden. Background video plays without sound."),
                        color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                }
                Switch(checked = settings.animationEnabled, onCheckedChange = onAnimation, enabled = !loading,
                    modifier = Modifier.testTag("background_animation").semantics { contentDescription = animationLabel })
            }
            if (!loading && !originalAvailable && settings.kind != BackgroundKind.ORIGINAL) {
                Text(uiText("Animația originală cu cuplul nu este inclusă în această versiune. Restaurarea folosește fundalul negru al aplicației.",
                    "The original couple animation is not included in this build. Restore uses the app’s black background."),
                    color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
    }
}
