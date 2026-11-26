package dev.sorrowrain.maimaidata.services

import dev.sorrowrain.maimaidata.models.DivingFishBinding
import dev.sorrowrain.maimaidata.models.DivingFishSecret
import dev.sorrowrain.maimaidata.models.DivingFishUploadRecordDTO
import dev.sorrowrain.maimaidata.models.LXNSBinding
import dev.sorrowrain.maimaidata.models.LXNSConfiguration
import dev.sorrowrain.maimaidata.models.LXNSSecret
import dev.sorrowrain.maimaidata.models.LXNSUploadScoreDTO
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.PlayerIdentity
import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.RatingCalculator
import dev.sorrowrain.maimaidata.models.ScoreAuthMethod
import dev.sorrowrain.maimaidata.models.ScoreProfile
import dev.sorrowrain.maimaidata.models.ScoreProfileSecret
import dev.sorrowrain.maimaidata.models.ScoreProvider
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.models.SyncBackendRecordDTO
import dev.sorrowrain.maimaidata.models.SyncMode
import java.util.Locale

/**
 * Pure stateless score merge, third-party writeback payload builder, CN rating snapshot,
 * and v2 -> v3 profile upgrade engine. Matches iOS `ScoreSyncEngine.swift` 1:1.
 */
object ScoreSyncEngine {

    data class CNRatingSnapshot(
        val cnRating: Int,
        val b35Rating: Int,
        val b15Rating: Int
    )

    data class UpgradedLegacyIdentity(
        val identity: PlayerIdentity,
        val lxns: LXNSSecret? = null,
        val divingFish: DivingFishSecret? = null
    )

    data class WritebackBuildResult<T>(
        val uploads: List<T>,
        val skippedDeletedCount: Int,
        val sampleSkippedSongIds: List<Int>
    )

    data class NicknameMigrationResult(
        val identities: List<PlayerIdentity>,
        val didChange: Boolean
    )

    fun fcRank(fc: String): Int = when (fc.lowercase(Locale.ROOT)) {
        "fc" -> 1
        "fcp" -> 2
        "ap" -> 3
        "app" -> 4
        else -> 0
    }

    fun fsRank(fs: String): Int = when (fs.lowercase(Locale.ROOT)) {
        "sync" -> 1
        "fs" -> 2
        "fsp" -> 3
        "fsd" -> 4
        "fsdp" -> 5
        else -> 0
    }

    fun maxFC(lhs: String, rhs: String): String {
        val l = lhs.lowercase(Locale.ROOT)
        val r = rhs.lowercase(Locale.ROOT)
        return if (fcRank(l) >= fcRank(r)) l else r
    }

    fun maxFS(lhs: String, rhs: String): String {
        val l = lhs.lowercase(Locale.ROOT)
        val r = rhs.lowercase(Locale.ROOT)
        return if (fsRank(l) >= fsRank(r)) l else r
    }

    fun normalizeBackendRecords(
        dtos: List<SyncBackendRecordDTO>,
        songsById: Map<Int, SongData>,
        region: MaimaiConstants.ServerRegion
    ): List<ProberRecord> {
        val bestByKey = LinkedHashMap<String, ProberRecord>(dtos.size)
        for (dto in dtos) {
            val isUtage = dto.songId >= 100_000 || dto.levelIndex >= 10
            val canonicalLevelIndex = if (isUtage) {
                if (dto.levelIndex == 1 || dto.levelIndex == 11) 11 else 10
            } else {
                dto.levelIndex
            }
            val song = songsById[dto.songId]
            val recordType = when {
                isUtage -> "utage"
                song != null -> song.type
                dto.songId >= 10_000 -> MaimaiConstants.CHART_TYPE_DX
                else -> MaimaiConstants.CHART_TYPE_SD
            }
            val chartIdx = song?.chartArrayIndex(canonicalLevelIndex)
            val ds = if (isUtage) 0.0 else (chartIdx?.let { song.ds.getOrNull(it) } ?: 0.0)
            val level = chartIdx?.let { song.level.getOrNull(it) } ?: "-"
            val title = song?.title ?: "Song #${dto.songId}"
            val normalizedFC = normalizedBadgeWithAchievementFloor(
                fc = dto.fc.orEmpty().lowercase(Locale.ROOT),
                achievements = dto.achievements
            )
            val fs = dto.fs.orEmpty().lowercase(Locale.ROOT)
            val rate = LXNSCatalogConverter.fallbackRateString(dto.achievements)
            val isAP = normalizedFC == "ap" || normalizedFC == "app"
            val ra = if (isUtage) 0 else RatingCalculator.singleSongRating(
                ds = ds,
                achievement = dto.achievements,
                isAP = isAP,
                apBonusEnabled = region.usesAPBonus
            )

            val record = ProberRecord(
                achievements = dto.achievements,
                ds = ds,
                fc = normalizedFC,
                fs = fs,
                level = level,
                levelIndex = canonicalLevelIndex,
                ra = ra,
                rate = rate,
                songId = dto.songId,
                title = title,
                type = recordType,
                dxScore = dto.dxScore,
                apBonusEnabled = region.usesAPBonus
            )

            val key = recordMergeKey(record)
            val prev = bestByKey[key]
            bestByKey[key] = if (prev != null) mergePairBest(prev, record) else record
        }
        return bestByKey.values.toList()
    }

