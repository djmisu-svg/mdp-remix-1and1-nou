package ro.mdp.remix.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import ro.mdp.remix.playback.AudioMeters
import ro.mdp.remix.playback.PlaybackState
import ro.mdp.remix.playback.ReelMotion
import kotlin.math.cos
import kotlin.math.sin

private val DeckBlack = Color(0xFF09090B)
private val DeckRed = Color(0xFFA60920)
private val DeckWhite = Color.White
private val DeckMuted = Color(0xFFA4A4A4)

/** A mechanical deck driven by the real local player and its decoded PCM meters. */
@Composable
fun PremiumTapeDeck(
    modifier: Modifier = Modifier,
    playback: PlaybackState,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onFavorite: (String) -> Unit,
    isFavorite: Boolean = false,
    compact: Boolean = false,
    poweredOverride: Boolean? = null,
    onPoweredChange: ((Boolean) -> Unit)? = null,
) {
    var internalPowered by rememberSaveable { mutableStateOf(true) }
    val powered = poweredOverride ?: internalPowered
    val changePower: (Boolean) -> Unit = { value ->
        internalPowered = value
        onPoweredChange?.invoke(value)
    }
    LaunchedEffect(playback.isPlaying) {
        if (playback.isPlaying) changePower(true)
    }
    val meters by AudioMeters.levels.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val reelActivity by ReelMotion.activity.collectAsStateWithLifecycle()
    var reelFrame by remember { mutableStateOf(ReelMotion.snapshot()) }
    LaunchedEffect(lifecycleOwner, reelActivity.version) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // The service owns phase. Navigation, resizing and background playback
            // never restart a reel, and system animator scale cannot stop the motor.
            reelFrame = ReelMotion.snapshot()
            if (reelFrame.isPlaying || reelActivity.isPlaying) {
                while (isActive) {
                    withFrameNanos { reelFrame = ReelMotion.snapshot() }
                    if (!reelFrame.isPlaying) break
                }
            }
        }
    }
    val running = reelFrame.isPlaying
    val progress = if (playback.durationMs > 0) {
        (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
    } else 0f
    val leftRotation = reelFrame.leftDegrees
    val rightRotation = reelFrame.rightDegrees
    // PCM attack/release and held sample peaks are measured by the audio thread.
    // Do not smear transients or manufacture movement with a second UI envelope.
    val left = if (running) meters.left else 0f
    val right = if (running) meters.right else 0f
    val haptic = LocalHapticFeedback.current
    val controlEnabled = powered && playback.connected
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.testTag("tape_deck").clip(shape).background(DeckBlack)
            .border(1.dp, Color.White.copy(alpha = .16f), shape).padding(if (compact) 12.dp else 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(6.dp).background(if (running) DeckRed else Color.White.copy(alpha = .25f), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("MdP / REFERENCE 01", color = DeckWhite, fontSize = 11.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("STEREO", color = DeckMuted, fontSize = 9.sp, letterSpacing = 1.6.sp)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Keep the full flange/head assembly clear of the fixed-height
            // stereo instruments on narrow phones, while scaling on tablets.
            val deckHeight = maxOf(maxWidth / 1.5f, maxWidth * .468f + 86.dp)
            Canvas(Modifier.fillMaxWidth().height(deckHeight)) {
                val unit = size.width / 1_000f
                scale(unit, unit, pivot = Offset.Zero) {
                    drawFaceplate(leftRotation, rightRotation, progress, powered)
                }
            }
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                ReelSemantics(leftRotation, running, "reel_left", uiText("Rolă sursă", "Supply reel"), Modifier.weight(1f))
                ReelSemantics(rightRotation, running, "reel_right", uiText("Rolă preluare", "Take-up reel"), Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AnalogMeter(left, if (running) meters.livePeakLeft else 0f, if (running) meters.peakLeft else 0f, "L", "vu_left", Modifier.weight(1f))
                PowerControl(powered, Modifier.padding(horizontal = 12.dp)) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (powered && playback.isPlaying) onTogglePlayback()
                    changePower(!powered)
                }
                AnalogMeter(right, if (running) meters.livePeakRight else 0f, if (running) meters.peakRight else 0f, "R", "vu_right", Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (playback.currentTrackId == null) uiText("Sunetul tău. Fără compromis.", "Your sound. Uncompromised.") else playback.title,
                    color = DeckWhite, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (playback.currentTrackId == null) uiText("Importă muzică pentru a porni banda", "Import music to roll the tape")
                    else playback.artist.ifBlank { uiText("Audio local", "Local audio") },
                    color = DeckMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { playback.currentTrackId?.let(onFavorite) }, enabled = playback.currentTrackId != null) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    uiText("Comută favorit", "Toggle favorite"),
                    tint = if (isFavorite) DeckRed else DeckMuted, modifier = Modifier.size(21.dp),
                )
            }
        }
        var scrubbing by remember { mutableStateOf(false) }
        var seekFraction by remember { mutableFloatStateOf(0f) }
        val seekDescription = uiText("Poziție redare", "Playback position")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TimeLabel(if (scrubbing) (seekFraction * playback.durationMs).toLong() else playback.positionMs)
            Slider(
                value = if (scrubbing) seekFraction else progress,
                onValueChange = { scrubbing = true; seekFraction = it },
                onValueChangeFinished = { onSeek((seekFraction * playback.durationMs).toLong()); scrubbing = false },
                enabled = controlEnabled && playback.durationMs > 0,
                modifier = Modifier.weight(1f).height(36.dp).padding(horizontal = 7.dp)
                    .testTag("deck_seek").semantics { contentDescription = seekDescription },
                colors = deckSliderColors(),
            )
            TimeLabel(playback.durationMs)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wideControls = maxWidth >= 360.dp
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                DeckKey(Icons.Rounded.SkipPrevious, uiText("Melodia precedentă", "Previous track"), "deck_previous", controlEnabled, onPrevious)
                if (wideControls) DeckKey(Icons.Rounded.Replay10, uiText("Înapoi 10 secunde", "Rewind 10 seconds"), "deck_rewind", controlEnabled, { onSeek((playback.positionMs - 10_000L).coerceAtLeast(0L)) })
                DeckKey(
                    if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playback.isPlaying) uiText("Pauză", "Pause") else uiText("Redare", "Play"),
                    "deck_play", controlEnabled, onTogglePlayback, primary = true,
                )
                if (wideControls) DeckKey(Icons.Rounded.Forward10, uiText("Înainte 10 secunde", "Forward 10 seconds"), "deck_forward", controlEnabled, { onSeek((playback.positionMs + 10_000L).coerceAtMost(playback.durationMs.coerceAtLeast(0L))) })
                DeckKey(Icons.Rounded.SkipNext, uiText("Melodia următoare", "Next track"), "deck_next", controlEnabled, onNext)
                DeckKey(Icons.Rounded.Stop, uiText("Oprire", "Stop"), "deck_stop", controlEnabled, {
                    if (playback.isPlaying) onTogglePlayback()
                    onSeek(0L)
                })
            }
        }
        val volumeDescription = uiText("Volum", "Volume")
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VolumeUp, null, tint = DeckMuted, modifier = Modifier.size(17.dp))
            Slider(
                value = playback.volume.coerceIn(0f, 1f), onValueChange = onVolume,
                enabled = controlEnabled,
                modifier = Modifier.weight(1f).height(36.dp).padding(horizontal = 10.dp)
                    .testTag("deck_volume").semantics { contentDescription = volumeDescription },
                colors = deckSliderColors(),
            )
            Text("${(playback.volume * 100).toInt()}%", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = DeckMuted)
        }
    }
}

