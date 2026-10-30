package app.playlistplayer.ui.tv.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.playlistplayer.R
import app.playlistplayer.feature.search.LiveHit
import app.playlistplayer.feature.search.SearchSection
import app.playlistplayer.feature.search.SearchSectionKind
import app.playlistplayer.feature.search.SearchUiState
import app.playlistplayer.feature.search.SearchViewModel
import app.playlistplayer.feature.search.VodHit
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.components.TvEmptyState
import app.playlistplayer.ui.tv.components.TvErrorState
import app.playlistplayer.ui.tv.components.TvLoadingState
import app.playlistplayer.ui.tv.components.TvTextField

/**
 * TV global search (U11): a query field (on-screen keyboard) above results that update while
 * typing. Sections Live / Movies / Series; inside each, a row per playlist under its name (R25).
 * Live hits play directly; title hits open the merged title like a browse card.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onPlayChannel: (LiveHit) -> Unit,
    onOpenTitle: (VodHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    Column(modifier = modifier.fillMaxSize().padding(vertical = UiTokens.OverscanVertical)) {
        TvTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            label = stringResource(R.string.search_field_label),
            emptyPlaceholder = stringResource(R.string.search_field_placeholder),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.OverscanHorizontal),
        )
        Box(modifier = Modifier.fillMaxSize().padding(top = UiTokens.SpaceL), contentAlignment = Alignment.TopCenter) {
            when (val s = state) {
                SearchUiState.Idle -> TvEmptyState(
                    title = stringResource(R.string.search_idle_title),
                    message = stringResource(R.string.search_idle_message),
                    focusable = false,
                )
                is SearchUiState.Searching -> TvLoadingState()
                is SearchUiState.NoResults -> TvEmptyState(
                    title = stringResource(R.string.search_no_results_title, s.query),
                    message = stringResource(R.string.search_no_results_message),
                    focusable = false,
                )
                is SearchUiState.Error -> TvErrorState(message = stringResource(R.string.search_error), onRetry = viewModel::retry)
                is SearchUiState.Results -> SearchResultsList(s.sections, onPlayChannel, onOpenTitle)
            }
        }
    }
}

@Composable
private fun SearchResultsList(sections: List<SearchSection>, onPlayChannel: (LiveHit) -> Unit, onOpenTitle: (VodHit) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = UiTokens.SpaceXl),
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
    ) {
        for (section in sections) {
            item(key = "section:${section.kind}") { TvRowHeading(sectionTitle(section.kind), large = true) }
            when (section) {
                is SearchSection.Live -> items(section.groups, key = { "live:${it.playlist.id}" }) { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
                        TvRowHeading(group.playlist.name)
                        TvCardRow {
                            items(group.hits, key = { "${it.playlistId}:${it.ordinal}" }) { hit ->
                                TvChannelCard(name = hit.name, subtitle = hit.groupName, logo = hit.logo, onClick = { onPlayChannel(hit) })
                            }
                        }
                    }
                }
                is SearchSection.Titles -> items(section.groups, key = { "${section.kind}:${it.playlist.id}" }) { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
                        TvRowHeading(group.playlist.name)
                        TvCardRow {
                            items(group.hits, key = { it.titleKey }) { hit ->
                                TvPosterCard(
                                    title = hit.title,
                                    subtitle = hit.year?.toString(),
                                    posterUrl = hit.posterUrl,
                                    onClick = { onOpenTitle(hit) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun sectionTitle(kind: SearchSectionKind): String = stringResource(
    when (kind) {
        SearchSectionKind.LIVE -> R.string.search_section_live
        SearchSectionKind.MOVIES -> R.string.search_section_movies
        SearchSectionKind.SERIES -> R.string.search_section_series
    },
)
