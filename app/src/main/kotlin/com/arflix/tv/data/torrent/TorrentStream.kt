package com.arflix.tv.data.torrent

import android.content.Context
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.Locale

/**
 * Torrent embutido (Streaming Modes TV): toca direto as fontes P2P dos addons do Stremio (infoHash/magnet),
 * sem TorrServer nem debrid. Igual ao Stremio Service do PC: um servidor HTTP local em 127.0.0.1 entrega o
 * arquivo de vídeo ao player (Media3) com suporte a Range, e o libtorrent baixa em ordem a partir do ponto
 * que o player pede (pulos na barra viram prioridade nas peças do novo ponto).
 *
 * Fire TV tem pouco espaço: só um torrent por vez fica guardado; ao trocar de título o anterior é apagado,
 * e a pasta é limpa toda vez que o app abre.
 */
object TorrentStream {
    private const val TAG = "TorrentStream"
    private const val PREFERRED_PORT = 11471
    private const val PATH = "/torrent/"
    private const val METADATA_TIMEOUT_MS = 90_000L
    private const val PIECE_TIMEOUT_MS = 120_000L
    private const val READAHEAD_BYTES = 24L * 1024 * 1024
    private const val STORAGE_MARGIN_BYTES = 300L * 1024 * 1024
    private val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg")

    @Volatile private var appContext: Context? = null
    @Volatile private var port = 0
    private var server: io.ktor.server.engine.ApplicationEngine? = null
    private val lock = Any()
    private var session: SessionManager? = null
    @Volatile private var current: Active? = null
    private val activateMutex = Mutex()

    private class Active(val hash: String, val handle: TorrentHandle, val saveDir: File) {
        @Volatile var fileIndex = -1
        @Volatile var tailRequested = false
    }

    private val LOCAL_URL = Regex("^http://127\\.0\\.0\\.1:(\\d+)$PATH([0-9a-f]{40})(.*)$")

    /** Endereço do torrent embutido (de qualquer execução: a lista de fontes fica em cache entre aberturas do app). */
    fun isLocalUrl(url: String?): Boolean = url != null && LOCAL_URL.matches(url)

    /**
     * Endereço guardado de uma execução anterior: liga o servidor e acerta a porta (se a 11471 estava ocupada,
     * a porta muda). Outros endereços voltam como vieram.
     */
    fun refreshUrl(context: Context, url: String): String? {
        val m = LOCAL_URL.matchEntire(url) ?: return url
        if (!ensureServer(context)) return null
        return "http://127.0.0.1:$port$PATH${m.groupValues[2]}${m.groupValues[3]}"
    }