    fun mergeQuickSync(
        existing: List<ProberRecord>,
        incoming: List<ProberRecord>
    ): List<ProberRecord> {
        val mergedByKey = LinkedHashMap<String, ProberRecord>(maxOf(existing.size, incoming.size))
        for (local in existing) {
            val key = recordMergeKey(local)
            val prev = mergedByKey[key]
            mergedByKey[key] = if (prev != null) mergePairBest(prev, local) else local
        }
        for (remote in incoming) {
            val key = recordMergeKey(remote)
            val prev = mergedByKey[key]
            if (prev != null) {
                mergedByKey[key] = mergePairBest(prev, remote)
            } else {
                mergedByKey[key] = remote.copy(
                    fc = normalizedBadgeWithAchievementFloor(remote.fc, remote.achievements),
                    fs = remote.fs.lowercase(Locale.ROOT),
                    rate = LXNSCatalogConverter.fallbackRateString(remote.achievements)
                )
            }
        }
        return sortedDeterministically(mergedByKey.values.toList())
    }

    fun applyFullSync(incoming: List<ProberRecord>): List<ProberRecord> {
        val byKey = LinkedHashMap<String, ProberRecord>(incoming.size)
        for (item in incoming) {
            val normalized = item.copy(
                fc = normalizedBadgeWithAchievementFloor(item.fc, item.achievements),
                fs = item.fs.lowercase(Locale.ROOT),
                rate = item.rate.ifEmpty { LXNSCatalogConverter.fallbackRateString(item.achievements) }
            )
            val key = recordMergeKey(normalized)
            val prev = byKey[key]
            byKey[key] = if (prev != null) mergePairBest(prev, normalized) else normalized
        }
        return sortedDeterministically(byKey.values.toList())
    }

    fun isEligibleForCNProberUpload(
        record: ProberRecord,
        cnSongsById: Map<Int, SongData>
    ): Boolean {
        if (record.achievements <= 0.0) return false
        if (cnSongsById.isEmpty()) return false
        val song = cnSongsById[record.songId] ?: return false
        if (song.isDisabled == true) return false
        return if (record.isUtage) {
            song.isUtage
        } else {
            song.chartArrayIndex(record.levelIndex) != null
        }
    }

