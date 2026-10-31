package com.pablopinheiroo.telegramninjaprobe

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TelegramProbePlugin : Plugin() {
    override fun load(context: Context) {
        TelegramSession.initialize(context, resources)
        TelegramProbeSettings.initialize(resources)
        openSettings = { settingsContext ->
            TelegramProbeSettings.open(settingsContext)
        }
        registerMainAPI(TelegramProbeProvider())
    }
}
