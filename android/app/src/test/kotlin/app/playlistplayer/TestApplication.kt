package app.playlistplayer

import android.app.Application

/**
 * Robolectric application for `:app` unit tests (see `robolectric.properties`): no container, no
 * Keystore, no WorkManager. Integration tests create an [AppContainer] with test overrides.
 */
class TestApplication : Application()