@Composable
private fun ReelSemantics(angle: Float, running: Boolean, tag: String, description: String, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(1f).testTag(tag).semantics {
        contentDescription = description
        stateDescription = "angle=${(angle % 360f + 360f) % 360f};running=$running"
    })
}

@Composable
private fun deckSliderColors() = SliderDefaults.colors(
    thumbColor = DeckWhite, activeTrackColor = DeckRed,
    inactiveTrackColor = Color.White.copy(alpha = .13f),
    disabledThumbColor = DeckMuted.copy(alpha = .35f), disabledActiveTrackColor = DeckRed.copy(alpha = .3f),
    disabledInactiveTrackColor = Color.White.copy(alpha = .06f),
)

@Composable
private fun TimeLabel(milliseconds: Long) {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000
    Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", color = DeckMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun DeckKey(icon: ImageVector, description: String, tag: String, enabled: Boolean, onClick: () -> Unit, primary: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth by animateFloatAsState(if (pressed) 1.5f else 0f, tween(90), label = "transport key depth")
    val haptic = LocalHapticFeedback.current
    IconButton(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        enabled = enabled, interactionSource = interaction,
        modifier = Modifier.size(if (primary) 56.dp else 48.dp).testTag(tag)
            .graphicsLayer { translationY = depth.dp.toPx(); scaleX = if (pressed) .96f else 1f; scaleY = scaleX }
            .drawBehind {
                val inset = if (primary) 2.dp.toPx() else 3.dp.toPx()
                drawRoundRect(
                    Brush.verticalGradient(if (primary) listOf(DeckRed, DeckRed.copy(alpha = .65f)) else listOf(Color.White.copy(alpha = .09f), DeckBlack)),
                    topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2), cornerRadius = CornerRadius(11.dp.toPx()),
                )
                drawRoundRect(
                    if (primary) DeckRed.copy(alpha = .9f) else Color.White.copy(alpha = .16f),
                    topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2), cornerRadius = CornerRadius(11.dp.toPx()), style = Stroke(1.dp.toPx()),
                )
            },
    ) {
        Icon(icon, description, tint = DeckWhite.copy(alpha = if (enabled) 1f else .25f), modifier = Modifier.size(if (primary) 30.dp else 22.dp))
    }
}

