package com.pablopinheiroo.telegramninjaprobe

import android.content.Context
import android.os.Build
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class GroupVideo(
    val messageId: Long,
    val fileId: Int,
    val fileName: String,
    val mimeType: String,
    val size: Long
)

data class GroupContent(
    val title: String,
    val videos: List<GroupVideo>
)

/** One TDLib client and one encrypted, device-local Telegram session for this test plugin. */
object TelegramSession {
    private const val TEST_GROUP_USERNAME = "umtrabalhodoinferno"
    private const val APPLICATION_VERSION = "0.1.0-probe"

    @Volatile private var appContext: Context? = null
    @Volatile private var vault: ProbeVault? = null
    @Volatile private var client: Client? = null
    @Volatile private var nativeResources: android.content.res.Resources? = null
    @Volatile private var authorized = false
    @Volatile private var currentStatus = "O teste ainda não foi conectado."
    @Volatile private var statusListener: ((String) -> Unit)? = null

    private val pendingDownloads = ConcurrentHashMap<Int, CompletableFuture<TdApi.File>>()
    private val downloadedVideos = ConcurrentHashMap<Int, GroupVideo>()

    fun initialize(context: Context, resources: android.content.res.Resources?) {
        appContext = context.applicationContext
        vault = ProbeVault(context.applicationContext)
        nativeResources = resources
    }

    fun setStatusListener(listener: ((String) -> Unit)?) {
        statusListener = listener
        listener?.invoke(currentStatus)
    }

    fun status(): String = currentStatus
    fun isAuthorized(): Boolean = authorized
    fun savedApiId(): String = vault?.getString("api_id").orEmpty()
    fun savedApiHash(): String = vault?.getString("api_hash").orEmpty()

    fun resumeIfSaved() {
        val secure = vault ?: return publish("Armazenamento seguro ainda não foi inicializado.")
        val apiId = secure.getString("api_id") ?: return publish("Informe seu API ID e API Hash para conectar.")
        val apiHash = secure.getString("api_hash") ?: return publish("Informe seu API ID e API Hash para conectar.")
        connect(apiId, apiHash)
    }

