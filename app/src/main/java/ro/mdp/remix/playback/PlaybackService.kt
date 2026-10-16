package ro.mdp.remix.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
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
        AudioMeters.reset()
        Log.d("MdP.AudioMeters", "Service created: pid=${Process.myPid()}; meter=${System.identityHashCode(AudioMeters)}")

        val player = ExoPlayer.Builder(this, MeterRenderersFactory(this))
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
        player.addListener(object : Player.Listener {
            private var lastIsPlaying = false

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                ) {
                    AudioMeters.clear()
                }
                // Includes pause, buffering, audio-focus loss, errors and end-of-queue.
                AudioMeters.setPlaybackActive(player.isPlaying)
                if (lastIsPlaying != player.isPlaying) {
                    lastIsPlaying = player.isPlaying
                    Log.d("MdP.AudioMeters", "Playback active=${player.isPlaying}; state=${player.playbackState}; ready=${player.playWhenReady}; suppression=${player.playbackSuppressionReason}")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AudioMeters.setPlaybackActive(false)
                Log.e("MdP.AudioMeters", "Playback failed: code=${error.errorCodeName}")
            }
        })

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
        AudioMeters.reset()
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