@Composable
private fun PowerControl(powered: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth by animateFloatAsState(if (pressed) .94f else 1f, tween(100), label = "power press")
    var sweep = 0f
    var pulse = .35f
    if (powered) {
        val transition = rememberInfiniteTransition(label = "power illumination")
        val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(3_200, easing = LinearEasing)), label = "power sweep")
        val glow by transition.animateFloat(.45f, 1f, infiniteRepeatable(tween(1_650), RepeatMode.Reverse), label = "power breath")
        sweep = rotation
        pulse = glow
    }
    val label = uiText("Alimentare deck", "Deck power")
    val state = if (powered) "On" else "Off"
    IconButton(
        onClick = onClick, interactionSource = interaction,
        modifier = modifier.size(74.dp).testTag("deck_power").semantics {
            contentDescription = label
            stateDescription = state
            toggleableState = if (powered) ToggleableState.On else ToggleableState.Off
        }.graphicsLayer { scaleX = depth; scaleY = depth }.drawBehind {
            val radius = size.minDimension / 2
            if (powered) drawCircle(Brush.radialGradient(listOf(DeckRed.copy(alpha = .30f * pulse), DeckRed.copy(alpha = .10f), Color.Transparent)), radius)
            drawCircle(Color.White.copy(alpha = .12f), radius * .79f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF252525), DeckBlack)), radius * .76f)
            drawCircle(if (powered) DeckRed.copy(alpha = .5f) else Color.White.copy(alpha = .18f), radius * .72f, style = Stroke(2.dp.toPx()))
            if (powered) {
                val diameter = radius * 1.44f
                val start = Offset(center.x - diameter / 2, center.y - diameter / 2)
                drawArc(DeckRed.copy(alpha = pulse), sweep, 105f, false, start, Size(diameter, diameter), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                drawArc(Color.White.copy(alpha = pulse * .8f), sweep, 13f, false, start, Size(diameter, diameter), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
            drawCircle(Color.Black.copy(alpha = .8f), radius * .60f)
            drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = .08f), DeckBlack)), radius * .56f)
        },
    ) {
        Icon(Icons.Rounded.PowerSettingsNew, null, tint = if (powered) DeckRed else DeckMuted, modifier = Modifier.size(27.dp))
    }
}

