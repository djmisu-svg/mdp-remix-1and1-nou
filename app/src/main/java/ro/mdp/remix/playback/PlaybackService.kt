package ro.mdp.remix.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ro.mdp.remix.MainActivity

/** Owns playback independently of the Activity, including the lock-screen controls. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // MediaSessionService supplies the foreground media notification and exposes
        // the same player to Bluetooth headsets, system media controls and the app.
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // Keep active music running when the user dismisses the app from Recents.
        // A failed/idle player may retain playWhenReady=true, but is no longer
        // doing useful playback and should not leave an orphaned service alive.
        val ongoing = player != null && player.playWhenReady && player.mediaItemCount > 0 &&
            (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY)
        if (!ongoing) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
