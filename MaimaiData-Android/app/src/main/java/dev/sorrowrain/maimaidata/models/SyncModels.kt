package dev.sorrowrain.maimaidata.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@Serializable
enum class SyncChannelKind {
    @SerialName("selfHosted") SELF_HOSTED,
    @SerialName("lxns") LXNS,
    @SerialName("divingFish") DIVING_FISH;

    val displayName: String
        get() = when (this) {
            SELF_HOSTED -> "国服直连"
            LXNS -> "落雪咖啡屋"
            DIVING_FISH -> "水鱼查分器"
        }

    val shortName: String
        get() = when (this) {
            SELF_HOSTED -> "国服"
            LXNS -> "落雪"
            DIVING_FISH -> "水鱼"
        }
}

enum class ChannelBindingStatus {
    UNAVAILABLE,
    UNBOUND,
    BOUND,
    NEEDS_REAUTH
}

enum class SyncOperationKind {
    BIND,
    QUICK_SYNC,
    FULL_SYNC,
    PULL_LXNS,
    PULL_DIVING_FISH,
    PULL_MERGED,
    WRITEBACK
}

sealed interface SyncPlan {
    data object None : SyncPlan
    data object CNDirectQuick : SyncPlan
    data class SingleProber(val channel: SyncChannelKind) : SyncPlan
    data object MergedProbers : SyncPlan
}

data class SyncOutcome(
    val warningMessage: String? = null
)

data class ActiveSyncTask(
    val identityId: String?,
    val operation: SyncOperationKind
)

@Serializable
enum class SyncMode {
    @SerialName("quick") QUICK,
    @SerialName("full") FULL,
    @SerialName("lxns") LXNS,
    @SerialName("divingFish") DIVING_FISH;

    val displayName: String
        get() = when (this) {
            QUICK -> "快速同步"
            FULL -> "完整同步"
            LXNS, DIVING_FISH -> "同步成绩"
        }

    val isSelfHosted: Boolean
        get() = this == QUICK || this == FULL

    val isAuthoritativeBadge: Boolean
        get() = this == FULL || this == LXNS || this == DIVING_FISH
}

@Serializable
data class SelfHostedBinding(
    val externalUserId: Long,
    val displayName: String = "",
    val playerRating: Int? = null,
    val boundAtEpochMs: Long
)

@Serializable
data class LXNSBinding(
    val authMethod: ScoreAuthMethod,
    val accountKey: String,
    val displayName: String,
    val friendCode: Long? = null,
    val hasWriteScope: Boolean = false,
    val boundAtEpochMs: Long
)

@Serializable
data class DivingFishBinding(
    val authMethod: ScoreAuthMethod,
    val accountKey: String,
    val username: String,
    val nickname: String,
    val boundAtEpochMs: Long
)

