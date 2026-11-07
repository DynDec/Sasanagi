package com.sasayaki.player

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.sasayaki.player.data.CoverCache
import com.sasayaki.player.data.CoverFetcher
import com.sasayaki.player.data.CoverKeyer
import com.sasayaki.player.data.DurationRepository
import com.sasayaki.player.data.LibraryRepository
import com.sasayaki.player.data.ProgressRepository
import com.sasayaki.player.data.SettingsRepository
import com.sasayaki.player.playback.PlaybackEngine
import kotlinx.coroutines.MainScope

/** App-wide singletons. */
class AppGraph(app: Application) {
    val scope = MainScope()
    val settings = SettingsRepository(app, scope)
    val library = LibraryRepository(app, scope, settings)
    val progress = ProgressRepository(app, scope)
    val durations = DurationRepository(app, scope)
    val covers = CoverCache(app)
    val engine by lazy { PlaybackEngine(app, scope, settings, library, progress, durations, covers) }
}

class SasayakiApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(CoverKeyer())
            add(CoverFetcher.Factory(graph.covers))
        }
        .crossfade(true)
        .build()
}
