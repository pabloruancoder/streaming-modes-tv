package com.arflix.tv.ui.screens.modes

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Título das fileiras de "Novidades e populares" (dados do addon Streaming Modes, como no PC). */
internal data class NPItem(
    val imdbId: String,
    val movie: Boolean,
    val name: String,
    val description: String = "",
    val background: String? = null,
    val poster: String? = null,
    val logo: String? = null,
    val genres: List<String> = emptyList(),
    val year: String = "",
    /** Estreias: AAAA-MM-DD. */
    val date: String? = null,
    val kind: String? = null
) {
    val daysUntil: Long? get() = date?.let { runCatching { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(it.take(10))) }.getOrNull() }
    /** Ainda não estreou (não dá para assistir). */
    val upcoming: Boolean get() = (daysUntil ?: -1) > 0
}

internal data class NPRow(val id: String, val title: String, val items: List<NPItem>, val ranked: Boolean = false, val upcoming: Boolean = false)

/**
 * "Novidades e populares" igual à do plugin do PC: fileiras Novidades na Netflix, Chegando esta semana,
 * Top 10 séries no Brasil esta semana, Chegando na próxima semana, Top 10 filmes, Vale a espera e Bombando agora.
 * Os dados vêm do addon publicado no GitHub Pages (Top 10 oficial e estreias com data e sinopse em português).
 */
@HiltViewModel
internal class NetflixNewPopularViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbApi: TmdbApi
) : ViewModel() {
    private val _rows = MutableStateFlow<List<NPRow>>(emptyList())
    val rows: StateFlow<List<NPRow>> = _rows.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val prefs = context.getSharedPreferences("streaming_modes", Context.MODE_PRIVATE)
    private val _reminders = MutableStateFlow(prefs.getStringSet("nf_reminders", emptySet()).orEmpty())
    val reminders: StateFlow<Set<String>> = _reminders.asStateFlow()
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val recentS = async { catalog("series", "netflix.recent-series") }
            val recentM = async { catalog("movie", "netflix.recent-movies") }
            val topS = async { catalog("series", "netflix.top10-series") }
            val topM = async { catalog("movie", "netflix.top10-movies") }
            val trendS = async { catalog("series", "netflix.trending-series") }
            val trendM = async { catalog("movie", "netflix.trending-movies") }
            val soon = async { upcoming() }
            val up = soon.await()
            fun upRow(id: String, title: String, test: (Long) -> Boolean) =
                NPRow(id, title, up.filter { it.daysUntil?.let(test) == true }, upcoming = true)
            _rows.value = listOf(
                NPRow("new", "Novidades na Netflix", interleave(recentS.await(), recentM.await())),
                upRow("week", "Chegando esta semana") { it in 0..7 },
                NPRow("top_s", "Top 10 séries no Brasil esta semana", topS.await().take(10), ranked = true),
                upRow("next", "Chegando na próxima semana") { it in 8..14 },
                NPRow("top_m", "Top 10 filmes no Brasil esta semana", topM.await().take(10), ranked = true),
                upRow("later", "Vale a espera") { it > 14 },
                NPRow("trend", "Bombando agora", interleave(trendS.await(), trendM.await()).take(40))
            ).filter { it.items.isNotEmpty() }
            _loading.value = false
        }
    }

    fun toggleReminder(item: NPItem) {
        val next = _reminders.value.toMutableSet().apply { if (!add(item.imdbId)) remove(item.imdbId) }
        prefs.edit().putStringSet("nf_reminders", next).apply()
        _reminders.value = next
    }

    /** IMDb → TMDB (o app abre títulos pelo id do TMDB). */
    suspend fun resolve(item: NPItem): Pair<MediaType, Int>? = withContext(Dispatchers.IO) {
        runCatching {
            val r = tmdbApi.findByExternalId(item.imdbId, Constants.TMDB_API_KEY, "imdb_id")
            if (item.movie) r.movieResults.firstOrNull()?.let { MediaType.MOVIE to it.id } ?: r.tvResults.firstOrNull()?.let { MediaType.TV to it.id }
            else r.tvResults.firstOrNull()?.let { MediaType.TV to it.id } ?: r.movieResults.firstOrNull()?.let { MediaType.MOVIE to it.id }
        }.getOrNull()
    }

    private fun interleave(a: List<NPItem>, b: List<NPItem>): List<NPItem> = buildList {
        for (i in 0 until maxOf(a.size, b.size)) { a.getOrNull(i)?.let(::add); b.getOrNull(i)?.let(::add) }
    }.distinctBy { it.imdbId }

    private suspend fun catalog(type: String, id: String): List<NPItem> =
        fetch("$PAGES/catalog/$type/$id.json")?.optJSONArray("metas")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.toItem(type == "movie") }
        }.orEmpty()

    private suspend fun upcoming(): List<NPItem> =
        fetch("$PAGES/sm/upcoming/netflix.json")?.optJSONArray("items")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> o.toItem(o.optString("type") == "movie") } }
        }.orEmpty().filter { (it.daysUntil ?: -1) >= 0 }.sortedBy { it.date }

    private fun JSONObject.toItem(movie: Boolean): NPItem? {
        val id = optString("id").takeIf { it.startsWith("tt") } ?: return null
        val genres = optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty()
        return NPItem(
            imdbId = id, movie = movie, name = optString("name"),
            description = optString("description"),
            background = optString("background").ifBlank { null },
            poster = optString("poster").ifBlank { null },
            logo = optString("logo").ifBlank { null },
            genres = genres,
            year = optString("releaseInfo"),
            date = optString("date").ifBlank { null },
            kind = optString("kind").ifBlank { null }
        )
    }

    private suspend fun fetch(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PAGES = "https://pabloruancoder.github.io/stremio-streaming-modes"
    }
}
