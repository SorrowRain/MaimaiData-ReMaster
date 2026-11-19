package dev.sorrowrain.maimaidata.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

object LXNSConfiguration {
    const val CLIENT_ID = "9e67172c-d7b9-4bcf-b8a9-185b8afe1b63"
    const val REDIRECT_URI = "maimaidata://oauth/lxns"
    const val CALLBACK_SCHEME = "maimaidata"
    const val SCOPES = "read_player openid profile"
    const val AUTHORIZE_URL = "https://maimai.lxns.net/oauth/authorize"
    const val API_BASE = "https://maimai.lxns.net/api/v0"
}

@Serializable
data class LXNSAPIEnvelope<T>(
    val success: Boolean = false,
    val code: Int = 0,
    val message: String? = null,
    val data: T? = null
)

@Serializable
data class LXNSOAuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Int = 3600,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
    @SerialName("id_token") val idToken: String? = null
)

@Serializable
data class LXNSOAuthErrorResponse(
    val error: String = "",
    @SerialName("error_description") val errorDescription: String? = null
)

@Serializable
data class LXNSUserInfo(
    val sub: String,
    val name: String? = null,
    @SerialName("preferred_username") val preferredUsername: String? = null
) {
    val displayUsername: String
        get() = preferredUsername?.takeIf { it.isNotEmpty() }
            ?: name?.takeIf { it.isNotEmpty() }
            ?: ""
}

@Serializable
data class LXNSPlayer(
    val name: String,
    val rating: Int? = null,
    @SerialName("friend_code") val friendCode: Long? = null,
    @SerialName("course_rank") val courseRank: Int? = null,
    @SerialName("class_rank") val classRank: Int? = null,
    @SerialName("upload_time") val uploadTime: String? = null
)

@Serializable
data class LXNSScore(
    val id: Int,
    @SerialName("song_name") val songName: String? = null,
    val level: String? = null,
    @SerialName("level_index") val levelIndex: Int,
    val achievements: Double,
    val fc: String? = null,
    val fs: String? = null,
    @SerialName("dx_score") val dxScore: Int? = null,
    @SerialName("dx_rating") val dxRating: Double? = null,
    val rate: String? = null,
    val type: String
)

@Serializable
data class LXNSSongListResponse(
    val songs: List<LXNSSong> = emptyList(),
    val versions: List<LXNSVersion> = emptyList()
)

@Serializable
data class LXNSVersion(
    val id: Int,
    val title: String,
    val version: Int
)

@Serializable
data class LXNSSong(
    val id: Int,
    val title: String,
    val artist: String,
    val genre: String,
    val bpm: Int,
    val version: Int,
    @SerialName("release_date") val releaseDate: String? = null,
    val locked: Boolean? = null,
    val disabled: Boolean? = null,
    val difficulties: LXNSSongDifficulties
)

@Serializable
data class LXNSSongDifficulties(
    val standard: List<LXNSSongDifficulty> = emptyList(),
    val dx: List<LXNSSongDifficulty> = emptyList(),
    val utage: List<LXNSSongDifficultyUtage>? = null
)

@Serializable
data class LXNSSongDifficulty(
    val type: String,
    val difficulty: Int,
    val level: String,
    @SerialName("level_value") val levelValue: Double,
    @SerialName("note_designer") val noteDesigner: String? = null,
    val version: Int? = null,
    val notes: LXNSNotes? = null
)

@Serializable
data class LXNSNotes(
    val total: Int? = null,
    val tap: Int? = null,
    val hold: Int? = null,
    val slide: Int? = null,
    val touch: Int? = null,
    @SerialName("break") val breakCount: Int? = null
) {
    val sdArray: List<Int>
        get() = listOf(tap ?: 0, hold ?: 0, slide ?: 0, breakCount ?: 0)

    val dxArray: List<Int>
        get() = listOf(tap ?: 0, hold ?: 0, slide ?: 0, touch ?: 0, breakCount ?: 0)
}

@Serializable(with = LXNSUtageNotesSerializer::class)
sealed class LXNSUtageNotes {
    data class Single(val notes: LXNSNotes) : LXNSUtageNotes()
    data class Buddy(val left: LXNSNotes, val right: LXNSNotes) : LXNSUtageNotes()
}

