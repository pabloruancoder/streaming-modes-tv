package com.arflix.tv.ui.screens.modes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.random.Random

/** Capa da aba Descobrir (dados do TMDB). */
internal data class DItem(
    val type: MediaType,
    val id: Int,
    val title: String,
    val backdrop: String,
    val poster: String?,
    val overview: String,
    val year: String,
    val vote: Double,
    val votes: Int,
    val genres: List<String>
) {
    val key get() = "${type}_$id"
}

/** Fileira da aba Descobrir. [ranked] = Top 10 com números; [rating] = mostra a nota na capa. */
internal data class DRow(val id: String, val title: String, val items: List<DItem>, val ranked: Boolean = false, val rating: Boolean = false)

/**
 * Aba "Descobrir" do modo Netflix: fileiras para quem não sabe o que assistir (em alta, mais assistidos,
 * mais famosos, mais bem avaliados, joias escondidas, lançamentos, minisséries, filmes rápidos, clássicos,
 * "Porque você assistiu"...), sempre só com o que está na Netflix Brasil. Dá para filtrar por gênero e
 * "Atualizar" sorteia outras páginas/ordem, então a aba muda a cada visita.
 */
@HiltViewModel
internal class NetflixDiscoverViewModel @Inject constructor() : ViewModel() {
    private val _rows = MutableStateFlow<List<DRow>>(emptyList())
    val rows: StateFlow<List<DRow>> = _rows.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _genre = MutableStateFlow<NSGenre?>(null)
    val genre: StateFlow<NSGenre?> = _genre.asStateFlow()
    private val _logos = MutableStateFlow<Map<String, String>>(emptyMap())
    val logos: StateFlow<Map<String, String>> = _logos.asStateFlow()

    private var seeds: List<NSSeed> = emptyList()
    private var job: Job? = null
    private var started = false
    private var round = 0
    private val logoAsked = ConcurrentHashMap.newKeySet<String>()
    private val logoGate = Semaphore(4)

    /** Chamado ao abrir a aba (uma vez); [seeds] = o que a pessoa assistiu por último. */
    fun start(seeds: List<NSSeed>) {
        if (started) return
        started = true
        this.seeds = seeds
        reload()
    }

    fun setGenre(g: NSGenre?) {
        if (g?.label == _genre.value?.label) return
        _genre.value = g
        reload()
    }

    /** "Atualizar": outras páginas, outra semente e outra ordem das fileiras do meio. */
    fun refresh() {
        round++
        reload()
    }

    /** "Surpreenda-me": um título bem avaliado ao acaso entre os que estão na tela. */
    fun surprise(): DItem? {
        val all = _rows.value.flatMap { it.items }.distinctBy { it.key }
        return all.filter { it.vote >= 7.0 && it.votes >= 200 }.ifEmpty { all }.randomOrNull()
    }

    fun wantLogos(items: List<DItem>) {
        items.filter { logoAsked.add(it.key) }.forEach { item ->
            viewModelScope.launch {
                logoGate.withPermit {
                    val t = if (item.type == MediaType.MOVIE) "movie" else "tv"
                    val logos = tmdb("/$t/${item.id}/images", mapOf("include_image_language" to "pt,en,null"), lang = false)
                        ?.optJSONArray("logos").objects()
                    val best = logos.firstOrNull { it.optString("iso_639_1") == "pt" } ?: logos.firstOrNull { it.optString("iso_639_1") == "en" }
                    best?.optString("file_path")?.takeIf { it.startsWith("/") }?.let { path ->
                        _logos.value = _logos.value + (item.key to "$IMG/w300$path")
                    }
                }
            }
        }
    }

    // ---------- Fileiras ----------

    private class Spec(val id: String, val title: String, val ranked: Boolean = false, val rating: Boolean = false,
                       val pinned: Boolean = false, val load: suspend () -> List<DItem>)

    private fun reload() {
        job?.cancel()
        _rows.value = emptyList()
        _loading.value = true
        val g = _genre.value
        val rnd = Random(System.nanoTime())
        job = viewModelScope.launch {
            val specs = plan(g, rnd)
            val done = arrayOfNulls<List<DItem>>(specs.size)
            val lock = Mutex()
            // Cada fileira aparece assim que chega, na ordem do plano e sem repetir títulos das de cima.
            suspend fun publish() {
                val seen = HashSet<String>()
                val out = mutableListOf<DRow>()
                for ((i, s) in specs.withIndex()) {
                    val items = done[i] ?: break
                    val fresh = if (s.ranked) items.take(10) else items.filter { it.key !in seen }.take(20)
                    fresh.forEach { seen += it.key }
                    if (fresh.size >= 4) out += DRow(s.id, s.title, fresh, s.ranked, s.rating)
                }
                _rows.value = out
                out.take(3).forEach { r -> wantLogos(r.items.take(8)) }
            }
            specs.withIndex().toList().mapLimit(4) { (i, s) ->
                val items = runCatching { s.load() }.getOrElse { if (it is CancellationException) throw it; emptyList() }
                lock.withLock { done[i] = items; publish() }
            }
            _loading.value = false
        }
    }

