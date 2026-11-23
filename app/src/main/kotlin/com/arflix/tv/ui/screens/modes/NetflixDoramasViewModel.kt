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

/** Filtro por país da aba Doramas. */
internal data class DoramaCountry(val label: String, val codes: String)

/** Progresso de um dorama que a pessoa começou (fileira "Continue assistindo"). */
internal data class DResume(val progress: Int, val label: String?)

internal val DORAMA_COUNTRIES = listOf(
    DoramaCountry("Todos", "KR|JP|CN|TW|TH"),
    DoramaCountry("Coreia", "KR"),
    DoramaCountry("Japão", "JP"),
    DoramaCountry("China", "CN"),
    DoramaCountry("Taiwan", "TW"),
    DoramaCountry("Tailândia", "TH")
)

/**
 * Aba "Doramas" do modo Netflix: só séries asiáticas (Coreia, Japão, China, Taiwan, Tailândia), sem anime,
 * reality ou talk show. Mostra TODOS os doramas (não só os da Netflix; o app toca pelas fontes da pessoa),
 * com Top 10, em alta, mais bem avaliados, joias escondidas, por gênero, "Porque você assistiu" e, no fim,
 * a fileira "Todos os doramas", que vai carregando mais conforme a pessoa anda para a direita.
 */
@HiltViewModel
internal class NetflixDoramasViewModel @Inject constructor() : ViewModel() {
    private val _rows = MutableStateFlow<List<DRow>>(emptyList())
    val rows: StateFlow<List<DRow>> = _rows.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _country = MutableStateFlow(DORAMA_COUNTRIES.first())
    val country: StateFlow<DoramaCountry> = _country.asStateFlow()
    /** "16 episódios • 1 temporada", por item (carregado quando a capa ganha foco). */
    private val _info = MutableStateFlow<Map<String, String>>(emptyMap())
    val info: StateFlow<Map<String, String>> = _info.asStateFlow()
    private val _progress = MutableStateFlow<Map<String, DResume>>(emptyMap())
    val progress: StateFlow<Map<String, DResume>> = _progress.asStateFlow()

    private var seeds: List<NSSeed> = emptyList()
    private var resume: List<NetflixResume> = emptyList()
    private var job: Job? = null
    private var started = false
    private var round = 0
    private val infoAsked = ConcurrentHashMap.newKeySet<String>()
    private val infoGate = Semaphore(4)

    // "Todos os doramas": paginação sem fim.
    private var allPage = 0
    private var allTotalPages = Int.MAX_VALUE
    private var allLoading = false

    fun start(seeds: List<NSSeed>, resume: List<NetflixResume>) {
        if (started) return
        started = true
        this.seeds = seeds
        this.resume = resume
        reload()
    }

    fun setCountry(c: DoramaCountry) {
        if (c.label == _country.value.label) return
        _country.value = c
        reload()
    }

    fun refresh() {
        round++
        reload()
    }

    fun surprise(): DItem? {
        val all = _rows.value.flatMap { it.items }.distinctBy { it.key }
        return all.filter { it.vote >= 7.8 && it.votes >= 100 }.ifEmpty { all }.randomOrNull()
    }

    /** Busca nº de episódios/temporadas dos itens (cache por item). */
    fun wantInfo(items: List<DItem>) {
        items.filter { it.key !in _info.value && infoAsked.add(it.key) }.forEach { item ->
            viewModelScope.launch {
                infoGate.withPermit { tmdb("/tv/${item.id}")?.let { rememberInfo(item.key, it) } }
            }
        }
    }

    private fun rememberInfo(key: String, d: JSONObject) {
        val eps = d.optInt("number_of_episodes")
        val seasons = d.optInt("number_of_seasons")
        val parts = buildList {
            if (eps > 0) add(if (eps == 1) "1 episódio" else "$eps episódios")
            if (seasons > 0) add(if (seasons == 1) "1 temporada" else "$seasons temporadas")
        }
        if (parts.isNotEmpty()) _info.value = _info.value + (key to parts.joinToString(" • "))
    }

    /** Chamado quando a pessoa chega perto do fim da fileira "Todos os doramas". */
    fun loadMoreAll() {
        if (allLoading || allPage >= allTotalPages) return
        allLoading = true
        val myJob = job
        viewModelScope.launch {
            try {
                val more = allPageItems(allPage + 1)
                if (job !== myJob) return@launch
                allPage++
                _rows.value = _rows.value.map { r ->
                    if (r.id == ALL_ROW) r.copy(items = (r.items + more).distinctBy { it.key }) else r
                }
            } finally {
                allLoading = false
            }
        }
    }

