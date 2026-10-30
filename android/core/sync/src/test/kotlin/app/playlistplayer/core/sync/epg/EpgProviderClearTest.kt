package app.playlistplayer.core.sync.epg

import app.playlistplayer.core.database.entity.EpgMapOrigin
import app.playlistplayer.core.database.entity.ProgrammeSource
import app.playlistplayer.core.sync.epg.EpgTestEnv.Companion.DAY
import app.playlistplayer.core.sync.epg.EpgTestEnv.Companion.HOUR
import app.playlistplayer.core.sync.epg.EpgTestEnv.Companion.NOW
import app.playlistplayer.core.sync.epg.EpgTestEnv.Companion.xml
import app.playlistplayer.core.sync.epg.EpgTestEnv.Companion.xmltv
import app.playlistplayer.core.sync.epg.EpgTestEnv.Programme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** U15: turning Xtream processing off removes the provider guide but keeps the override guide. */
@RunWith(RobolectricTestRunner::class)
class EpgProviderClearTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var env: EpgTestEnv
    private val keys = listOf("bbc1.uk", "bluey.247", "BBCOneOxford.uk", "BBC2.uk", "xtream:497001", "xtream:162096")

    @Before
    fun setUp() {
        env = EpgTestEnv(temp.root)
    }

    @After
    fun tearDown() = env.close()

    private suspend fun programmes(id: Long) = env.epg.programmes(id, keys, NOW - 10 * DAY, NOW + 10 * DAY)

    @Test
    fun keepsOverrideMappingsAndProgrammesOnly() = runBlocking<Unit> {
        val id = env.playlist()
        env.h.secureStore.putEpgOverrideUrl(id, env.overrideUrl)
        env.override = {
            xml(
                xmltv(
                    listOf("bluey.247" to "Bluey", "bbc1.uk" to "BBC One"),
                    listOf(Programme("bluey.247", NOW, NOW + HOUR, "Override Bluey"), Programme("bbc1.uk", NOW, NOW + HOUR, "Override BBC")),
                ),
            )
        }
        env.syncer().sync(id, backfill = true)
        assertThat(programmes(id).map { it.channelKey }).contains("BBCOneOxford.uk")

        env.epg.clearProviderData(id)

        assertThat(env.epg.mappings(id).map { it.origin }.toSet()).containsExactly(EpgMapOrigin.OVERRIDE.name)
        val left = programmes(id)
        assertThat(left.map { it.title }).containsExactly("Override Bluey", "Override BBC")
        assertThat(left.none { it.sourceType == ProgrammeSource.XTREAM_ARCHIVE }).isTrue()
        val state = env.epg.state(id)!!
        assertThat(state.providerMatchedCount).isEqualTo(0)
        assertThat(state.matchedCount).isEqualTo(3)
        assertThat(state.matchRate).isWithin(1e-9).of(3.0 / 5)
        assertThat(state.programmeCount).isEqualTo(2)
        assertThat(state.activeSource).isEqualTo(EpgMapOrigin.OVERRIDE.name)
    }

    @Test
    fun withoutAnOverrideTheGuideIsCleared() = runBlocking<Unit> {
        val id = env.playlist()
        env.syncer().sync(id, backfill = true)
        assertThat(env.epg.programmeCount(id)).isGreaterThan(0)

        env.epg.clearProviderData(id)

        assertThat(env.epg.programmeCount(id)).isEqualTo(0)
        assertThat(env.epg.mappings(id)).isEmpty()
        assertThat(env.epg.state(id)!!.lastImportAt).isNull()
    }
}