object LXNSUtageNotesSerializer : KSerializer<LXNSUtageNotes> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("LXNSUtageNotes")

    override fun deserialize(decoder: Decoder): LXNSUtageNotes {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return LXNSUtageNotes.Single(LXNSNotes())
        val obj = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: return LXNSUtageNotes.Single(LXNSNotes())
        val json = jsonDecoder.json
        val leftEl = obj["left"] as? JsonObject
        val rightEl = obj["right"] as? JsonObject
        if (leftEl != null && rightEl != null) {
            return LXNSUtageNotes.Buddy(
                left = json.decodeFromJsonElement(leftEl),
                right = json.decodeFromJsonElement(rightEl)
            )
        }
        return LXNSUtageNotes.Single(json.decodeFromJsonElement(obj))
    }

    override fun serialize(encoder: Encoder, value: LXNSUtageNotes) {
        val jsonEncoder = encoder as JsonEncoder
        val json = jsonEncoder.json
        val element = when (value) {
            is LXNSUtageNotes.Single -> json.encodeToJsonElement(value.notes)
            is LXNSUtageNotes.Buddy -> buildJsonObject {
                put("left", json.encodeToJsonElement(value.left))
                put("right", json.encodeToJsonElement(value.right))
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }
}

@Serializable
data class LXNSSongDifficultyUtage(
    val type: String,
    val difficulty: Int,
    val level: String,
    @SerialName("level_value") val levelValue: Double? = null,
    @SerialName("note_designer") val noteDesigner: String? = null,
    val version: Int? = null,
    val kanji: String? = null,
    val description: String? = null,
    @SerialName("is_buddy") val isBuddy: Boolean? = null,
    val notes: LXNSUtageNotes? = null
)

@Serializable
data class LXNSAliasListResponse(
    val aliases: List<LXNSAliasEntry> = emptyList()
)

@Serializable
data class LXNSAliasEntry(
    @SerialName("song_id") val songId: Int,
    val aliases: List<String> = emptyList()
)

@Serializable
data class LXNSPlateListResponse(
    val plates: List<LXNSPlate> = emptyList()
)

@Serializable
data class LXNSPlate(
    val id: Int,
    val name: String,
    val description: String? = null,
    val genre: String? = null,
    val required: List<LXNSPlateRequirement>? = null
)

@Serializable
data class LXNSPlateRequirement(
    val difficulties: List<Int>? = null,
    val rate: String? = null,
    val fc: String? = null,
    val fs: String? = null,
    val songs: List<LXNSPlateRequiredSong>? = null
)

@Serializable
data class LXNSPlateRequiredSong(
    val id: Int,
    val title: String? = null,
    val type: String
)

@Serializable
data class CNPlateCatalog(
    @SerialName("updated_at") val updatedAt: String,
    val groups: List<CNPlateRuleGroup>
)

@Serializable
data class CNPlateRuleGroup(
    val id: String,
    val title: String,
    @SerialName("version_asset") val versionAsset: String,
    val badges: List<String>,
    @SerialName("basic_to_master_song_ids") val basicToMasterSongIds: List<Int>,
    @SerialName("remaster_song_ids") val remasterSongIds: List<Int>
) {
    val resolvedVersionAsset: String
        get() = if (id == "真") "maimai" else versionAsset
}

data class PlateCheckScope(
    val id: String,
    val title: String,
    val versionAsset: String,
    val rule: Rule
) {
    sealed class Rule {
        data class ExplicitIds(
            val basicToMaster: Set<Int>,
            val remaster: Set<Int>
        ) : Rule()

        data class Versions(
            val includedVersions: Set<String>,
            val excludedIds: Set<Int> = emptySet(),
            val excludedStandardTitles: Set<String> = emptySet(),
            val sdRemasterIds: Set<Int>? = null,
            val sdRemasterFallbackTitles: Set<String> = emptySet()
        ) : Rule()
    }

    val supportsRemaster: Boolean
        get() = when (val r = rule) {
            is Rule.ExplicitIds -> r.remaster.isNotEmpty()
            is Rule.Versions -> r.sdRemasterIds != null
        }

    fun contains(song: SongData, difficulty: DifficultyType): Boolean {
        if (difficulty.rawValue < 0 || song.isUtage || song.isDisabled == true || song.charts.size <= difficulty.rawValue) {
            return false
        }
        return when (val r = rule) {
            is Rule.ExplicitIds -> {
                val target = if (difficulty == DifficultyType.REMASTER) r.remaster else r.basicToMaster
                song.numericId > 0 && song.numericId in target
            }
            is Rule.Versions -> {
                if (song.numericId > 0 && song.numericId in r.excludedIds) return false
                if (!song.isDX && song.title in r.excludedStandardTitles) return false
                if (song.basicInfo.from !in r.includedVersions) return false
                if (difficulty == DifficultyType.REMASTER) {
                    val sdIds = r.sdRemasterIds ?: return false
                    if (song.numericId > 0) {
                        song.numericId in sdIds
                    } else {
                        !song.isDX && song.title in r.sdRemasterFallbackTitles
                    }
                } else {
                    true
                }
            }
        }
    }
}
