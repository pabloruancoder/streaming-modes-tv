package com.arflix.tv.ui.screens.modes

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.embed.EmbedSources
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.StreamRepository
import com.arflix.tv.data.repository.effectiveSettingsDataStore as settingsDataStore
import com.arflix.tv.ui.screens.collections.ServiceHomeTrailerSettings
import com.arflix.tv.ui.screens.details.isPortugueseStream
import com.arflix.tv.ui.screens.home.observeHomeProfilePreferences
import com.arflix.tv.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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

/** Idioma original dos filmes de cada país (créditos de filmes não trazem país de origem). */
private val COUNTRY_LANGS = mapOf("KR" to listOf("ko"), "JP" to listOf("ja"), "CN" to listOf("zh", "cn"), "TW" to listOf("zh"), "TH" to listOf("th"))

/**
 * Aba "Doramas" do modo Netflix: só séries e filmes asiáticos (Coreia, Japão, China, Taiwan, Tailândia), sem
 * anime, reality ou talk show. Mostra TODOS os doramas (não só os da Netflix; o app toca pelas fontes da
 * pessoa): Continue assistindo, sua lista, em exibição (com dia do episódio novo), Top 10, "Porque você
 * assistiu", atores que a pessoa mais vê, temas, filmes e, no fim, "Todos os doramas" com paginação.
 */
