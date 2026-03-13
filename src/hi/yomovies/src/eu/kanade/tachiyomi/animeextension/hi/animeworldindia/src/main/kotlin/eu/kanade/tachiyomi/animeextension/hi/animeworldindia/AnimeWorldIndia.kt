package eu.kanade.tachiyomi.animeextension.hi.animeworldindia

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * AnimeWorldIndia — Aniyomi Extension
 *
 * Target: https://watchanimeworld.net (also watchanimeworld.in)
 * Platform: WordPress with Cloudflare
 * Languages: Hindi, Tamil, Telugu, English
 *
 * NOTE: If CSS selectors break, open the site in browser DevTools
 * and inspect the HTML to update the selectors below.
 */
class AnimeWorldIndia :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    // ─────────────────────────────────────────────────────
    // Source Metadata
    // ─────────────────────────────────────────────────────

    override val name = "AnimeWorld India"

    // Use watchanimeworld.net as primary; change to watchanimeworld.in if needed
    override val baseUrl = "https://watchanimeworld.net"

    override val lang = "hi"
    override val supportsLatest = true

    // Cloudflare is used on this site — must use cloudflareClient
    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ─────────────────────────────────────────────────────
    // Popular Anime — /category/type/anime/page/N/
    // ─────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/category/type/anime/page/$page/", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        return parseAnimePage(document)
    }

    // ─────────────────────────────────────────────────────
    // Latest Updates — home page or /page/N/
    // ─────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/page/$page/", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        return parseAnimePage(document)
    }

    // ─────────────────────────────────────────────────────
    // Search — WordPress /?s= query
    // ─────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // If there's a text query, use WordPress search
        if (query.isNotBlank()) {
            val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .build()
            return GET(url.toString(), headers)
        }

        // Otherwise, apply category/type/genre filters
        var categoryPath = ""
        var genrePath = ""

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> {
                    if (filter.state != 0) {
                        categoryPath = "/category/type/${filter.toUriPart()}"
                    }
                }
                is GenreFilter -> {
                    if (filter.state != 0) {
                        genrePath = "/category/${filter.toUriPart()}"
                    }
                }
                is LanguageFilter -> {
                    if (filter.state != 0) {
                        categoryPath = "/category/language/${filter.toUriPart()}"
                    }
                }
                else -> {}
            }
        }

        val path = when {
            categoryPath.isNotEmpty() -> "$categoryPath/page/$page/"
            genrePath.isNotEmpty() -> "$genrePath/page/$page/"
            else -> "/category/type/anime/page/$page/"
        }

        return GET("$baseUrl$path", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        return parseAnimePage(document)
    }

    // ─────────────────────────────────────────────────────
    // Shared page parser — handles all listing pages
    // ─────────────────────────────────────────────────────

    private fun parseAnimePage(document: Document): AnimesPage {
        // Try multiple known WordPress theme selectors in order
        val animeElements = document.select(ANIME_CARD_SELECTOR)

        val animeList = animeElements.mapNotNull { el ->
            runCatching { parseAnimeElement(el) }.getOrNull()
        }

        // WordPress pagination: next page link
        val hasNextPage = document.select("a.next.page-numbers, .pagination .next, a[rel=next]").isNotEmpty()

        return AnimesPage(animeList, hasNextPage)
    }

    private fun parseAnimeElement(element: Element): SAnime {
        return SAnime.create().apply {
            // Most WordPress anime themes use an <a> wrapper with href + title/image inside
            val link = element.selectFirst("a[href]")
                ?: throw Exception("No link found")

            setUrlWithoutDomain(link.attr("href"))

            title = element.selectFirst(
                "h2.entry-title, h3.entry-title, h2, h3, .title, .post-title, " +
                    "a[href] img[alt], img[alt]"
            )?.let {
                if (it.tagName() == "img") it.attr("alt") else it.text()
            } ?: link.attr("title").ifBlank { link.text() }

            thumbnail_url = element.selectFirst("img[src]")
                ?.let {
                    it.attr("abs:data-src").ifBlank { it.attr("abs:src") }
                }
        }
    }

    // ─────────────────────────────────────────────────────
    // Anime Details
    // ─────────────────────────────────────────────────────

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string())

        return SAnime.create().apply {
            title = document.selectFirst(
                "h1.entry-title, .post-title h1, h1.single-title, h1"
            )?.text() ?: ""

            thumbnail_url = document.selectFirst(
                ".post-thumbnail img, .entry-thumbnail img, " +
                    ".wp-post-image, article img[src], .featured-img img"
            )?.let {
                it.attr("abs:data-src").ifBlank { it.attr("abs:src") }
            }

            description = document.selectFirst(
                ".entry-content p, .post-content p, .synopsis, .description, " +
                    ".entry-summary, .plot, div.content p"
            )?.text()

            genre = document.select(
                "a[href*='/category/'], .cat-links a, .tags a, " +
                    "a[rel='category tag'], span.cat a"
            ).joinToString(", ") { it.text() }

            author = document.selectFirst(
                "a[href*='studio'], span:contains(Studio), " +
                    ".entry-meta span:contains(Producer)"
            )?.text()?.replace("Studio:", "")?.trim()

            status = when {
                document.select("a[href*='ongoing'], span:contains(Ongoing)").isNotEmpty() ->
                    SAnime.ONGOING
                document.select("a[href*='completed'], span:contains(Completed)").isNotEmpty() ->
                    SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // Episode List
    // ─────────────────────────────────────────────────────

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body.string())

        // Pattern 1: Dedicated episode list items (most common on WP anime themes)
        val episodeElements = document.select(EPISODE_SELECTOR)

        if (episodeElements.isNotEmpty()) {
            return episodeElements.mapIndexed { index, el ->
                SEpisode.create().apply {
                    val link = el.selectFirst("a[href]")
                        ?: return@mapIndexed null

                    setUrlWithoutDomain(link.attr("href"))

                    name = link.text().trim().ifBlank { "Episode ${index + 1}" }

                    // Try to parse upload date if available
                    date_upload = el.selectFirst("time, span.date, .ep-date")
                        ?.let { parseDate(it.text()) } ?: 0L

                    // Try to extract episode number from name
                    episode_number = extractEpisodeNumber(name) ?: (index + 1).toFloat()
                }
            }.filterNotNull().reversed()
        }

        // Pattern 2: The single page itself IS the episode (for movies / one-shots)
        // Return a single episode pointing to the current URL
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = document.selectFirst("h1.entry-title, h1")?.text() ?: "Episode 1"
                episode_number = 1f
                date_upload = System.currentTimeMillis()
            }
        )
    }

    // ─────────────────────────────────────────────────────
    // Video Extraction
    // ─────────────────────────────────────────────────────

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body.string())
        val videoList = mutableListOf<Video>()
        val pageHtml = response.body.string()

        // ── Strategy 1: Direct <video> or <source> tags ──
        document.select("video source[src], source[src]").forEach { src ->
            val url = src.attr("abs:src")
            val quality = src.attr("label").ifBlank { src.attr("size").ifBlank { "Default" } }
            if (url.isNotBlank() && isVideoUrl(url)) {
                videoList.add(Video(url, quality, url))
            }
        }

        document.select("video[src]").forEach { vid ->
            val url = vid.attr("abs:src")
            if (url.isNotBlank() && isVideoUrl(url)) {
                videoList.add(Video(url, "Video", url))
            }
        }

        // ── Strategy 2: Embedded iframes (most common on WP anime sites) ──
        document.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank() && !iframeSrc.contains("google") &&
                !iframeSrc.contains("facebook") && !iframeSrc.contains("disqus")
            ) {
                // Fetch the iframe page and look for video there
                try {
                    val iframeResponse = client.newCall(GET(iframeSrc, headers)).execute()
                    val iframeHtml = iframeResponse.body.string()
                    videoList.addAll(extractVideosFromHtml(iframeHtml, iframeSrc))
                } catch (_: Exception) { }
            }
        }

        // ── Strategy 3: JS variable extraction from page source ──
        videoList.addAll(extractVideosFromHtml(pageHtml, response.request.url.toString()))

        // ── Strategy 4: WordPress AJAX video player (some WP themes) ──
        document.select("div[data-video-id], div[data-src]").forEach { div ->
            val url = div.attr("data-video-id").ifBlank { div.attr("data-src") }
            if (url.isNotBlank() && isVideoUrl(url)) {
                videoList.add(Video(url, "Player", url))
            }
        }

        if (videoList.isEmpty()) {
            throw Exception(
                "No video sources found. The site may have changed structure. " +
                    "Try opening this episode in WebView."
            )
        }

        return videoList.distinctBy { it.url }
    }

    /**
     * Extracts video URLs from raw HTML using multiple regex patterns.
     * Covers common JS player setups used on WordPress anime sites.
     */
    private fun extractVideosFromHtml(html: String, sourceUrl: String): List<Video> {
        val found = mutableListOf<Video>()

        // M3U8 / HLS stream
        Regex("""['"](https?://[^'"]+\.m3u8[^'"]*?)['"]""")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { url -> found.add(Video(url, "HLS", url)) }

        // Direct MP4
        Regex("""['"](https?://[^'"]+\.mp4[^'"]*?)['"]""")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { url -> found.add(Video(url, "MP4", url)) }

        // file: "URL" (JWPlayer / VideoJS pattern)
        Regex("""(?:file|src)\s*:\s*['"](https?://[^'"]+?)['"]""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { isVideoUrl(it) }
            .distinct()
            .forEach { url -> found.add(Video(url, "Player", url)) }

        // var videoUrl = "URL" or var url = "URL"
        Regex("""var\s+(?:videoUrl|video_url|url|src)\s*=\s*['"](https?://[^'"]+?)['"]""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { isVideoUrl(it) }
            .distinct()
            .forEach { url -> found.add(Video(url, "Direct", url)) }

        // Streamtape embed detection
        if (html.contains("streamtape")) {
            Regex("""streamtape\.com/e/([A-Za-z0-9]+)""")
                .find(html)?.groupValues?.get(1)?.let { id ->
                    val stUrl = "https://streamtape.com/e/$id"
                    found.add(Video(stUrl, "Streamtape", stUrl))
                }
        }

        // Dailymotion embed detection
        Regex("""dailymotion\.com/embed/video/([A-Za-z0-9]+)""")
            .find(html)?.groupValues?.get(1)?.let { id ->
                val dmUrl = "https://www.dailymotion.com/embed/video/$id"
                found.add(Video(dmUrl, "Dailymotion", dmUrl))
            }

        return found
    }

    // ─────────────────────────────────────────────────────
    // Filters
    // ─────────────────────────────────────────────────────

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Only one filter works at a time"),
        AnimeFilter.Separator(),
        TypeFilter(),
        LanguageFilter(),
        GenreFilter(),
    )

    private class TypeFilter : UriPartFilter(
        "Type",
        arrayOf(
            Pair("All", ""),
            Pair("Anime", "anime"),
            Pair("Movies", "movies"),
            Pair("Series", "series"),
        )
    )

    private class LanguageFilter : UriPartFilter(
        "Language",
        arrayOf(
            Pair("All", ""),
            Pair("Hindi", "hindi"),
            Pair("Tamil", "tamil"),
            Pair("Telugu", "telugu"),
            Pair("English", "english"),
        )
    )

    private class GenreFilter : UriPartFilter(
        "Genre",
        arrayOf(
            Pair("All", ""),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Comedy", "comedy"),
            Pair("Drama", "drama"),
            Pair("Fantasy", "fantasy"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Magic", "magic"),
            Pair("Mecha", "mecha"),
            Pair("Mystery", "mystery"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Shounen", "shounen"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Sports", "sports"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Thriller", "thriller"),
        )
    )

    open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ─────────────────────────────────────────────────────
    // User Preferences (Quality Selection)
    // ─────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Video Quality"
            entries = arrayOf("HLS/M3U8 (best)", "MP4 (direct)", "Any")
            entryValues = arrayOf("HLS", "MP4", "Any")
            setDefaultValue("HLS")
            summary = "%s"
        }
        screen.addPreference(qualityPref)
    }

    // Sort videos by user preference
    private fun sortVideos(videos: List<Video>): List<Video> {
        val pref = preferences.getString(PREF_QUALITY_KEY, "HLS") ?: "HLS"
        return when (pref) {
            "HLS" -> videos.sortedByDescending { it.quality.contains("HLS", true) }
            "MP4" -> videos.sortedByDescending { it.quality.contains("MP4", true) }
            else -> videos
        }
    }

    // ─────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────

    private fun isVideoUrl(url: String): Boolean {
        return url.contains(".m3u8") || url.contains(".mp4") ||
            url.contains("streamtape") || url.contains("dailymotion") ||
            url.contains("vidcloud") || url.contains("doodstream") ||
            url.contains("upstream") || url.contains("mixdrop")
    }

    private fun extractEpisodeNumber(name: String): Float? {
        // Try "Episode 12", "Ep 12", "E12", "12" at end
        val patterns = listOf(
            Regex("""[Ee]pisode\s*(\d+(?:\.\d+)?)"""),
            Regex("""[Ee]p\.?\s*(\d+(?:\.\d+)?)"""),
            Regex("""[Ee](\d+(?:\.\d+)?)"""),
            Regex("""(\d+(?:\.\d+)?)\s*$"""),
        )
        for (pattern in patterns) {
            pattern.find(name)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
        }
        return null
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching {
            SimpleDateFormat("MMMM dd, yyyy", Locale.ENGLISH).parse(dateStr.trim())?.time ?: 0L
        }.getOrElse {
            runCatching {
                SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).parse(dateStr.trim())?.time ?: 0L
            }.getOrDefault(0L)
        }
    }

    companion object {
        // ── SELECTORS ──────────────────────────────────────────────
        // These target WordPress anime theme card structures.
        // If the site redesigns, update these with DevTools inspection.

        /** Selects individual anime cards on listing pages */
        private const val ANIME_CARD_SELECTOR =
            "article.post, " +
                "li.movies-list-item, " +
                "div.post-item, " +
                "div.item, " +
                ".movie-item, " +
                ".blog-post, " +
                "div.entry, " +
                ".thecontent li"

        /** Selects episode entries on a show's detail page */
        private const val EPISODE_SELECTOR =
            "ul.episodelist li, " +
                ".ep-list li, " +
                ".episodes-list li, " +
                "ul.episodios li, " +
                ".episodios li, " +
                ".episodes li, " +
                "li.episode, " +
                "div.episodelist a, " +
                "ul li a[href*='episode'], " +
                "ul li a[href*='ep-']"

        private const val PREF_QUALITY_KEY = "preferred_quality"
    }
}
