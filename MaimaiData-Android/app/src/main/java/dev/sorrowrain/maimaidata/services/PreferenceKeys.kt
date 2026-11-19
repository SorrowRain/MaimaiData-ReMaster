package dev.sorrowrain.maimaidata.services

import android.content.SharedPreferences

/**
 * SharedPreferences & credential key names used by [DataStore] and [AccountCredentialStore].
 * Matches iOS `PreferenceKeys.swift` 1:1.
 */
object PreferenceKeys {
    const val PREFS_NAME = "maimaidata_prefs"

    const val favorites = "favorite_ids"
    const val searchHistory = "search_history"
    const val activeProfileId = "active_score_profile_id"
    const val serverRegion = "server_region"
    const val cnCatalogSync = "cn_catalog_sync_ts"
    const val zetarakuSync = "zetaraku_sync_ts"
    const val chartStatsSync = "chart_stats_sync_ts"
    const val showAlias = "enable_show_alias"
    const val aliasSearch = "enable_alias_search"
    const val charterSearch = "enable_charter_search"
    const val useDFNickname = "use_df_nickname"
    const val customNickname = "custom_nickname"
    const val lastVersionCheck = "last_version_check"
    const val lastVersionCheckIntl = "last_version_check_intl"
    const val lastVersionCheckJp = "last_version_check_jp"
    const val lastLevelCheck = "last_level_check"
    const val appUpdateCheck = "app_update_check_ts"
    const val ignoredAppUpdateVersion = "ignored_app_update_version"

    // Legacy single-provider keys retained strictly for one-time migration & test parity
    const val savedAccounts = "saved_accounts"
    const val username = "df_username"
    const val password = "df_password"
    const val cookie = "df_cookie"
    const val dfNickname = "df_nickname"

    fun ensureDefaultBooleans(prefs: SharedPreferences) {
        val editor = prefs.edit()
        var changed = false
        if (!prefs.contains(showAlias)) {
            editor.putBoolean(showAlias, true)
            changed = true
        }
        if (!prefs.contains(aliasSearch)) {
            editor.putBoolean(aliasSearch, true)
            changed = true
        }
        if (!prefs.contains(charterSearch)) {
            editor.putBoolean(charterSearch, false)
            changed = true
        }
        if (!prefs.contains(useDFNickname)) {
            editor.putBoolean(useDFNickname, true)
            changed = true
        }
        if (changed) {
            editor.apply()
        }
    }
}