@Composable
private fun AnalogMeter(level: Float, livePeak: Float, peak: Float, channel: String, tag: String, modifier: Modifier = Modifier) {
    val description = uiText("Nivel audio canal $channel", "Audio level channel $channel")
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(52.dp).testTag(tag).semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(level.coerceIn(0f, 1f), 0f..1f)
            stateDescription = "peak=${peak.coerceIn(0f, 1f)}"
        }) {
            val inset = 1.dp.toPx()
            drawRoundRect(Color.White.copy(alpha = .11f), cornerRadius = CornerRadius(5.dp.toPx()))
            drawRoundRect(DeckBlack, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), CornerRadius(4.dp.toPx()))
            val pivot = Offset(size.width * .5f, size.height * .87f)
            val radius = minOf(size.height * .75f, size.width * .43f)
            for (step in 0..20) {
                val angle = -145f + step * 5.5f
                val outer = radial(pivot, radius, angle)
                val inner = radial(pivot, radius * if (step % 5 == 0) .81f else .9f, angle)
                drawLine(if (step >= 16) DeckRed else DeckMuted.copy(alpha = .8f), inner, outer, if (step % 5 == 0) 1.5.dp.toPx() else .7.dp.toPx())
            }
            val needleAngle = -145f + level.coerceIn(0f, 1f) * 110f
            drawLine(DeckRed, pivot + Offset(1.dp.toPx(), 1.dp.toPx()), radial(pivot, radius * .85f, needleAngle) + Offset(1.dp.toPx(), 1.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(DeckMuted, 2.dp.toPx(), pivot)
            drawCircle(DeckBlack, 1.dp.toPx(), pivot)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.LTGRAY; textSize = 7.sp.toPx(); typeface = Typeface.MONOSPACE }
            drawContext.canvas.nativeCanvas.drawText("−60    −20    0", size.width * .12f, size.height * .25f, paint)
            paint.textSize = 6.sp.toPx()
            drawContext.canvas.nativeCanvas.drawText("VU $channel / dBFS", 7.dp.toPx(), size.height - 6.dp.toPx(), paint)
        }
        SegmentedMeter(livePeak, peak, channel, Modifier.fillMaxWidth().padding(top = 3.dp))
    }
}

