package com.sasayaki.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.sasayaki.player.MainActivity
import com.sasayaki.player.SasayakiApp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/** Hosts the media session so playback continues in the background with a media notification. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        val engine = (application as SasayakiApp).graph.engine
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, engine.player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    isForPlayback: Boolean,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
                    engine.resumptionItems() ?: throw UnsupportedOperationException("Nothing to resume")
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        // Keep playing in the background if the user swipes the app away mid-listen.
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            (application as SasayakiApp).graph.engine.saveProgress()
            stopSelf()
        }
    }

    override fun onDestroy() {
        (application as SasayakiApp).graph.engine.saveProgress()
        (application as SasayakiApp).graph.progress.flush()
        session?.run {
            // The player is owned by the app-wide engine, so only the session is released.
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