@HiltViewModel
internal class NetflixDoramasViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    profileManager: ProfileManager,
    private val streamRepository: StreamRepository
) : ViewModel() {
    private val _rows = MutableStateFlow<List<DRow>>(emptyList())
    val rows: StateFlow<List<DRow>> = _rows.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _country = MutableStateFlow(DORAMA_COUNTRIES.first())
    val country: StateFlow<DoramaCountry> = _country.asStateFlow()
    /** "16 episódios • 1 temporada" (ou "1h 52min" para filme), por item. */
    private val _info = MutableStateFlow<Map<String, String>>(emptyMap())
    val info: StateFlow<Map<String, String>> = _info.asStateFlow()
    private val _progress = MutableStateFlow<Map<String, DResume>>(emptyMap())
    val progress: StateFlow<Map<String, DResume>> = _progress.asStateFlow()
    /** Nome em inglês para doramas sem tradução em português (senão apareceria em tailandês, chinês...). */
    private val _titles = MutableStateFlow<Map<String, String>>(emptyMap())
    val titles: StateFlow<Map<String, String>> = _titles.asStateFlow()
    /** "Ep. 7 sábado", "Ep. novo hoje"... para doramas em exibição. */
    private val _airing = MutableStateFlow<Map<String, String>>(emptyMap())
    val airing: StateFlow<Map<String, String>> = _airing.asStateFlow()
    /** "Dublado" / "Legendado", pelo que as fontes da pessoa oferecem (só para o item em destaque). */
    private val _audio = MutableStateFlow<Map<String, String>>(emptyMap())
    val audio: StateFlow<Map<String, String>> = _audio.asStateFlow()
    /** Trailer (YouTube) do item em destaque: chave do item -> id do vídeo. */
    private val _trailers = MutableStateFlow<Map<String, String>>(emptyMap())
    val trailers: StateFlow<Map<String, String>> = _trailers.asStateFlow()

    /** Doramas começados que já têm episódio novo saído depois do ponto onde a pessoa parou. */
    private val _newEp = MutableStateFlow<Set<String>>(emptySet())
    val newEp: StateFlow<Set<String>> = _newEp.asStateFlow()
    /** Calendário da semana: uma fileira por dia (hoje + 6), com os doramas que soltam episódio nele. */
    private val _calendar = MutableStateFlow<List<DRow>>(emptyList())
    val calendar: StateFlow<List<DRow>> = _calendar.asStateFlow()
    private val _calLoading = MutableStateFlow(false)
    val calLoading: StateFlow<Boolean> = _calLoading.asStateFlow()
    private var calJob: Job? = null
    private var calCountry: String? = null

    // "Não tenho interesse": escondidos para sempre da aba (guardado no aparelho).
    private val prefs = context.getSharedPreferences("sm_doramas", Context.MODE_PRIVATE)
    private val _hidden = MutableStateFlow(prefs.getStringSet("hidden", emptySet()).orEmpty().toSet())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    /** As mesmas configurações de trailer da Home (tocar sozinho, som, atraso). */
    val trailerSettings: StateFlow<ServiceHomeTrailerSettings> = observeHomeProfilePreferences(
        activeProfileId = profileManager.activeProfileId,
        preferences = context.settingsDataStore.data
    ).map { ServiceHomeTrailerSettings(autoPlay = it.trailerAutoPlay, soundEnabled = it.trailerSoundEnabled, delaySeconds = it.trailerDelaySeconds) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ServiceHomeTrailerSettings())

    private var seeds: List<NSSeed> = emptyList()
    private var resume: List<NetflixResume> = emptyList()
    private var myList: List<MediaItem> = emptyList()
    private var job: Job? = null
    private var started = false
    private var round = 0
    private val infoAsked = ConcurrentHashMap.newKeySet<String>()
    private val infoGate = Semaphore(4)
    private val titleAsked = ConcurrentHashMap.newKeySet<String>()
    private val trailerAsked = ConcurrentHashMap.newKeySet<String>()
    private val audioAsked = ConcurrentHashMap.newKeySet<String>()
    private val audioGate = Semaphore(1)

    // "Todos os doramas": paginação sem fim.
    private var allPage = 0
    private var allTotalPages = Int.MAX_VALUE
    private var allLoading = false

    fun start(seeds: List<NSSeed>, resume: List<NetflixResume>, myList: List<MediaItem>) {
        if (started) return
        started = true
        this.seeds = seeds
        this.resume = resume
        this.myList = myList
        reload()
    }

    fun setCountry(c: DoramaCountry) {
        if (c.label == _country.value.label) return
        _country.value = c
        calCountry = null
        _calendar.value = emptyList()
        reload()
    }

    fun refresh() {
        round++
        reload()
    }

    /** "Não tenho interesse": some da aba agora e nas próximas vezes. */
    fun hide(item: DItem) {
        _hidden.value = _hidden.value + item.key
        prefs.edit().putStringSet("hidden", _hidden.value).apply()
        val drop: (List<DRow>) -> List<DRow> = { rows ->
            rows.map { r -> r.copy(items = r.items.filter { it.key != item.key }) }.filter { it.items.isNotEmpty() }
        }
        _rows.value = drop(_rows.value)
        _calendar.value = drop(_calendar.value)
        saveCache(_rows.value)
    }

    /** Mostra de novo tudo o que foi escondido. */
    fun restoreHidden() {
        _hidden.value = emptySet()
        prefs.edit().remove("hidden").apply()
        prefs.edit().remove(cacheKey()).apply()
        reload()
    }

    /** Monta o calendário da semana (uma vez por país). */
    fun loadCalendar() {
        val codes = _country.value.codes
        if (calCountry == codes && (_calendar.value.isNotEmpty() || calJob?.isActive == true)) return
        calCountry = codes
        calJob?.cancel()
        _calendar.value = emptyList()
        _calLoading.value = true
        calJob = viewModelScope.launch {
            val today = LocalDate.now()
            val days = (0..6).map { today.plusDays(it.toLong()) }
            val hiddenNow = _hidden.value
            val rows = days.toList().mapLimit(3) { day ->
                val items = discover(mapOf("sort_by" to "popularity.desc", "air_date.gte" to day.toString(), "air_date.lte" to day.toString()))
                    .filter { it.key !in hiddenNow }.take(20)
                wantInfo(items.take(10))
                val dayName = WEEKDAYS[day.dayOfWeek].orEmpty()
                val date = day.format(DateTimeFormatter.ofPattern("dd/MM"))
                val title = when (day) {
                    today -> "Hoje • $dayName, $date"
                    today.plusDays(1) -> "Amanhã • $dayName, $date"
                    else -> "${dayName.replaceFirstChar { it.uppercase() }}, $date"
                }
                DRow("day${day}", title, items)
            }.filter { it.items.isNotEmpty() }
            _calendar.value = rows
            fixTitles(rows.flatMap { it.items })
            _calLoading.value = false
        }
    }

    fun surprise(): DItem? {
        val all = _rows.value.flatMap { it.items }.distinctBy { it.key }
        return all.filter { it.vote >= 7.8 && it.votes >= 100 }.ifEmpty { all }.randomOrNull()
    }

    /** Busca nº de episódios/temporadas (ou duração do filme) e o próximo episódio dos itens. */
    fun wantInfo(items: List<DItem>) {
        items.filter { it.key !in _info.value && infoAsked.add(it.key) }.forEach { item ->
            viewModelScope.launch {
                infoGate.withPermit { tmdb(detailsPath(item.type, item.id))?.let { rememberInfo(item.key, item.type, it) } }
            }
        }
    }

    /** Troca títulos sem letras latinas pelo nome em inglês do TMDB. */
    fun fixTitles(items: List<DItem>) {
        items.filter { !hasLatin(it.title) && titleAsked.add(it.key) }.forEach { item ->
            viewModelScope.launch {
                infoGate.withPermit {
                    val d = tmdb(detailsPath(item.type, item.id), mapOf("language" to "en-US"), lang = false)
                    val en = d?.optString("name").orEmpty().ifBlank { d?.optString("title").orEmpty() }
                    if (hasLatin(en)) _titles.value = _titles.value + (item.key to en)
                }
            }
        }
    }

    /** Trailer do item em destaque (português primeiro, depois qualquer idioma). */
    fun wantTrailer(item: DItem) {
        if (!trailerAsked.add(item.key)) return
        viewModelScope.launch {
            val path = "/${if (item.type == MediaType.MOVIE) "movie" else "tv"}/${item.id}/videos"
            val videos = (tmdb(path)?.optJSONArray("results").objects() +
                tmdb(path, mapOf("include_video_language" to "en,ko,ja,zh,th,null"), lang = false)?.optJSONArray("results").objects())
                .filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() }
            val best = videos.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") }
                ?: videos.firstOrNull { it.optString("type") == "Trailer" }
                ?: videos.firstOrNull { it.optString("type") == "Teaser" }
            best?.optString("key")?.let { _trailers.value = _trailers.value + (item.key to it) }
        }
    }

    /**
     * Dublado ou legendado: pergunta às fontes da pessoa pelo 1º episódio (ou pelo filme). Áudio em português
     * em alguma fonte = "Dublado"; só fontes sem português = "Legendado". Sem fontes, não mostra nada.
     */
    fun wantAudio(item: DItem) {
        if (!audioAsked.add(item.key)) return
        viewModelScope.launch {
            audioGate.withPermit {
                val imdb = if (item.type == MediaType.MOVIE) tmdb("/movie/${item.id}")?.optString("imdb_id")
                else tmdb("/tv/${item.id}/external_ids", lang = false)?.optString("imdb_id")
                if (imdb.isNullOrBlank() || !imdb.startsWith("tt")) return@withPermit
                val result = withTimeoutOrNull(25_000) {
                    runCatching {
                        if (item.type == MediaType.MOVIE) streamRepository.resolveMovieStreams(imdb, item.title, item.year.toIntOrNull())
                        else streamRepository.resolveEpisodeStreams(imdb, 1, 1, tmdbId = item.id, title = item.title)
                    }.getOrElse { if (it is CancellationException) throw it; null }
                } ?: return@withPermit
                val label = when {
                    result.streams.any { isPortugueseStream(it) } -> "Dublado"
                    result.streams.any { !EmbedSources.isEmbedStream(it) } -> "Legendado"
                    else -> null
                }
                label?.let { _audio.value = _audio.value + (item.key to it) }
            }
        }
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
                fixTitles(more)
            } finally {
                allLoading = false
            }
        }
    }

    // ---------- Fileiras ----------

    private class Spec(val id: String, val title: String, val ranked: Boolean = false, val rating: Boolean = false,
                       val noDedupe: Boolean = false, val limit: Int = 20, val minItems: Int = 4, val load: suspend () -> List<DItem>)

    /** Dorama/filme asiático que a pessoa começou ou salvou (base das fileiras pessoais). */
    private class LibItem(val item: DItem, val fromHistory: Boolean)

    private fun reload() {
        job?.cancel()
        // Abre na hora com as fileiras guardadas da última vez; as novas entram quando ficarem prontas.
        val cached = if (round == 0) loadCache() else null
        _rows.value = cached.orEmpty()
        _loading.value = true
        allPage = 0; allTotalPages = Int.MAX_VALUE; allLoading = false
        val rnd = Random(System.nanoTime())
        job = viewModelScope.launch {
            val (cont, list) = coroutineScope {
                val c = async { continueItems() }
                val l = async { myListItems() }
                c.await() to l.await()
            }
            val lib = cont.map { LibItem(it, true) } + list.filter { l -> cont.none { it.key == l.key } }.map { LibItem(it, false) }
            val specs = plan(rnd, cont, list, lib)
            val done = arrayOfNulls<List<DItem>>(specs.size)
            val lock = Mutex()
            val hiddenNow = _hidden.value
            suspend fun publish(final: Boolean) {
                if (cached != null && !final) return
                val seen = HashSet<String>()
                val out = mutableListOf<DRow>()
                for ((i, s) in specs.withIndex()) {
                    val items = (done[i] ?: if (final) emptyList() else break).filter { it.key !in hiddenNow }
                    val fresh = when {
                        s.ranked -> items.take(10)
                        s.noDedupe -> items
                        else -> items.filter { it.key !in seen }.take(s.limit)
                    }
                    if (!s.noDedupe) fresh.forEach { seen += it.key }
                    val title = actorTitles[s.id] ?: s.title
                    if (fresh.size >= s.minItems && title.isNotBlank()) out += DRow(s.id, title, fresh, s.ranked, s.rating)
                }
                _rows.value = out
                out.firstOrNull()?.let { wantInfo(it.items.take(2)) }
                fixTitles(out.flatMap { it.items })
                if (final) saveCache(out)
            }
            specs.withIndex().toList().mapLimit(4) { (i, s) ->
                val items = runCatching { s.load() }.getOrElse { if (it is CancellationException) throw it; emptyList() }
                lock.withLock { done[i] = items; publish(final = false) }
            }
            lock.withLock { publish(final = true) }
            _loading.value = false
        }
    }

    private fun plan(rnd: Random, cont: List<DItem>, list: List<DItem>, lib: List<LibItem>): List<Spec> {
        val today = LocalDate.now()
        fun pg(max: Int) = if (round == 0) 1 else rnd.nextInt(1, max + 1)

        val personal = buildList {
            if (cont.isNotEmpty()) add(Spec(CONT_ROW, "Continue assistindo seus doramas", noDedupe = true, minItems = 1) { cont })
            if (list.isNotEmpty()) add(Spec(LIST_ROW, "Sua lista de doramas", noDedupe = true, minItems = 1) { list })
        }
        val top = listOf(
            Spec(AIRING_ROW, "Em exibição: episódios novos toda semana") { airingNow() },
            Spec("top10", "Top 10 doramas da semana", ranked = true) { trending() },
            Spec("hot", "Doramas em alta agora") {
                discover(mapOf("sort_by" to "popularity.desc"), dateFrom = today.minusYears(2), page = pg(2))
            }
        )

        // "Porque você assistiu/salvou": até 3 fileiras, dos doramas da pessoa primeiro.
        val becauseSeeds = (lib.filter { it.item.type == MediaType.TV }.map { Triple(it.item.id, titleOf(it.item), it.fromHistory) } +
            seeds.filter { it.type == MediaType.TV }.map { Triple(it.id, it.name, true) })
            .distinctBy { it.first }
        val because = if (becauseSeeds.isEmpty()) emptyList() else {
            val start = (round * 3).mod(becauseSeeds.size)
            (becauseSeeds.drop(start) + becauseSeeds.take(start)).take(3).mapIndexed { i, (id, name, watched) ->
                Spec("because$i", if (watched) "Porque você assistiu $name" else "Porque você salvou $name") { similar(id) }
            }
        }
        // Atores que mais aparecem nos doramas da pessoa.
        val libTv = lib.filter { it.item.type == MediaType.TV }.map { it.item.id }.take(8)
        val actors = if (libTv.isEmpty()) emptyList() else (0 until 2).map { i ->
            Spec("actor$i", "") { actorRow(libTv, i) }
        }

        val middle = listOf(
            Spec("best", "Os mais bem avaliados de todos os tempos", rating = true) {
                discover(mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to 250), page = pg(3))
            },
            Spec("famous", "Os doramas mais famosos") {
                discover(mapOf("sort_by" to "vote_count.desc"), page = pg(3))
            },
            Spec("romance", "Romances de suspirar") {
                byKeywords(listOf("romance", "love", "first love"), pg(3))
            },
            Spec("movies_hot", "Filmes asiáticos em alta") {
                discoverMovies(mapOf("sort_by" to "popularity.desc", "vote_count.gte" to 20), dateFrom = today.minusYears(3), page = pg(2))
            },
            Spec("gems", "Joias escondidas: pouca gente viu, todo mundo amou", rating = true) {
                discover(mapOf("sort_by" to "vote_average.desc", "vote_average.gte" to 7.8, "vote_count.gte" to 40, "vote_count.lte" to 400), page = pg(3))
            },
            Spec("school", "Escolar e juventude") {
                byKeywords(listOf("high school", "school", "coming of age", "youth"), pg(2))
            },
            Spec("sageuk", "Históricos e de época (Sageuk)") {
                byKeywords(listOf("historical drama", "joseon dynasty", "historical", "period drama", "ancient china"), pg(2))
            },
            Spec("new", "Lançamentos que valem o play") {
                discover(mapOf("sort_by" to "popularity.desc", "vote_count.gte" to 10), dateFrom = today.minusDays(200), page = pg(2))
            },
            Spec("revenge", "Vingança") {
                byKeywords(listOf("revenge"), pg(2))
            },
            Spec("office", "CEO e romance no escritório") {
                byKeywords(listOf("office romance", "workplace romance", "ceo", "chaebol", "boss"), pg(2))
            },
            Spec("movies_best", "Filmes asiáticos mais bem avaliados", rating = true) {
                discoverMovies(mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to 300), page = pg(3))
            },
            Spec("short", "Curtinhos para maratonar") {
                discover(mapOf("sort_by" to "popularity.desc", "with_type" to 2, "vote_count.gte" to 10), page = pg(2))
            },
            Spec("netflix", "Doramas na Netflix") {
                discover(mapOf("sort_by" to "popularity.desc", "with_watch_providers" to NETFLIX_PROVIDER, "watch_region" to "BR",
                    "with_watch_monetization_types" to "flatrate|ads"), page = pg(3))
            },
            Spec("comedy", "Comédias românticas e leves") {
                discover(mapOf("sort_by" to "popularity.desc", "with_genres" to "35", "vote_count.gte" to 20), page = pg(3))
            },
            Spec("movies_romance", "Filmes de romance asiáticos") {
                discoverMovies(mapOf("sort_by" to "popularity.desc", "with_genres" to "10749", "vote_count.gte" to 20), page = pg(3))
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
            addAll(personal)
            addAll(top)
            because.firstOrNull()?.let { add(it) }
            actors.firstOrNull()?.let { add(it) }
            // O resto das fileiras pessoais entra no meio, espaçado.
            val extras = because.drop(1) + actors.drop(1)
            mid.forEachIndexed { i, s ->
                add(s)
                if (i % 3 == 1) extras.getOrNull(i / 3)?.let { add(it) }
            }
            // Sempre por último: todos os doramas, do mais popular para o menos (carrega mais ao andar).
            add(Spec(ALL_ROW, "Todos os doramas", noDedupe = true) {
                val first = allPageItems(1)
                allPage = 1
                first
            })
        }
    }

    // ---------- Consultas ----------

    private fun detailsPath(type: MediaType, id: Int) = if (type == MediaType.MOVIE) "/movie/$id" else "/tv/$id"

    /** Confere país e gênero nos detalhes do TMDB; devolve o item pronto (com info guardada) ou null. */
    private suspend fun libraryItem(type: MediaType, id: Int): DItem? {
        val d = tmdb(detailsPath(type, id)) ?: return null
        val codes = _country.value.codes.split('|')
        val countries = d.optJSONArray("origin_country").strings() +
            d.optJSONArray("production_countries").objects().map { it.optString("iso_3166_1") }.takeIf { type == MediaType.MOVIE }.orEmpty()
        if (countries.none { it in codes }) return null
        val genreIds = d.optJSONArray("genres").objects().map { it.optInt("id") }
        if (genreIds.any { it in EXCLUDED_IDS }) return null
        d.put("genre_ids", JSONArray(genreIds))
        val item = toItem(d, type) ?: return null
        rememberInfo(item.key, type, d)
        return item
    }

    /** Doramas que a pessoa começou (do histórico), só do país escolhido, com o progresso de cada um. */
    private suspend fun continueItems(): List<DItem> {
        val list = resume.filter { it.item.mediaType == MediaType.TV || it.item.mediaType == MediaType.MOVIE }
            .distinctBy { "${it.item.mediaType}_${it.item.id}" }
        val fresh = HashSet<String>()
        val found = list.mapLimit(6) { r ->
            val item = libraryItem(r.item.mediaType, r.item.id) ?: return@mapLimit null
            val label = if (r.season != null && r.episode != null) "T${r.season}:E${r.episode}" else null
            if (item.type == MediaType.TV && hasNewEpisode(r)) fresh += item.key
            item to DResume(r.progress, label)
        }.filterNotNull()
        _progress.value = found.associate { it.first.key to it.second }
        _newEp.value = fresh
        // Quem tem episódio novo vai para a frente.
        return found.map { it.first }.sortedByDescending { it.key in fresh }
    }

    /**
     * Saiu episódio novo (nas últimas 3 semanas) depois do ponto onde a pessoa está: o último episódio no ar
     * vem depois do que ela está vendo, ou é o próprio próximo episódio (ainda não começado).
     */
    private suspend fun hasNewEpisode(r: NetflixResume): Boolean {
        val s = r.season ?: return false
        val e = r.episode ?: return false
        val last = tmdb("/tv/${r.item.id}")?.optJSONObject("last_episode_to_air") ?: return false
        val date = runCatching { LocalDate.parse(last.optString("air_date")) }.getOrNull() ?: return false
        if (date.isBefore(LocalDate.now().minusDays(21))) return false
        val ls = last.optInt("season_number")
        val le = last.optInt("episode_number")
        return ls > s || (ls == s && le > e) || (ls == s && le == e && r.progress <= 1)
    }

    // ---------- Cache (abrir na hora) ----------

    private fun cacheKey() = "rows_" + _country.value.codes

    private fun saveCache(rows: List<DRow>) {
        runCatching {
            val arr = JSONArray()
            rows.filter { it.id != CONT_ROW }.forEach { r ->
                arr.put(JSONObject().put("id", r.id).put("title", r.title).put("ranked", r.ranked).put("rating", r.rating)
                    .put("items", JSONArray().apply {
                        r.items.take(40).forEach { i ->
                            put(JSONObject().put("type", i.type.name).put("id", i.id).put("title", i.title).put("backdrop", i.backdrop)
                                .put("poster", i.poster ?: "").put("overview", i.overview).put("year", i.year).put("vote", i.vote)
                                .put("votes", i.votes).put("genres", JSONArray(i.genres)))
                        }
                    }))
            }
            prefs.edit().putString(cacheKey(), arr.toString()).apply()
        }
    }

    private fun loadCache(): List<DRow>? = runCatching {
        val raw = prefs.getString(cacheKey(), null) ?: return null
        val hiddenNow = _hidden.value
        JSONArray(raw).objects().map { r ->
            DRow(r.optString("id"), r.optString("title"), r.optJSONArray("items").objects().map { i ->
                DItem(
                    type = MediaType.valueOf(i.optString("type")), id = i.optInt("id"), title = i.optString("title"),
                    backdrop = i.optString("backdrop"), poster = i.optString("poster").ifBlank { null }, overview = i.optString("overview"),
                    year = i.optString("year"), vote = i.optDouble("vote", 0.0), votes = i.optInt("votes"),
                    genres = i.optJSONArray("genres").strings()
                )
            }.filter { it.key !in hiddenNow }, r.optBoolean("ranked"), r.optBoolean("rating"))
        }.filter { it.items.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Doramas e filmes asiáticos da Minha lista. */
    private suspend fun myListItems(): List<DItem> =
        myList.filter { it.mediaType == MediaType.TV || it.mediaType == MediaType.MOVIE }
            .distinctBy { "${it.mediaType}_${it.id}" }
            .take(40)
            .mapLimit(6) { libraryItem(it.mediaType, it.id) }
            .filterNotNull()

    /** Doramas com episódio indo ao ar esta semana; já busca o dia do próximo episódio de cada um. */
    private suspend fun airingNow(): List<DItem> {
        val today = LocalDate.now()
        val items = discover(mapOf("sort_by" to "popularity.desc", "air_date.gte" to today.minusDays(6).toString(),
            "air_date.lte" to today.plusDays(7).toString()))
        wantInfo(items.take(20))
        return items
    }

    /** Fileira "Com <ator>": o i-ésimo ator que mais aparece nos doramas da pessoa. */
    private suspend fun actorRow(libTv: List<Int>, index: Int): List<DItem> {
        val counts = HashMap<Int, Pair<String, Double>>()
        val freq = HashMap<Int, Int>()
        libTv.mapLimit(4) { tmdb("/tv/$it/aggregate_credits", lang = false)?.optJSONArray("cast").objects().take(4) }
            .flatten()
            .forEach { c ->
                val id = c.optInt("id")
                if (id <= 0) return@forEach
                freq[id] = (freq[id] ?: 0) + 1
                counts[id] = c.optString("name") to c.optDouble("popularity", 0.0)
            }
        val ranked = freq.keys.sortedWith(compareByDescending<Int> { freq[it] }.thenByDescending { counts[it]?.second ?: 0.0 })
        val personId = ranked.getOrNull(index) ?: return emptyList()
        val name = counts[personId]?.first.orEmpty()
        val codes = _country.value.codes.split('|')
        val langs = codes.flatMap { COUNTRY_LANGS[it].orEmpty() }
        val credits = tmdb("/person/$personId/combined_credits")?.optJSONArray("cast").objects()
        val items = credits.filter { x ->
            when (x.optString("media_type")) {
                "tv" -> x.optJSONArray("origin_country").strings().any { it in codes }
                "movie" -> x.optString("original_language") in langs
                else -> false
            } && x.optJSONArray("genre_ids").ints().none { it in EXCLUDED_IDS } && x.optInt("vote_count") >= 3
        }.sortedByDescending { it.optDouble("popularity", 0.0) }
            .mapNotNull { toItem(it, if (it.optString("media_type") == "movie") MediaType.MOVIE else MediaType.TV) }
            .distinctBy { it.key }
        if (name.isNotBlank()) actorTitles["actor$index"] = "Com $name"
        return items
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

    /** Filmes asiáticos (sem animação nem documentário). */
    private suspend fun discoverMovies(params: Map<String, Any>, page: Int = 1, dateFrom: LocalDate? = null): List<DItem> {
        val p = mutableMapOf<String, Any>("with_origin_country" to _country.value.codes, "without_genres" to "16,99")
        p.putAll(params)
        dateFrom?.let { p["primary_release_date.gte"] = it.toString() }
        return listOf(page, page + 1).mapLimit(2) { tmdb("/discover/movie", p + ("page" to it))?.optJSONArray("results").objects() }
            .flatten().mapNotNull { toItem(it, MediaType.MOVIE) }.distinctBy { it.key }
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

    private suspend fun similar(id: Int): List<DItem> {
        val (rec, sim) = coroutineScope {
            val r = async { tmdb("/tv/$id/recommendations") }
            val s = async { tmdb("/tv/$id/similar") }
            r.await() to s.await()
        }
        return (rec?.optJSONArray("results").objects() + sim?.optJSONArray("results").objects())
            .filter { isDorama(it) && it.optInt("id") != id }
            .mapNotNull { toItem(it) }
            .distinctBy { it.key }
    }

    private fun isDorama(x: JSONObject): Boolean {
        val countries = x.optJSONArray("origin_country").strings()
        if (countries.none { it in _country.value.codes.split('|') }) return false
        val ids = x.optJSONArray("genre_ids").ints()
        return ids.none { it in EXCLUDED_IDS }
    }

    private fun rememberInfo(key: String, type: MediaType, d: JSONObject) {
        val parts = buildList {
            if (type == MediaType.MOVIE) {
                val min = d.optInt("runtime")
                if (min > 0) add(if (min >= 60) "${min / 60}h ${"%02d".format(min % 60)}min" else "${min}min")
            } else {
                val eps = d.optInt("number_of_episodes")
                val seasons = d.optInt("number_of_seasons")
                if (eps > 0) add(if (eps == 1) "1 episódio" else "$eps episódios")
                if (seasons > 0) add(if (seasons == 1) "1 temporada" else "$seasons temporadas")
            }
        }
        if (parts.isNotEmpty()) _info.value = _info.value + (key to parts.joinToString(" • "))
        if (type == MediaType.TV) airingLabel(d)?.let { _airing.value = _airing.value + (key to it) }
    }

    /** "Ep. novo hoje", "Ep. 7 amanhã", "Ep. 7 sábado" ou "Ep. 7 em 28/11". */
    private fun airingLabel(d: JSONObject): String? {
        val today = LocalDate.now()
        val next = d.optJSONObject("next_episode_to_air")
        val last = d.optJSONObject("last_episode_to_air")
        val lastDate = last?.optString("air_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (lastDate == today) return "Ep. novo hoje"
        val nextDate = next?.optString("air_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val ep = next.optInt("episode_number").takeIf { it > 0 }?.let { "Ep. $it" } ?: "Ep. novo"
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, nextDate)
        return when {
            days < 0 -> null
            days == 0L -> "$ep hoje"
            days == 1L -> "$ep amanhã"
            days <= 6 -> "$ep ${WEEKDAYS[nextDate.dayOfWeek]}"
            days <= 60 -> "$ep em ${nextDate.format(DateTimeFormatter.ofPattern("dd/MM"))}"
            else -> null
        }
    }

    private fun toItem(x: JSONObject, type: MediaType = MediaType.TV): DItem? {
        if (x.optJSONArray("genre_ids").ints().any { it in EXCLUDED_IDS }) return null
        val backdrop = x.optString("backdrop_path").takeIf { it.startsWith("/") } ?: return null
        val title = x.optString("name").ifBlank { x.optString("title") }.ifBlank { return null }
        return DItem(
            type = type,
            id = x.optInt("id"),
            title = title,
            backdrop = "$IMG/w780$backdrop",
            poster = x.optString("poster_path").takeIf { it.startsWith("/") }?.let { "$IMG/w342$it" },
            overview = x.optString("overview"),
            year = x.optString("first_air_date").ifBlank { x.optString("release_date") }.take(4),
            vote = x.optDouble("vote_average", 0.0),
            votes = x.optInt("vote_count"),
            genres = x.optJSONArray("genre_ids").ints().mapNotNull { GENRE_NAMES[it] }.distinct()
        )
    }

    private fun titleOf(item: DItem) = _titles.value[item.key] ?: item.title

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

    private fun hasLatin(s: String) = s.any { it in 'A'..'Z' || it in 'a'..'z' }
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }

    private suspend fun <T, R> List<T>.mapLimit(n: Int, f: suspend (T) -> R): List<R> = coroutineScope {
        val gate = Semaphore(n)
        map { async { gate.withPermit { f(it) } } }.awaitAll()
    }

    /** Título das fileiras de ator ("Com Lee Min-ho"), conhecido só depois de carregar. */
    private val actorTitles = ConcurrentHashMap<String, String>()

    companion object {
        const val ALL_ROW = "all"
        const val CONT_ROW = "continue"
        const val LIST_ROW = "mylist"
        const val AIRING_ROW = "airing"
        private val keywordIds = ConcurrentHashMap<String, Int>()
        private const val NETFLIX_PROVIDER = 8
        private const val IMG = "https://image.tmdb.org/t/p"
        // Sem animação/anime, infantil, notícias, reality e talk show: só doramas de verdade.
        private val EXCLUDED_IDS = setOf(16, 10762, 10763, 10764, 10767)
        private val EXCLUDED_GENRES = EXCLUDED_IDS.joinToString(",")
        private val cache = ConcurrentHashMap<String, JSONObject>()
        private val WEEKDAYS = mapOf(
            DayOfWeek.MONDAY to "segunda", DayOfWeek.TUESDAY to "terça", DayOfWeek.WEDNESDAY to "quarta", DayOfWeek.THURSDAY to "quinta",
            DayOfWeek.FRIDAY to "sexta", DayOfWeek.SATURDAY to "sábado", DayOfWeek.SUNDAY to "domingo"
        )
        private val GENRE_NAMES = mapOf(
            35 to "Comédia", 80 to "Policial", 18 to "Drama", 10751 to "Família", 9648 to "Mistério",
            10759 to "Ação", 10765 to "Fantasia", 10766 to "Novela", 10768 to "Política", 37 to "Faroeste", 99 to "Documentário",
            28 to "Ação", 12 to "Aventura", 10749 to "Romance", 53 to "Suspense", 27 to "Terror", 14 to "Fantasia", 878 to "Ficção científica"
        )
    }
}