    fun buildDivingFishWriteback(
        localRecords: List<ProberRecord>,
        cloudRecords: List<ProberRecord>,
        songsById: Map<Int, SongData>,
        cnSongsById: Map<Int, SongData> = emptyMap(),
        musicTitlesById: Map<Int, String> = emptyMap()
    ): WritebackBuildResult<DivingFishUploadRecordDTO> {
        val cloudByKey = HashMap<String, ProberRecord>()
        for (cloud in cloudRecords) {
            if (cloud.isUtage || cloud.levelIndex !in 0..4) continue
            val key = recordMergeKey(cloud)
            val prev = cloudByKey[key]
            cloudByKey[key] = if (prev != null) mergePairBest(prev, cloud) else cloud
        }

        val uploads = mutableListOf<DivingFishUploadRecordDTO>()
        var skippedDeletedCount = 0
        val sampleSkippedIds = mutableListOf<Int>()

        for (local in sortedDeterministically(localRecords)) {
            if (local.isUtage || local.levelIndex !in 0..4) continue
            if (local.achievements <= 0.0) continue
            if (!isEligibleForCNProberUpload(local, cnSongsById)) {
                skippedDeletedCount += 1
                if (local.songId !in sampleSkippedIds && sampleSkippedIds.size < 5) {
                    sampleSkippedIds += local.songId
                }
                continue
            }
            val cloud = cloudByKey[recordMergeKey(local)]
            if (cloud != null && !isStrictlyBetter(local, cloud)) continue

            val title = resolveDivingFishTitle(local, cloud, songsById, musicTitlesById)
            if (title.isEmpty()) continue

            val mergedAchi = maxOf(local.achievements, cloud?.achievements ?: 0.0)
            val mergedDx = maxOf(local.dxScore, cloud?.dxScore ?: 0)
            val mergedFC = normalizedBadgeWithAchievementFloor(
                fc = maxFC(local.fc, cloud?.fc.orEmpty()),
                achievements = mergedAchi
            )
            val mergedFS = maxFS(local.fs, cloud?.fs.orEmpty())
            val chartType = if (local.type.equals(MaimaiConstants.CHART_TYPE_DX, ignoreCase = true)) {
                MaimaiConstants.CHART_TYPE_DX
            } else {
                MaimaiConstants.CHART_TYPE_SD
            }

            uploads += DivingFishUploadRecordDTO(
                achievements = mergedAchi,
                title = title,
                type = chartType,
                levelIndex = local.levelIndex,
                fc = mergedFC,
                fs = mergedFS,
                dxScore = mergedDx
            )
        }
        return WritebackBuildResult(
            uploads = uploads,
            skippedDeletedCount = skippedDeletedCount,
            sampleSkippedSongIds = sampleSkippedIds
        )
    }

    fun needsDivingFishTitleMap(
        localRecords: List<ProberRecord>,
        cloudRecords: List<ProberRecord>,
        cnSongsById: Map<Int, SongData> = emptyMap()
    ): Boolean {
        val cloudByKey = HashMap<String, ProberRecord>()
        for (cloud in cloudRecords) {
            if (cloud.isUtage || cloud.levelIndex !in 0..4) continue
            val key = recordMergeKey(cloud)
            val prev = cloudByKey[key]
            cloudByKey[key] = if (prev != null) mergePairBest(prev, cloud) else cloud
        }
        for (local in localRecords) {
            if (local.isUtage || local.levelIndex !in 0..4) continue
            if (!isEligibleForCNProberUpload(local, cnSongsById)) continue
            val cloud = cloudByKey[recordMergeKey(local)] ?: return true
            if (isStrictlyBetter(local, cloud) && cloud.title.trim().isEmpty()) {
                return true
            }
        }
        return false
    }

