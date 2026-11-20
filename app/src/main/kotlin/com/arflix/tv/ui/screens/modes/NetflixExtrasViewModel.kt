package com.arflix.tv.ui.screens.modes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.repository.ContinueWatchingItem
import com.arflix.tv.data.repository.ContinueWatchingMerge
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.data.repository.WatchHistoryRepository
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.data.repository.sync.RemoteSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Item de "Continuar assistindo" para a Home Netflix: o título + onde parou. */
internal data class NetflixResume(val item: MediaItem, val progress: Int, val season: Int?, val episode: Int?, val positionSeconds: Long)

/**
 * Fileiras pessoais da Home Netflix: "Continuar assistindo" (mesmas fontes de progresso que a Home do app)
 * e "Minha lista".
 */
@HiltViewModel
internal class NetflixExtrasViewModel @Inject constructor(
    private val remoteSyncManager: RemoteSyncManager,
    private val traktRepository: TraktRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val watchlistRepository: WatchlistRepository
) : ViewModel() {
    private val _resume = MutableStateFlow<List<NetflixResume>>(emptyList())
    val resume: StateFlow<List<NetflixResume>> = _resume.asStateFlow()
    val myList: StateFlow<List<MediaItem>> = watchlistRepository.watchlistItems

    fun refresh() {
        viewModelScope.launch {
            runCatching { watchlistRepository.refreshWatchlistItems() }.onFailure { if (it is CancellationException) throw it }
        }
        viewModelScope.launch {
            val items = runCatching { loadContinueWatching() }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
            _resume.value = items.filter { it.progress in 1..95 || it.isUpNext }.take(20).map { it.toResume() }
        }
    }

    private suspend fun loadContinueWatching(): List<ContinueWatchingItem> {
        val primary = runCatching { remoteSyncManager.getContinueWatching() }.getOrDefault(emptyList())
        val local = runCatching { traktRepository.getLocalContinueWatchingSnapshot() }.getOrDefault(emptyList())
        val history = runCatching { watchHistoryRepository.getContinueWatching() }.getOrDefault(emptyList())
            .map(ContinueWatchingMerge::fromHistory)
            .sortedByDescending { it.updatedAtMs }
            .distinctBy { "${it.mediaType}:${it.id}" }
        return ContinueWatchingMerge.merge(primary.ifEmpty { history.ifEmpty { local } }, local, history)
    }

    private fun ContinueWatchingItem.toResume(): NetflixResume {
        fun img(path: String?) = path?.let { if (it.startsWith("/")) "https://image.tmdb.org/t/p/w780$it" else it }
        return NetflixResume(
            item = MediaItem(
                id = id,
                title = title,
                overview = overview,
                year = year,
                mediaType = mediaType,
                image = img(posterPath).orEmpty(),
                backdrop = img(backdropPath) ?: img(episodeStillPath),
                progress = progress
            ),
            progress = progress,
            season = displaySeason,
            episode = displayEpisode,
            positionSeconds = resumePositionSeconds
        )
    }
}
