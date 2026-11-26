import CryptoKit
import Foundation
import Security

/// 玩家身份档案与分渠道凭据统一保存在 Keychain；UserDefaults 仅保存当前激活身份 UUID 与界面偏好。
enum AccountCredentialStore {
    private static let service = "dev.0xsr.maimaidata.accounts"
    private static let identitiesV3Key = "player_identities_v3"
    private static let profilesV2Key = "score_profiles_v2"
    private static let legacyAccountsKey = "saved_accounts"
    private static let selfHostedEndpointKey = "self_hosted_endpoint_v1"

    struct LoadedIdentities {
        let identities: [PlayerIdentity]
        let activeIdentityId: String?
        let loadFailed: Bool
    }

    private static func load(_ key: String) -> String {
        guard let data = loadData(key),
              let value = String(data: data, encoding: .utf8) else { return "" }
        return value
    }

    private static func delete(_ key: String) {
        let query = baseQuery(key)
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - Self-Hosted Endpoint Configuration

    static func deleteSelfHostedEndpointConfig() {
        delete(selfHostedEndpointKey)
    }

    // MARK: - Split Per-Channel Secrets

    static func loadSelfHostedSecret(for identityId: String) -> SelfHostedSecret {
        guard let data = loadData(selfHostedSecretKey(for: identityId)),
              let decoded = try? JSONDecoder().decode(SelfHostedSecret.self, from: data) else {
            return SelfHostedSecret()
        }
        return decoded
    }

    @discardableResult
    static func saveSelfHostedSecret(_ secret: SelfHostedSecret, for identityId: String) -> Bool {
        guard let data = try? JSONEncoder().encode(secret) else { return false }
        return saveData(data, key: selfHostedSecretKey(for: identityId))
    }

    static func deleteSelfHostedSecret(for identityId: String) {
        delete(selfHostedSecretKey(for: identityId))
    }

    static func loadLXNSSecret(for identityId: String) -> LXNSSecret {
        guard let data = loadData(lxnsSecretKey(for: identityId)),
              let decoded = try? JSONDecoder().decode(LXNSSecret.self, from: data) else {
            return LXNSSecret()
        }
        return decoded
    }

    @discardableResult
    static func saveLXNSSecret(_ secret: LXNSSecret, for identityId: String) -> Bool {
        guard let data = try? JSONEncoder().encode(secret) else { return false }
        return saveData(data, key: lxnsSecretKey(for: identityId))
    }

    static func deleteLXNSSecret(for identityId: String) {
        delete(lxnsSecretKey(for: identityId))
    }

    static func loadDivingFishSecret(for identityId: String) -> DivingFishSecret {
        guard let data = loadData(divingFishSecretKey(for: identityId)),
              let decoded = try? JSONDecoder().decode(DivingFishSecret.self, from: data) else {
            return DivingFishSecret()
        }
        return decoded
    }

    @discardableResult
    static func saveDivingFishSecret(_ secret: DivingFishSecret, for identityId: String) -> Bool {
        guard let data = try? JSONEncoder().encode(secret) else { return false }
        return saveData(data, key: divingFishSecretKey(for: identityId))
    }

    static func deleteDivingFishSecret(for identityId: String) {
        delete(divingFishSecretKey(for: identityId))
    }

    static func deleteAllChannelSecrets(for identityId: String) {
        deleteSelfHostedSecret(for: identityId)
        deleteLXNSSecret(for: identityId)
        deleteDivingFishSecret(for: identityId)
    }

    // MARK: - Player Identities v3 (+ Mechanical v2 / Legacy Upgrade)

    @discardableResult
    static func saveIdentities(_ identities: [PlayerIdentity]) -> Bool {
        guard let data = try? JSONEncoder().encode(identities) else { return false }
        return saveData(data, key: identitiesV3Key)
    }

    static func resetIdentitiesAfterLoadFailure(defaults: UserDefaults = .standard) {
        delete(identitiesV3Key)
        defaults.removeObject(forKey: PreferenceKeys.activeIdentityId)
    }

    static func decodeIdentitiesLossy(from data: Data) -> [PlayerIdentity]? {
        if let decoded = try? JSONDecoder().decode([PlayerIdentity].self, from: data) {
            return decoded
        }
        guard let rawArray = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else {
            return nil
        }
        if rawArray.isEmpty {
            return []
        }
        var recovered: [PlayerIdentity] = []
        let decoder = JSONDecoder()
        for item in rawArray {
            guard JSONSerialization.isValidJSONObject(item),
                  let elemData = try? JSONSerialization.data(withJSONObject: item),
                  let identity = try? decoder.decode(PlayerIdentity.self, from: elemData) else {
                continue
            }
            recovered.append(identity)
        }
        return recovered.isEmpty ? nil : recovered
    }

    static func loadOrMigrateIdentities(
        defaults: UserDefaults = .standard,
        appSupportDir: URL
    ) -> LoadedIdentities {
        let storedActiveId = defaults.string(forKey: PreferenceKeys.activeIdentityId)?.lowercased()

        switch loadDataResult(identitiesV3Key) {
        case .found(let existingData):
            if let existingIdentities = decodeIdentitiesLossy(from: existingData) {
                let validActiveId = existingIdentities.contains(where: { $0.id == storedActiveId })
                    ? storedActiveId
                    : existingIdentities.first?.id
                if validActiveId != storedActiveId {
                    defaults.set(validActiveId, forKey: PreferenceKeys.activeIdentityId)
                }
                return LoadedIdentities(
                    identities: existingIdentities,
                    activeIdentityId: validActiveId,
                    loadFailed: false
                )
            }
            // Existing v3 payload failed to decode — engage write-guard latch so we never wipe saved data.
            return LoadedIdentities(identities: [], activeIdentityId: nil, loadFailed: true)
        case .failed:
            // Keychain unavailable (e.g. device locked) — engage write-guard latch.
            return LoadedIdentities(identities: [], activeIdentityId: nil, loadFailed: true)
        case .notFound:
            break
        }

        // Check mechanical 1:1 upgrade from v2 `score_profiles_v2`
        switch loadDataResult(profilesV2Key) {
        case .found(let v2Data):
            guard let v2Profiles = try? JSONDecoder().decode([ScoreProfile].self, from: v2Data) else {
                return LoadedIdentities(identities: [], activeIdentityId: nil, loadFailed: true)
            }
            let v2ActiveId = defaults.string(forKey: PreferenceKeys.activeProfileId)?.lowercased()
            var secretsById: [UUID: ScoreProfileSecret] = [:]
            for p in v2Profiles {
                switch loadLegacyV2SecretResult(for: p.id) {
                case .found(let secret):
                    secretsById[p.id] = secret
                case .notFound:
                    secretsById[p.id] = ScoreProfileSecret()
                case .failed:
                    // Secret existed in Keychain but could not be read/decoded — abort upgrade instead of losing credentials.
                    return LoadedIdentities(identities: [], activeIdentityId: nil, loadFailed: true)
                }
            }
            let nowMs = Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
            let upgraded = ScoreSyncEngine.upgradeLegacyProfiles(
                v2Profiles,
                secretsById: secretsById,
                nowEpochMs: nowMs
            )

            for entry in upgraded {
                let id = entry.identity.id
                if let lxns = entry.lxns {
                    guard saveLXNSSecret(lxns, for: id) else {
                        return LoadedIdentities(identities: [], activeIdentityId: nil, loadFailed: true)
                    }
                }
                if let df = entry.df {
                    guard saveDivingFishSecret(df, for: id) else {
                        return LoadedIdentities(identities: [], activeIdentityId: nil, loadFailed: true)
                    }
                }
                if let parsedUUID = UUID(uuidString: id) {
                    let oldRecordsURL = legacyProfileRecordsFileURL(for: parsedUUID, in: appSupportDir)
                    let newRecordsURL = recordsFileURL(for: id, in: appSupportDir)
                    if FileManager.default.fileExists(atPath: oldRecordsURL.path),
                       !FileManager.default.fileExists(atPath: newRecordsURL.path) {
                        try? FileManager.default.copyItem(at: oldRecordsURL, to: newRecordsURL)
                    }
                }
            }

            let identities = upgraded.map(\.identity)
            guard saveIdentities(identities) else {
                return LoadedIdentities(identities: [], activeIdentityId: nil, loadFailed: true)
            }
            let validActiveId = identities.contains(where: { $0.id == v2ActiveId })
                ? v2ActiveId
                : identities.first?.id
            defaults.set(validActiveId, forKey: PreferenceKeys.activeIdentityId)

            // Clean up migrated v2 slots and records files once v3 persistence succeeded.
            delete(profilesV2Key)
            defaults.removeObject(forKey: PreferenceKeys.activeProfileId)
            for p in v2Profiles {
                delete(legacyProfileSecretKey(for: p.id))
                let oldRecordsURL = legacyProfileRecordsFileURL(for: p.id, in: appSupportDir)
                try? FileManager.default.removeItem(at: oldRecordsURL)
            }

            return LoadedIdentities(
                identities: identities,
                activeIdentityId: validActiveId,
                loadFailed: false
            )
        case .failed:
            return LoadedIdentities(identities: [], activeIdentityId: nil, loadFailed: true)
        case .notFound:
            break
        }

        // One-time migration from v1 single-provider Diving-Fish UserDefaults/Keychain
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
        var secretsById: [UUID: ScoreProfileSecret] = [:]
        for item in migrated.entries {
            secretsById[item.profile.id] = item.secret
        }
        let nowMs = Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
        let upgraded = ScoreSyncEngine.upgradeLegacyProfiles(
            migrated.entries.map(\.profile),
            secretsById: secretsById,
            nowEpochMs: nowMs
        )

        for entry in upgraded {
            let id = entry.identity.id
            if let df = entry.df {
                guard saveDivingFishSecret(df, for: id) else {
                    return LoadedIdentities(identities: [], activeIdentityId: nil, loadFailed: true)
                }
            }
            if let uname = entry.identity.divingFish?.username, !uname.isEmpty {
                let oldRecordsURL = appSupportDir.appendingPathComponent("records_\(uname.sha256Hex).json")
                let newRecordsURL = recordsFileURL(for: id, in: appSupportDir)
                if FileManager.default.fileExists(atPath: oldRecordsURL.path),
                   !FileManager.default.fileExists(atPath: newRecordsURL.path) {
                    try? FileManager.default.copyItem(at: oldRecordsURL, to: newRecordsURL)
                }
            }
        }

        let identities = upgraded.map(\.identity)
        if !saveIdentities(identities) {
            return LoadedIdentities(identities: [], activeIdentityId: nil, loadFailed: true)
        }
        let activeId = migrated.activeProfileId?.uuidString.lowercased()
        defaults.set(activeId, forKey: PreferenceKeys.activeIdentityId)

        defaults.removeObject(forKey: PreferenceKeys.savedAccounts)
        defaults.removeObject(forKey: PreferenceKeys.username)
        defaults.removeObject(forKey: PreferenceKeys.password)
        defaults.removeObject(forKey: PreferenceKeys.cookie)
        defaults.removeObject(forKey: PreferenceKeys.dfNickname)
        delete(legacyAccountsKey)
        delete(PreferenceKeys.password)
        delete(PreferenceKeys.cookie)

        return LoadedIdentities(identities: identities, activeIdentityId: activeId, loadFailed: false)
    }

    static func recordsFileURL(for identityId: String, in appSupportDir: URL) -> URL {
        appSupportDir.appendingPathComponent("records_identity_\(identityId.lowercased()).json")
    }

    static func legacyProfileRecordsFileURL(for identityId: String, in appSupportDir: URL) -> URL {
        appSupportDir.appendingPathComponent("records_profile_\(identityId.lowercased()).json")
    }

    static func deleteLegacyProfileSecret(for identityId: String) {
        delete("profile_secret_\(identityId.lowercased())")
    }

    private static func legacyProfileRecordsFileURL(for profileId: UUID, in appSupportDir: URL) -> URL {
        legacyProfileRecordsFileURL(for: profileId.uuidString, in: appSupportDir)
    }

    private static func legacyProfileSecretKey(for profileId: UUID) -> String {
        "profile_secret_\(profileId.uuidString.lowercased())"
    }

    private enum SecretLoadResult {
        case found(ScoreProfileSecret)
        case notFound
        case failed
    }

    private static func loadLegacyV2SecretResult(for profileId: UUID) -> SecretLoadResult {
        let key = legacyProfileSecretKey(for: profileId)
        switch loadDataResult(key) {
        case .notFound:
            return .notFound
        case .failed:
            return .failed
        case .found(let data):
            guard let decoded = try? JSONDecoder().decode(ScoreProfileSecret.self, from: data) else {
                return .failed
            }
            return .found(decoded)
        }
    }

    /// Pure migration builder exposed for unit testing (legacy v1 -> v2 compatibility).
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

    private static func selfHostedSecretKey(for identityId: String) -> String {
        "identity_sh_\(identityId.lowercased())"
    }

    private static func lxnsSecretKey(for identityId: String) -> String {
        "identity_lxns_\(identityId.lowercased())"
    }

    private static func divingFishSecretKey(for identityId: String) -> String {
        "identity_df_\(identityId.lowercased())"
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