    /** Fonte P2P de um addon (infoHash, ou url magnet:) → endereço local tocável pelo player. */
    fun urlFor(context: Context, infoHash: String?, magnetUrl: String?, fileIdx: Int?, sources: List<String>?, fileName: String?): String? {
        val fromMagnet = magnetUrl?.takeIf { it.startsWith("magnet:", true) }?.let(::hashFromMagnet)
        val hash = (infoHash ?: fromMagnet)?.trim()?.lowercase(Locale.US)
            ?.removePrefix("urn:btih:")?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: return null
        if (!ensureServer(context)) return null
        val trackers = buildList {
            sources.orEmpty().map { it.trim().removePrefix("tracker:") }
                .filter { it.startsWith("udp://", true) || it.startsWith("http://", true) || it.startsWith("https://", true) }
                .forEach(::add)
            magnetUrl?.let { m -> Regex("[?&]tr=([^&]+)").findAll(m).forEach { add(java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")) } }
        }.distinct().take(30)
        val q = buildList {
            if (fileIdx != null && fileIdx >= 0) add("idx=$fileIdx")
            fileName?.takeIf { it.isNotBlank() }?.let { add("dn=" + enc(it)) }
            trackers.forEach { add("tr=" + enc(it)) }
        }.joinToString("&")
        return "http://127.0.0.1:$port$PATH$hash" + (if (q.isEmpty()) "" else "?$q")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun hashFromMagnet(m: String): String? =
        Regex("xt=urn:btih:([0-9a-fA-F]{40})").find(m)?.groupValues?.get(1)

    private fun ensureServer(context: Context): Boolean = synchronized(lock) {
        if (server != null) return true
        appContext = context.applicationContext
        val p = runCatching { ServerSocket(PREFERRED_PORT).use { it.localPort } }.getOrNull()
            ?: runCatching { ServerSocket(0).use { it.localPort } }.getOrNull() ?: return false
        return runCatching {
            server = embeddedServer(CIO, port = p, host = "127.0.0.1") {
                routing {
                    get("$PATH{hash}") { serve(call, body = true) }
                    head("$PATH{hash}") { serve(call, body = false) }
                }
            }.also { it.start(wait = false) }
            port = p
            Log.i(TAG, "servidor local na porta $p")
            true
        }.getOrElse { Log.e(TAG, "falha ao abrir o servidor", it); false }
    }

    private fun cacheRoot(): File = File(appContext!!.cacheDir, "torrents")

    private fun ensureSession(): SessionManager = synchronized(lock) {
        session?.let { return it }
        // Restos de uma sessão anterior (o app foi fechado no meio): apaga tudo.
        cacheRoot().deleteRecursively()
        cacheRoot().mkdirs()
        val sp = SettingsPack()
            .connectionsLimit(200)
            .activeDownloads(2)
        sp.setEnableDht(true)
        sp.setEnableLsd(true)
        val s = SessionManager(false)
        s.start(SessionParams(sp))
        session = s
        Log.i(TAG, "sessão iniciada")
        return s
    }

    /** Torrent ativo para este hash (troca de título apaga o anterior com os arquivos). */
    private suspend fun activate(hash: String, trackers: List<String>, dn: String?): Active = activateMutex.withLock {
        withContext(Dispatchers.IO) { activateLocked(hash, trackers, dn) }
    }

    private suspend fun activateLocked(hash: String, trackers: List<String>, dn: String?): Active {
        val s = ensureSession()
        synchronized(lock) {
            current?.takeIf { it.hash == hash }?.let { return it }
            current?.let { old ->
                Log.i(TAG, "apagando torrent anterior ${old.hash}")
                runCatching { s.remove(old.handle, SessionHandle.DELETE_FILES) }
                old.saveDir.deleteRecursively()
            }
            current = null
        }
        val dir = File(cacheRoot(), hash).apply { mkdirs() }
        val magnet = buildString {
            append("magnet:?xt=urn:btih:").append(hash)
            dn?.let { append("&dn=").append(enc(it)) }
            trackers.forEach { append("&tr=").append(enc(it)) }
        }
        s.download(magnet, dir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val sha = Sha1Hash.parseHex(hash)
        var handle: TorrentHandle? = null
        val until = System.currentTimeMillis() + 10_000
        while (handle == null && System.currentTimeMillis() < until) {
            handle = s.find(sha)?.takeIf { it.isValid }
            if (handle == null) delay(50)
        }
        val h = handle ?: error("torrent não foi adicionado")
        return Active(hash, h, dir).also { synchronized(lock) { current = it } }
    }

    private suspend fun metadata(a: Active): TorrentInfo? {
        val until = System.currentTimeMillis() + METADATA_TIMEOUT_MS
        while (System.currentTimeMillis() < until) {
            if (a.handle.status().hasMetadata()) a.handle.torrentFile()?.let { return it }
            delay(200)
        }
        return null
    }

    /** O arquivo pedido pelo addon (fileIdx) ou, sem ele, o maior vídeo do torrent. */
    private fun chooseFile(ti: TorrentInfo, idx: Int?, dn: String?): Int {
        val fs = ti.files()
        val n = fs.numFiles()
        if (idx != null && idx in 0 until n) return idx
        if (!dn.isNullOrBlank()) {
            (0 until n).firstOrNull { fs.filePath(it).substringAfterLast('/').equals(dn, true) }?.let { return it }
        }
        val videos = (0 until n).filter { fs.filePath(it).substringAfterLast('.').lowercase(Locale.US) in VIDEO_EXT }
        return (videos.ifEmpty { (0 until n).toList() }).maxByOrNull { fs.fileSize(it) } ?: 0
    }

    private fun select(a: Active, ti: TorrentInfo, file: Int) {
        if (a.fileIndex == file) return
        val n = ti.files().numFiles()
        a.handle.prioritizeFiles(Array(n) { if (it == file) Priority.DEFAULT else Priority.IGNORE })
        a.handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        a.fileIndex = file
    }

    /** Peças do ponto pedido em diante, com prazo (o libtorrent corre atrás delas primeiro). */
    private fun prioritize(a: Active, ti: TorrentInfo, file: Int, offset: Long, fileSize: Long) {
        val fs = ti.files()
        val first = ti.mapFile(file, offset, 1).piece()
        val lastOfFile = ti.mapFile(file, fileSize - 1, 1).piece()
        val count = (READAHEAD_BYTES / fs.pieceLength()).toInt().coerceIn(4, 64)
        for (i in 0 until count) {
            val p = first + i
            if (p > lastOfFile) break
            if (!a.handle.havePiece(p)) a.handle.setPieceDeadline(p, 300 + i * 150)
        }
        // MKV/MP4 costumam ter o índice no fim do arquivo: o player lê o fim logo no começo.
        if (!a.tailRequested) {
            a.tailRequested = true
            for (p in maxOf(first, lastOfFile - 1)..lastOfFile) a.handle.setPieceDeadline(p, 500)
        }
    }

    private suspend fun serve(call: ApplicationCall, body: Boolean) {
        val hash = call.parameters["hash"]?.lowercase(Locale.US)
        if (hash == null || !hash.matches(Regex("[0-9a-f]{40}"))) { call.respond(HttpStatusCode.BadRequest); return }
        val qp = call.request.queryParameters
        val idx = qp["idx"]?.toIntOrNull()
        val dn = qp["dn"]
        val trackers = qp.getAll("tr").orEmpty()
        val a = runCatching { activate(hash, trackers, dn) }.getOrElse {
            Log.e(TAG, "falha ao adicionar $hash", it); call.respond(HttpStatusCode.InternalServerError); return
        }
        val ti = metadata(a) ?: run {
            Log.w(TAG, "sem metadados em ${METADATA_TIMEOUT_MS / 1000}s: $hash"); call.respond(HttpStatusCode.GatewayTimeout); return
        }
        val fs = ti.files()
        val file = chooseFile(ti, idx, dn)
        val size = fs.fileSize(file)
        val path = File(a.saveDir, fs.filePath(file))
        val free = runCatching { a.saveDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        val have = if (path.exists()) path.length() else 0L
        if (size - have > free - STORAGE_MARGIN_BYTES) {
            Log.w(TAG, "arquivo de ${size shr 20} MB não cabe (livre: ${free shr 20} MB)")
            call.respond(HttpStatusCode.InsufficientStorage); return
        }
        select(a, ti, file)

        val range = call.request.headers[HttpHeaders.Range]
        val (start, end) = parseRange(range, size) ?: run {
            call.response.header(HttpHeaders.ContentRange, "bytes */$size")
            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable); return
        }
        val length = end - start + 1
        val type = when (path.extension.lowercase(Locale.US)) {
            "mkv" -> ContentType("video", "x-matroska")
            "mp4", "m4v" -> ContentType.Video.MP4
            "webm" -> ContentType("video", "webm")
            "avi" -> ContentType("video", "x-msvideo")
            "ts", "m2ts" -> ContentType("video", "mp2t")
            else -> ContentType.Application.OctetStream
        }
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        if (range != null) call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$size")
        val status = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
        if (!body) {
            call.respond(object : io.ktor.http.content.OutgoingContent.NoContent() {
                override val status = status
                override val contentType = type
                override val contentLength = length
            })
            return
        }
        prioritize(a, ti, file, start, size)
        val fileOffset = fs.fileOffset(file)
        val pieceLen = fs.pieceLength().toLong()
        // Tamanho pelo próprio Ktor (cabeçalho manual + chunked confundia o player sobre o tamanho do arquivo).
        call.respondBytesWriter(contentType = type, status = status, contentLength = length) {
            val buf = ByteArray(256 * 1024)
            var pos = start
            var raf: RandomAccessFile? = null
            try {
                while (pos <= end) {
                    val piece = ti.mapFile(file, pos, 1).piece()
                    val waitUntil = System.currentTimeMillis() + PIECE_TIMEOUT_MS
                    var lastBump = 0L
                    while (!a.handle.havePiece(piece)) {
                        if (current !== a) return@respondBytesWriter
                        val now = System.currentTimeMillis()
                        if (now > waitUntil) { Log.w(TAG, "peça $piece não chegou"); return@respondBytesWriter }
                        if (now - lastBump > 2_000) { prioritize(a, ti, file, pos, size); lastBump = now }
                        delay(60)
                    }
                    // Até o fim desta peça (dentro do arquivo e do pedido).
                    val pieceEndInFile = (piece + 1) * pieceLen - fileOffset - 1
                    val stop = minOf(end, pieceEndInFile, size - 1)
                    if (raf == null) {
                        if (!path.exists()) { delay(100); continue }
                        raf = RandomAccessFile(path, "r")
                    }
                    var p2 = pos
                    while (p2 <= stop) {
                        val n = minOf(buf.size.toLong(), stop - p2 + 1).toInt()
                        raf.seek(p2)
                        val r = raf.read(buf, 0, n)
                        if (r <= 0) { delay(50); break }
                        writeFully(buf, 0, r)
                        p2 += r
                    }
                    if (p2 > pos) {
                        pos = p2
                        // A janela de leitura acompanha o que o player já consumiu.
                        if ((pos - start) % (8L * 1024 * 1024) < buf.size) prioritize(a, ti, file, minOf(pos, size - 1), size)
                    }
                }
            } finally {
                runCatching { raf?.close() }
            }
        }
    }

    /** "bytes=a-b", "bytes=a-" ou "bytes=-n" → (início, fim) dentro do arquivo; sem Range = arquivo inteiro. */
    private fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
        if (size <= 0) return null
        if (header.isNullOrBlank()) return 0L to size - 1
        val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
        val a = spec.substringBefore('-').trim()
        val b = spec.substringAfter('-', "").trim()
        val (s, e) = when {
            a.isEmpty() && b.isNotEmpty() -> { val n = b.toLongOrNull() ?: return null; (size - n).coerceAtLeast(0) to size - 1 }
            else -> {
                val s = a.toLongOrNull() ?: return null
                s to (b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1))
            }
        }
        return if (s in 0..e && s < size) s to e else null
    }
}