    fun buildLXNSWriteback(
        localRecords: List<ProberRecord>,
        cloudRecords: List<ProberRecord>,
        cnSongsById: Map<Int, SongData> = emptyMap()
    ): WritebackBuildResult<LXNSUploadScoreDTO> {
        val cloudByKey = HashMap<String, ProberRecord>()
        for (cloud in cloudRecords) {
            val target = lxnsWritebackTarget(cloud) ?: continue
            val prev = cloudByKey[target.key]
            cloudByKey[target.key] = if (prev != null) mergePairBest(prev, cloud) else cloud
        }

        val localByKey = LinkedHashMap<String, Pair<ProberRecord, LXNSWritebackTarget>>()
        for (local in sortedDeterministically(localRecords)) {
            val target = lxnsWritebackTarget(local) ?: continue
            val prev = localByKey[target.key]
            localByKey[target.key] = if (prev != null) {
                mergePairBest(prev.first, local) to target
            } else {
                local to target
            }
        }

        val orderedLocals = localByKey.values.sortedWith(
            compareBy<Pair<ProberRecord, LXNSWritebackTarget>>(
                { it.first.songId },
                { it.first.levelIndex },
                { it.first.type }
            )
        )

        val uploads = mutableListOf<LXNSUploadScoreDTO>()
        var skippedDeletedCount = 0
        val sampleSkippedIds = mutableListOf<Int>()

        for ((local, target) in orderedLocals) {
            if (local.achievements <= 0.0) continue
            if (!isEligibleForCNProberUpload(local, cnSongsById)) {
                skippedDeletedCount += 1
                if (local.songId !in sampleSkippedIds && sampleSkippedIds.size < 5) {
                    sampleSkippedIds += local.songId
                }
                continue
            }
            val cloud = cloudByKey[target.key]
            if (cloud != null && !isStrictlyBetter(local, cloud)) continue

            val mergedAchi = maxOf(local.achievements, cloud?.achievements ?: 0.0)
            val mergedDx = maxOf(local.dxScore, cloud?.dxScore ?: 0)
            val mergedFC = normalizedBadgeWithAchievementFloor(
                fc = maxFC(local.fc, cloud?.fc.orEmpty()),
                achievements = mergedAchi
            )
            val mergedFS = maxFS(local.fs, cloud?.fs.orEmpty())

            uploads += LXNSUploadScoreDTO(
                id = target.id,
                levelIndex = target.levelIndex,
                achievements = mergedAchi,
                fc = mergedFC.takeIf { it.isNotEmpty() },
                fs = mergedFS.takeIf { it.isNotEmpty() },
                dxScore = mergedDx.takeIf { it > 0 },
                type = target.type
            )
        }
        return WritebackBuildResult(
            uploads = uploads,
            skippedDeletedCount = skippedDeletedCount,
            sampleSkippedSongIds = sampleSkippedIds
        )
    }

    fun computeCNRatingSnapshot(
        records: List<ProberRecord>,
        songsById: Map<Int, SongData>
    ): CNRatingSnapshot {
        val oldList = ArrayList<ProberRecord>()
        val newList = ArrayList<ProberRecord>()

        for (rec in records) {
            if (rec.isUtage) continue
            val song = songsById[rec.songId] ?: continue
            if (song.isUtage || song.isDisabled == true || song.chartArrayIndex(rec.levelIndex) == null) {
                continue
            }
            val projected = rec.projected(onto = song, region = MaimaiConstants.ServerRegion.CN)
            if (song.basicInfo.isNew) {
                newList += projected
            } else {
                oldList += projected
            }
        }

        val b50Order = Comparator<ProberRecord> { lhs, rhs ->
            val raCmp = rhs.computedRating.compareTo(lhs.computedRating)
            if (raCmp != 0) return@Comparator raCmp
            val achiCmp = rhs.achievements.compareTo(lhs.achievements)
            if (achiCmp != 0) return@Comparator achiCmp
            val songCmp = lhs.songId.compareTo(rhs.songId)
            if (songCmp != 0) return@Comparator songCmp
            lhs.levelIndex.compareTo(rhs.levelIndex)
        }
        oldList.sortWith(b50Order)
        newList.sortWith(b50Order)

        val b35 = oldList.take(35).sumOf { it.computedRating }
        val b15 = newList.take(15).sumOf { it.computedRating }
        return CNRatingSnapshot(
            cnRating = b35 + b15,
            b35Rating = b35,
            b15Rating = b15
        )
    }

