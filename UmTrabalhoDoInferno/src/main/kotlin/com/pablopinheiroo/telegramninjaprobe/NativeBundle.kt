package com.pablopinheiroo.telegramninjaprobe

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Process
import java.io.File

/**
 * TDLib native libraries are packaged as raw resources in the CS3 plugin.
 * Extract them into private app storage and load them by absolute path.
 */
object NativeBundle {
    private const val RESOURCE_PACKAGE = "com.pablopinheiroo.telegramninjaprobe"

    @Volatile
    private var loadedAbi: String? = null

    @Synchronized
    fun ensureLoaded(context: Context, resources: Resources?): String {
        loadedAbi?.let { return "TDLib nativa já carregada para $it." }

        val pluginResources = resources
            ?: throw IllegalStateException("O pacote de teste não disponibilizou os recursos nativos.")

        val manifestId = pluginResources.getIdentifier(
            "native_manifest",
            "raw",
            RESOURCE_PACKAGE
        )
        if (manifestId == 0) {
            throw IllegalStateException("Manifesto das bibliotecas nativas não encontrado no pacote.")
        }

        val entries = pluginResources.openRawResource(manifestId)
            .bufferedReader()
            .useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.trim().split("|")
                    if (parts.size == 3) NativeEntry(parts[0], parts[1], parts[2]) else null
                }.toList()
            }

        if (entries.isEmpty()) {
            throw IllegalStateException("O manifesto nativo está vazio ou inválido.")
        }

        val processIs64Bit = Process.is64Bit()
        val processAbis = Build.SUPPORTED_ABIS.filter { abi ->
            if (processIs64Bit) {
                abi == "arm64-v8a" || abi == "x86_64"
            } else {
                abi == "armeabi-v7a" || abi == "x86"
            }
        }

        val selectedAbi = processAbis.firstOrNull { abi ->
            entries.any { it.abi == abi }
        } ?: throw IllegalStateException(
            "Este aparelho não possui ABI incluída no teste. ABIs do aparelho: " +
                Build.SUPPORTED_ABIS.joinToString()
        )

        val selected = entries.filter { it.abi == selectedAbi }
        if (selected.none { it.fileName == "libtdjni.so" }) {
            throw IllegalStateException("libtdjni.so não foi incluída para $selectedAbi.")
        }

        val nativeDirectory = File(
            context.noBackupFilesDir,
            "telegramninja/native/$selectedAbi"
        )
        if (!nativeDirectory.exists() && !nativeDirectory.mkdirs()) {
            throw IllegalStateException("Não foi possível criar a pasta privada das bibliotecas.")
        }

        // Load shared dependencies before the JNI bridge.
        val ordered = selected.sortedWith(
            compareBy<NativeEntry> { if (it.fileName == "libtdjni.so") 1 else 0 }
                .thenBy { it.fileName }
        )

        for (entry in ordered) {
            require(entry.fileName.matches(Regex("^[A-Za-z0-9_.-]+\\.so$"))) {
                "Nome de biblioteca inválido no manifesto."
            }

            val resourceId = pluginResources.getIdentifier(
                entry.resourceName,
                "raw",
                RESOURCE_PACKAGE
            )
            if (resourceId == 0) {
                throw IllegalStateException("Biblioteca ${entry.fileName} ausente do pacote.")
            }

            val target = File(nativeDirectory, entry.fileName)
            if (!target.exists() || target.length() == 0L) {
                val temporary = File(nativeDirectory, entry.fileName + ".tmp")
                pluginResources.openRawResource(resourceId).use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                if (!temporary.renameTo(target)) {
                    temporary.delete()
                    throw IllegalStateException("Não foi possível preparar ${entry.fileName}.")
                }
            }

            target.setReadable(true, false)
            target.setWritable(false, false)

            try {
                System.load(target.absolutePath)
            } catch (error: UnsatisfiedLinkError) {
                throw IllegalStateException(
                    "Falha ao carregar ${entry.fileName} ($selectedAbi): ${error.message}",
                    error
                )
            }
        }

        loadedAbi = selectedAbi
        return "Bibliotecas TDLib carregadas com sucesso ($selectedAbi)."
    }

    private data class NativeEntry(
        val abi: String,
        val resourceName: String,
        val fileName: String
    )
}
