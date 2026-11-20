package com.arflix.tv.ui.screens.modes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.CatalogKind
import com.arflix.tv.data.model.CatalogSourceType
import com.arflix.tv.data.model.CollectionGroupKind
import com.arflix.tv.data.model.CollectionSourceConfig
import com.arflix.tv.data.model.CollectionSourceKind
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.modes.StreamingModes
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.ui.screens.collections.ServiceHome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Uma aba da tela "Novidades e populares". */
internal data class NewPopularTab(val key: String, val label: String, val items: List<MediaItem> = emptyList(),
                                  val loading: Boolean = true, val ranked: Boolean = false, val upcoming: Boolean = false)

/**
 * "Novidades e populares" da Netflix (como no app de TV e no plugin do PC): Novidades, Top 10 séries,
 * Top 10 filmes e Em breve, só com o catálogo da Netflix Brasil.
 */
@HiltViewModel
internal class NetflixNewPopularViewModel @Inject constructor(
    private val mediaRepository: MediaRepository
) : ViewModel() {
    private val _tabs = MutableStateFlow(
        listOf(
            NewPopularTab("new", "🍿 Novidades"),
            NewPopularTab("top_series", "🔟 Top 10 séries", ranked = true),
            NewPopularTab("top_movies", "🔟 Top 10 filmes", ranked = true),
            NewPopularTab("soon", "🔜 Em breve", upcoming = true)
        )
    )
    val tabs: StateFlow<List<NewPopularTab>> = _tabs.asStateFlow()
    private var loaded = false

    fun load(mode: StreamingModes.Mode) {
        if (loaded) return
        loaded = true
        val today = LocalDate.now()
        val service = StreamingModes.catalog(mode)
        val rows = ServiceHome.rows(service, today)
        fun rowCatalog(id: String) = rows.firstOrNull { it.id == id }?.catalog
        load("new", rowCatalog(ServiceHome.NEW_ROW_ID), 30)
        load("top_series", rowCatalog(ServiceHome.TOP10_SERIES_ROW_ID), 10)
        load("top_movies", rowCatalog(ServiceHome.TOP10_MOVIES_ROW_ID), 10)
        load("soon", comingSoon(mode, today), 30)
    }

    /** Estreias: séries originais do serviço com primeira exibição a partir de amanhã (TMDB). */
    private fun comingSoon(mode: StreamingModes.Mode, today: LocalDate): CatalogConfig? {
        val network = when (mode.key) {
            "netflix" -> "213"
            "disney" -> "2739"
            "apple" -> "2552"
            "prime" -> "1024"
            "max" -> "3186|49"
            else -> return null
        }
        return CatalogConfig(
            id = "sm_soon_${mode.key}",
            title = "Em breve",
            sourceType = CatalogSourceType.PREINSTALLED,
            isPreinstalled = true,
            kind = CatalogKind.COLLECTION,
            collectionGroup = CollectionGroupKind.SERVICE,
            collectionSources = listOf(
                CollectionSourceConfig(
                    kind = CollectionSourceKind.TMDB_DISCOVER,
                    mediaType = "series",
                    sortBy = "first_air_date.asc",
                    discoverParams = mapOf(
                        "with_networks" to network,
                        "first_air_date.gte" to today.plusDays(1).toString(),
                        "first_air_date.lte" to today.plusMonths(4).toString()
                    )
                )
            )
        )
    }

    private fun load(key: String, catalog: CatalogConfig?, limit: Int) {
        if (catalog == null) { update(key, emptyList()); return }
        viewModelScope.launch {
            val items = try {
                mediaRepository.loadCollectionCatalogPage(catalog, offset = 0, limit = limit).items
                    .filter { it.id > 0 }.distinctBy { it.mediaType to it.id }.take(limit)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            update(key, items)
        }
    }

    private fun update(key: String, items: List<MediaItem>) {
        _tabs.update { tabs -> tabs.map { if (it.key == key) it.copy(items = items, loading = false) else it } }
    }
}
