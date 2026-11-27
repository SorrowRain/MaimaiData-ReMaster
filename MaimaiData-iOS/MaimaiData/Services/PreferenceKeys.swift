import Foundation

/// UserDefaults / Keychain key names used by DataStore.
enum PreferenceKeys {
    static let favorites = "favorite_ids"
    static let searchHistory = "search_history"
    static let activeProfileId = "active_score_profile_id"
    static let activeIdentityId = "active_player_identity_id"
    static let serverRegion = "server_region"
    static let cnCatalogSync = "cn_catalog_sync_ts"
    static let zetarakuSync = "zetaraku_sync_ts"
    static let chartStatsSync = "chart_stats_sync_ts"
    static let unknownSongCatalogRefresh = "unknown_song_catalog_refresh_ts"
    static let showAlias = "enable_show_alias"
    static let aliasSearch = "enable_alias_search"
    static let charterSearch = "enable_charter_search"
    static let useDFNickname = "use_df_nickname"
    static let customNickname = "custom_nickname"
    static let lastVersionCheck = "last_version_check"
    static let lastVersionCheckIntl = "last_version_check_intl"
    static let lastVersionCheckJp = "last_version_check_jp"
    static let lastVersionCheckScopeId = "last_version_check_scope_id"
    static let lastVersionCheckIntlScopeId = "last_version_check_intl_scope_id"
    static let lastVersionCheckJpScopeId = "last_version_check_jp_scope_id"
    static let lastLevelCheck = "last_level_check"
    static let appUpdateCheck = "app_update_check_ts"
    static let ignoredAppUpdateVersion = "ignored_app_update_version"

    // Legacy single-provider keys retained strictly for one-time migration
    static let savedAccounts = "saved_accounts"
    static let username = "df_username"
    static let password = "df_password"
    static let cookie = "df_cookie"
    static let dfNickname = "df_nickname"
}

extension PreferenceKeys {
    /// Default values for boolean settings (registered once at launch).
    static func registerDefaults(in defaults: UserDefaults) {
        defaults.register(defaults: [
            showAlias: true,
            aliasSearch: true,
            charterSearch: false,
            useDFNickname: true
        ])
    }
}
