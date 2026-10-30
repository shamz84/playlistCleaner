package app.playlistplayer.ui.mobile.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.playlistplayer.R
import app.playlistplayer.feature.search.LiveHit
import app.playlistplayer.feature.search.SearchSection
import app.playlistplayer.feature.search.SearchSectionKind
import app.playlistplayer.feature.search.SearchUiState
import app.playlistplayer.feature.search.SearchViewModel
import app.playlistplayer.feature.search.VodHit
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.mobile.MobileDimens
import app.playlistplayer.ui.mobile.MobileEmpty
import app.playlistplayer.ui.mobile.MobileError
import app.playlistplayer.ui.mobile.MobileLoading

object MobileSearchTags {
    const val FIELD = "mobile.search.field"
    const val RESULTS = "mobile.search.results"
}

/**
 * Phone global search (U11): the same [SearchViewModel] as TV. A query field with a clear
 * action, then Live / Movies / Series sections, each with a row per playlist under its name.
 */
@Composable
fun MobileSearchScreen(
    viewModel: SearchViewModel,
    onPlayChannel: (LiveHit) -> Unit,
    onOpenTitle: (VodHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val clearLabel = stringResource(R.string.search_clear)
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            label = { Text(stringResource(R.string.search_field_label)) },
            placeholder = { Text(stringResource(R.string.search_field_placeholder)) },
            singleLine = true,
            trailingIcon = if (query.isNotEmpty()) {
                {
                    TextButton(
                        onClick = viewModel::clear,
                        modifier = Modifier
                            .sizeIn(minHeight = MobileDimens.MinTouchTarget, minWidth = MobileDimens.MinTouchTarget)
                            .semantics { contentDescription = clearLabel },
                    ) {
                        Text("✕")
                    }
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(MobileDimens.ScreenPadding)
                .testTag(MobileSearchTags.FIELD),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when (val s = state) {
                SearchUiState.Idle -> MobileEmpty(
                    title = stringResource(R.string.search_idle_title),
                    message = stringResource(R.string.search_idle_message),
                )
                is SearchUiState.Searching -> MobileLoading()
                is SearchUiState.NoResults -> MobileEmpty(
                    title = stringResource(R.string.search_no_results_title, s.query),
                    message = stringResource(R.string.search_no_results_message),
                )
                is SearchUiState.Error -> MobileError(stringResource(R.string.search_error), onRetry = viewModel::retry)
                is SearchUiState.Results -> MobileResultsList(s.sections, onPlayChannel, onOpenTitle)
            }
        }
    }
}

@Composable
private fun MobileResultsList(sections: List<SearchSection>, onPlayChannel: (LiveHit) -> Unit, onOpenTitle: (VodHit) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(MobileSearchTags.RESULTS),
        contentPadding = PaddingValues(bottom = UiTokens.SpaceXl),
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
    ) {
        for (section in sections) {
            item(key = "section:${section.kind}") { MobileRowHeading(mobileSectionTitle(section.kind), large = true) }
            when (section) {
                is SearchSection.Live -> items(section.groups, key = { "live:${it.playlist.id}" }) { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
                        MobileRowHeading(group.playlist.name)
                        MobileCardRow {
                            items(group.hits, key = { "${it.playlistId}:${it.ordinal}" }) { hit ->
                                MobileChannelCard(name = hit.name, subtitle = hit.groupName, logo = hit.logo, onClick = { onPlayChannel(hit) })
                            }
                        }
                    }
                }
                is SearchSection.Titles -> items(section.groups, key = { "${section.kind}:${it.playlist.id}" }) { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
                        MobileRowHeading(group.playlist.name)
                        MobileCardRow {
                            items(group.hits, key = { it.titleKey }) { hit ->
                                MobilePosterCard(title = hit.title, subtitle = hit.year?.toString(), posterUrl = hit.posterUrl, onClick = { onOpenTitle(hit) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun mobileSectionTitle(kind: SearchSectionKind): String = stringResource(
    when (kind) {
        SearchSectionKind.LIVE -> R.string.search_section_live
        SearchSectionKind.MOVIES -> R.string.search_section_movies
        SearchSectionKind.SERIES -> R.string.search_section_series
    },
)
