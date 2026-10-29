package app.playlistplayer

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Which UI tree the single APK shows: tv-material on TV devices, material3 on phones/tablets.
 *
 * Detection relies only on the device's UI mode and declared system features. Input devices
 * (keyboard, D-pad, game controller, touch screen presence) are deliberately ignored: a phone
 * with a Bluetooth keyboard or D-pad remote is still a phone.
 */
enum class FormFactor {
    Tv,
    Mobile;

    companion object {
        /** Fire TV devices declare this feature in addition to (or instead of) leanback. */
        const val FEATURE_AMAZON_FIRE_TV: String = "amazon.hardware.fire_tv"

        /**
         * Pure decision function.
         *
         * @param uiModeType the `Configuration.UI_MODE_TYPE_*` value reported by [UiModeManager]
         *   or the current configuration.
         * @param hasLeanbackFeature whether [PackageManager.FEATURE_LEANBACK] is present.
         * @param hasFireTvFeature whether [FEATURE_AMAZON_FIRE_TV] is present.
         */
        fun resolve(
            uiModeType: Int,
            hasLeanbackFeature: Boolean,
            hasFireTvFeature: Boolean = false,
        ): FormFactor = when {
            uiModeType == Configuration.UI_MODE_TYPE_TELEVISION -> Tv
            hasLeanbackFeature || hasFireTvFeature -> Tv
            else -> Mobile
        }

        /** Detects the form factor of the device [context] runs on. */
        fun detect(context: Context): FormFactor {
            val uiModeManager = context.getSystemService(UiModeManager::class.java)
            val managerType = uiModeManager?.currentModeType ?: Configuration.UI_MODE_TYPE_UNDEFINED
            val configType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
            val uiModeType =
                if (managerType == Configuration.UI_MODE_TYPE_TELEVISION) managerType else configType
            val packageManager = context.packageManager
            return resolve(
                uiModeType = uiModeType,
                hasLeanbackFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
                hasFireTvFeature = packageManager.hasSystemFeature(FEATURE_AMAZON_FIRE_TV),
            )
        }
    }
}
