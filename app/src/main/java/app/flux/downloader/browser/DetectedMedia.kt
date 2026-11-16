package app.flux.downloader.browser

import app.flux.downloader.engine.MediaKind
import app.flux.downloader.engine.Quality

/** Un video/audio que el navegador detectó y ya leyó (calidades y peso incluidos). */
data class DetectedMedia(
    val id: String,
    val url: String,
    val kind: MediaKind,
    val durationSec: Long,
    val qualities: List<Quality>,
    val audioOnly: Boolean,
    /** Cabeceras con las que se leyó y con las que hay que descargar (Referer, User-Agent, Cookie). */
    val headers: Map<String, String>,
    /** Tipo de contenido real del archivo directo (video/mp4…): define la extensión al guardarlo. */
    val mime: String? = null,
    /** Lo que la página sabe del video: título, portada y si se está reproduciendo ahora. */
    val title: String = "",
    val thumb: String? = null,
    val playing: Boolean = false,
)

private fun identity(m: DetectedMedia): String = runCatching {
    val u = java.net.URI(m.url)
    (u.host.orEmpty() + u.path.orEmpty()).trimEnd('/')
}.getOrDefault(m.url)

/** La misma ruta sin los números de calidad (…_1080p.mp4 y …_720p.mp4 son el mismo video). */
private val qualityToken = Regex("""(?i)(?<![a-z0-9])\d{3,4}p?(?![a-z0-9])""")

private fun identityNoQuality(m: DetectedMedia): String = qualityToken.replace(identity(m), "#")

private fun rank(m: DetectedMedia): Long {
    val q = m.qualities.firstOrNull()
    return (q?.height ?: 0).toLong() * 1_000_000_000_000L + (q?.bytes ?: 0L)
}

private fun sameVideo(a: DetectedMedia, b: DetectedMedia): Boolean {
    if (identity(a) == identity(b)) return true
    val na = identityNoQuality(a)
    if (na.contains('#') && na == identityNoQuality(b)) return true
    val ta = a.thumb
    if (ta != null && ta == b.thumb) return true
    val sa = a.qualities.firstOrNull()?.bytes
    val sb = b.qualities.firstOrNull()?.bytes
    // El mismo archivo servido con otra firma/token: mismo peso exacto
    if (a.kind == MediaKind.DIRECT && b.kind == MediaKind.DIRECT && sa != null && sa == sb && sa > 1_000_000) return true
    val sameLength = a.durationSec > 0 && kotlin.math.abs(a.durationSec - b.durationSec) <= 2
    // Mismo título y misma duración
    if (a.title.isNotBlank() && a.title == b.title && sameLength) return true
    // Listas de streaming (una por calidad) de la misma duración: son calidades del mismo video
    if (a.kind != MediaKind.DIRECT && b.kind != MediaKind.DIRECT && sameLength && a.durationSec > 30) return true
    return false
}

/** Cada calidad lleva su propia fuente: se completa con la del medio si no la trae. */
private fun withSource(m: DetectedMedia): List<Quality> =
    m.qualities.map { it.copy(url = it.url ?: m.url, kind = it.kind ?: m.kind, mime = it.mime ?: m.mime) }

/**
 * Une las detecciones que son el mismo video en UNA sola, con la unión de todas sus calidades (cada una con su
 * propia fuente). Así se ven todas (240p… 1080p… con su peso) y "Todos" descarga uno por video.
 */
fun dedupeDetected(list: List<DetectedMedia>): List<DetectedMedia> {
    val groups = ArrayList<MutableList<DetectedMedia>>()
    for (m in list) {
        val g = groups.firstOrNull { grp -> grp.any { sameVideo(it, m) } }
        if (g == null) groups += mutableListOf(m) else g += m
    }
    return groups.map { g ->
        if (g.size == 1) {
            val m = g[0]
            m.copy(qualities = withSource(m))
        } else {
            val best = g.firstOrNull { it.playing } ?: g.maxByOrNull { rank(it) }!!
            val merged = LinkedHashMap<Int, Quality>()
            g.flatMap { withSource(it) }
                .sortedWith(compareByDescending<Quality> { it.bytes != null && !it.estimated }.thenByDescending { it.bytes != null })
                .forEach { q -> if (q.height !in merged) merged[q.height] = q }
            best.copy(
                qualities = merged.values.sortedByDescending { if (it.height == 0) -1 else it.height },
                title = best.title.ifBlank { g.firstOrNull { it.title.isNotBlank() }?.title.orEmpty() },
                thumb = best.thumb ?: g.firstOrNull { it.thumb != null }?.thumb,
                playing = g.any { it.playing },
                durationSec = maxOf(best.durationSec, g.maxOf { it.durationSec }),
            )
        }
    }
}

/** Calidad que se elige sola: la mejor que no pase del tope de Ajustes (0 = la mejor). null = "Original". */
fun pickQualityObj(m: DetectedMedia, maxHeight: Int): Quality? {
    val qs = m.qualities
    if (qs.isEmpty()) return null
    if (maxHeight == 0) return qs.maxByOrNull { it.height } ?: qs.first()
    return qs.filter { it.height in 1..maxHeight }.maxByOrNull { it.height } ?: qs.minByOrNull { it.height } ?: qs.first()
}

fun pickQuality(m: DetectedMedia, maxHeight: Int): Int? = pickQualityObj(m, maxHeight)?.height?.takeIf { it > 0 }