    // ---------- Fileiras ----------

    private class Spec(val id: String, val title: String, val ranked: Boolean = false, val rating: Boolean = false,
                       val noDedupe: Boolean = false, val limit: Int = 20, val minItems: Int = 4, val load: suspend () -> List<DItem>)

    private fun reload() {
        job?.cancel()
        _rows.value = emptyList()
        _loading.value = true
        allPage = 0; allTotalPages = Int.MAX_VALUE; allLoading = false
        val rnd = Random(System.nanoTime())
        job = viewModelScope.launch {
            val specs = plan(rnd)
            val done = arrayOfNulls<List<DItem>>(specs.size)
            val lock = Mutex()
            suspend fun publish() {
                val seen = HashSet<String>()
                val out = mutableListOf<DRow>()
                for ((i, s) in specs.withIndex()) {
                    val items = done[i] ?: break
                    val fresh = when {
                        s.ranked -> items.take(10)
                        s.noDedupe -> items
                        else -> items.filter { it.key !in seen }.take(s.limit)
                    }
                    if (!s.noDedupe) fresh.forEach { seen += it.key }
                    if (fresh.size >= s.minItems) out += DRow(s.id, s.title, fresh, s.ranked, s.rating)
                }
                _rows.value = out
                out.firstOrNull()?.let { wantInfo(it.items.take(2)) }
            }
            specs.withIndex().toList().mapLimit(4) { (i, s) ->
                val items = runCatching { s.load() }.getOrElse { if (it is CancellationException) throw it; emptyList() }
                lock.withLock { done[i] = items; publish() }
            }
            _loading.value = false
        }
    }

