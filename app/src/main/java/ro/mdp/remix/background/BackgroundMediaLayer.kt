package ro.mdp.remix.background

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/** Real native-renderer observations. Frame counters never advance from a diagnostic timer. */
data class BackgroundRenderState(
    val source: String? = null,
    val kind: BackgroundKind = BackgroundKind.ORIGINAL,
    val loaded: Boolean = false,
    val lifecycleStarted: Boolean = false,
    val animationEnabled: Boolean = true,
    val playing: Boolean = false,
    val muted: Boolean = true,
    val audioTracksDisabled: Boolean = true,
    val width: Int = 0,
    val height: Int = 0,
    val frameCount: Long = 0,
    val positionMs: Long = 0,
    val loopCount: Long = 0,
    val error: String? = null,
)

object BackgroundMediaDiagnostics {
    private val lock = Any()
    private var currentOwner: Any? = null
    private val mutableState = MutableStateFlow(BackgroundRenderState())
    val state: StateFlow<BackgroundRenderState> = mutableState.asStateFlow()

    internal fun attach(owner: Any, initial: BackgroundRenderState) = synchronized(lock) {
        currentOwner = owner
        mutableState.value = initial
    }

    internal fun update(owner: Any, transform: (BackgroundRenderState) -> BackgroundRenderState) = synchronized(lock) {
        if (currentOwner === owner) mutableState.value = transform(mutableState.value)
    }

    internal fun detach(owner: Any) = synchronized(lock) {
        if (currentOwner === owner) {
            currentOwner = null
            mutableState.value = BackgroundRenderState()
        }
    }
}

/** Native media lives behind Compose controls and never participates in touch handling. */
@Composable
fun BackgroundMediaLayer(
    settings: State<BackgroundSettings>,
    modifier: Modifier = Modifier,
    onError: (String?) -> Unit = {},
) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val mediaView = remember(context) { BackgroundMediaView(context) }
    val latestError by rememberUpdatedState(onError)
    var started by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner, mediaView) {
        val observer = LifecycleEventObserver { _, _ ->
            started = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            // ON_STOP can pause Compose's frame clock before a recomposition runs.
            // Stop the native decoder/player synchronously in the lifecycle callback.
            mediaView.setLifecycleStarted(started)
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            mediaView.setLifecycleStarted(false)
        }
    }
    BoxWithConstraints(modifier.background(Color(0xFF09090B)).testTag("background_layer")) {
        val density = LocalDensity.current
        val decodeEdge = with(density) { max(maxWidth.toPx(), maxHeight.toPx()).toInt() }.coerceIn(256, 2_048)
        AndroidView(
            factory = { mediaView },
            modifier = Modifier.fillMaxSize().testTag("background_media"),
            onReset = null,
            onRelease = { it.release() },
            // Read the live selection inside AndroidView's snapshot observer.
            // A captured settings value can remain stale while the surrounding
            // constraints/subcomposition are unchanged after returning from SAF.
            update = { it.render(settings.value, started, decodeEdge) { message -> latestError(message) } },
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.value.dimming.coerceIn(0f, 1f))))
    }
}
