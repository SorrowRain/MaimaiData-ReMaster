import CryptoKit
import Foundation
import Security

/// 成绩档案凭据与令牌统一保存在 Keychain；UserDefaults 仅保存当前激活档案 UUID 与界面偏好。
enum AccountCredentialStore {
    private static let service = "dev.0xsr.maimaidata.accounts"
    private static let profilesKey = "score_profiles_v2"
    private static let legacyAccountsKey = "saved_accounts"

    private static func load(_ key: String) -> String {
        guard let data = loadData(key),
              let value = String(data: data, encoding: .utf8) else { return "" }
        return value
    }

    private static func delete(_ key: String) {
        let query = baseQuery(key)
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - Multi-Provider Score Profiles

    static func loadSecret(for profileId: UUID) -> ScoreProfileSecret {
        let key = secretKey(for: profileId)
        guard let data = loadData(key),
              let decoded = try? JSONDecoder().decode(ScoreProfileSecret.self, from: data) else {
            return ScoreProfileSecret()
        }
        return decoded
    }

    @discardableResult
    static func saveSecret(_ secret: ScoreProfileSecret, for profileId: UUID) -> Bool {
        guard let data = try? JSONEncoder().encode(secret) else { return false }
        return saveData(data, key: secretKey(for: profileId))
    }

    static func deleteSecret(for profileId: UUID) {
        delete(secretKey(for: profileId))
    }

    @discardableResult
    static func saveProfiles(_ profiles: [ScoreProfile]) -> Bool {
        guard let data = try? JSONEncoder().encode(profiles) else { return false }
        return saveData(data, key: profilesKey)
    }

    static func loadOrMigrateProfiles(
        defaults: UserDefaults = .standard,
        appSupportDir: URL
    ) -> (profiles: [ScoreProfile], activeProfileId: UUID?) {
        let storedActiveId = defaults.string(forKey: PreferenceKeys.activeProfileId)
            .flatMap(UUID.init(uuidString:))

        switch loadDataResult(profilesKey) {
        case .found(let existingData):
            if let existingProfiles = try? JSONDecoder().decode([ScoreProfile].self, from: existingData) {
                let validActiveId = existingProfiles.contains(where: { $0.id == storedActiveId })
                    ? storedActiveId
                    : existingProfiles.first?.id
                if validActiveId != storedActiveId {
                    defaults.set(validActiveId?.uuidString, forKey: PreferenceKeys.activeProfileId)
                }
                return (existingProfiles, validActiveId)
            }
            // Existing profiles payload could not be decoded — never overwrite or wipe saved data.
            return ([], nil)
        case .failed:
            // Keychain temporarily unavailable (e.g. locked) — never overwrite or wipe saved data.
            return ([], nil)
        case .notFound:
            break
        }

        // One-time migration from legacy Diving-Fish single-account storage
        let legacyAccountsData = loadData(legacyAccountsKey) ?? defaults.data(forKey: PreferenceKeys.savedAccounts)
        let legacyAccounts = legacyAccountsData
            .flatMap { try? JSONDecoder().decode([SavedAccount].self, from: $0) } ?? []
        let legacyUsername = defaults.string(forKey: PreferenceKeys.username) ?? ""
        let legacyPassword = load(PreferenceKeys.password)
        let legacyCookie = load(PreferenceKeys.cookie)
        let legacyNickname = defaults.string(forKey: PreferenceKeys.dfNickname) ?? ""

        let migrated = buildMigratedProfiles(
            legacyAccounts: legacyAccounts,
            currentUsername: legacyUsername,
            currentPassword: legacyPassword,
            currentCookie: legacyCookie,
            currentNickname: legacyNickname
        )

        for item in migrated.entries {
            saveSecret(item.secret, for: item.profile.id)
            let oldRecordsURL = appSupportDir.appendingPathComponent("records_\(item.profile.username.sha256Hex).json")
            let newRecordsURL = recordsFileURL(for: item.profile.id, in: appSupportDir)
            if FileManager.default.fileExists(atPath: oldRecordsURL.path),
               !FileManager.default.fileExists(atPath: newRecordsURL.path) {
                try? FileManager.default.copyItem(at: oldRecordsURL, to: newRecordsURL)
            }
        }

        let profiles = migrated.entries.map(\.profile)
        saveProfiles(profiles)
        defaults.set(migrated.activeProfileId?.uuidString, forKey: PreferenceKeys.activeProfileId)

        // Clean up legacy UserDefaults & Keychain slots after migration completes
        defaults.removeObject(forKey: PreferenceKeys.savedAccounts)
        defaults.removeObject(forKey: PreferenceKeys.username)
        defaults.removeObject(forKey: PreferenceKeys.password)
        defaults.removeObject(forKey: PreferenceKeys.cookie)
        defaults.removeObject(forKey: PreferenceKeys.dfNickname)
        delete(legacyAccountsKey)
        delete(PreferenceKeys.password)
        delete(PreferenceKeys.cookie)

        return (profiles, migrated.activeProfileId)
    }

    static func recordsFileURL(for profileId: UUID, in appSupportDir: URL) -> URL {
        appSupportDir.appendingPathComponent("records_profile_\(profileId.uuidString.lowercased()).json")
    }

    /// Pure migration builder exposed for unit testing.
    static func buildMigratedProfiles(
        legacyAccounts: [SavedAccount],
        currentUsername: String,
        currentPassword: String,
        currentCookie: String,
        currentNickname: String
    ) -> (entries: [(profile: ScoreProfile, secret: ScoreProfileSecret)], activeProfileId: UUID?) {
        var entries: [(profile: ScoreProfile, secret: ScoreProfileSecret)] = []
        var activeId: UUID?

        for acct in legacyAccounts where !acct.username.isEmpty {
            if entries.contains(where: { $0.profile.accountKey == acct.username }) { continue }
            let isCurrent = (acct.username == currentUsername)
            let id = UUID()
            let profile = ScoreProfile(
                id: id,
                provider: .divingFish,
                authMethod: .divingFishPassword,
                accountKey: acct.username,
                username: acct.username,
                nickname: isCurrent ? currentNickname : "",
                lastSyncedAt: nil
            )
            let secret = ScoreProfileSecret(
                password: acct.password.isEmpty && isCurrent ? currentPassword : acct.password,
                cookie: isCurrent ? currentCookie : ""
            )
            entries.append((profile, secret))
            if isCurrent {
                activeId = id
            }
        }

        if !currentUsername.isEmpty, activeId == nil {
            let id = UUID()
            let profile = ScoreProfile(
                id: id,
                provider: .divingFish,
                authMethod: .divingFishPassword,
                accountKey: currentUsername,
                username: currentUsername,
                nickname: currentNickname,
                lastSyncedAt: nil
            )
            let secret = ScoreProfileSecret(password: currentPassword, cookie: currentCookie)
            entries.insert((profile, secret), at: 0)
            activeId = id
        }

        return (entries, activeId)
    }

    // MARK: - Keychain Helpers

    private enum KeychainReadResult {
        case found(Data)
        case notFound
        case failed(OSStatus)
    }

    private static func secretKey(for profileId: UUID) -> String {
        "profile_secret_\(profileId.uuidString.lowercased())"
    }

    private static func loadData(_ key: String) -> Data? {
        if case .found(let data) = loadDataResult(key) {
            return data
        }
        return nil
    }

    private static func loadDataResult(_ key: String) -> KeychainReadResult {
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data {
            return .found(data)
        }
        if status == errSecItemNotFound {
            return .notFound
        }
        return .failed(status)
    }

    @discardableResult
    private static func saveData(_ data: Data, key: String) -> Bool {
        let query = baseQuery(key)
        let attributes = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecSuccess {
            return true
        }
        if status == errSecItemNotFound {
            var item = query
            item.merge(attributes) { _, new in new }
            return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
        }
        return false
    }

    private static func baseQuery(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
    }
}

extension String {
    /// 用于脱敏标识（如 Token 指纹或旧版缓存文件名），不用于密码哈希。
    var sha256Hex: String {
        SHA256.hash(data: Data(utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