    fun upgradeLegacyProfiles(
        legacyProfiles: List<ScoreProfile>,
        secretsById: Map<String, ScoreProfileSecret>,
        nowEpochMs: Long
    ): List<UpgradedLegacyIdentity> {
        return legacyProfiles.map { profile ->
            val lowerId = profile.id.lowercase(Locale.ROOT)
            val sec = secretsById[profile.id] ?: secretsById[lowerId] ?: ScoreProfileSecret()
            val syncedMs = profile.lastSyncedAtEpochMs

            when (profile.provider) {
                ScoreProvider.LXNS -> {
                    val hasWrite = profile.authMethod == ScoreAuthMethod.LXNS_PERSONAL_TOKEN
                    val parsedFriendCode = if (profile.accountKey.startsWith("fc_")) {
                        profile.accountKey.removePrefix("fc_").toLongOrNull()
                    } else {
                        null
                    }
                    val binding = LXNSBinding(
                        authMethod = profile.authMethod,
                        accountKey = profile.accountKey,
                        displayName = profile.displayTitle,
                        friendCode = parsedFriendCode,
                        hasWriteScope = hasWrite,
                        boundAtEpochMs = syncedMs ?: nowEpochMs
                    )
                    val identity = PlayerIdentity(
                        id = lowerId,
                        label = profile.displayTitle,
                        lxns = binding,
                        lastSyncMode = SyncMode.LXNS,
                        lastSyncedAtEpochMs = syncedMs,
                        lastFullSyncAtEpochMs = syncedMs
                    )
                    UpgradedLegacyIdentity(
                        identity = identity,
                        lxns = LXNSSecret(
                            personalToken = sec.personalToken,
                            accessToken = sec.accessToken,
                            refreshToken = sec.refreshToken,
                            accessTokenExpiresAtEpochSec = sec.accessTokenExpiresAtEpochSec,
                            grantedScopes = if (hasWrite) LXNSConfiguration.SCOPES else null
                        )
                    )
                }

                ScoreProvider.DIVING_FISH -> {
                    val binding = DivingFishBinding(
                        authMethod = profile.authMethod,
                        accountKey = profile.accountKey,
                        username = profile.username,
                        nickname = profile.nickname,
                        boundAtEpochMs = syncedMs ?: nowEpochMs
                    )
                    val identity = PlayerIdentity(
                        id = lowerId,
                        label = profile.displayTitle,
                        divingFish = binding,
                        lastSyncMode = SyncMode.DIVING_FISH,
                        lastSyncedAtEpochMs = syncedMs,
                        lastFullSyncAtEpochMs = syncedMs
                    )
                    UpgradedLegacyIdentity(
                        identity = identity,
                        divingFish = DivingFishSecret(
                            username = profile.username,
                            password = sec.password,
                            cookie = sec.cookie,
                            importToken = sec.importToken
                        )
                    )
                }
            }
        }
    }

    fun migrateLegacyB50Nickname(
        identities: List<PlayerIdentity>,
        legacyUseProberNickname: Boolean,
        legacyCustomNickname: String
    ): NicknameMigrationResult {
        val trimmed = legacyCustomNickname.trim()
        if (legacyUseProberNickname || trimmed.isEmpty() || identities.isEmpty()) {
            return NicknameMigrationResult(identities = identities, didChange = false)
        }
        var didChange = false
        val updated = identities.map { identity ->
            if (identity.customNickname.trim().isNotEmpty()) {
                identity
            } else {
                didChange = true
                identity.copy(customNickname = trimmed)
            }
        }
        return NicknameMigrationResult(identities = updated, didChange = didChange)
    }

    private data class LXNSWritebackTarget(
        val key: String,
        val id: Int,
        val type: String,
        val levelIndex: Int
    )

    private fun lxnsWritebackTarget(record: ProberRecord): LXNSWritebackTarget? {
        if (record.songId <= 0) return null
        if (!record.isUtage && record.levelIndex !in 0..4) return null
        if (record.isUtage) {
            return LXNSWritebackTarget(
                key = "${record.songId}_utage_0",
                id = record.songId,
                type = "utage",
                levelIndex = 0
            )
        }
        if (record.type.equals(MaimaiConstants.CHART_TYPE_DX, ignoreCase = true)) {
            val lxnsId = if (record.songId >= 10_000) record.songId - 10_000 else record.songId
            return LXNSWritebackTarget(
                key = "${lxnsId}_dx_${record.levelIndex}",
                id = lxnsId,
                type = "dx",
                levelIndex = record.levelIndex
            )
        }
        return LXNSWritebackTarget(
            key = "${record.songId}_standard_${record.levelIndex}",
            id = record.songId,
            type = "standard",
            levelIndex = record.levelIndex
        )
    }

    fun recordMergeKey(rec: ProberRecord): String = "${rec.songId}_${rec.levelIndex}"

