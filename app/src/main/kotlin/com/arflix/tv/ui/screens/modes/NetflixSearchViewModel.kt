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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Capa da busca: título do TMDB com imagem de fundo. */
internal data class NSItem(val type: MediaType, val id: Int, val title: String, val backdrop: String, val popularity: Double = 0.0) {
    val key get() = "${type}_$id"
}

/** Bloco da tela de busca: no feed vazio, um por tema ("Para quem curte..."); na busca, um só com os resultados. */
internal data class NSSection(val key: String, val title: String, val items: List<NSItem>)

/** Semente do feed "Recomendados para você": algo que a pessoa assistiu. */
internal data class NSSeed(val type: MediaType, val id: Int, val name: String)

/** Categorias da coluna da esquerda (as mesmas do plugin do PC). */
internal val NF_SEARCH_CATEGORIES = listOf(
    "Terror", "Comédia", "Ação", "Drama", "Romance", "Suspense", "Policial", "Ficção científica",
    "Fantasia", "Documentários", "Doramas", "Anime", "Animação", "Família", "Reality shows", "Brasileiros", "Mistério", "Guerra", "Faroeste"
)

internal fun nfNorm(s: String?): String =
    Normalizer.normalize((s ?: "").lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

internal data class NSGenre(
    val label: String,
    val movie: String?,
    val tv: String?,
    val terms: List<String>,
    val lang: String? = null,
    val country: String? = null,
    val only: String? = null,
    val seriesOnly: Boolean = false
)

/** Gêneros do TMDB (ids de filme / série) e os termos que as pessoas digitam. Igual ao addon do PC (src/search.js). */
internal val NF_GENRES = listOf(
    NSGenre("Terror", "27", "9648,10765", listOf("terror", "horror", "medo", "assustador", "filme de terror")),
    NSGenre("Ação", "28", "10759", listOf("acao", "action", "aventura", "acao e aventura")),
    NSGenre("Comédia", "35", "35", listOf("comedia", "comedy", "engracado", "humor")),
    NSGenre("Drama", "18", "18", listOf("drama")),
    NSGenre("Romance", "10749", "10766", listOf("romance", "romantico", "romanticos", "amor")),
    NSGenre("Ficção científica", "878", "10765", listOf("ficcao", "ficcao cientifica", "sci fi", "scifi")),
    NSGenre("Fantasia", "14", "10765", listOf("fantasia", "fantasy")),
    NSGenre("Suspense", "53", "9648", listOf("suspense", "thriller")),
    NSGenre("Policial", "80", "80", listOf("policial", "crime", "crimes", "investigacao")),
    NSGenre("Documentários", "99", "99", listOf("documentario", "documentarios", "doc", "docs")),
    NSGenre("Animação", "16", "16", listOf("animacao", "desenho", "desenhos", "animados")),
    NSGenre("Anime", "16", "16", listOf("anime", "animes"), lang = "ja"),
    NSGenre("Doramas", null, "18", listOf("dorama", "doramas", "kdrama", "k drama", "kdramas", "drama coreano", "dramas coreanos", "novela coreana", "coreano", "coreanos", "cdrama", "jdrama"), country = "KR|JP|CN|TW|TH", seriesOnly = true),
    NSGenre("Família", "10751", "10751", listOf("familia", "infantil", "kids", "criancas")),
    NSGenre("Mistério", "9648", "9648", listOf("misterio")),
    NSGenre("Guerra", "10752", "10768", listOf("guerra", "war")),
    NSGenre("Reality shows", null, "10764", listOf("reality", "reality show", "reality shows")),
    NSGenre("Faroeste", "37", "37", listOf("faroeste", "western", "velho oeste")),
    NSGenre("Musicais", "10402", null, listOf("musical", "musicais", "musica")),
    NSGenre("Brasileiros", null, null, listOf("brasileiro", "brasileiros", "brasileira", "brasileiras", "nacional", "nacionais"), country = "BR")
)

private val LEAD = Regex("^(filmes?|series?) (de |do |da )?")
private val TAIL = Regex(" (filmes?|series?)$")

/** "terror", "filmes de terror", "Terror - séries"... */
private fun matchGenre(q: String): NSGenre? {
    var n = nfNorm(q)
    val kind = (LEAD.find(n) ?: TAIL.find(n))?.groupValues?.get(1)
    val only = kind?.let { if (it.startsWith("filme")) "movie" else "tv" }
    n = n.replace(LEAD, "").replace(TAIL, "")
    return NF_GENRES.firstOrNull { n in it.terms }?.copy(only = only)
}

private fun guessGenre(q: String): NSGenre? {
    val n = nfNorm(q)
    return if (n.length >= 3) NF_GENRES.firstOrNull { g -> g.terms.any { it.startsWith(n) } } else null
}

/**
 * Busca da Netflix na TV, com a mesma lógica do plugin do PC:
 * - vazia: feed infinito de blocos ("Recomendados para você", "Porque você assistiu X", "Para quem curte...");
 * - digitando: títulos, gêneros e pessoas (ator/diretor) que estão na Netflix Brasil, com mais páginas ao descer;
 * - coluna da esquerda: categorias, que viram sugestões enquanto você digita.
 * O addon do GitHub Pages é estático, então as consultas ao TMDB são feitas aqui no aparelho.
 */
@HiltViewModel
internal class NetflixSearchViewModel @Inject constructor() : ViewModel() {
    private val _sections = MutableStateFlow<List<NSSection>>(emptyList())
    val sections: StateFlow<List<NSSection>> = _sections.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()
    private val _ended = MutableStateFlow(false)
    val ended: StateFlow<Boolean> = _ended.asStateFlow()
    private val _noResults = MutableStateFlow<String?>(null)
    val noResults: StateFlow<String?> = _noResults.asStateFlow()
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()
    private val _termsLabel = MutableStateFlow("Categorias")
    val termsLabel: StateFlow<String> = _termsLabel.asStateFlow()
    private val _terms = MutableStateFlow(NF_SEARCH_CATEGORIES)
    val terms: StateFlow<List<String>> = _terms.asStateFlow()
    private val _logos = MutableStateFlow<Map<String, String>>(emptyMap())
    val logos: StateFlow<Map<String, String>> = _logos.asStateFlow()

    private var query = ""
    private var seeds: List<NSSeed> = emptyList()
    private var started = false
    private var job: Job? = null
    private var moreJob: Job? = null
    private var lastSuggestions: List<String> = emptyList()
    /** Próxima página do que está na tela; devolve false quando acabou. */
    private var next: (suspend () -> Boolean)? = null
    private val logoAsked = ConcurrentHashMap.newKeySet<String>()
    private val logoGate = Semaphore(4)

    fun start(seeds: List<NSSeed>) {
        if (started) return
        started = true
        this.seeds = seeds.distinctBy { it.type to it.id }.take(6)
        if (query.isBlank()) run(immediate = true)
    }

    fun setQuery(q: String) {
        if (q == query) return
        query = q
        renderTerms(q, null)
        if (started) run(immediate = false)
    }

    /** A tela chama quando o foco chega perto do fim. */
    fun loadMore() {
        val step = next ?: return
        if (moreJob?.isActive == true || job?.isActive == true) return
        moreJob = viewModelScope.launch {
            _loadingMore.value = true
            try {
                if (!step()) finish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                finish()
            } finally {
                _loadingMore.value = false
            }
        }
    }

    private fun finish() {
        next = null
        _ended.value = _sections.value.sumOf { it.items.size } > 10
    }

    private fun run(immediate: Boolean) {
        job?.cancel(); moreJob?.cancel()
        _loadingMore.value = false
        val q = query.trim()
        job = viewModelScope.launch {
            if (!immediate) delay(350)
            _loading.value = true
            _ended.value = false
            _noResults.value = null
            _offline.value = false
            _sections.value = emptyList()
            next = null
            try {
                if (q.isBlank()) startFeed() else startSearch(q)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _offline.value = _sections.value.isEmpty()
            } finally {
                _loading.value = false
            }
        }
    }

    // ---------- Coluna da esquerda ----------

    private fun renderTerms(text: String, serverTerms: List<String>?) {
        val q = nfNorm(text)
        if (q.isEmpty()) {
            _termsLabel.value = "Categorias"
            _terms.value = NF_SEARCH_CATEGORIES
            return
        }
        if (serverTerms != null) lastSuggestions = serverTerms
        val local = NF_SEARCH_CATEGORIES.filter { nfNorm(it).startsWith(q) }
        val fromServer = lastSuggestions.filter { serverTerms != null || nfNorm(it).contains(q) }
        val terms = (local + fromServer).distinctBy { nfNorm(it) }.take(12)
        _termsLabel.value = if (terms.isNotEmpty()) "Sugestões" else ""
        _terms.value = terms
    }

    // ---------- Busca ----------

    private suspend fun startSearch(q: String) {
        val genre = matchGenre(q)
        val guess = if (genre == null) guessGenre(q) else null
        val g = genre ?: guess
        val n = nfNorm(q)
        val (fromGenre, text) = coroutineScope {
            val a = async { if (g != null) genreResults(g, listOf(1, 2)) else emptyList() }
            val b = async { textResults(q) }
            a.await() to b.await()
        }
        fun nameHit(x: Pair<JSONObject, String>) = names(x.first).any { v -> v.startsWith(n) || v.split(' ').any { it.startsWith(n) } }
        val ordered = when {
            genre != null -> fromGenre + text.titles
            guess != null -> text.titles.filter(::nameHit) + fromGenre + text.titles.filterNot(::nameHit)
            else -> text.titles + text.personHits
        }
        val pool = ordered.distinctBy { "${it.second}:${it.first.optInt("id")}" }.take(90)
        val genreKeys = fromGenre.map { "${it.second}:${it.first.optInt("id")}" }.toSet()
        val ok = pool.mapLimit(12) { (x, type) -> "$type:${x.optInt("id")}" in genreKeys || isOnNetflix(type, x.optInt("id")) }
        val items = pool.filterIndexed { i, _ -> ok[i] }.take(60).mapNotNull { (x, type) -> toItem(x, type) }.toMutableList()
        // Poucos resultados pelo nome: completa com títulos parecidos com o primeiro (a Netflix faz o mesmo).
        if (g == null && items.isNotEmpty() && items.size < 24) {
            val first = items.first()
            val ids = items.map { it.key }.toMutableSet()
            similarOnNetflix(first.type, first.id).forEach { if (ids.add(it.key)) items += it }
        }
        if (nfNorm(query) == n) renderTerms(query, suggestionsFor(q, g, items, text.people))
        if (items.isEmpty()) {
            _noResults.value = q
            return
        }
        val title = genre?.label?.let { label -> genre.only?.let { "$label - ${if (it == "movie") "filmes" else "séries"}" } ?: label }
            ?: "Resultados para \"$q\""
        val shown = items.map { it.key }.toMutableSet()
        _sections.value = listOf(NSSection("search", title, items))
        askLogos(items)
        var page = 1
        var empty = 0
        next = suspend {
            if (page > 15) false else {
                val (more, hasMore) = searchMore(q, g, page++)
                val fresh = more.filter { shown.add(it.key) }
                if (fresh.isNotEmpty()) {
                    val cur = _sections.value.firstOrNull()
                    if (cur != null) _sections.value = listOf(cur.copy(items = cur.items + fresh))
                    askLogos(fresh)
                    empty = 0
                }
                hasMore && (fresh.isNotEmpty() || ++empty <= 2)
            }
        }
    }

    private suspend fun searchMore(q: String, g: NSGenre?, page: Int): Pair<List<NSItem>, Boolean> {
        val pages = listOf(page * 2 + 1, page * 2 + 2)
        if (g != null) {
            val pool = genreResults(g, pages)
            return pool.mapNotNull { (x, type) -> toItem(x, type) } to pool.isNotEmpty()
        }
        val res = pages.mapLimit(2) { pg -> tmdb("/search/multi", mapOf("query" to q, "include_adult" to "false", "page" to pg)) }
        val pool = res.flatMap { it?.optJSONArray("results").objects() }
            .filter { it.optString("media_type") == "movie" || it.optString("media_type") == "tv" }
            .map { it to it.optString("media_type") }
        val ok = pool.mapLimit(12) { (x, type) -> isOnNetflix(type, x.optInt("id")) }
        val items = pool.filterIndexed { i, _ -> ok[i] }.mapNotNull { (x, type) -> toItem(x, type) }
        return items to (page < 6)
    }

    private suspend fun genreResults(g: NSGenre, pages: List<Int>): List<Pair<JSONObject, String>> {
        suspend fun forType(type: String): List<JSONObject> {
            if (g.only != null && g.only != type) return emptyList()
            if (g.seriesOnly && type == "movie") return emptyList()
            val params = mutableMapOf<String, Any>("sort_by" to "popularity.desc")
            val genre = if (type == "movie") g.movie else g.tv
            if (g.country == null && genre == null) return emptyList()
            g.country?.let { params["with_origin_country"] = it }
            genre?.let { params["with_genres"] = it }
            if (g.seriesOnly) params["without_genres"] = "16" // doramas: sem anime/animação
            g.lang?.let { params["with_original_language"] = it }
            return pages.mapLimit(2) { discover(type, params, it) }.flatten()
        }
        val (movies, series) = coroutineScope {
            val m = async { forType("movie") }
            val s = async { forType("tv") }
            m.await() to s.await()
        }
        return buildList {
            for (i in 0 until maxOf(movies.size, series.size)) {
                movies.getOrNull(i)?.let { add(it to "movie") }
                series.getOrNull(i)?.let { add(it to "tv") }
            }
        }
    }

    private class TextResults(val titles: List<Pair<JSONObject, String>>, val personHits: List<Pair<JSONObject, String>>, val people: List<String>)

    private suspend fun textResults(q: String): TextResults {
        val res = listOf(1, 2).mapLimit(2) { tmdb("/search/multi", mapOf("query" to q, "include_adult" to "false", "page" to it)) }
        val titles = mutableListOf<Pair<JSONObject, String>>()
        val people = mutableListOf<JSONObject>()
        for (r in res.flatMap { it?.optJSONArray("results").objects() }) {
            when (r.optString("media_type")) {
                "movie" -> titles += r to "movie"
                "tv" -> titles += r to "tv"
                "person" -> if (people.size < 2 && r.optDouble("popularity", 0.0) > 3) people += r
            }
        }
        // Pessoa buscada pelo nome: traz os trabalhos dela (os mais populares primeiro).
        val target = nfNorm(q)
        val personHits = mutableListOf<Pair<JSONObject, String>>()
        for (p in people.filter { nfNorm(it.optString("name")).let { n -> n.contains(target) || target.contains(n) } }) {
            val credits = tmdb("/person/${p.optInt("id")}/combined_credits") ?: continue
            val cast = credits.optJSONArray("cast").objects()
            val crew = credits.optJSONArray("crew").objects().filter { it.optString("job") == "Director" || it.optString("job") == "Creator" }
            (cast + crew)
                .filter { it.optString("media_type") == "movie" || it.optString("media_type") == "tv" }
                .filterNot { w -> w.optJSONArray("genre_ids").ints().any { it == 10767 || it == 10763 } }
                .sortedByDescending { it.optDouble("popularity", 0.0) }
                .take(40)
                .forEach { personHits += it to it.optString("media_type") }
        }
        fun exact(x: JSONObject): Int {
            val ns = names(x)
            return if (ns.any { it == target }) 2 else if (ns.any { it.contains(target) }) 1 else 0
        }
        titles.sortWith(compareByDescending<Pair<JSONObject, String>> { exact(it.first) }.thenByDescending { it.first.optDouble("popularity", 0.0) })
        return TextResults(titles, personHits, people.map { it.optString("name") })
    }

    private fun suggestionsFor(q: String, g: NSGenre?, items: List<NSItem>, people: List<String>): List<String> {
        val n = nfNorm(q)
        val out = mutableListOf<String>()
        fun push(s: String?) { if (!s.isNullOrBlank() && out.none { nfNorm(it) == nfNorm(s) }) out += s }
        NF_GENRES.forEach { gg -> if (nfNorm(gg.label).startsWith(n) || gg.terms.any { it.startsWith(n) }) push(gg.label) }
        if (g != null && !g.seriesOnly) { push("${g.label} - filmes"); push("${g.label} - séries") }
        people.forEach(::push)
        // Só nomes em alfabeto latino (o TMDB às vezes devolve o título original em outra escrita).
        items.filter { t -> t.title.any { it in 'A'..'Z' || it in 'a'..'z' } }.take(12).forEach { push(it.title) }
        return out.take(12)
    }

    // ---------- Feed "Recomendados para você" ----------

    private sealed class Block(val title: String) {
        class Mix(title: String, val seeds: List<SeedInfo>) : Block(title)
        class Similar(title: String, val seed: SeedInfo) : Block(title)
        class Combo(title: String, val type: String, val genres: String) : Block(title)
        class Trending(title: String, val type: String) : Block(title)
        class Acclaimed(title: String, val type: String) : Block(title)
    }

    private class SeedInfo(val type: String, val id: Int, val name: String, val genres: List<Pair<Int, String>>)

    private suspend fun startFeed() {
        val blocks = plan()
        val shown = mutableSetOf<String>()
        var page = 0
        var emptyStreak = 0
        val step: suspend () -> Boolean = step@{
            val block = blocks[page % blocks.size]
            val round = page / blocks.size
            if (round > 4) return@step false
            val items = blockItems(block, round)
            val title = if (round == 0) block.title else "Mais: ${block.title.replaceFirstChar { it.lowercase() }}"
            val key = "feed$page"
            page++
            val fresh = items.filter { it.key !in shown }
            // Bloco com pouca coisa nova (já apareceu antes) não vale uma seção própria.
            if (fresh.size >= 4) {
                emptyStreak = 0
                fresh.forEach { shown += it.key }
                _sections.value = _sections.value + NSSection(key, title, fresh)
                askLogos(fresh)
                true
            } else ++emptyStreak <= 8
        }
        // Enche a tela com os primeiros blocos; o resto vem ao descer.
        var more = true
        while (more && _sections.value.size < 2) more = step()
        next = if (more) step else null
        if (!more) finish()
    }

    private suspend fun plan(): List<Block> {
        val infos = seeds.mapLimit(4) { seedInfo(it) }.filterNotNull()
        val blocks = mutableListOf<Block>(Block.Mix("Recomendados para você", infos.take(3)))
        val used = mutableSetOf<String>()
        for (s in infos) {
            if (s.genres.isNotEmpty()) {
                val pair = s.genres.take(2)
                val key = "${s.type}:${pair.map { it.first }.sorted().joinToString(",")}"
                if (used.add(key)) {
                    blocks += Block.Combo("Para quem curte ${comboLabel(pair.map { it.second }, s.type).replaceFirstChar { it.lowercase() }}",
                        s.type, pair.joinToString(",") { it.first.toString() })
                }
            }
            blocks += Block.Similar("Porque você assistiu ${s.name}", s)
        }
        for (s in infos) for (g in s.genres.take(2)) {
            if (used.add("${s.type}:${g.first}")) blocks += Block.Combo(comboLabel(listOf(g.second), s.type), s.type, g.first.toString())
        }
        blocks += Block.Trending("Séries em alta", "tv")
        blocks += Block.Trending("Filmes em alta", "movie")
        blocks += Block.Acclaimed("Aclamados pela crítica", "movie")
        blocks += Block.Acclaimed("Séries aclamadas", "tv")
        return blocks
    }

    private suspend fun seedInfo(seed: NSSeed): SeedInfo? {
        val type = if (seed.type == MediaType.MOVIE) "movie" else "tv"
        val d = tmdb("/$type/${seed.id}") ?: return null
        val genres = d.optJSONArray("genres").objects()
            .filter { it.optInt("id") !in setOf(10767, 10763, 10766) }
            .map { it.optInt("id") to (GENRE_PT[it.optString("name")] ?: it.optString("name")) }
        return SeedInfo(type, seed.id, d.optString("title").ifBlank { d.optString("name") }.ifBlank { seed.name }, genres)
    }

    private suspend fun blockItems(block: Block, round: Int): List<NSItem> {
        val page = round + 1
        return when (block) {
            is Block.Mix -> {
                if (round > 0) return emptyList()
                val lists = block.seeds.mapLimit(3) { similarOnNetflix(if (it.type == "movie") MediaType.MOVIE else MediaType.TV, it.id) }
                val out = buildList { for (i in 0 until 18) lists.forEach { l -> l.getOrNull(i)?.let(::add) } }
                if (out.size >= 6) out else {
                    val (a, b) = coroutineScope {
                        val s = async { discoverItems("tv", mapOf("sort_by" to "popularity.desc"), 1) }
                        val m = async { discoverItems("movie", mapOf("sort_by" to "popularity.desc"), 1) }
                        s.await() to m.await()
                    }
                    out + a.take(10) + b.take(10)
                }
            }
            is Block.Similar -> if (round > 0) emptyList() else similarOnNetflix(if (block.seed.type == "movie") MediaType.MOVIE else MediaType.TV, block.seed.id)
            is Block.Combo -> discoverItems(block.type, mapOf("sort_by" to "popularity.desc", "with_genres" to block.genres, "vote_count.gte" to 20), page)
            is Block.Trending -> discoverItems(block.type, mapOf("sort_by" to "popularity.desc"), page + 1)
            is Block.Acclaimed -> discoverItems(block.type, mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to 800), page)
        }
    }

    private fun comboLabel(names: List<String>, type: String): String {
        val a = names.getOrNull(0)?.let { SHORT[it] ?: it } ?: ""
        val b = names.getOrNull(1)?.let { SHORT[it] ?: it }
        val kind = if (type == "movie") "Filmes" else "Séries"
        val low: (String) -> String = { it.replaceFirstChar { c -> c.lowercase() } }
        if (b == null) return if (a == "Documentário") "Documentários" else "$kind de ${low(a)}"
        if (a == "Documentário" || b == "Documentário") return "Documentários de ${low(if (a == "Documentário") b else a)}"
        if (a == "Animação" || b == "Animação") return "Animações de ${low(if (a == "Animação") b else a)}"
        return "$kind de ${low(a)} e ${low(b)}"
    }

    // ---------- TMDB ----------

    private suspend fun discover(type: String, params: Map<String, Any>, page: Int): List<JSONObject> =
        tmdb("/discover/$type", params + mapOf(
            "with_watch_providers" to NETFLIX_PROVIDER, "watch_region" to REGION,
            "with_watch_monetization_types" to "flatrate|ads", "page" to page
        ))?.optJSONArray("results").objects()

    private suspend fun discoverItems(type: String, params: Map<String, Any>, page: Int): List<NSItem> =
        discover(type, params, page).mapNotNull { toItem(it, type) }

    private suspend fun isOnNetflix(type: String, id: Int): Boolean {
        val region = tmdb("/$type/$id/watch/providers", lang = false)?.optJSONObject("results")?.optJSONObject(REGION) ?: return false
        return listOf("flatrate", "ads", "free").any { k -> region.optJSONArray(k).objects().any { it.optInt("provider_id") == NETFLIX_PROVIDER } }
    }

    private suspend fun similarOnNetflix(type: MediaType, id: Int): List<NSItem> {
        val t = if (type == MediaType.MOVIE) "movie" else "tv"
        val (rec, sim) = coroutineScope {
            val r = async { tmdb("/$t/$id/recommendations") }
            val s = async { tmdb("/$t/$id/similar") }
            r.await() to s.await()
        }
        val pool = (rec?.optJSONArray("results").objects() + sim?.optJSONArray("results").objects())
            .distinctBy { it.optInt("id") }.take(40)
        val ok = pool.mapLimit(10) { isOnNetflix(t, it.optInt("id")) }
        return pool.filterIndexed { i, _ -> ok[i] }.take(18).mapNotNull { toItem(it, t) }
    }

    private fun toItem(x: JSONObject, type: String): NSItem? {
        val backdrop = x.optString("backdrop_path").takeIf { it.startsWith("/") } ?: return null // sem imagem o card fica vazio
        val title = x.optString("title").ifBlank { x.optString("name") }.ifBlank { return null }
        return NSItem(if (type == "movie") MediaType.MOVIE else MediaType.TV, x.optInt("id"), title, "$IMG/w780$backdrop", x.optDouble("popularity", 0.0))
    }

    /** Logo do título (português, senão inglês), como nas capas da Netflix. */
    private fun askLogos(items: List<NSItem>) {
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

    private fun names(x: JSONObject) = listOf("title", "name", "original_title", "original_name").map { nfNorm(x.optString(it)) }.filter { it.isNotEmpty() }

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
        /** Respostas do TMDB em memória: voltar para a busca vazia ou repetir um termo é instantâneo. */
        private val cache = ConcurrentHashMap<String, JSONObject>()
        private val GENRE_PT = mapOf(
            "Action & Adventure" to "Ação e aventura", "Sci-Fi & Fantasy" to "Ficção científica e fantasia",
            "War & Politics" to "Guerra e política", "Kids" to "Infantil", "News" to "Jornalismo", "Reality" to "Reality show",
            "Soap" to "Novela", "Talk" to "Talk show"
        )
        private val SHORT = mapOf(
            "Ficção científica e fantasia" to "Ficção científica", "Ação e aventura" to "Ação", "Guerra e política" to "Guerra"
        )
    }
}
