package app.playlistplayer

import android.content.Context

/**
 * Manual dependency container (no Hilt). One instance lives in [PlaylistPlayerApp] and is
 * shared by every Activity and ViewModel factory. Later units add repositories, the player
 * factory, sync scheduling and secure storage here as lazily created singletons.
 */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    /** The device's form factor never changes while the process is alive. */
    val formFactor: FormFactor by lazy { FormFactor.detect(appContext) }

    /** Restricted Drive API key injected at build time from local.properties/env; may be empty. */
    val driveApiKey: String = BuildConfig.DRIVE_API_KEY
}
