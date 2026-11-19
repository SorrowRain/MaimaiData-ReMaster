package dev.sorrowrain.maimaidata.services

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.sorrowrain.maimaidata.models.SavedAccount
import dev.sorrowrain.maimaidata.models.ScoreAuthMethod
import dev.sorrowrain.maimaidata.models.ScoreProfile
import dev.sorrowrain.maimaidata.models.ScoreProfileSecret
import dev.sorrowrain.maimaidata.models.ScoreProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
 * Hardware-backed `AndroidKeyStore` AES-256-GCM encrypted credential store for multi-provider
 * [ScoreProfile] and [ScoreProfileSecret] entries under `context.filesDir/credentials/`.
 * Matches iOS `AccountCredentialStore.swift` 1:1.
 */
object AccountCredentialStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "maimaidata_master_key_v1"
    private const val CREDENTIALS_DIR_NAME = "credentials"
    private const val PROFILES_SLOT = "score_profiles_v2"
    private const val GCM_TAG_BITS = 128

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    data class LoadedProfiles(
        val profiles: List<ScoreProfile>,
        val activeProfileId: String?
    )

    data class MigratedProfileEntry(
        val profile: ScoreProfile,
        val secret: ScoreProfileSecret
    )

    data class MigrationResult(
        val entries: List<MigratedProfileEntry>,
        val activeProfileId: String?
    )

    @Synchronized
    fun loadSecret(context: Context, profileId: String): ScoreProfileSecret {
        val raw = readEncryptedBytes(context, secretSlot(profileId)) ?: return ScoreProfileSecret()
        return runCatching {
            json.decodeFromString<ScoreProfileSecret>(raw.toString(Charsets.UTF_8))
        }.getOrDefault(ScoreProfileSecret())
    }

    @Synchronized
    fun saveSecret(context: Context, profileId: String, secret: ScoreProfileSecret) {
        val bytes = json.encodeToString(secret).toByteArray(Charsets.UTF_8)
        writeEncryptedBytes(context, secretSlot(profileId), bytes)
    }

    @Synchronized
    fun deleteSecret(context: Context, profileId: String) {
        deleteSlot(context, secretSlot(profileId))
    }

    @Synchronized
    fun saveProfiles(context: Context, profiles: List<ScoreProfile>) {
        val bytes = json.encodeToString(profiles).toByteArray(Charsets.UTF_8)
        writeEncryptedBytes(context, PROFILES_SLOT, bytes)
    }

    @Synchronized
    fun loadOrMigrateProfiles(
        context: Context,
        prefs: SharedPreferences
    ): LoadedProfiles {
        val storedActiveId = prefs.getString(PreferenceKeys.activeProfileId, null)
            ?.takeIf { it.isNotEmpty() }

        val profilesFile = slotFile(context, PROFILES_SLOT)
        if (profilesFile.exists()) {
            val existingBytes = readEncryptedBytes(context, PROFILES_SLOT)
            val existingProfiles = existingBytes?.let { raw ->
                runCatching {
                    json.decodeFromString<List<ScoreProfile>>(raw.toString(Charsets.UTF_8))
                }.getOrNull()
            }
            if (existingProfiles != null) {
                val validActiveId = if (existingProfiles.any { it.id == storedActiveId }) {
                    storedActiveId
                } else {
                    existingProfiles.firstOrNull()?.id
                }
                if (validActiveId != storedActiveId) {
                    prefs.edit().putString(PreferenceKeys.activeProfileId, validActiveId).apply()
                }
                return LoadedProfiles(profiles = existingProfiles, activeProfileId = validActiveId)
            }
            // Encrypted profiles file already exists but failed to decrypt/decode transiently —
            // never fall through to legacy migration or overwrite saved credentials with [].
            return LoadedProfiles(profiles = emptyList(), activeProfileId = null)
        }

        // One-time migration from legacy single-provider preferences
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

        val appFilesDir = context.filesDir
        for (entry in migrated.entries) {
            saveSecret(context, entry.profile.id, entry.secret)
            val oldRecordsFile = File(appFilesDir, "records_${entry.profile.username.sha256Hex()}.json")
            val newRecordsFile = recordsFile(appFilesDir, entry.profile.id)
            if (oldRecordsFile.exists() && !newRecordsFile.exists()) {
                runCatching { oldRecordsFile.copyTo(newRecordsFile, overwrite = false) }
            }
        }

        val profiles = migrated.entries.map { it.profile }
        saveProfiles(context, profiles)

        prefs.edit()
            .putString(PreferenceKeys.activeProfileId, migrated.activeProfileId)
            .remove(PreferenceKeys.savedAccounts)
            .remove(PreferenceKeys.username)
            .remove(PreferenceKeys.password)
            .remove(PreferenceKeys.cookie)
            .remove(PreferenceKeys.dfNickname)
            .apply()

        return LoadedProfiles(profiles = profiles, activeProfileId = migrated.activeProfileId)
    }

    fun recordsFile(appFilesDir: File, profileId: String): File =
        File(appFilesDir, "records_profile_${profileId.lowercase(Locale.ROOT)}.json")

    /**
     * Pure JVM-testable migration builder matching iOS `AccountCredentialStore.buildMigratedProfiles`.
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

    private fun secretSlot(profileId: String): String =
        "profile_secret_${profileId.lowercase(Locale.ROOT)}"

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

    private fun writeEncryptedBytes(context: Context, slot: String, plaintext: ByteArray) {
        val encoded = encryptPayload(plaintext)
        val dest = slotFile(context, slot)
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        tmp.writeBytes(encoded)
        if (!tmp.renameTo(dest)) {
            dest.writeBytes(encoded)
            tmp.delete()
        }
    }

    private fun readEncryptedBytes(context: Context, slot: String): ByteArray? {
        val file = slotFile(context, slot)
        if (!file.exists()) return null
        return runCatching {
            val blob = file.readBytes()
            if (blob.size < 14) return null
            val ivLength = blob[0].toInt() and 0xFF
            if (ivLength !in 12..16 || blob.size <= 1 + ivLength) return null
            val iv = blob.copyOfRange(1, 1 + ivLength)
            val ciphertext = blob.copyOfRange(1 + ivLength, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        }.getOrNull()
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
        // Only generate a master key if none exists; never delete/recreate an existing alias
        // because doing so would permanently orphan all other encrypted credential slots.
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
