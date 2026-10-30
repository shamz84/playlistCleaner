package app.playlistplayer.feature.settings

import app.playlistplayer.core.sync.secure.SecretRead
import app.playlistplayer.core.sync.secure.SecureStore

/**
 * [SecretSlotStore] over the generic encrypted slots of [SecureStore]. A slot whose ciphertext no
 * longer decrypts reads as [SlotRead.Unreadable] (not missing), so [ParentalControl] fails closed.
 */
class SecureStoreSecretSlots(private val secureStore: SecureStore) : SecretSlotStore {
    override suspend fun read(name: String): SlotRead = when (val read = secureStore.readString(name)) {
        SecretRead.Missing -> SlotRead.Missing
        SecretRead.Unreadable -> SlotRead.Unreadable
        is SecretRead.Value -> SlotRead.Present(read.value)
    }

    override suspend fun put(name: String, value: String) = secureStore.putString(name, value)

    override suspend fun remove(name: String) = secureStore.remove(name)
}
