package com.pablopinheiroo.telegramninjaprobe

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper

object TelegramProbeSettings {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeContext: Context? = null
    @Volatile private var pluginResources: android.content.res.Resources? = null
    @Volatile private var lastPrompt = ""

    fun initialize(resources: android.content.res.Resources?) {
        pluginResources = resources
    }

    fun open(context: Context) {
        activeContext = context
        TelegramSession.setStatusListener { status ->
            mainHandler.post { onStatus(context, status) }
        }

        val savedId = TelegramSession.savedApiId()
        val savedHash = TelegramSession.savedApiHash()
        if (savedId.isBlank() || savedHash.isBlank()) {
            showCredentials(context, savedId, savedHash)
        } else {
            AlertDialog.Builder(context)
                .setTitle("TelegramNinja — teste")
                .setMessage(
                    "Grupo de teste: t.me/umtrabalhodoinferno\n\n" +
                        "Status: ${TelegramSession.status()}\n\n" +
                        "A primeira conexão solicita telefone, código e, se ativada, senha 2FA. " +
                        "A sessão será salva criptografada neste aparelho."
                )
                .setPositiveButton("Conectar / retomar") { _, _ ->
                    startConnection(context, savedId, savedHash, resume = true)
                }
                .setNeutralButton("Alterar API ID / Hash") { _, _ ->
                    showCredentials(context, savedId, savedHash)
                }
                .setNegativeButton("Fechar", null)
                .show()
        }
    }

    private fun showCredentials(context: Context, oldId: String, oldHash: String) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val idInput = EditText(context).apply {
            hint = "API ID (número)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(oldId)
        }
        val hashInput = EditText(context).apply {
            hint = "API Hash"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(oldHash)
        }
        layout.addView(TextView(context).apply { text = "Dados obtidos em my.telegram.org/apps" })
        layout.addView(idInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout.addView(hashInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        AlertDialog.Builder(context)
            .setTitle("Conectar ao Telegram")
            .setMessage("Os dados serão guardados criptografados no Android. Não são enviados ao GitHub.")
            .setView(layout)
            .setPositiveButton("Salvar e conectar") { _, _ ->
                startConnection(context, idInput.text.toString(), hashInput.text.toString(), resume = false)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startConnection(context: Context, apiId: String, apiHash: String, resume: Boolean) {
        lastPrompt = ""
        Toast.makeText(context, "Preparando biblioteca TDLib...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val nativeStatus = NativeBundle.ensureLoaded(context.applicationContext, pluginResources)
                mainHandler.post {
                    Toast.makeText(context, nativeStatus, Toast.LENGTH_LONG).show()
                    if (resume) TelegramSession.resumeIfSaved()
                    else TelegramSession.connect(apiId, apiHash)
                }
            } catch (error: Throwable) {
                mainHandler.post {
                    AlertDialog.Builder(context)
                        .setTitle("Falha no teste TDLib")
                        .setMessage(error.message ?: error.javaClass.simpleName)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun onStatus(context: Context, status: String) {
        when (status) {
            "WAIT_PHONE" -> promptOnce(context, status, "Telefone do Telegram", "Informe o telefone com código do país, por exemplo +55...") {
                TelegramSession.submitPhoneNumber(it)
            }
            "WAIT_CODE" -> promptOnce(context, status, "Código de confirmação", "Digite o código enviado pelo Telegram.") {
                TelegramSession.submitCode(it)
            }
            "WAIT_PASSWORD" -> promptOnce(context, status, "Senha de duas etapas", "Digite a senha 2FA da sua conta Telegram.") {
                TelegramSession.submitPassword(it)
            }
            "WAIT_EMAIL" -> promptOnce(context, status, "E-mail de autenticação", "O Telegram solicitou um endereço de e-mail.") {
                TelegramSession.submitEmailAddress(it)
            }
            "WAIT_EMAIL_CODE" -> promptOnce(context, status, "Código do e-mail", "Digite o código recebido por e-mail.") {
                TelegramSession.submitEmailCode(it)
            }
            "READY" -> {
                lastPrompt = ""
                Toast.makeText(context, "Telegram conectado. Abra a extensão para carregar o grupo de teste.", Toast.LENGTH_LONG).show()
            }
            "WAIT_OTHER_DEVICE" -> Toast.makeText(
                context,
                "Confirme o acesso no outro dispositivo Telegram.",
                Toast.LENGTH_LONG
            ).show()
            else -> {
                if (status.startsWith("Falha") || status.startsWith("Erro") ||
                    status.startsWith("Telegram (") || status.startsWith("Código recusado") ||
                    status.startsWith("Senha recusada")) {
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
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
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
}