    private fun plan(g: NSGenre?, rnd: Random): List<Spec> {
        val today = LocalDate.now()
        fun pg(max: Int) = if (round == 0) 1 else rnd.nextInt(1, max + 1)
        val series = g?.seriesOnly == true || g?.only == "tv" || (g != null && g.movie == null && g.country == null)
        val films = g?.only == "movie" || (g != null && g.tv == null && g.country == null)

        val top = listOf(
            Spec("top10", "Os 10 mais assistidos da semana", ranked = true, pinned = true) { trendingOnNetflix("week", g, 1..2) },
            Spec("hot", "Em alta hoje", pinned = true) { trendingOnNetflix("day", g, if (round == 0) 1..2 else 2..3) }
        )
        val middle = buildList {
            add(Spec("hype", "Os mais comentados do momento") {
                both(g, mapOf("sort_by" to "popularity.desc"), dateFrom = today.minusDays(75), page = pg(2))
            })
            add(Spec("famous", "Os mais famosos de todos os tempos") {
                both(g, mapOf("sort_by" to "vote_count.desc"), page = pg(4))
            })
            add(Spec("best", "Os mais bem avaliados", rating = true) {
                both(g, mapOf("sort_by" to "vote_average.desc"), minVotes = 1200 to 400, page = pg(3))
            })
            add(Spec("gems", "Joias escondidas: pouca gente viu, todo mundo amou", rating = true) {
                both(g, mapOf("sort_by" to "vote_average.desc", "vote_average.gte" to 7.4), minVotes = 120 to 60, maxVotes = 1500, page = pg(3))
            })
            add(Spec("new", "Lançamentos que valem o play") {
                both(g, mapOf("sort_by" to "popularity.desc", "vote_average.gte" to 6.5, "vote_count.gte" to 40), dateFrom = today.minusDays(240), page = pg(2))
            })
            if (!films) add(Spec("mini", "Minisséries para maratonar no fim de semana") {
                one("tv", g, mapOf("sort_by" to "popularity.desc", "with_type" to "2", "vote_count.gte" to 30), page = pg(3))
            })
            if (!series) add(Spec("short", "Filmes rápidos: até 1h40") {
                one("movie", g, mapOf("sort_by" to "popularity.desc", "with_runtime.gte" to 60, "with_runtime.lte" to 100,
                    "vote_average.gte" to 6.3, "vote_count.gte" to 200), page = pg(3))
            })
            add(Spec("classic", "Clássicos que nunca envelhecem", rating = true) {
                both(g, mapOf("sort_by" to "vote_count.desc", "vote_average.gte" to 7.3), dateTo = LocalDate.of(2008, 12, 31), page = pg(2))
            })
            if (!series) add(Spec("boxoffice", "Sucessos de bilheteria") {
                one("movie", g, mapOf("sort_by" to "revenue.desc"), page = pg(3))
            })
            add(Spec("binge", "Séries viciantes: impossível ver só um episódio") {
                if (films) emptyList() else one("tv", g, mapOf("sort_by" to "popularity.desc", "vote_average.gte" to 7.8, "vote_count.gte" to 300), page = pg(3))
            })
            if (g == null) {
                add(Spec("br", "Produções brasileiras em destaque") {
                    both(NF_GENRES.first { it.country == "BR" }, mapOf("sort_by" to "popularity.desc"), page = pg(2))
                })
                add(Spec("dorama", "Doramas que estão bombando") {
                    one("tv", NF_GENRES.first { it.seriesOnly }, mapOf("sort_by" to "popularity.desc"), page = pg(2))
                })
                add(Spec("family", "Para ver com a família toda") {
                    both(NF_GENRES.first { it.label == "Família" }, mapOf("sort_by" to "popularity.desc", "vote_average.gte" to 6.5), page = pg(3))
                })
            }
        }
        // Personalizado: "Porque você assistiu X" (a semente muda a cada "Atualizar").
        val personal = seeds.takeIf { it.isNotEmpty() }?.let { list ->
            val seed = list[(round + rnd.nextInt(0, 2)).mod(list.size)]
            Spec("because", "Porque você assistiu ${seed.name}") { similarOnNetflix(seed, g) }
        }
        val mid = if (round == 0) middle else middle.shuffled(rnd)
        return buildList {
            addAll(top)
            personal?.let { add(it) }
            addAll(mid)
        }
    }

