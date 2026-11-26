package dev.sorrowrain.maimaidata.services

import dev.sorrowrain.maimaidata.BuildConfig
import dev.sorrowrain.maimaidata.models.SelfHostedEndpointConfig
import java.util.Locale

object SyncServiceConfig {
    fun builtIn(): SelfHostedEndpointConfig =
        resolve(
            hostOrUrl = BuildConfig.SYNC_SERVICE_HOST,
            accessKey = BuildConfig.SYNC_SERVICE_ACCESS_KEY
        )

    fun resolve(hostOrUrl: String, accessKey: String): SelfHostedEndpointConfig {
        val cleanedHost = hostOrUrl.trim()
        val cleanedKey = accessKey.trim()
        if (cleanedHost.isEmpty() || cleanedKey.isEmpty()) {
            return SelfHostedEndpointConfig()
        }
        val baseUrl = if (cleanedHost.contains("://")) {
            cleanedHost
        } else {
            val hostOnly = cleanedHost.substringBefore(':')
            val lower = hostOnly.lowercase(Locale.ROOT)
            val scheme = if (lower == "localhost" || lower == "127.0.0.1") "http" else "https"
            "$scheme://$cleanedHost"
        }
        return SelfHostedEndpointConfig(serverBaseURL = baseUrl, accessKey = cleanedKey)
    }
}