/** These signal LEDs are the sole green/yellow accents on the black and red deck. */
@Composable
private fun SegmentedMeter(level: Float, peak: Float, channel: String, modifier: Modifier = Modifier) {
    val description = uiText("Indicator LED audio $channel", "$channel audio LED meter")
    val actualLevel = level.coerceIn(0f, 1f)
    val actualPeak = peak.coerceIn(0f, 1f)
    val tag = if (channel == "L") "led_left" else "led_right"
    Canvas(modifier.height(19.dp).testTag(tag).semantics {
        contentDescription = description
        progressBarRangeInfo = ProgressBarRangeInfo(actualLevel, 0f..1f)
        stateDescription = "peak=$actualPeak"
    }) {
        val count = 16
        val inset = 4.dp.toPx()
        val gap = 1.5.dp.toPx()
        val width = ((size.width - inset * 2 - gap * (count - 1)) / count).coerceAtLeast(1f)
        val height = 7.dp.toPx()
        val top = (size.height - height) / 2
        drawRoundRect(Color.White.copy(alpha = .10f), cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(DeckBlack, Offset(1f, 1f), Size(size.width - 2f, size.height - 2f), CornerRadius(3.dp.toPx()))
        val peakSegment = if (actualPeak > 0f) ((actualPeak * count).toInt() - 1).coerceIn(0, count - 1) else -1
        for (segment in 0 until count) {
            val color = when {
                segment < 12 -> Color(0xFF39D77D)
                segment < 14 -> Color(0xFFFFCE3A)
                else -> DeckRed
            }
            val lit = actualLevel >= (segment + 1).toFloat() / count
            val heldPeak = segment == peakSegment
            val origin = Offset(inset + segment * (width + gap), top)
            if (lit || heldPeak) {
                val glowCenter = origin + Offset(width / 2, height / 2)
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = if (lit) .24f else .14f), Color.Transparent), glowCenter, height * .95f), height * .95f, glowCenter)
            }
            drawRoundRect(color.copy(alpha = if (lit) 1f else .10f), origin, Size(width, height), CornerRadius(.8.dp.toPx()))
            if (lit) {
                drawRoundRect(Color.White.copy(alpha = .22f), origin + Offset(.4.dp.toPx(), .7.dp.toPx()), Size((width - .8.dp.toPx()).coerceAtLeast(.5f), 1.dp.toPx()), CornerRadius(.3.dp.toPx()))
            }
            if (heldPeak) {
                drawRoundRect(color, Offset(origin.x, top - 2.dp.toPx()), Size(width, 1.5.dp.toPx()), CornerRadius(.4.dp.toPx()))
                drawLine(Color.White.copy(alpha = .65f), Offset(origin.x, top - 2.dp.toPx()), Offset(origin.x + width, top - 2.dp.toPx()), .5.dp.toPx())
            }
        }
    }
}

private fun DrawScope.drawFaceplate(leftAngle: Float, rightAngle: Float, progress: Float, powered: Boolean) {
    // A back-wound take-up routes over the outer right guide, so its opposite
    // rotation feeds the same left-to-right tape path without a crossed ribbon.
    drawLine(Color.White.copy(alpha = .04f), Offset(12f, 468f), Offset(988f, 468f), 1f)
    val tape = Path().apply {
        moveTo(414f, 310f); lineTo(354f, 424f); lineTo(409f, 443f)
        lineTo(591f, 443f); lineTo(808f, 443f); lineTo(848f, 425f); lineTo(920f, 310f)
    }
    drawPath(tape, Color(0xFF353535), style = Stroke(9f))
    drawPath(tape, Color.White.copy(alpha = .24f), style = Stroke(1f))
    drawReel(Offset(247f, 215f), 202f, .84f - progress * .35f, leftAngle)
    drawReel(Offset(753f, 215f), 202f, .49f + progress * .35f, rightAngle)
    for (x in listOf(354f, 828f)) {
        drawCircle(Color.Black, 28f, Offset(x, 424f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFABABAB), Color(0xFF2A2A2A)), Offset(x - 8, 416f), 32f), 23f, Offset(x, 422f))
        drawCircle(DeckBlack, 12f, Offset(x, 422f))
        drawCircle(Color.White.copy(alpha = .45f), 4f, Offset(x, 422f))
    }
    drawRoundRect(Color.Black, Offset(414f, 376f), Size(172f, 85f), CornerRadius(8f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF373737), DeckBlack), 376f, 453f), Offset(422f, 376f), Size(156f, 76f), CornerRadius(6f))
    drawRoundRect(Color.White.copy(alpha = .20f), Offset(422f, 376f), Size(156f, 76f), CornerRadius(6f), style = Stroke(1f))
    drawRect(DeckRed.copy(alpha = if (powered) 1f else .3f), Offset(433f, 387f), Size(134f, 4f))
    drawLine(Color.White.copy(alpha = .3f), Offset(454f, 401f), Offset(546f, 401f), 2f)
    drawLine(Color.White.copy(alpha = .13f), Offset(454f, 408f), Offset(546f, 408f), 2f)
    drawLine(Color.White.copy(alpha = .13f), Offset(454f, 415f), Offset(546f, 415f), 2f)
    for (x in listOf(435f, 565f)) screw(Offset(x, 436f), 4f)
    for (point in listOf(Offset(12f, 25f), Offset(988f, 25f), Offset(12f, 452f), Offset(988f, 452f))) screw(point, 5f)
}

