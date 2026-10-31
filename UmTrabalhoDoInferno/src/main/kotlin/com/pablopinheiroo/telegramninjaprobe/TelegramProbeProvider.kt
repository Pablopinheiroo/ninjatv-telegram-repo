package com.pablopinheiroo.telegramninjaprobe

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TelegramProbeProvider : MainAPI() {
    override var mainUrl = "https://t.me/umtrabalhodoinferno"
    override var name = "Um Trabalho do Inferno — Telegram (Teste)"
    override var lang = "pt-BR"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val usesWebView = false
    override val supportedTypes = setOf(TvType.TvSeries)
    override val mainPage = mainPageOf(
        "Um Trabalho do Inferno - DUBLADO" to "telegram-probe://umtrabalhodoinferno"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val item = newTvSeriesSearchResponse(
            "Um Trabalho do Inferno - DUBLADO",
            "telegram-probe://umtrabalhodoinferno",
            TvType.TvSeries
        )
        return newHomePageResponse(request.name, listOf(item), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val title = "Um Trabalho do Inferno - DUBLADO"
        return if (query.isBlank() || title.contains(query, ignoreCase = true)) {
            listOf(newTvSeriesSearchResponse(title, "telegram-probe://umtrabalhodoinferno", TvType.TvSeries))
        } else emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        if (!TelegramSession.isAuthorized()) {
            throw ErrorLoadingException(
                "Antes de abrir o grupo, entre em Configurações da extensão e conecte sua conta Telegram."
            )
        }

        val content = withContext(Dispatchers.IO) {
            TelegramSession.findTestGroup()
        }
        if (content.videos.isEmpty()) {
            throw ErrorLoadingException(
                "O grupo foi localizado (${content.title}), mas não foram encontrados vídeos nas últimas 100 mensagens."
            )
        }

        val episodes = content.videos.mapIndexed { index, video ->
            val parsed = parseEpisode(video.fileName, index + 1)
            newEpisode(video.fileId.toString()) {
                name = parsed.title
                season = parsed.season
                episode = parsed.episode
                description = video.mimeType
            }
        }

        return newTvSeriesLoadResponse(
            "Um Trabalho do Inferno - DUBLADO",
            url,
            TvType.TvSeries,
            episodes
        ) {
            plot = "Teste de leitura do grupo Telegram: ${content.title}. " +
                "Os vídeos são apresentados como episódios para validar a integração TDLib."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fileId = data.toIntOrNull() ?: return false
        val downloaded = withContext(Dispatchers.IO) {
            TelegramSession.downloadVideo(fileId)
        }
        callback(
            newExtractorLink(
                source = name,
                name = "Telegram — arquivo local de teste",
                url = downloaded.toURI().toString(),
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }

    private data class EpisodeLabel(val title: String, val season: Int, val episode: Int)

    private fun parseEpisode(fileName: String, fallbackEpisode: Int): EpisodeLabel {
        val title = fileName.substringBeforeLast('.', fileName).trim()
        val patterns = listOf(
            Regex("(?i).*?S(\\d{1,2})[ ._-]*E(\\d{1,3}).*"),
            Regex("(?i).*?(\\d{1,2})[xX](\\d{1,3}).*"),
            Regex("(?i).*?(?:EP|EPIS[ÓO]DIO)[ ._-]*(\\d{1,3}).*")
        )
        for ((index, pattern) in patterns.withIndex()) {
            val match = pattern.matchEntire(title) ?: continue
            val season = if (index < 2) match.groupValues[1].toIntOrNull() ?: 1 else 1
            val episode = if (index < 2) match.groupValues[2].toIntOrNull() ?: fallbackEpisode
                else match.groupValues[1].toIntOrNull() ?: fallbackEpisode
            return EpisodeLabel(title, season, episode)
        }
        return EpisodeLabel(title.ifBlank { "Episódio $fallbackEpisode" }, 1, fallbackEpisode)
    }
}