    // ---------- Consultas ----------

    /** Filmes e séries intercalados. [minVotes] = (filmes, séries). */
    private suspend fun both(
        g: NSGenre?, params: Map<String, Any>, page: Int = 1, minVotes: Pair<Int, Int>? = null, maxVotes: Int? = null,
        dateFrom: LocalDate? = null, dateTo: LocalDate? = null
    ): List<DItem> = coroutineScope {
        val m = async { one("movie", g, params, page, minVotes?.first, maxVotes, dateFrom, dateTo) }
        val s = async { one("tv", g, params, page, minVotes?.second, maxVotes, dateFrom, dateTo) }
        interleave(m.await(), s.await())
    }

    private suspend fun one(
        type: String, g: NSGenre?, params: Map<String, Any>, page: Int = 1, minVotes: Int? = null, maxVotes: Int? = null,
        dateFrom: LocalDate? = null, dateTo: LocalDate? = null
    ): List<DItem> {
        val gp = genreParams(type, g) ?: return emptyList()
        val p = params.toMutableMap()
        p.putAll(gp)
        minVotes?.let { p["vote_count.gte"] = it }
        maxVotes?.let { p["vote_count.lte"] = it }
        val dateKey = if (type == "movie") "primary_release_date" else "first_air_date"
        dateFrom?.let { p["$dateKey.gte"] = it.toString() }
        dateTo?.let { p["$dateKey.lte"] = it.toString() }
        if (p["sort_by"] == "revenue.desc" && type == "tv") return emptyList()
        // Duas páginas seguidas: a fileira fica cheia mesmo depois de tirar repetidos.
        val pages = listOf(page, page + 1).mapLimit(2) { discover(type, p, it) }.flatten()
        return pages.mapNotNull { toItem(it, type) }.distinctBy { it.key }
    }

    /** Parâmetros do gênero escolhido para esse tipo; null = esse tipo não entra (ex.: Doramas só séries). */
    private fun genreParams(type: String, g: NSGenre?): Map<String, Any>? {
        if (g == null) return emptyMap()
        if (g.only != null && g.only != type) return null
        if (g.seriesOnly && type == "movie") return null
        val genre = if (type == "movie") g.movie else g.tv
        if (g.country == null && genre == null) return null
        return buildMap {
            g.country?.let { put("with_origin_country", it) }
            genre?.let { put("with_genres", it) }
            g.lang?.let { put("with_original_language", it) }
            if (g.seriesOnly) put("without_genres", "16")
        }
    }

    /** Em alta no TMDB (dia/semana), só o que está na Netflix BR e combina com o gênero. */
    private suspend fun trendingOnNetflix(window: String, g: NSGenre?, pages: IntRange): List<DItem> {
        val pool = pages.toList().mapLimit(2) { tmdb("/trending/all/$window", mapOf("page" to it)) }
            .flatMap { it?.optJSONArray("results").objects() }
            .filter { it.optString("media_type") == "movie" || it.optString("media_type") == "tv" }
            .filter { matches(it, it.optString("media_type"), g) }
            .distinctBy { "${it.optString("media_type")}_${it.optInt("id")}" }
        val ok = pool.mapLimit(10) { isOnNetflix(it.optString("media_type"), it.optInt("id")) }
        val onNf = pool.filterIndexed { i, _ -> ok[i] }.mapNotNull { toItem(it, it.optString("media_type")) }
        // Com filtro de gênero o "em alta" fica curto: completa com os populares do gênero na Netflix.
        if (onNf.size >= 10 || g == null) return onNf
        return (onNf + both(g, mapOf("sort_by" to "popularity.desc"))).distinctBy { it.key }
    }

    private fun matches(x: JSONObject, type: String, g: NSGenre?): Boolean {
        if (g == null) return true
        if (g.only != null && g.only != type) return false
        if (g.seriesOnly && type == "movie") return false
        val ids = x.optJSONArray("genre_ids").ints().toSet()
        val want = (if (type == "movie") g.movie else g.tv)?.split(',', '|')?.mapNotNull { it.trim().toIntOrNull() }
        if (want == null && g.country == null) return false
        if (want != null && !ids.containsAll(want)) return false
        if (g.seriesOnly && 16 in ids) return false
        g.lang?.let { if (x.optString("original_language") != it) return false }
        g.country?.let { c ->
            val countries = x.optJSONArray("origin_country")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            if (countries.none { it in c.split('|') }) return false
        }
        return true
    }