    private fun plan(rnd: Random): List<Spec> {
        val today = LocalDate.now()
        fun pg(max: Int) = if (round == 0) 1 else rnd.nextInt(1, max + 1)
        val top = listOf(
            Spec(CONT_ROW, "Continue assistindo seus doramas", noDedupe = true, minItems = 1) { continueItems() },
            Spec("top10", "Top 10 doramas da semana", ranked = true) { trending() },
            Spec("hot", "Doramas em alta agora") {
                discover(mapOf("sort_by" to "popularity.desc"), dateFrom = today.minusYears(2), page = pg(2))
            }
        )
        val personal = seeds.filter { it.type == MediaType.TV }.takeIf { it.isNotEmpty() }?.let { list ->
            val seed = list[(round + rnd.nextInt(0, 2)).mod(list.size)]
            Spec("because", "Porque você assistiu ${seed.name}") { similar(seed) }
        }
        val middle = listOf(
            Spec("best", "Os mais bem avaliados de todos os tempos", rating = true) {
                discover(mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to 250), page = pg(3))
            },
            Spec("famous", "Os doramas mais famosos") {
                discover(mapOf("sort_by" to "vote_count.desc"), page = pg(3))
            },
            Spec("gems", "Joias escondidas: pouca gente viu, todo mundo amou", rating = true) {
                discover(mapOf("sort_by" to "vote_average.desc", "vote_average.gte" to 7.8, "vote_count.gte" to 40, "vote_count.lte" to 400), page = pg(3))
            },
            Spec("new", "Lançamentos que valem o play") {
                discover(mapOf("sort_by" to "popularity.desc", "vote_count.gte" to 10), dateFrom = today.minusDays(200), page = pg(2))
            },
            Spec("netflix", "Doramas na Netflix") {
                discover(mapOf("sort_by" to "popularity.desc", "with_watch_providers" to NETFLIX_PROVIDER, "watch_region" to "BR",
                    "with_watch_monetization_types" to "flatrate|ads"), page = pg(3))
            },
            Spec("comedy", "Comédias românticas e leves") {
                discover(mapOf("sort_by" to "popularity.desc", "with_genres" to "35", "vote_count.gte" to 20), page = pg(3))
            },
            Spec("romance", "Romances de suspirar") {
                byKeywords(listOf("romance", "love", "first love"), pg(3))
            },
            Spec("school", "Escolar e juventude") {
                byKeywords(listOf("high school", "school", "coming of age", "youth"), pg(2))
            },
            Spec("sageuk", "Históricos e de época (Sageuk)") {
                byKeywords(listOf("historical drama", "joseon dynasty", "historical", "period drama", "ancient china"), pg(2))
            },
            Spec("revenge", "Vingança") {
                byKeywords(listOf("revenge"), pg(2))
            },
            Spec("office", "CEO e romance no escritório") {
                byKeywords(listOf("office romance", "workplace romance", "ceo", "chaebol", "boss"), pg(2))
            },
            Spec("short", "Curtinhos para maratonar") {
                discover(mapOf("sort_by" to "popularity.desc", "with_type" to 2, "vote_count.gte" to 10), page = pg(2))
            },
            Spec("fantasy", "Fantasia e sobrenatural") {
                discover(mapOf("sort_by" to "popularity.desc", "with_genres" to "10765", "vote_count.gte" to 20), page = pg(3))
            },
            Spec("crime", "Suspense, mistério e crimes") {
                discover(mapOf("sort_by" to "popularity.desc", "with_genres" to "80|9648", "vote_count.gte" to 20), page = pg(3))
            },
            Spec("action", "Ação e aventura") {
                discover(mapOf("sort_by" to "popularity.desc", "with_genres" to "10759", "vote_count.gte" to 20), page = pg(3))
            },
            Spec("soap", "Novelas para acompanhar") {
                discover(mapOf("sort_by" to "popularity.desc", "with_genres" to "10766"), page = pg(2))
            },
            Spec("classic", "Clássicos que marcaram época", rating = true) {
                discover(mapOf("sort_by" to "vote_count.desc", "vote_average.gte" to 7.5), dateTo = LocalDate.of(2015, 12, 31), page = pg(2))
            }
        )
        val mid = if (round == 0) middle else middle.shuffled(rnd)
        return buildList {
            addAll(top)
            personal?.let { add(it) }
            addAll(mid)
            // Sempre por último: todos os doramas, do mais popular para o menos (carrega mais ao andar).
            add(Spec(ALL_ROW, "Todos os doramas", noDedupe = true) {
                val first = allPageItems(1)
                allPage = 1
                first
            })
        }
    }

    // ---------- Consultas ----------

    /** Doramas que a pessoa começou (do histórico), só do país escolhido, com o progresso de cada um. */
    private suspend fun continueItems(): List<DItem> {
        val codes = _country.value.codes.split('|')
        val tv = resume.filter { it.item.mediaType == MediaType.TV }.distinctBy { it.item.id }
        val found = tv.mapLimit(6) { r ->
            val d = tmdb("/tv/${r.item.id}") ?: return@mapLimit null
            val countries = d.optJSONArray("origin_country").strings()
            if (countries.none { it in codes }) return@mapLimit null
            val genreIds = d.optJSONArray("genres").objects().map { it.optInt("id") }
            if (genreIds.any { it in EXCLUDED_IDS }) return@mapLimit null
            d.put("genre_ids", JSONArray(genreIds))
            val item = toItem(d) ?: return@mapLimit null
            rememberInfo(item.key, d)
            val label = if (r.season != null && r.episode != null) "T${r.season}:E${r.episode}" else null
            item to DResume(r.progress, label)
        }.filterNotNull()
        _progress.value = found.associate { it.first.key to it.second }
        return found.map { it.first }
    }

    /** Fileira temática por palavras-chave do TMDB (os ids são procurados pelo nome uma vez e guardados). */
    private suspend fun byKeywords(names: List<String>, page: Int): List<DItem> {
        val ids = names.mapLimit(3) { keywordId(it) }.filterNotNull().distinct()
        if (ids.isEmpty()) return emptyList()
        return discover(mapOf("sort_by" to "popularity.desc", "with_keywords" to ids.joinToString("|"), "vote_count.gte" to 5), page = page)
    }

    private suspend fun keywordId(name: String): Int? {
        keywordIds[name]?.let { return it.takeIf { id -> id > 0 } }
        val json = tmdb("/search/keyword", mapOf("query" to name), lang = false) ?: return null
        val res = json.optJSONArray("results").objects()
        val id = (res.firstOrNull { it.optString("name").equals(name, ignoreCase = true) } ?: res.firstOrNull())?.optInt("id") ?: 0
        keywordIds[name] = id
        return id.takeIf { it > 0 }
    }

    private suspend fun allPageItems(page: Int): List<DItem> {
        val json = tmdb("/discover/tv", baseParams() + mapOf("sort_by" to "popularity.desc", "page" to page))
        json?.optInt("total_pages")?.takeIf { it > 0 }?.let { allTotalPages = minOf(it, 500) }
        return json?.optJSONArray("results").objects().mapNotNull { toItem(it) }
    }

    private fun baseParams(): Map<String, Any> = mapOf(
        "with_origin_country" to _country.value.codes,
        "without_genres" to EXCLUDED_GENRES
    )

    /** Duas páginas seguidas: a fileira fica cheia mesmo depois de tirar repetidos. */
    private suspend fun discover(params: Map<String, Any>, page: Int = 1, dateFrom: LocalDate? = null, dateTo: LocalDate? = null): List<DItem> {
        val p = baseParams().toMutableMap()
        p.putAll(params)
        dateFrom?.let { p["first_air_date.gte"] = it.toString() }
        dateTo?.let { p["first_air_date.lte"] = it.toString() }
        return listOf(page, page + 1).mapLimit(2) { tmdb("/discover/tv", p + ("page" to it))?.optJSONArray("results").objects() }
            .flatten().mapNotNull { toItem(it) }.distinctBy { it.key }
    }

    /** Em alta da semana no TMDB, só doramas. Completa com os populares se o "em alta" vier curto. */
    private suspend fun trending(): List<DItem> {
        val pool = (1..5).toList().mapLimit(3) { tmdb("/trending/tv/week", mapOf("page" to it)) }
            .flatMap { it?.optJSONArray("results").objects() }
            .filter { isDorama(it) }
            .mapNotNull { toItem(it) }
            .distinctBy { it.key }
        if (pool.size >= 10) return pool
        return (pool + discover(mapOf("sort_by" to "popularity.desc"), dateFrom = LocalDate.now().minusYears(1))).distinctBy { it.key }
    }

    private suspend fun similar(seed: NSSeed): List<DItem> {
        if (seed.type != MediaType.TV) return emptyList()
        val (rec, sim) = coroutineScope {
            val r = async { tmdb("/tv/${seed.id}/recommendations") }
            val s = async { tmdb("/tv/${seed.id}/similar") }
            r.await() to s.await()
        }
        return (rec?.optJSONArray("results").objects() + sim?.optJSONArray("results").objects())
            .filter { isDorama(it) }
            .mapNotNull { toItem(it) }
            .distinctBy { it.key }
    }

    private fun isDorama(x: JSONObject): Boolean {
        val countries = x.optJSONArray("origin_country")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        if (countries.none { it in _country.value.codes.split('|') }) return false
        val ids = x.optJSONArray("genre_ids").ints()
        return ids.none { it in EXCLUDED_IDS }
    }

    private fun toItem(x: JSONObject): DItem? {
        if (x.optJSONArray("genre_ids").ints().any { it in EXCLUDED_IDS }) return null
        val backdrop = x.optString("backdrop_path").takeIf { it.startsWith("/") } ?: return null
        val title = x.optString("name").ifBlank { x.optString("title") }.ifBlank { return null }
        return DItem(
            type = MediaType.TV,
            id = x.optInt("id"),
            title = title,
            backdrop = "$IMG/w780$backdrop",
            poster = x.optString("poster_path").takeIf { it.startsWith("/") }?.let { "$IMG/w342$it" },
            overview = x.optString("overview"),
            year = x.optString("first_air_date").take(4),
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
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }

    private suspend fun <T, R> List<T>.mapLimit(n: Int, f: suspend (T) -> R): List<R> = coroutineScope {
        val gate = Semaphore(n)
        map { async { gate.withPermit { f(it) } } }.awaitAll()
    }

    companion object {
        const val ALL_ROW = "all"
        const val CONT_ROW = "continue"
        private val keywordIds = ConcurrentHashMap<String, Int>()
        private const val NETFLIX_PROVIDER = 8
        private const val IMG = "https://image.tmdb.org/t/p"
        // Sem animação/anime, infantil, notícias, reality e talk show: só doramas de verdade.
        private val EXCLUDED_IDS = setOf(16, 10762, 10763, 10764, 10767)
        private val EXCLUDED_GENRES = EXCLUDED_IDS.joinToString(",")
        private val cache = ConcurrentHashMap<String, JSONObject>()
        private val GENRE_NAMES = mapOf(
            35 to "Comédia", 80 to "Policial", 18 to "Drama", 10751 to "Família", 9648 to "Mistério",
            10759 to "Ação", 10765 to "Fantasia", 10766 to "Novela", 10768 to "Política", 37 to "Faroeste", 99 to "Documentário"
        )
    }
}