@Serializable
data class PlayerIdentity(
    val id: String = UUID.randomUUID().toString().lowercase(Locale.ROOT),
    val label: String = "",
    val customNickname: String = "",
    val hasMixedScoreSources: Boolean = false,
    val selfHosted: SelfHostedBinding? = null,
    val lxns: LXNSBinding? = null,
    val divingFish: DivingFishBinding? = null,
    val lastSyncMode: SyncMode? = null,
    val lastSyncedAtEpochMs: Long? = null,
    val lastFullSyncAtEpochMs: Long? = null
) {
    val hasAnyChannel: Boolean
        get() = selfHosted != null || lxns != null || divingFish != null

    val boundChannelCount: Int
        get() = listOfNotNull(selfHosted, lxns, divingFish).size

    val defaultSyncMode: SyncMode?
        get() = when {
            selfHosted != null -> SyncMode.QUICK
            lxns != null -> SyncMode.LXNS
            divingFish != null -> SyncMode.DIVING_FISH
            else -> null
        }

    val primaryDisplayName: String
        get() = listOf(
            selfHosted?.displayName.orEmpty(),
            lxns?.displayName.orEmpty(),
            divingFish?.nickname.orEmpty(),
            divingFish?.username.orEmpty(),
            label
        ).firstOrNull { it.isNotBlank() }?.trim() ?: "玩家档案"

    val b50PlayerName: String
        get() = customNickname.trim().ifEmpty { primaryDisplayName }

    val syncPlan: SyncPlan
        get() = when {
            selfHosted != null -> SyncPlan.CNDirectQuick
            lxns != null && divingFish != null -> SyncPlan.MergedProbers
            lxns != null -> SyncPlan.SingleProber(SyncChannelKind.LXNS)
            divingFish != null -> SyncPlan.SingleProber(SyncChannelKind.DIVING_FISH)
            else -> SyncPlan.None
        }

    val canUploadToProbers: Boolean
        get() = uploadTargets.isNotEmpty() && (selfHosted != null || (lxns != null && divingFish != null))

    val boundChannelsSummary: String
        get() {
            val parts = mutableListOf<String>()
            if (selfHosted != null) {
                parts += SyncChannelKind.SELF_HOSTED.displayName
            }
            if (lxns != null) {
                parts += if (needsLXNSReauthForWrite) "落雪（需重新授权）" else "落雪"
            }
            if (divingFish != null) {
                parts += "水鱼"
            }
            return if (parts.isEmpty()) "未绑定" else parts.joinToString(" · ")
        }

    val needsLXNSReauthForWrite: Boolean
        get() = lxns?.let { it.authMethod == ScoreAuthMethod.LXNS_OAUTH && !it.hasWriteScope } ?: false

    val uploadTargets: List<SyncChannelKind>
        get() {
            val targets = mutableListOf<SyncChannelKind>()
            if (lxns != null && !needsLXNSReauthForWrite) {
                targets += SyncChannelKind.LXNS
            }
            if (divingFish != null) {
                targets += SyncChannelKind.DIVING_FISH
            }
            return targets
        }

    fun bindingStatus(channel: SyncChannelKind, cnDirectAvailable: Boolean): ChannelBindingStatus =
        when (channel) {
            SyncChannelKind.SELF_HOSTED -> when {
                !cnDirectAvailable -> ChannelBindingStatus.UNAVAILABLE
                selfHosted != null -> ChannelBindingStatus.BOUND
                else -> ChannelBindingStatus.UNBOUND
            }
            SyncChannelKind.LXNS -> when {
                lxns == null -> ChannelBindingStatus.UNBOUND
                needsLXNSReauthForWrite -> ChannelBindingStatus.NEEDS_REAUTH
                else -> ChannelBindingStatus.BOUND
            }
            SyncChannelKind.DIVING_FISH -> if (divingFish != null) {
                ChannelBindingStatus.BOUND
            } else {
                ChannelBindingStatus.UNBOUND
            }
        }

    fun lastSyncText(
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String? {
        val epochMs = lastSyncedAtEpochMs ?: return null
        if (epochMs <= 0L) return null
        val syncCal = Calendar.getInstance(timeZone, Locale.ROOT).apply { timeInMillis = epochMs }
        val nowCal = Calendar.getInstance(timeZone, Locale.ROOT).apply { timeInMillis = nowMs }
        val timeText = String.format(
            Locale.US,
            "%02d:%02d",
            syncCal.get(Calendar.HOUR_OF_DAY),
            syncCal.get(Calendar.MINUTE)
        )
        if (syncCal.get(Calendar.ERA) == nowCal.get(Calendar.ERA) &&
            syncCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
            syncCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
        ) {
            return "上次同步 今天 $timeText"
        }
        val yesterdayCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (syncCal.get(Calendar.ERA) == yesterdayCal.get(Calendar.ERA) &&
            syncCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
            syncCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
        ) {
            return "上次同步 昨天 $timeText"
        }
        val dateText = String.format(
            Locale.US,
            "%02d-%02d",
            syncCal.get(Calendar.MONTH) + 1,
            syncCal.get(Calendar.DAY_OF_MONTH)
        )
        return "上次同步 $dateText $timeText"
    }
}

// Split AndroidKeyStore Channel Secrets

@Serializable
data class SelfHostedEndpointConfig(
    val serverBaseURL: String = "",
    val accessKey: String = ""
) {
    val isConfigured: Boolean
        get() = serverBaseURL.isNotBlank() && accessKey.isNotBlank()
}

@Serializable
data class SelfHostedSecret(
    val syncToken: String = ""
)

@Serializable
data class LXNSSecret(
    val personalToken: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val accessTokenExpiresAtEpochSec: Long? = null,
    val grantedScopes: String? = null
)

@Serializable
data class DivingFishSecret(
    val username: String = "",
    val password: String = "",
    val cookie: String = "",
    val importToken: String = ""
)

// Self-Hosted Proxy DTOs

@Serializable
data class SyncBackendBindRequest(
    val qrContent: String,
    val fullSync: Boolean
)

@Serializable
data class SyncBackendFullRequest(
    val qrContent: String
)

@Serializable
data class SyncBackendProfileDTO(
    val userId: Long,
    val userName: String? = null,
    val playerRating: Int? = null,
    val iconId: Int? = null,
    val nameplateId: Int? = null,
    val trophyId: Int? = null,
    val lastDataVersion: String? = null,
    val lastPlayDate: String? = null
)

@Serializable
data class SyncBackendRecordDTO(
    val songId: Int,
    val levelIndex: Int,
    val achievements: Double,
    val dxScore: Int = 0,
    val fc: String? = null,
    val fs: String? = null
)

@Serializable
data class SyncBackendResponse(
    val syncToken: String? = null,
    val mode: String,
    val profile: SyncBackendProfileDTO,
    val records: List<SyncBackendRecordDTO> = emptyList(),
    val recordCount: Int = 0,
    val syncedAtUnixMs: Long = 0L,
    val cached: Boolean? = null
)

@Serializable
data class SyncBackendErrorEnvelope(
    val error: ErrorDetail? = null
) {
    @Serializable
    data class ErrorDetail(
        val code: String = "",
        val message: String = "",
        val returnCode: Int? = null,
        val retryAfterSec: Int? = null
    )
}

// Third-Party Writeback Payloads

@Serializable
data class DivingFishUploadRecordDTO(
    val achievements: Double,
    val title: String,
    val type: String,
    @SerialName("level_index") val levelIndex: Int,
    val fc: String,
    val fs: String,
    val dxScore: Int
)

@Serializable
data class LXNSUploadScoreDTO(
    val id: Int,
    @SerialName("level_index") val levelIndex: Int,
    val achievements: Double,
    val fc: String? = null,
    val fs: String? = null,
    @SerialName("dx_score") val dxScore: Int? = null,
    val type: String
)

@Serializable
data class LXNSUploadScoresRequest(
    val scores: List<LXNSUploadScoreDTO>
)

// Writeback & Local History Snapshot

data class WritebackChannelStatus(
    val channel: SyncChannelKind,
    val uploadedCount: Int,
    val skippedCount: Int,
    val errorMessage: String? = null
) {
    val succeeded: Boolean
        get() = errorMessage == null
}

data class WritebackSummary(
    val results: List<WritebackChannelStatus>
) {
    val isAllSucceeded: Boolean
        get() = results.all { it.succeeded }

    val hasAnySucceeded: Boolean
        get() = results.any { it.succeeded }

    val summaryText: String
        get() {
            if (results.isEmpty()) return ""
            if (isAllSucceeded) return "上传成功"
            val succeededNames = results
                .filter { it.succeeded }
                .joinToString("、") { it.channel.shortName }
            val failedText = results
                .filter { !it.succeeded }
                .joinToString("；") { "${it.channel.shortName}上传失败：${it.errorMessage ?: "请稍后重试"}" }
            return if (succeededNames.isEmpty()) failedText else "${succeededNames}上传成功，$failedText"
        }
}

@Serializable
data class SyncHistoryEntry(
    val identityId: String,
    val timestampEpochMs: Long,
    val syncMode: SyncMode,
    val cnRating: Int,
    val b35Rating: Int,
    val b15Rating: Int,
    val recordCount: Int,
    val playedSongsCount: Int,
    val isBadgeBaseline: Boolean
)