private fun DrawScope.drawReel(center: Offset, radius: Float, tapeRadius: Float, angle: Float) {
    drawCircle(Color.Black.copy(alpha = .9f), radius + 7f, center + Offset(0f, 10f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF323232), DeckBlack), center, radius), radius, center)
    rotate(angle, center) {
        drawCircle(Brush.radialGradient(listOf(Color(0xFF9A9A9A), Color(0xFF3B3B3B), Color(0xFF797979), Color(0xFF2C2C2C)), center - Offset(radius * .2f, radius * .25f), radius * 1.5f), radius, center)
        drawCircle(Color.White.copy(alpha = .45f), radius - 3f, center, style = Stroke(2f))
        drawCircle(Color.Black.copy(alpha = .45f), radius - 10f, center, style = Stroke(2f))
        for (spoke in 0..2) {
            rotate(spoke * 120f, center) {
                fun point(r: Float, degrees: Float) = radial(center, radius * r, degrees)
                val a = point(.36f, 19f)
                val hole = Path().apply {
                    moveTo(a.x, a.y)
                    val b = point(.68f, 4f); val c = point(.91f, 16f); val d = point(.82f, 40f)
                    cubicTo(b.x, b.y, c.x, c.y, d.x, d.y)
                    val e = point(.76f, 61f); val f = point(.63f, 80f); val g = point(.42f, 86f)
                    cubicTo(e.x, e.y, f.x, f.y, g.x, g.y)
                    val h = point(.28f, 71f); val i = point(.24f, 43f)
                    cubicTo(h.x, h.y, i.x, i.y, a.x, a.y); close()
                }
                drawPath(hole, DeckBlack)
                clipPath(hole) {
                    drawCircle(Color(0xFF232323), radius * tapeRadius, center)
                    for (groove in 0..18) {
                        val r = radius * (.28f + groove * .031f)
                        if (r <= radius * tapeRadius) drawCircle(Color.White.copy(alpha = .06f), r, center, style = Stroke(1f))
                    }
                }
                drawPath(hole, Color.Black.copy(alpha = .85f), style = Stroke(4f))
                drawPath(hole, Color.White.copy(alpha = .20f), style = Stroke(1.1f))
                screw(point(.28f, 117f), 4.5f)
            }
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFFB3B3B3), Color(0xFF4A4A4A), Color(0xFF252525)), center - Offset(6f, 6f), radius * .26f), radius * .24f, center)
        drawCircle(Color.White.copy(alpha = .45f), radius * .24f, center, style = Stroke(1f))
        drawCircle(DeckBlack, radius * .115f, center)
        drawCircle(Color(0xFF898989), radius * .057f, center)
        drawLine(DeckBlack, center - Offset(radius * .035f, 0f), center + Offset(radius * .035f, 0f), 3f)
    }
    // A stationary highlight gives the spinning metal a physical light source.
    drawArc(Color.White.copy(alpha = .28f), 218f, 83f, false, center - Offset(radius, radius), Size(radius * 2, radius * 2), style = Stroke(3f))
}

private fun DrawScope.screw(center: Offset, radius: Float) {
    drawCircle(Color.Black, radius + 1f, center + Offset(0f, 1f))
    drawCircle(Color(0xFF797979), radius, center)
    drawLine(DeckBlack, center - Offset(radius * .65f, 0f), center + Offset(radius * .65f, 0f), 1.5f)
}

private fun radial(center: Offset, radius: Float, angle: Float): Offset {
    val radians = Math.toRadians(angle.toDouble())
    return center + Offset(cos(radians).toFloat() * radius, sin(radians).toFloat() * radius)
}