    fun isStrictlyBetter(local: ProberRecord, than: ProberRecord): Boolean {
        if (local.achievements > than.achievements + 0.00001) return true
        if (local.dxScore > than.dxScore) return true
        if (fcRank(local.fc) > fcRank(than.fc)) return true
        if (fsRank(local.fs) > fsRank(than.fs)) return true
        return false
    }

    private fun normalizedBadgeWithAchievementFloor(fc: String, achievements: Double): String {
        val lower = fc.lowercase(Locale.ROOT)
        return if (achievements >= 101.0) maxFC(lower, "app") else lower
    }

    private fun mergePairBest(existing: ProberRecord, incoming: ProberRecord): ProberRecord {
        val bestAchi = maxOf(existing.achievements, incoming.achievements)
        val bestDx = maxOf(existing.dxScore, incoming.dxScore)
        val bestFC = normalizedBadgeWithAchievementFloor(
            fc = maxFC(existing.fc, incoming.fc),
            achievements = bestAchi
        )
        val bestFS = maxFS(existing.fs, incoming.fs)

        val preferredDs = if (incoming.ds > 0.0) incoming.ds else existing.ds
        val preferredLevel = if (incoming.level != "-" && incoming.level.isNotEmpty()) incoming.level else existing.level
        val preferredTitle = if (!incoming.title.startsWith("Song #") && incoming.title.isNotEmpty()) incoming.title else existing.title
        val apBonus = existing.apBonusEnabled || incoming.apBonusEnabled
        val isUtage = existing.isUtage || incoming.isUtage
        val ra = if (isUtage) 0 else RatingCalculator.singleSongRating(
            ds = preferredDs,
            achievement = bestAchi,
            isAP = bestFC == "ap" || bestFC == "app",
            apBonusEnabled = apBonus
        )

        return incoming.copy(
            achievements = bestAchi,
            ds = preferredDs,
            fc = bestFC,
            fs = bestFS,
            level = preferredLevel,
            ra = ra,
            rate = LXNSCatalogConverter.fallbackRateString(bestAchi),
            title = preferredTitle,
            dxScore = bestDx,
            apBonusEnabled = apBonus
        )
    }

    private fun resolveDivingFishTitle(
        local: ProberRecord,
        cloud: ProberRecord?,
        songsById: Map<Int, SongData>,
        musicTitlesById: Map<Int, String>
    ): String {
        cloud?.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        musicTitlesById[local.songId]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        songsById[local.songId]?.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val localTitle = local.title.trim()
        if (localTitle.isNotEmpty() && !localTitle.startsWith("Song #")) return localTitle
        return ""
    }

    private fun sortedDeterministically(records: List<ProberRecord>): List<ProberRecord> =
        records.sortedWith(compareBy<ProberRecord>({ it.songId }, { it.levelIndex }, { it.type }))

    fun normalizeCachedRecords(records: List<ProberRecord>): List<ProberRecord> {
        if (records.isEmpty()) return emptyList()
        val bestByKey = LinkedHashMap<String, ProberRecord>(records.size)
        for (rec in records) {
            val isUtage = rec.songId >= 100_000 || rec.levelIndex >= 10 || rec.type.equals("utage", ignoreCase = true)
            val normalized = if (isUtage) {
                val canonicalIdx = if (rec.levelIndex == 1 || rec.levelIndex == 11) 11 else 10
                val fc = normalizedBadgeWithAchievementFloor(rec.fc.lowercase(Locale.ROOT), rec.achievements)
                rec.copy(
                    ds = 0.0,
                    fc = fc,
                    fs = rec.fs.lowercase(Locale.ROOT),
                    levelIndex = canonicalIdx,
                    ra = 0,
                    rate = LXNSCatalogConverter.fallbackRateString(rec.achievements),
                    type = "utage"
                )
            } else {
                rec
            }
            val key = recordMergeKey(normalized)
            val existing = bestByKey[key]
            bestByKey[key] = if (existing == null) normalized else mergePairBest(existing, normalized)
        }
        return sortedDeterministically(bestByKey.values.toList())
    }
}