    fun connect(apiIdInput: String, apiHashInput: String) {
        val context = appContext ?: return publish("Contexto do NinjaTV indisponível.")
        val secure = vault ?: ProbeVault(context).also { vault = it }
        val apiId = apiIdInput.trim().toIntOrNull()
        val apiHash = apiHashInput.trim()

        if (apiId == null || apiId <= 0) return publish("API ID inválido.")
        if (apiHash.isBlank()) return publish("Informe o API Hash.")
        if (client != null) return publish("Cliente TDLib já iniciado: $currentStatus")

        secure.putString("api_id", apiId.toString())
        secure.putString("api_hash", apiHash)
        authorized = false
        downloadedVideos.clear()
        publish("Criando cliente TDLib dentro da extensão...")

        try {
            client = Client.create(
                { update -> handleUpdate(update) },
                { error -> publish("Erro TDLib: ${error.message ?: error.javaClass.simpleName}") },
                { error -> publish("Erro no callback TDLib: ${error.message ?: error.javaClass.simpleName}") }
            )
        } catch (error: Throwable) {
            client = null
            publish("Não foi possível iniciar o cliente TDLib: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun handleUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuthorizationState(update.authorizationState)
            is TdApi.UpdateFile -> {
                val file = update.file
                if (file.local != null && file.local.isDownloadingCompleted) {
                    pendingDownloads[file.id]?.complete(file)
                }
            }
        }
    }

    private fun handleAuthorizationState(state: TdApi.AuthorizationState) {
        val context = appContext ?: return
        val secure = vault ?: ProbeVault(context).also { vault = it }

        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val apiId = secure.getString("api_id")?.toIntOrNull() ?: 0
                val apiHash = secure.getString("api_hash").orEmpty()
                val databaseDirectory = File(context.filesDir, "telegramninja/tdlib/database")
                val filesDirectory = File(context.filesDir, "telegramninja/tdlib/files")
                databaseDirectory.mkdirs()
                filesDirectory.mkdirs()

                val parameters = TdApi.SetTdlibParameters().apply {
                    useTestDc = false
                    this.databaseDirectory = databaseDirectory.absolutePath
                    this.filesDirectory = filesDirectory.absolutePath
                    databaseEncryptionKey = secure.getOrCreateBytes("tdlib_database_key", 32)
                    useFileDatabase = true
                    useChatInfoDatabase = true
                    useMessageDatabase = true
                    useSecretChats = false
                    this.apiId = apiId
                    this.apiHash = apiHash
                    systemLanguageCode = "pt-BR"
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
                    systemVersion = Build.VERSION.RELEASE ?: "Android"
                    applicationVersion = APPLICATION_VERSION
                }

                client?.send(parameters) { result ->
                    if (result is TdApi.Error) {
                        publish("Falha ao configurar TDLib (${result.code}): ${result.message}")
                    }
                }
                publish("Parâmetros configurados. Preparando autenticação...")
            }

            is TdApi.AuthorizationStateWaitPhoneNumber ->
                publish("WAIT_PHONE")

            is TdApi.AuthorizationStateWaitCode ->
                publish("WAIT_CODE")

            is TdApi.AuthorizationStateWaitPassword ->
                publish("WAIT_PASSWORD")

            is TdApi.AuthorizationStateWaitEmailAddress ->
                publish("WAIT_EMAIL")

            is TdApi.AuthorizationStateWaitEmailCode ->
                publish("WAIT_EMAIL_CODE")

            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                publish("WAIT_OTHER_DEVICE")

            is TdApi.AuthorizationStateWaitRegistration ->
                publish("A conta precisa concluir o cadastro no Telegram.")

            is TdApi.AuthorizationStateReady -> {
                authorized = true
                publish("READY")
            }

            is TdApi.AuthorizationStateLoggingOut -> {
                authorized = false
                publish("Saindo da conta Telegram...")
            }

            is TdApi.AuthorizationStateClosing -> {
                authorized = false
                publish("Encerrando a sessão Telegram...")
            }

            is TdApi.AuthorizationStateClosed -> {
                authorized = false
                client = null
                publish("Sessão TDLib encerrada.")
            }

            else -> publish("Estado TDLib: ${state.javaClass.simpleName}")
        }
    }

    fun submitPhoneNumber(phone: String) {
        val value = phone.trim()
        if (value.isBlank()) return publish("Telefone não informado.")
        val current = client ?: return publish("Cliente TDLib ainda não foi iniciado.")
        current.send(TdApi.SetAuthenticationPhoneNumber(value, null)) { result ->
            if (result is TdApi.Error) publish("Telegram (${result.code}): ${result.message}")
        }
        publish("Solicitando código de confirmação...")
    }

    fun submitCode(code: String) {
        val value = code.trim()
        if (value.isBlank()) return publish("Código não informado.")
        val current = client ?: return publish("Cliente TDLib ainda não foi iniciado.")
        current.send(TdApi.CheckAuthenticationCode(value)) { result ->
            if (result is TdApi.Error) publish("Código recusado (${result.code}): ${result.message}")
        }
        publish("Validando código...")
    }

    fun submitPassword(password: String) {
        if (password.isBlank()) return publish("Senha 2FA não informada.")
        val current = client ?: return publish("Cliente TDLib ainda não foi iniciado.")
        current.send(TdApi.CheckAuthenticationPassword(password)) { result ->
            if (result is TdApi.Error) publish("Senha recusada (${result.code}): ${result.message}")
        }
        publish("Validando senha 2FA...")
    }

    fun submitEmailAddress(email: String) {
        if (email.isBlank()) return publish("E-mail não informado.")
        val current = client ?: return publish("Cliente TDLib ainda não foi iniciado.")
        current.send(TdApi.SetAuthenticationEmailAddress(email.trim())) { result ->
            if (result is TdApi.Error) publish("Telegram (${result.code}): ${result.message}")
        }
        publish("Solicitando código por e-mail...")
    }

    fun submitEmailCode(code: String) {
        if (code.isBlank()) return publish("Código de e-mail não informado.")
        val current = client ?: return publish("Cliente TDLib ainda não foi iniciado.")
        current.send(
            TdApi.CheckAuthenticationEmailCode(
                TdApi.EmailAddressAuthenticationCode(code.trim())
            )
        ) { result ->
            if (result is TdApi.Error) publish("Código de e-mail recusado (${result.code}): ${result.message}")
        }
        publish("Validando código de e-mail...")
    }

    fun findTestGroup(): GroupContent {
        val current = client ?: throw IllegalStateException("Conecte o Telegram nas configurações da extensão.")
        if (!authorized) throw IllegalStateException("Faça login no Telegram nas configurações da extensão.")

        val chatFuture = CompletableFuture<TdApi.Chat>()
        current.send(TdApi.SearchPublicChat(TEST_GROUP_USERNAME)) { result ->
            when (result) {
                is TdApi.Chat -> chatFuture.complete(result)
                is TdApi.Error -> chatFuture.completeExceptionally(
                    IllegalStateException("Não foi possível localizar o grupo @${TEST_GROUP_USERNAME} (${result.code}): ${result.message}")
                )
                else -> chatFuture.completeExceptionally(
                    IllegalStateException("Resposta inesperada ao procurar o grupo.")
                )
            }
        }

        val chat = chatFuture.get(45, TimeUnit.SECONDS)
        val messagesFuture = CompletableFuture<List<GroupVideo>>()
        current.send(TdApi.GetChatHistory(chat.id, 0, 0, 100, false)) { result ->
            when (result) {
                is TdApi.Messages -> {
                    val videos = result.messages.mapNotNull { message -> extractVideo(message) }
                        .sortedBy { it.messageId }
                    videos.forEach { downloadedVideos[it.fileId] = it }
                    messagesFuture.complete(videos)
                }
                is TdApi.Error -> messagesFuture.completeExceptionally(
                    IllegalStateException(
                        "Não foi possível ler o histórico (${result.code}): ${result.message}. " +
                            "Confirme que sua conta participa do grupo."
                    )
                )
                else -> messagesFuture.completeExceptionally(
                    IllegalStateException("Resposta inesperada ao ler as mensagens do grupo.")
                )
            }
        }

        val videos = messagesFuture.get(90, TimeUnit.SECONDS)
        return GroupContent(chat.title, videos)
    }

    private fun extractVideo(message: TdApi.Message): GroupVideo? {
        return when (val content = message.content) {
            is TdApi.MessageVideo -> {
                val video = content.video
                val file = video.video
                GroupVideo(
                    message.id,
                    file.id,
                    video.fileName.ifBlank { "video-${file.id}.mp4" },
                    video.mimeType.ifBlank { "video/mp4" },
                    if (file.size > 0L) file.size else file.expectedSize
                )
            }
            is TdApi.MessageDocument -> {
                val document = content.document
                val fileName = document.fileName.ifBlank { "video-${document.document.id}" }
                val mime = document.mimeType.ifBlank { "application/octet-stream" }
                val extension = fileName.substringAfterLast('.', "").lowercase()
                val videoFile = mime.startsWith("video/") ||
                    extension in setOf("mp4", "mkv", "webm", "mov", "avi", "m4v")
                if (!videoFile) null else {
                    val file = document.document
                    GroupVideo(
                        message.id,
                        file.id,
                        fileName,
                        mime,
                        if (file.size > 0L) file.size else file.expectedSize
                    )
                }
            }
            else -> null
        }
    }

    fun downloadVideo(fileId: Int): File {
        val item = downloadedVideos[fileId]
            ?: throw IllegalArgumentException("Vídeo não encontrado. Atualize a página da série.")
        val current = client ?: throw IllegalStateException("Cliente TDLib não está conectado.")
        if (!authorized) throw IllegalStateException("A sessão Telegram não está autenticada.")

        val future = CompletableFuture<TdApi.File>()
        pendingDownloads[fileId] = future
        current.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { result ->
            when (result) {
                is TdApi.File -> {
                    if (result.local != null && result.local.isDownloadingCompleted) {
                        future.complete(result)
                    }
                }
                is TdApi.Error -> future.completeExceptionally(
                    IllegalStateException("Falha no download (${result.code}): ${result.message}")
                )
            }
        }

        val downloaded = try {
            future.get(30, TimeUnit.MINUTES)
        } finally {
            pendingDownloads.remove(fileId, future)
        }

        val path = downloaded.local?.path?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("TDLib não informou o arquivo local.")
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            throw IllegalStateException("O arquivo baixado não está disponível no armazenamento local.")
        }
        return file
    }

    private fun publish(message: String) {
        currentStatus = message
        statusListener?.invoke(message)
    }
}
