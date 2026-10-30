package app.playlistplayer.ui.mobile.navigation

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * One [ViewModelStore] per pushed screen, so a detail screen's ViewModel lives while the screen is
 * on the stack (including under the player) and is cleared when it is popped. Top-level
 * destinations use the activity's store instead, so their browse state survives tab switches.
 */
internal class EntryViewModelStores {
    private val stores = HashMap<Long, ViewModelStore>()
    private val owners = HashMap<Long, ViewModelStoreOwner>()

    fun owner(id: Long): ViewModelStoreOwner = owners.getOrPut(id) {
        val store = stores.getOrPut(id) { ViewModelStore() }
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    fun clear(id: Long) {
        owners.remove(id)
        stores.remove(id)?.clear()
    }

    fun clearAll() {
        stores.values.forEach { it.clear() }
        stores.clear()
        owners.clear()
    }
}
