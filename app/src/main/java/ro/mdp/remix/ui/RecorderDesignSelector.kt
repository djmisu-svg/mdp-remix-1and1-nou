package ro.mdp.remix.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.cos
import kotlin.math.sin

/** Uses the existing private settings file and edits only the recorder skin key. */
object RecorderDesignPreferences {
    const val FILE_NAME = "mdp_settings"
    const val KEY = "recorder_design"

    fun load(context: Context): RecorderDesign {
        val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val saved = try { preferences.getString(KEY, null) } catch (_: ClassCastException) { null }
        return RecorderDesign.fromId(saved)
    }

    fun save(context: Context, design: RecorderDesign) {
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY, design.id).apply()
    }
}

@Composable
fun RecorderDesignSelector(
    design: RecorderDesign,
    onDesignChange: (RecorderDesign) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val selectorLabel = uiText("Alege interfața recorderului", "Choose recorder interface")
    OutlinedButton(
        onClick = { open = true },
        modifier = modifier.fillMaxWidth().testTag("recorder_design_selector")
            .semantics { contentDescription = selectorLabel; stateDescription = design.label },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Hairline),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp), tint = Muted)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(uiText("INTERFAȚĂ RECORDER", "RECORDER INTERFACE"), color = Muted, fontSize = 8.sp,
                letterSpacing = 1.6.sp, fontFamily = FontFamily.Monospace)
            Text(design.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp), tint = Color.White)
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                color = Background, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .18f)),
            ) {
                Column(Modifier.heightIn(max = 680.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(uiText("STILUL TĂU. SUNETUL TĂU.", "YOUR STYLE. YOUR SOUND."),
                                color = Red, fontSize = 8.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                            Text(uiText("Alege interfața", "Choose your interface"), color = Color.White,
                                fontSize = 23.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 6.dp))
                        }
                        IconButton(onClick = { open = false }) {
                            Icon(Icons.Rounded.Close, uiText("Închide", "Close"), tint = Muted)
                        }
                    }
                    Text(uiText("Patru expresii ale aceluiași recorder. Muzica își păstrează ritmul.",
                        "Four expressions of the same recorder. Your music keeps its rhythm."),
                        color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 16.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false).selectableGroup().testTag("recorder_design_list"),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(RecorderDesign.entries, key = { it.id }) { option ->
                            RecorderDesignOption(option, option == design) {
                                onDesignChange(option)
                                open = false
                            }
                        }
                    }
                    HorizontalDivider(color = Hairline)
                    Text(uiText("Alegerea ta se salvează pe acest dispozitiv.", "Your selection is saved on this device."),
                        color = Muted, fontSize = 10.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun RecorderDesignOption(design: RecorderDesign, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("recorder_design_${design.id}")
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        color = if (selected) Color.White.copy(alpha = .045f) else Background,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Red else Hairline),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RecorderDesignPreview(design, Modifier.width(88.dp).height(68.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                Text(design.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(designDescription(design), color = Muted, fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 5.dp))
            }
            RadioButton(selected = selected, onClick = null, modifier = Modifier.size(24.dp),
                colors = RadioButtonDefaults.colors(selectedColor = Red, unselectedColor = Muted))
        }
    }
}

@Composable
private fun designDescription(design: RecorderDesign): String = when (design) {
    RecorderDesign.CLASSIC -> uiText("Argintiu vintage. Spirit de studio analogic.", "Vintage silver. The spirit of an analog studio.")
    RecorderDesign.BLACK_EDITION -> uiText("Negru mat. Detalii discrete, caracter precis.", "Matte black. Quiet details, precise character.")
    RecorderDesign.RED_EDITION -> uiText("Negru și roșu intens. Prezență fără compromis.", "Black and deep red. Uncompromising presence.")
    RecorderDesign.STUDIO_PRO -> uiText("Linii moderne. Claritate de studio profesional.", "Modern lines. Professional studio clarity.")
}

/** Local previews share each skin's materials and layout, without loading images. */
@Composable
private fun RecorderDesignPreview(design: RecorderDesign, modifier: Modifier = Modifier) {
    val silver = design == RecorderDesign.CLASSIC
    val redEdition = design == RecorderDesign.RED_EDITION
    val modern = design == RecorderDesign.STUDIO_PRO
    val chassis = when (design) {
        RecorderDesign.CLASSIC -> Color(0xFFC2C2C2)
        RecorderDesign.BLACK_EDITION -> Color(0xFF151515)
        RecorderDesign.RED_EDITION -> lerp(Color.Black, Red, .32f)
        RecorderDesign.STUDIO_PRO -> Color(0xFF242424)
    }
    val ink = if (silver) Color(0xFF242424) else Color.White.copy(alpha = .8f)
    Box(modifier.clip(RoundedCornerShape(7.dp)).background(chassis)) {
        Canvas(Modifier.matchParentSize().padding(5.dp)) {
            val w = size.width
            val h = size.height
            drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (silver) .3f else .06f), Color.Transparent)),
                cornerRadius = CornerRadius(3.dp.toPx()))
            drawRoundRect(Color.Black.copy(alpha = .12f), Offset(w * .03f, h * .13f),
                Size(w * .94f, h * .55f), CornerRadius(2.dp.toPx()))
            listOf(.29f, .71f).forEach { x ->
                val center = Offset(w * x, h * .37f)
                val radius = h * .22f
                drawCircle(if (silver) Color(0xFF373737) else Color(0xFFBEBEBE), radius, center)
                drawCircle(chassis, radius * .75f, center)
                repeat(3) { spoke ->
                    val angle = spoke * Math.PI * 2 / 3
                    drawLine(if (silver) Color(0xFF9B9B9B) else Color(0xFFC1C1C1), center,
                        Offset(center.x + cos(angle).toFloat() * radius * .8f, center.y + sin(angle).toFloat() * radius * .8f),
                        strokeWidth = radius * .27f)
                }
                drawCircle(if (redEdition) Red else if (silver) Color(0xFFB8B8B8) else Color(0xFF282828), radius * .23f, center)
            }
            drawLine(ink.copy(alpha = .4f), Offset(w * .07f, h * .08f), Offset(w * .34f, h * .08f), h * .025f)
            if (modern) {
                repeat(2) { channel -> repeat(10) { segment ->
                    drawRect(if (segment > 7) Red else ink.copy(alpha = .6f),
                        Offset(w * .08f + segment * w * .055f, h * (.77f + channel * .08f)),
                        Size(w * .04f, h * .045f))
                } }
            } else {
                repeat(2) { channel ->
                    val left = w * (.07f + channel * .35f)
                    drawRoundRect(if (silver) Color(0xFFDCDCDC) else Color.White.copy(alpha = .15f),
                        Offset(left, h * .75f), Size(w * .29f, h * .18f), CornerRadius(1.dp.toPx()))
                    drawLine(if (redEdition) Color.White else Red, Offset(left + w * .145f, h * .92f),
                        Offset(left + w * .11f, h * .78f), h * .018f)
                }
            }
            drawCircle(if (redEdition) Color.White.copy(alpha = .8f) else Red, h * .055f, Offset(w * .87f, h * .84f))
            drawRoundRect(Color.White.copy(alpha = if (silver) .5f else .12f), cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(.6.dp.toPx()))
        }
    }
}
