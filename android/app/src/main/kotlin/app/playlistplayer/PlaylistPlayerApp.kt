package app.playlistplayer

import android.app.Application
import androidx.work.Configuration

/**
 * Creates the [AppContainer] and starts process-lifetime work: the parental state load (the UI
 * waits for it), search indexing, and the periodic playlist / guide refresh schedules.
 *
 * Provides WorkManager's configuration on demand so workers get their dependencies from
 * [AppContainer] (the default startup initializer is removed in the manifest).
 */
class PlaylistPlayerApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.initializeAsync()
        container.startBackgroundWork()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(container.workerFactory)
            .build()
}
