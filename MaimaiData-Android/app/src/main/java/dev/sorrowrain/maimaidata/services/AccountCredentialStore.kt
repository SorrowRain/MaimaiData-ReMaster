package dev.sorrowrain.maimaidata.services

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.sorrowrain.maimaidata.models.DivingFishSecret
import dev.sorrowrain.maimaidata.models.LXNSSecret
import dev.sorrowrain.maimaidata.models.PlayerIdentity
import dev.sorrowrain.maimaidata.models.SavedAccount
import dev.sorrowrain.maimaidata.models.ScoreAuthMethod
import dev.sorrowrain.maimaidata.models.ScoreProfile
import dev.sorrowrain.maimaidata.models.ScoreProfileSecret
import dev.sorrowrain.maimaidata.models.ScoreProvider
import dev.sorrowrain.maimaidata.models.SelfHostedEndpointConfig
import dev.sorrowrain.maimaidata.models.SelfHostedSecret
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed `AndroidKeyStore` AES-256-GCM encrypted credential store for v3
 * [PlayerIdentity] and split per-channel secret slots under `context.filesDir/credentials/`.
 * Matches iOS `AccountCredentialStore.swift` 1:1.
 */
object AccountCredentialStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "maimaidata_master_key_v1"
    private const val CREDENTIALS_DIR_NAME = "credentials"
    private const val IDENTITIES_V3_SLOT = "player_identities_v3"
    private const val PROFILES_V2_SLOT = "score_profiles_v2"
    private const val SELF_HOSTED_ENDPOINT_SLOT = "self_hosted_endpoint_v1"
    private const val PENDING_OAUTH_SLOT = "pending_lxns_oauth_v1"
    private const val GCM_TAG_BITS = 128

    @Serializable
    data class PersistedPKCESession(
        val codeVerifier: String,
        val state: String,
        val targetIdentityId: String? = null,
        val createdAtEpochMs: Long
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    data class LoadedIdentities(
        val identities: List<PlayerIdentity>,
        val activeIdentityId: String?,
        val loadFailed: Boolean
    )

    data class MigratedProfileEntry(
        val profile: ScoreProfile,
        val secret: ScoreProfileSecret
    )

    data class MigrationResult(
        val entries: List<MigratedProfileEntry>,
        val activeProfileId: String?
    )

    // Self-Hosted Endpoint Cleanup

    @Synchronized
    fun deleteSelfHostedEndpointConfig(context: Context) {
        deleteSlot(context, SELF_HOSTED_ENDPOINT_SLOT)
    }

    // Split Per-Channel Secrets

    @Synchronized
    fun loadSelfHostedSecret(context: Context, identityId: String): SelfHostedSecret {
        val raw = readEncryptedBytes(context, selfHostedSecretSlot(identityId)) ?: return SelfHostedSecret()
        return runCatching {
            json.decodeFromString<SelfHostedSecret>(raw.toString(Charsets.UTF_8))
        }.getOrDefault(SelfHostedSecret())
    }

    @Synchronized
    fun saveSelfHostedSecret(context: Context, identityId: String, secret: SelfHostedSecret): Boolean {
        val bytes = json.encodeToString(secret).toByteArray(Charsets.UTF_8)
        return writeEncryptedBytes(context, selfHostedSecretSlot(identityId), bytes)
    }

    @Synchronized
    fun deleteSelfHostedSecret(context: Context, identityId: String) {
        deleteSlot(context, selfHostedSecretSlot(identityId))
    }

    @Synchronized
    fun loadLXNSSecret(context: Context, identityId: String): LXNSSecret {
        val raw = readEncryptedBytes(context, lxnsSecretSlot(identityId)) ?: return LXNSSecret()
        return runCatching {
            json.decodeFromString<LXNSSecret>(raw.toString(Charsets.UTF_8))
        }.getOrDefault(LXNSSecret())
    }

    @Synchronized
    fun saveLXNSSecret(context: Context, identityId: String, secret: LXNSSecret): Boolean {
        val bytes = json.encodeToString(secret).toByteArray(Charsets.UTF_8)
        return writeEncryptedBytes(context, lxnsSecretSlot(identityId), bytes)
    }

    @Synchronized
    fun deleteLXNSSecret(context: Context, identityId: String) {
        deleteSlot(context, lxnsSecretSlot(identityId))
    }

    @Synchronized
    fun loadDivingFishSecret(context: Context, identityId: String): DivingFishSecret {
        val raw = readEncryptedBytes(context, divingFishSecretSlot(identityId)) ?: return DivingFishSecret()
        return runCatching {
            json.decodeFromString<DivingFishSecret>(raw.toString(Charsets.UTF_8))
        }.getOrDefault(DivingFishSecret())
    }

    @Synchronized
    fun saveDivingFishSecret(context: Context, identityId: String, secret: DivingFishSecret): Boolean {
        val bytes = json.encodeToString(secret).toByteArray(Charsets.UTF_8)
        return writeEncryptedBytes(context, divingFishSecretSlot(identityId), bytes)
    }

    @Synchronized
    fun deleteDivingFishSecret(context: Context, identityId: String) {
        deleteSlot(context, divingFishSecretSlot(identityId))
    }

    @Synchronized
    fun deleteAllChannelSecrets(context: Context, identityId: String) {
        deleteSelfHostedSecret(context, identityId)
        deleteLXNSSecret(context, identityId)
        deleteDivingFishSecret(context, identityId)
    }

    // Player Identities v3 (+ Mechanical v2 / Legacy Upgrade)

    // Pending LXNS OAuth PKCE Session

    @Synchronized
    fun savePendingOAuthSession(context: Context, session: PersistedPKCESession): Boolean {
        val bytes = json.encodeToString(session).toByteArray(Charsets.UTF_8)
        return writeEncryptedBytes(context, PENDING_OAUTH_SLOT, bytes)
    }

    @Synchronized
    fun loadPendingOAuthSession(context: Context): PersistedPKCESession? {
        val raw = readEncryptedBytes(context, PENDING_OAUTH_SLOT) ?: return null
        return runCatching {
            json.decodeFromString<PersistedPKCESession>(raw.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    @Synchronized
    fun clearPendingOAuthSession(context: Context) {
        deleteSlot(context, PENDING_OAUTH_SLOT)
    }

    @Synchronized
    fun resetIdentitiesAfterLoadFailure(context: Context, prefs: SharedPreferences) {
        deleteSlot(context, IDENTITIES_V3_SLOT)
        prefs.edit().remove(PreferenceKeys.activeIdentityId).apply()
    }

    internal fun decodeIdentitiesLossy(rawText: String): List<PlayerIdentity>? {
        val array = runCatching { json.parseToJsonElement(rawText) }.getOrNull() as? JsonArray
            ?: return null
        if (array.isEmpty()) return emptyList()
        val recovered = ArrayList<PlayerIdentity>(array.size)
        for (elem in array) {
            val obj = elem as? JsonObject ?: continue
            val idPrimitive = obj["id"] as? JsonPrimitive ?: continue
            if (!idPrimitive.isString || idPrimitive.content.isBlank()) continue
            val direct = runCatching { json.decodeFromJsonElement<PlayerIdentity>(obj) }.getOrNull()
            val decoded = direct ?: runCatching {
                val stripped = JsonObject(obj.filterKeys { it != "lastSyncMode" })
                json.decodeFromJsonElement<PlayerIdentity>(stripped)
            }.getOrNull()
            if (decoded != null) {
                recovered += decoded.copy(id = decoded.id.lowercase(Locale.ROOT))
            }
        }
        return if (recovered.isEmpty()) null else recovered
    }

    @Synchronized
    fun saveIdentities(context: Context, identities: List<PlayerIdentity>): Boolean {
        val bytes = json.encodeToString(identities).toByteArray(Charsets.UTF_8)
        return writeEncryptedBytes(context, IDENTITIES_V3_SLOT, bytes)
    }

    @Synchronized
    fun loadOrMigrateIdentities(
        context: Context,
        prefs: SharedPreferences
    ): LoadedIdentities {
        val storedActiveId = prefs.getString(PreferenceKeys.activeIdentityId, null)
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)

        // Check v3 identities first
        when (val v3Read = readEncryptedResult(context, IDENTITIES_V3_SLOT)) {
            is EncryptedReadResult.Found -> {
                val existingIdentities = decodeIdentitiesLossy(v3Read.bytes.toString(Charsets.UTF_8))
                    ?: return LoadedIdentities(identities = emptyList(), activeIdentityId = null, loadFailed = true)
                val validActiveId = if (existingIdentities.any { it.id == storedActiveId }) {
                    storedActiveId
                } else {
                    existingIdentities.firstOrNull()?.id
                }
                if (validActiveId != storedActiveId) {
                    prefs.edit().putString(PreferenceKeys.activeIdentityId, validActiveId).apply()
                }
                return LoadedIdentities(
                    identities = existingIdentities,
                    activeIdentityId = validActiveId,
                    loadFailed = false
                )
            }
            is EncryptedReadResult.Failed -> {
                return LoadedIdentities(identities = emptyList(), activeIdentityId = null, loadFailed = true)
            }
            is EncryptedReadResult.NotFound -> Unit
        }

        // Mechanical 1:1 upgrade from v2 `score_profiles_v2`
        when (val v2Read = readEncryptedResult(context, PROFILES_V2_SLOT)) {
            is EncryptedReadResult.Found -> {
                val v2Profiles = runCatching {
                    json.decodeFromString<List<ScoreProfile>>(v2Read.bytes.toString(Charsets.UTF_8))
                }.getOrNull()
                    ?: return LoadedIdentities(identities = emptyList(), activeIdentityId = null, loadFailed = true)

                val v2ActiveId = prefs.getString(PreferenceKeys.activeProfileId, null)
                    ?.takeIf { it.isNotEmpty() }
                    ?.lowercase(Locale.ROOT)
                val secretsById = HashMap<String, ScoreProfileSecret>(v2Profiles.size)
                for (profile in v2Profiles) {
                    when (val secRes = loadLegacyV2SecretResult(context, profile.id)) {
                        is SecretLoadResult.Found -> secretsById[profile.id] = secRes.secret
                        is SecretLoadResult.NotFound -> secretsById[profile.id] = ScoreProfileSecret()
                        is SecretLoadResult.Failed -> {
                            return LoadedIdentities(identities = emptyList(), activeIdentityId = null, loadFailed = true)
                        }
                    }
                }
                val upgraded = ScoreSyncEngine.upgradeLegacyProfiles(
                    legacyProfiles = v2Profiles,
                    secretsById = secretsById,
                    nowEpochMs = System.currentTimeMillis()
                )

                val appFilesDir = context.filesDir
                for (entry in upgraded) {
                    val id = entry.identity.id
                    entry.lxns?.let { lxns ->
                        if (!saveLXNSSecret(context, id, lxns)) {
                            return LoadedIdentities(identities = emptyList(), activeIdentityId = null, loadFailed = true)
                        }
                    }
                    entry.divingFish?.let { df ->
                        if (!saveDivingFishSecret(context, id, df)) {
                            return LoadedIdentities(identities = emptyList(), activeIdentityId = null, loadFailed = true)
                        }
                    }
                    val oldRecords = legacyProfileRecordsFile(appFilesDir, id)
                    val newRecords = recordsFile(appFilesDir, id)
                    if (oldRecords.exists() && !newRecords.exists()) {
                        runCatching { oldRecords.copyTo(newRecords, overwrite = false) }
                    }
                }

                val identities = upgraded.map { it.identity }
                if (!saveIdentities(context, identities)) {
                    return LoadedIdentities(identities = emptyList(), activeIdentityId = null, loadFailed = true)
                }
                val validActiveId = if (identities.any { it.id == v2ActiveId }) {
                    v2ActiveId
                } else {
                    identities.firstOrNull()?.id
                }
                prefs.edit()
                    .putString(PreferenceKeys.activeIdentityId, validActiveId)
                    .remove(PreferenceKeys.activeProfileId)
                    .apply()

                deleteSlot(context, PROFILES_V2_SLOT)
                for (profile in v2Profiles) {
                    deleteLegacyProfileSecret(context, profile.id)
                    runCatching { legacyProfileRecordsFile(appFilesDir, profile.id).delete() }
                }

                return LoadedIdentities(
                    identities = identities,
                    activeIdentityId = validActiveId,
                    loadFailed = false
                )
            }
            is EncryptedReadResult.Failed -> {
                return LoadedIdentities(identities = emptyList(), activeIdentityId = null, loadFailed = true)
            }
            is EncryptedReadResult.NotFound -> Unit
        }

        // One-time migration from legacy v1 preferences
        val legacyAccountsJson = prefs.getString(PreferenceKeys.savedAccounts, null)
        val legacyAccounts = if (!legacyAccountsJson.isNullOrEmpty()) {
            runCatching { json.decodeFromString<List<SavedAccount>>(legacyAccountsJson) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val legacyUsername = prefs.getString(PreferenceKeys.username, null).orEmpty()
        val legacyPassword = prefs.getString(PreferenceKeys.password, null).orEmpty()
        val legacyCookie = prefs.getString(PreferenceKeys.cookie, null).orEmpty()
        val legacyNickname = prefs.getString(PreferenceKeys.dfNickname, null).orEmpty()

        val migrated = buildMigratedProfiles(
            legacyAccounts = legacyAccounts,
            currentUsername = legacyUsername,
            currentPassword = legacyPassword,
            currentCookie = legacyCookie,
            currentNickname = legacyNickname
        )
        val secretsById = HashMap<String, ScoreProfileSecret>(migrated.entries.size)
        for (item in migrated.entries) {
            secretsById[item.profile.id] = item.secret
        }
        val upgraded = ScoreSyncEngine.upgradeLegacyProfiles(
            legacyProfiles = migrated.entries.map { it.profile },
            secretsById = secretsById,
            nowEpochMs = System.currentTimeMillis()
        )

        val appFilesDir = context.filesDir
        for (entry in upgraded) {
            val id = entry.identity.id
            entry.divingFish?.let { df ->
                if (!saveDivingFishSecret(context, id, df)) {
                    return LoadedIdentities(identities = emptyList(), activeIdentityId = null, loadFailed = true)
                }
            }
            val uname = entry.identity.divingFish?.username.orEmpty()
            if (uname.isNotEmpty()) {
                val oldRecords = File(appFilesDir, "records_${uname.sha256Hex()}.json")
                val newRecords = recordsFile(appFilesDir, id)
                if (oldRecords.exists() && !newRecords.exists()) {
                    runCatching { oldRecords.copyTo(newRecords, overwrite = false) }
                }
            }
        }

        val identities = upgraded.map { it.identity }
        if (!saveIdentities(context, identities)) {
            return LoadedIdentities(identities = emptyList(), activeIdentityId = null, loadFailed = true)
        }
        val activeId = migrated.activeProfileId?.lowercase(Locale.ROOT)
        prefs.edit()
            .putString(PreferenceKeys.activeIdentityId, activeId)
            .remove(PreferenceKeys.savedAccounts)
            .remove(PreferenceKeys.username)
            .remove(PreferenceKeys.password)
            .remove(PreferenceKeys.cookie)
            .remove(PreferenceKeys.dfNickname)
            .apply()

        return LoadedIdentities(
            identities = identities,
            activeIdentityId = activeId,
            loadFailed = false
        )
    }

    fun recordsFile(appFilesDir: File, identityId: String): File =
        File(appFilesDir, "records_identity_${identityId.lowercase(Locale.ROOT)}.json")

    fun legacyProfileRecordsFile(appFilesDir: File, profileId: String): File =
        File(appFilesDir, "records_profile_${profileId.lowercase(Locale.ROOT)}.json")

    fun deleteLegacyProfileSecret(context: Context, profileId: String) {
        deleteSlot(context, "profile_secret_${profileId.lowercase(Locale.ROOT)}")
    }

    private sealed class SecretLoadResult {
        data class Found(val secret: ScoreProfileSecret) : SecretLoadResult()
        data object NotFound : SecretLoadResult()
        data object Failed : SecretLoadResult()
    }

    private fun loadLegacyV2SecretResult(context: Context, profileId: String): SecretLoadResult {
        val slot = "profile_secret_${profileId.lowercase(Locale.ROOT)}"
        return when (val read = readEncryptedResult(context, slot)) {
            is EncryptedReadResult.NotFound -> SecretLoadResult.NotFound
            is EncryptedReadResult.Failed -> SecretLoadResult.Failed
            is EncryptedReadResult.Found -> {
                val decoded = runCatching {
                    json.decodeFromString<ScoreProfileSecret>(read.bytes.toString(Charsets.UTF_8))
                }.getOrNull() ?: return SecretLoadResult.Failed
                SecretLoadResult.Found(decoded)
            }
        }
    }

    /**
     * Pure JVM-testable migration builder retained for v1 -> v2 test compatibility.
     */
    fun buildMigratedProfiles(
        legacyAccounts: List<SavedAccount>,
        currentUsername: String,
        currentPassword: String,
        currentCookie: String,
        currentNickname: String
    ): MigrationResult {
        val entries = mutableListOf<MigratedProfileEntry>()
        var activeId: String? = null

        for (acct in legacyAccounts) {
            if (acct.username.isEmpty()) continue
            if (entries.any { it.profile.accountKey == acct.username }) continue
            val isCurrent = acct.username == currentUsername
            val id = UUID.randomUUID().toString().lowercase(Locale.ROOT)
            val profile = ScoreProfile(
                id = id,
                provider = ScoreProvider.DIVING_FISH,
                authMethod = ScoreAuthMethod.DIVING_FISH_PASSWORD,
                accountKey = acct.username,
                username = acct.username,
                nickname = if (isCurrent) currentNickname else "",
                lastSyncedAtEpochMs = null
            )
            val secret = ScoreProfileSecret(
                password = if (acct.password.isEmpty() && isCurrent) currentPassword else acct.password,
                cookie = if (isCurrent) currentCookie else ""
            )
            entries += MigratedProfileEntry(profile = profile, secret = secret)
            if (isCurrent) {
                activeId = id
            }
        }

        if (currentUsername.isNotEmpty() && activeId == null) {
            val id = UUID.randomUUID().toString().lowercase(Locale.ROOT)
            val profile = ScoreProfile(
                id = id,
                provider = ScoreProvider.DIVING_FISH,
                authMethod = ScoreAuthMethod.DIVING_FISH_PASSWORD,
                accountKey = currentUsername,
                username = currentUsername,
                nickname = currentNickname,
                lastSyncedAtEpochMs = null
            )
            val secret = ScoreProfileSecret(
                password = currentPassword,
                cookie = currentCookie
            )
            entries.add(0, MigratedProfileEntry(profile = profile, secret = secret))
            activeId = id
        }

        return MigrationResult(entries = entries, activeProfileId = activeId)
    }

    // AndroidKeyStore AES-256-GCM Helpers

    private fun selfHostedSecretSlot(identityId: String): String =
        "identity_sh_${identityId.lowercase(Locale.ROOT)}"

    private fun lxnsSecretSlot(identityId: String): String =
        "identity_lxns_${identityId.lowercase(Locale.ROOT)}"

    private fun divingFishSecretSlot(identityId: String): String =
        "identity_df_${identityId.lowercase(Locale.ROOT)}"

    private fun credentialsDir(context: Context): File =
        File(context.filesDir, CREDENTIALS_DIR_NAME).apply { mkdirs() }

    private fun slotFile(context: Context, slot: String): File =
        File(credentialsDir(context), "$slot.enc")

    private fun deleteSlot(context: Context, slot: String) {
        val file = slotFile(context, slot)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun writeEncryptedBytes(context: Context, slot: String, plaintext: ByteArray): Boolean {
        return runCatching {
            val encoded = encryptPayload(plaintext)
            val dest = slotFile(context, slot)
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            tmp.writeBytes(encoded)
            if (!tmp.renameTo(dest)) {
                dest.writeBytes(encoded)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    private sealed class EncryptedReadResult {
        data class Found(val bytes: ByteArray) : EncryptedReadResult()
        data object NotFound : EncryptedReadResult()
        data object Failed : EncryptedReadResult()
    }

    private fun readEncryptedBytes(context: Context, slot: String): ByteArray? =
        (readEncryptedResult(context, slot) as? EncryptedReadResult.Found)?.bytes

    private fun readEncryptedResult(context: Context, slot: String): EncryptedReadResult {
        val file = slotFile(context, slot)
        if (!file.exists()) return EncryptedReadResult.NotFound
        return runCatching {
            val blob = file.readBytes()
            if (blob.size < 14) return EncryptedReadResult.Failed
            val ivLength = blob[0].toInt() and 0xFF
            if (ivLength !in 12..16 || blob.size <= 1 + ivLength) return EncryptedReadResult.Failed
            val iv = blob.copyOfRange(1, 1 + ivLength)
            val ciphertext = blob.copyOfRange(1 + ivLength, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            EncryptedReadResult.Found(cipher.doFinal(ciphertext))
        }.getOrElse { EncryptedReadResult.Failed }
    }

    private fun encryptPayload(plaintext: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        val output = ByteArray(1 + iv.size + ciphertext.size)
        output[0] = iv.size.toByte()
        System.arraycopy(iv, 0, output, 1, iv.size)
        System.arraycopy(ciphertext, 0, output, 1 + iv.size, ciphertext.size)
        return output
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            throw IllegalStateException("AndroidKeyStore master key alias exists but could not be loaded")
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}

/** Non-password SHA-256 hex digest for token fingerprinting and legacy cache filename lookup. */
fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        sb.append(String.format(Locale.US, "%02x", b))
    }
    return sb.toString()
}
