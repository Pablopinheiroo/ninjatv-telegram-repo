package com.pablopinheiroo.telegramninjaprobe

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TelegramApiCredentials(val apiId: String, val apiHash: String)

data class GitHubDeviceChallenge(
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long
)

/**
 * Reads Telegram API credentials from one private GitHub repository.
 * GitHub tokens are stored encrypted through ProbeVault/Android Keystore.
 */
object GitHubCredentialBridge {
    private const val OWNER = "Pablopinheiroo"
    private const val REPOSITORY = "ninjatv-telegram"
    private const val REPOSITORY_ID = "1441285379"
    private const val BRANCH = "main"
    private const val CREDENTIALS_PATH = "telegram-credentials.json"

    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val OAUTH_TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val API_FILE_URL =
        "https://api.github.com/repos/$OWNER/$REPOSITORY/contents/$CREDENTIALS_PATH?ref=$BRANCH"

    private const val GRANT_DEVICE = "urn:ietf:params:oauth:grant-type:device_code"

    fun setClientId(vault: ProbeVault, clientId: String) {
        val normalized = clientId.trim()
        require(normalized.isNotBlank()) { "Informe o Client ID do GitHub App." }
        val old = vault.getString("github_client_id").orEmpty()
        if (old != normalized) clearTokens(vault)
        vault.putString("github_client_id", normalized)
    }

    fun savedClientId(vault: ProbeVault): String =
        vault.getString("github_client_id").orEmpty()

    fun disconnect(vault: ProbeVault) {
        clearTokens(vault)
        vault.removeString("github_client_id")
    }

    /** Runs on a worker thread; onChallenge should post the code display to the UI thread. */
    fun loadTelegramCredentials(
        clientId: String,
        vault: ProbeVault,
        onChallenge: (GitHubDeviceChallenge) -> Unit,
        onStatus: (String) -> Unit
    ): TelegramApiCredentials {
        val token = obtainAccessToken(clientId, vault, onChallenge, onStatus)
        return try {
            fetchCredentials(token)
        } catch (error: GitHubRequestException) {
            if (error.httpCode != 401) throw error
            onStatus("A autorização do GitHub expirou. Renovando...")
            val renewed = refreshOrAuthorize(clientId, vault, onChallenge, onStatus)
            fetchCredentials(renewed)
        }
    }

    private fun obtainAccessToken(
        clientId: String,
        vault: ProbeVault,
        onChallenge: (GitHubDeviceChallenge) -> Unit,
        onStatus: (String) -> Unit
    ): String {
        val savedClientId = vault.getString("github_client_id").orEmpty()
        if (savedClientId != clientId) {
            clearTokens(vault)
            vault.putString("github_client_id", clientId)
        }

        val access = vault.getString("github_access_token").orEmpty()
        val expiresAt = vault.getString("github_access_expires_at")?.toLongOrNull() ?: 0L
        if (access.isNotBlank() && expiresAt > System.currentTimeMillis() + 60_000L) {
            return access
        }
        return refreshOrAuthorize(clientId, vault, onChallenge, onStatus)
    }

    private fun refreshOrAuthorize(
        clientId: String,
        vault: ProbeVault,
        onChallenge: (GitHubDeviceChallenge) -> Unit,
        onStatus: (String) -> Unit
    ): String {
        val refresh = vault.getString("github_refresh_token").orEmpty()
        if (refresh.isNotBlank()) {
            try {
                onStatus("Renovando autorização do GitHub...")
                val response = postForm(
                    OAUTH_TOKEN_URL,
                    linkedMapOf(
                        "client_id" to clientId,
                        "grant_type" to "refresh_token",
                        "refresh_token" to refresh
                    )
                )
                if (response.has("access_token")) {
                    saveTokens(vault, response)
                    return response.getString("access_token")
                }
            } catch (_: Exception) {
                // An expired/revoked refresh token requires a new device authorization.
                clearTokens(vault)
            }
        }
        return authorizeDevice(clientId, vault, onChallenge, onStatus)
    }

    private fun authorizeDevice(
        clientId: String,
        vault: ProbeVault,
        onChallenge: (GitHubDeviceChallenge) -> Unit,
        onStatus: (String) -> Unit
    ): String {
        onStatus("Solicitando autorização do GitHub...")
        val request = postForm(
            DEVICE_CODE_URL,
            linkedMapOf("client_id" to clientId, "scope" to "offline_access")
        )

        val deviceCode = request.optString("device_code")
        val userCode = request.optString("user_code")
        val verificationUri = request.optString("verification_uri", "https://github.com/login/device")
        val expiresIn = request.optLong("expires_in", 900L)
        var interval = request.optLong("interval", 5L)

        require(deviceCode.isNotBlank() && userCode.isNotBlank()) {
            "O GitHub não retornou o código. Confira o Client ID e se o Device Flow está ativado."
        }

        onChallenge(GitHubDeviceChallenge(userCode, verificationUri, expiresIn))
        onStatus("Aguardando você autorizar o TelegramNinja no GitHub...")

        val deadline = System.currentTimeMillis() + expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L)
            val response = postForm(
                OAUTH_TOKEN_URL,
                linkedMapOf(
                    "client_id" to clientId,
                    "device_code" to deviceCode,
                    "grant_type" to GRANT_DEVICE,
                    "repository_id" to REPOSITORY_ID
                )
            )