    private suspend fun similarOnNetflix(seed: NSSeed, g: NSGenre?): List<DItem> {
        val t = if (seed.type == MediaType.MOVIE) "movie" else "tv"
        val (rec, sim) = coroutineScope {
            val r = async { tmdb("/$t/${seed.id}/recommendations") }
            val s = async { tmdb("/$t/${seed.id}/similar") }
            r.await() to s.await()
        }
        val pool = (rec?.optJSONArray("results").objects() + sim?.optJSONArray("results").objects())
            .distinctBy { it.optInt("id") }.filter { matches(it, t, g) }.take(40)
        val ok = pool.mapLimit(10) { isOnNetflix(t, it.optInt("id")) }
        return pool.filterIndexed { i, _ -> ok[i] }.mapNotNull { toItem(it, t) }
    }

    private fun interleave(a: List<DItem>, b: List<DItem>): List<DItem> = buildList {
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let(::add)
            b.getOrNull(i)?.let(::add)
        }
    }

    // ---------- TMDB ----------

    private suspend fun discover(type: String, params: Map<String, Any>, page: Int): List<JSONObject> =
        tmdb("/discover/$type", params + mapOf(
            "with_watch_providers" to NETFLIX_PROVIDER, "watch_region" to REGION,
            "with_watch_monetization_types" to "flatrate|ads", "page" to page
        ))?.optJSONArray("results").objects()

    private suspend fun isOnNetflix(type: String, id: Int): Boolean {
        val region = tmdb("/$type/$id/watch/providers", lang = false)?.optJSONObject("results")?.optJSONObject(REGION) ?: return false
        return listOf("flatrate", "ads", "free").any { k -> region.optJSONArray(k).objects().any { it.optInt("provider_id") == NETFLIX_PROVIDER } }
    }

    private fun toItem(x: JSONObject, type: String): DItem? {
        val backdrop = x.optString("backdrop_path").takeIf { it.startsWith("/") } ?: return null
        val title = x.optString("title").ifBlank { x.optString("name") }.ifBlank { return null }
        val date = x.optString("release_date").ifBlank { x.optString("first_air_date") }
        return DItem(
            type = if (type == "movie") MediaType.MOVIE else MediaType.TV,
            id = x.optInt("id"),
            title = title,
            backdrop = "$IMG/w780$backdrop",
            poster = x.optString("poster_path").takeIf { it.startsWith("/") }?.let { "$IMG/w342$it" },
            overview = x.optString("overview"),
            year = date.take(4),
            vote = x.optDouble("vote_average", 0.0),
            votes = x.optInt("vote_count"),
            genres = x.optJSONArray("genre_ids").ints().mapNotNull { GENRE_NAMES[it] }.distinct()
        )
    }

    private suspend fun tmdb(path: String, params: Map<String, Any> = emptyMap(), lang: Boolean = true): JSONObject? {
        val all = buildMap<String, Any> {
            put("api_key", Constants.TMDB_API_KEY)
            if (lang) put("language", "pt-BR")
            putAll(params)
        }
        val url = "https://api.themoviedb.org/3$path?" + all.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value.toString(), "UTF-8")}" }
        cache[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                if (c.responseCode !in 200..299) return@withContext null
                val json = c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                if (cache.size > 1500) cache.clear()
                cache[url] = json
                json
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }

    private suspend fun <T, R> List<T>.mapLimit(n: Int, f: suspend (T) -> R): List<R> = coroutineScope {
        val gate = Semaphore(n)
        map { async { gate.withPermit { f(it) } } }.awaitAll()
    }

    companion object {
        private const val NETFLIX_PROVIDER = 8
        private const val REGION = "BR"
        private const val IMG = "https://image.tmdb.org/t/p"
        private val cache = ConcurrentHashMap<String, JSONObject>()
        private val GENRE_NAMES = mapOf(
            28 to "Ação", 12 to "Aventura", 16 to "Animação", 35 to "Comédia", 80 to "Policial", 99 to "Documentário",
            18 to "Drama", 10751 to "Família", 14 to "Fantasia", 36 to "História", 27 to "Terror", 10402 to "Música",
            9648 to "Mistério", 10749 to "Romance", 878 to "Ficção científica", 53 to "Suspense", 10752 to "Guerra",
            37 to "Faroeste", 10759 to "Ação", 10762 to "Infantil", 10764 to "Reality show", 10765 to "Ficção científica",
            10766 to "Novela", 10767 to "Talk show", 10768 to "Guerra"
        )
    }
}
