package com.pablopinheiroo.telegramninjaprobe

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object TelegramProbeSettings {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastPrompt = ""
    @Volatile private var pluginResources: android.content.res.Resources? = null

    fun initialize(resources: android.content.res.Resources?) {
        pluginResources = resources
    }

    fun open(context: Context) {
        TelegramSession.setStatusListener { status ->
            mainHandler.post { onStatus(context, status) }
        }

        val vault = ProbeVault(context.applicationContext)
        val clientId = GitHubCredentialBridge.savedClientId(vault)
        if (clientId.isBlank()) {
            showClientIdDialog(context, "")
        } else {
            showMainMenu(context, clientId)
        }
    }

    private fun showMainMenu(context: Context, clientId: String) {
        AlertDialog.Builder(context)
            .setTitle("TelegramNinja — GitHub privado")
            .setMessage(
                "Origem das credenciais: $GITHUB_REPOSITORY/$CREDENTIALS_FILE\n\n" +
                    "Client ID configurado: sim\n" +
                    "Status do Telegram: ${TelegramSession.status()}\n\n" +
                    "A extensão lê o arquivo privado pelo GitHub, guarda os tokens com Android Keystore " +
                    "e usa o API ID/API Hash sem pedir que você os digite no NinjaTV."
            )
            .setPositiveButton("Sincronizar e conectar") { _, _ ->
                startGitHubSync(context, clientId)
            }
            .setNeutralButton("Configurar GitHub App") { _, _ ->
                showClientIdDialog(context, clientId)
            }
            .setNegativeButton("Mais opções") { _, _ ->
                showMoreOptions(context)
            }
            .show()
    }

    private fun showMoreOptions(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("TelegramNinja")
            .setItems(arrayOf("Desconectar GitHub", "Fechar")) { _, which ->
                if (which == 0) {
                    GitHubCredentialBridge.disconnect(ProbeVault(context.applicationContext))
                    Toast.makeText(context, "GitHub desconectado neste aparelho.", Toast.LENGTH_LONG).show()
                    showClientIdDialog(context, "")
                }
            }
            .show()
    }

    private fun showClientIdDialog(context: Context, oldClientId: String) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val clientIdInput = EditText(context).apply {
            hint = "GitHub App Client ID"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(oldClientId)
        }
        layout.addView(
            TextView(context).apply {
                text = "Crie um GitHub App, ative Device Flow e conceda somente Contents: Read-only. Instale-o apenas no repositório privado Pablopinheiroo/ninjatv-telegram. Não informe Client Secret nem chave privada."
            }
        )
        layout.addView(
            clientIdInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        AlertDialog.Builder(context)
            .setTitle("Configurar acesso ao GitHub")
            .setMessage("O Client ID é público. Ele identifica o GitHub App, mas não dá acesso sozinho.")
            .setView(layout)
            .setPositiveButton("Salvar e continuar") { _, _ ->
                val clientId = clientIdInput.text.toString().trim()
                try {
                    GitHubCredentialBridge.setClientId(
                        ProbeVault(context.applicationContext),
                        clientId
                    )
                    startGitHubSync(context, clientId)
                } catch (error: Throwable) {
                    showError(context, "Configuração inválida", error)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startGitHubSync(context: Context, clientId: String) {
        if (clientId.isBlank()) {
            showClientIdDialog(context, "")
            return
        }

        lastPrompt = ""
        Toast.makeText(context, "Conectando ao GitHub privado...", Toast.LENGTH_LONG).show()
        val vault = ProbeVault(context.applicationContext)

        Thread {
            try {
                val credentials = GitHubCredentialBridge.loadTelegramCredentials(
                    clientId = clientId,
                    vault = vault,
                    onChallenge = { challenge ->
                        mainHandler.post { showDeviceChallenge(context, challenge) }
                    },
                    onStatus = { status ->
                        mainHandler.post {
                            Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                        }
                    }
                )

                mainHandler.post {
                    Toast.makeText(
                        context,
                        "Credenciais privadas recuperadas. Preparando TDLib...",
                        Toast.LENGTH_LONG
                    ).show()
                    startConnection(context, credentials.apiId, credentials.apiHash)
                }
            } catch (error: Throwable) {
                mainHandler.post {
                    showError(context, "Falha na conexão com GitHub", error)
                }
            }
        }.start()
    }

    private fun showDeviceChallenge(context: Context, challenge: GitHubDeviceChallenge) {
        val message =
            "Abra o GitHub e informe este código:\n\n" +
                "${challenge.userCode}\n\n" +
                "O código expira em ${challenge.expiresInSeconds / 60} minutos. " +
                "Depois de autorizar, volte ao NinjaTV. A extensão continuará verificando automaticamente."

        AlertDialog.Builder(context)
            .setTitle("Autorizar TelegramNinja")
            .setMessage(message)
            .setPositiveButton("Abrir GitHub") { _, _ ->
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(challenge.verificationUri))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (error: Throwable) {
                    showError(context, "Não foi possível abrir o GitHub", error)
                }
            }
            .setNeutralButton("Copiar código") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Código GitHub", challenge.userCode))
                Toast.makeText(context, "Código copiado.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun startConnection(context: Context, apiId: String, apiHash: String) {
        Toast.makeText(context, "Preparando biblioteca TDLib...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val nativeStatus = NativeBundle.ensureLoaded(
                    context.applicationContext,
                    pluginResources
                )
                mainHandler.post {
                    Toast.makeText(context, nativeStatus, Toast.LENGTH_LONG).show()
                    TelegramSession.connect(apiId, apiHash)
                }
            } catch (error: Throwable) {
                mainHandler.post {
                    showError(context, "Falha ao carregar TDLib", error)
                }
            }
        }.start()
    }

    private fun onStatus(context: Context, status: String) {
        when (status) {
            "WAIT_PHONE" -> promptOnce(
                context,
                status,
                "Telefone do Telegram",
                "Informe o telefone com código do país, por exemplo +55..."
            ) { TelegramSession.submitPhoneNumber(it) }

            "WAIT_CODE" -> promptOnce(
                context,
                status,
                "Código de confirmação",
                "Digite o código enviado pelo Telegram."
            ) { TelegramSession.submitCode(it) }

            "WAIT_PASSWORD" -> promptOnce(
                context,
                status,
                "Senha de duas etapas",
                "Digite a senha 2FA da sua conta Telegram."
            ) { TelegramSession.submitPassword(it) }

            "WAIT_EMAIL" -> promptOnce(
                context,
                status,
                "E-mail de autenticação",
                "O Telegram solicitou um endereço de e-mail."
            ) { TelegramSession.submitEmailAddress(it) }

            "WAIT_EMAIL_CODE" -> promptOnce(
                context,
                status,
                "Código do e-mail",
                "Digite o código recebido por e-mail."
            ) { TelegramSession.submitEmailCode(it) }

            "READY" -> {
                lastPrompt = ""
                Toast.makeText(
                    context,
                    "Telegram conectado. Abra a extensão para carregar o grupo de teste.",
                    Toast.LENGTH_LONG
                ).show()
            }

            "WAIT_OTHER_DEVICE" -> Toast.makeText(
                context,
                "Confirme o acesso no outro dispositivo Telegram.",
                Toast.LENGTH_LONG
            ).show()

            else -> {
                if (
                    status.startsWith("Falha") ||
                    status.startsWith("Erro") ||
                    status.startsWith("Telegram (") ||
                    status.startsWith("Código recusado") ||
                    status.startsWith("Senha recusada")
                ) {
                    Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun promptOnce(
        context: Context,
        state: String,
        title: String,
        message: String,
        submit: (String) -> Unit
    ) {
        if (lastPrompt == state) return
        lastPrompt = state
        val input = EditText(context).apply {
            setSingleLine(true)
            inputType = if (state == "WAIT_PASSWORD") {
                android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
            hint = when (state) {
                "WAIT_PHONE" -> "+55..."
                "WAIT_CODE", "WAIT_EMAIL_CODE" -> "Código"
                "WAIT_PASSWORD" -> "Senha 2FA"
                else -> "E-mail"
            }
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Continuar") { _, _ ->
                lastPrompt = ""
                submit(input.text.toString())
            }
            .setNegativeButton("Cancelar") { _, _ -> lastPrompt = "" }
            .create()
        dialog.setOnCancelListener { lastPrompt = "" }
        dialog.show()
    }

    private fun showError(context: Context, title: String, error: Throwable) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton("OK", null)
            .show()
    }

    private const val GITHUB_REPOSITORY = "Pablopinheiroo/ninjatv-telegram"
    private const val CREDENTIALS_FILE = "telegram-credentials.json"
}