            when (response.optString("error")) {
                "authorization_pending" -> continue
                "slow_down" -> {
                    interval = response.optLong("interval", interval + 5L)
                    continue
                }
                "access_denied" ->
                    throw IllegalStateException("A autorização do TelegramNinja foi recusada no GitHub.")
                "expired_token", "token_expired" ->
                    throw IllegalStateException("O código do GitHub expirou. Inicie a conexão novamente.")
                "" -> {
                    if (response.has("access_token")) {
                        saveTokens(vault, response)
                        onStatus("GitHub autorizado. Lendo o arquivo privado...")
                        return response.getString("access_token")
                    }
                    throw IllegalStateException(
                        response.optString("error_description", "Resposta inesperada do GitHub.")
                    )
                }
                else -> throw IllegalStateException(
                    response.optString("error_description", response.optString("error"))
                )
            }
        }
        throw IllegalStateException("O código do GitHub expirou. Inicie a conexão novamente.")
    }

    private fun saveTokens(vault: ProbeVault, response: JSONObject) {
        val access = response.optString("access_token")
        require(access.isNotBlank()) { "O GitHub não retornou um token de acesso." }
        vault.putString("github_access_token", access)

        val now = System.currentTimeMillis()
        val accessSeconds = response.optLong("expires_in", 28_800L)
        vault.putString("github_access_expires_at", (now + accessSeconds * 1000L).toString())

        val refresh = response.optString("refresh_token")
        if (refresh.isNotBlank()) {
            vault.putString("github_refresh_token", refresh)
            val refreshSeconds = response.optLong("refresh_token_expires_in", 15_897_600L)
            vault.putString("github_refresh_expires_at", (now + refreshSeconds * 1000L).toString())
        }
    }

    private fun fetchCredentials(accessToken: String): TelegramApiCredentials {
        val connection = (URL(API_FILE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
        }

        val code = connection.responseCode
        val body = readBody(connection)
        connection.disconnect()

        if (code !in 200..299) {
            val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
            val explanation = when (code) {
                401 -> "A autorização do GitHub expirou ou foi revogada."
                403 -> "O GitHub negou o acesso. Confira Contents: Read-only e a instalação do App no repositório privado."
                404 -> "Não encontrei $CREDENTIALS_PATH no repositório privado $OWNER/$REPOSITORY."
                else -> "GitHub respondeu HTTP $code."
            }
            throw GitHubRequestException(code, (explanation + " " + message).trim())
        }

        val fileJson = JSONObject(body)
        val encoded = fileJson.optString("content")
        require(encoded.isNotBlank()) { "O GitHub retornou o arquivo sem conteúdo." }
        val decoded = String(
            Base64.decode(encoded.replace("\n", "").replace("\r", ""), Base64.DEFAULT),
            Charsets.UTF_8
        )
        val credentials = JSONObject(decoded)
        val apiId = credentials.optString("api_id").trim()
        val apiHash = credentials.optString("api_hash").trim()
        require(apiId.toIntOrNull()?.let { it > 0 } == true) {
            "O campo api_id está vazio ou inválido em $CREDENTIALS_PATH."
        }
        require(apiHash.isNotBlank()) {
            "O campo api_hash está vazio em $CREDENTIALS_PATH."
        }
        return TelegramApiCredentials(apiId, apiHash)
    }

    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        val payload = fields.entries.joinToString("&") { (key, value) ->
            encode(key) + "=" + encode(value)
        }.toByteArray(Charsets.UTF_8)

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }

        connection.outputStream.use { it.write(payload) }
        val code = connection.responseCode
        val body = readBody(connection)
        connection.disconnect()

        val json = runCatching { JSONObject(body) }.getOrElse {
            throw IllegalStateException("Resposta inválida do GitHub (HTTP $code).")
        }
        if (code !in 200..299) {
            throw IllegalStateException(
                json.optString("error_description", json.optString("message", "GitHub HTTP $code"))
            )
        }
        return json
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun clearTokens(vault: ProbeVault) {
        listOf(
            "github_access_token",
            "github_access_expires_at",
            "github_refresh_token",
            "github_refresh_expires_at"
        ).forEach(vault::removeString)
    }

    private class GitHubRequestException(
        val httpCode: Int,
        message: String
    ) : IllegalStateException(message)
}
