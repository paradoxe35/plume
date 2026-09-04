package me.pngwasi.plume.data

/**
 * Whether an action can actually run.
 *
 * One rule, in one place, because three screens and the desktop's launch decision all have to agree
 * on it. They did not: each demanded a key regardless of [ProviderConfig.authRequired], so a local
 * runtime — Ollama, LM Studio — that is working perfectly still reported "Setup needed", with no
 * way to make the warning go away.
 */
fun AppSettings.isReady(action: Action, keyedProviders: Set<String>): Boolean =
    isProviderReady(providerIdFor(action), keyedProviders)

/** The same rule for one named provider, which is what a list of them needs. */
fun AppSettings.isProviderReady(providerId: String, keyedProviders: Set<String>): Boolean {
    val config = providers[providerId] ?: return false
    return config.isConfigured() && (!config.authRequired || providerId in keyedProviders)
}

/** Both actions ready, which is the only state where nothing needs the user's attention. */
fun AppSettings.isFullyConfigured(keyedProviders: Set<String>): Boolean =
    Action.entries.all { isReady(it, keyedProviders) }

/** Which providers hold a usable key. Hits the platform's secret store, so call it off the UI thread. */
fun AppSettings.keyedProviders(secrets: SecretStore): Set<String> =
    providers.keys.filterTo(mutableSetOf()) {
        secrets.hasKey(it) && secrets.getKey(it).isNotBlank()
    }
