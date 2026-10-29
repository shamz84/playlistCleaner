package app.playlistplayer

import android.app.Application
import androidx.work.Configuration

/**
 * Provides WorkManager's configuration on demand so workers get their dependencies from
 * [AppContainer] (the default startup initializer is removed in the manifest).
 */
class PlaylistPlayerApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(container.workerFactory)
            .build()
}
