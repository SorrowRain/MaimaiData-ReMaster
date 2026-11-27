package dev.sorrowrain.maimaidata.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.w3c.dom.Element
import org.w3c.dom.Text
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

@Serializable
data class ChartStatsResponse(
    val charts: Map<String, List<ChartStatEntry>> = emptyMap()
)

@Serializable
data class ChartStatEntry(
    @SerialName("fit_diff") val fitDiff: Double? = null
)

@Serializable
data class ProberRecordsResponse(
    val username: String? = null,
    val nickname: String? = null,
    val records: List<ProberRecord>? = null,
    val status: String? = null,
    val message: String? = null
)

@Serializable
data class ProberRecord(
    val achievements: Double,
    val ds: Double,
    val fc: String = "",
    val fs: String = "",
    val level: String = "-",
    @SerialName("level_index") val levelIndex: Int,
    val ra: Int = 0,
    val rate: String = "d",
    @SerialName("song_id") val songId: Int,
    val title: String,
    val type: String,
    val dxScore: Int = 0,
    @Transient val apBonusEnabled: Boolean = false
) {
    val id: String
        get() = "${songId}_${levelIndex}_$type"

    val difficulty: DifficultyType
        get() = DifficultyType.fromRawValue(levelIndex)

    val isUtage: Boolean
        get() = songId >= 100_000 || levelIndex >= 10 || type.equals("utage", ignoreCase = true)

    val isAllPerfect: Boolean
        get() = fc == "ap" || fc == "app"

    val computedRating: Int
        get() {
            if (isUtage) return 0
            return RatingCalculator.singleSongRating(
                ds = ds,
                achievement = achievements,
                isAP = isAllPerfect,
                apBonusEnabled = apBonusEnabled
            )
        }

    fun projected(onto: SongData, region: MaimaiConstants.ServerRegion): ProberRecord {
        val chartIdx = onto.chartArrayIndex(levelIndex)
        val targetDs: Double
        val targetLevel: String
        val targetTitle: String

        if (region == MaimaiConstants.ServerRegion.CN) {
            targetDs = if (isUtage) {
                0.0
            } else if (ds > 0.0) {
                ds
            } else {
                chartIdx?.let { onto.ds.getOrNull(it) } ?: 0.0
            }
            targetLevel = if (level != "-" && level.isNotEmpty()) {
                level
            } else {
                chartIdx?.let { onto.level.getOrNull(it) } ?: level
            }
            targetTitle = if (title.isNotEmpty() && !title.startsWith("Song #")) {
                title
            } else {
                onto.title.ifEmpty { title }
            }
        } else {
            targetDs = if (isUtage) 0.0 else (chartIdx?.let { onto.ds.getOrNull(it) } ?: ds)
            targetLevel = chartIdx?.let { onto.level.getOrNull(it) } ?: level
            targetTitle = onto.title.ifEmpty { title }
        }

        return copy(
            ds = targetDs,
            level = targetLevel,
            title = targetTitle,
            apBonusEnabled = region.usesAPBonus
        )
    }

    fun rankAssetName(region: MaimaiConstants.ServerRegion): String =
        MaimaiConstants.rankAssetName(rate, region)

    val fcAssetName: String?
        get() = when (fc) {
            "fc", "fcp", "ap", "app" -> "mmd_player_rtsong_$fc"
            else -> null
        }

    val fsAssetName: String?
        get() = when (fs) {
            "fs", "fsp", "fsd", "fsdp" -> "mmd_player_rtsong_$fs"
            else -> null
        }

    val rankAccessibilityLabel: String
        get() = "评级 ${rate.uppercase().replace("P", "+")}"

    val fcAccessibilityLabel: String
        get() {
            val display = when (fc) {
                "fc" -> "FC"
                "fcp" -> "FC+"
                "ap" -> "AP"
                "app" -> "AP+"
                else -> fc.uppercase()
            }
            return "连击状态 $display"
        }

    val fsAccessibilityLabel: String
        get() {
            val display = when (fs) {
                "fs" -> "FS"
                "fsp" -> "FS+"
                "fsd" -> "FSD"
                "fsdp" -> "FSD+"
                else -> fs.uppercase()
            }
            return "同步状态 $display"
        }

    val typeAssetName: String
        get() = if (type == MaimaiConstants.CHART_TYPE_DX) {
            "mmd_player_rtsong_icon_dx"
        } else {
            "mmd_player_rtsong_icon_standard"
        }

    val ratingBoardAssetName: String
        get() = when (levelIndex) {
            0 -> "mmd_rating_board_bsc"
            1 -> "mmd_rating_board_adv"
            2 -> "mmd_rating_board_exp"
            3 -> "mmd_rating_board_mas"
            else -> "mmd_rating_board_rem"
        }

    val ratingDiffAssetName: String
        get() = when (levelIndex) {
            0 -> "mmd_rating_diff_basic"
            1 -> "mmd_rating_diff_advanced"
            2 -> "mmd_rating_diff_expert"
            3 -> "mmd_rating_diff_master"
            else -> "mmd_rating_diff_remaster"
        }
}

@Serializable
enum class ScoreProvider {
    @SerialName("lxns") LXNS,
    @SerialName("divingFish") DIVING_FISH;

    val displayName: String
        get() = when (this) {
            LXNS -> "落雪咖啡屋"
            DIVING_FISH -> "水鱼查分器"
        }

    val shortName: String
        get() = when (this) {
            LXNS -> "落雪"
            DIVING_FISH -> "水鱼"
        }
}

@Serializable
enum class ScoreAuthMethod {
    @SerialName("lxnsOAuth") LXNS_OAUTH,
    @SerialName("lxnsPersonalToken") LXNS_PERSONAL_TOKEN,
    @SerialName("divingFishPassword") DIVING_FISH_PASSWORD,
    @SerialName("divingFishImportToken") DIVING_FISH_IMPORT_TOKEN;

    val shortTitle: String
        get() = when (this) {
            LXNS_OAUTH -> "OAuth 授权"
            LXNS_PERSONAL_TOKEN -> "个人密钥"
            DIVING_FISH_PASSWORD -> "账号密码"
            DIVING_FISH_IMPORT_TOKEN -> "导入 Token"
        }
}

@Serializable
data class ScoreProfile(
    val id: String,
    val provider: ScoreProvider,
    val authMethod: ScoreAuthMethod,
    val accountKey: String,
    val username: String,
    val nickname: String,
    val lastSyncedAtEpochMs: Long? = null
) {
    val displayTitle: String
        get() {
            val trimmedNick = nickname.trim()
            if (trimmedNick.isNotEmpty()) return trimmedNick
            val trimmedUser = username.trim()
            if (trimmedUser.isNotEmpty()) return trimmedUser
            return "${provider.shortName}档案"
        }

    val subtitleText: String
        get() {
            val parts = mutableListOf(provider.shortName, authMethod.shortTitle)
            val trimmedUser = username.trim()
            if (trimmedUser.isNotEmpty() && trimmedUser != displayTitle) {
                parts += trimmedUser
            }
            return parts.joinToString(" · ")
        }
}

@Serializable
data class ScoreProfileSecret(
    val password: String = "",
    val cookie: String = "",
    val importToken: String = "",
    val personalToken: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val accessTokenExpiresAtEpochSec: Long? = null
)

@Serializable
data class SavedAccount(
    val username: String,
    val password: String
)

data class ChecklistStats(
    val sssCount: Int,
    val fcCount: Int,
    val apCount: Int,
    val fsdCount: Int,
    val clearCount: Int,
    val totalCount: Int
) {
    constructor(records: List<ProberRecord>, totalCount: Int) : this(
        sssCount = records.count { it.achievements >= 100.0 },
        fcCount = records.count { it.fc.isNotEmpty() },
        apCount = records.count { it.fc == "ap" || it.fc == "app" },
        fsdCount = records.count { it.fs == "fsd" || it.fs == "fsdp" },
        clearCount = records.count { it.achievements >= 80.0 },
        totalCount = totalCount
    )
}

enum class SongSortOption(val label: String) {
    DEFAULT_DESC("默认排序"),
    EXPERT_ASC("EXPERT-升序"),
    EXPERT_DESC("EXPERT-降序"),
    MASTER_ASC("MASTER-升序"),
    MASTER_DESC("MASTER-降序"),
    REMASTER_ASC("RE:MASTER-升序"),
    REMASTER_DESC("RE:MASTER-降序");

    val filterDifficulty: DifficultyType?
        get() = when (this) {
            DEFAULT_DESC -> null
            EXPERT_ASC, EXPERT_DESC -> DifficultyType.EXPERT
            MASTER_ASC, MASTER_DESC -> DifficultyType.MASTER
            REMASTER_ASC, REMASTER_DESC -> DifficultyType.REMASTER
        }
}

data class SongFilterCriteria(
    val keyword: String = "",
    val selectedGenres: Set<String> = emptySet(),
    val selectedVersions: Set<String> = emptySet(),
    val selectedLevel: String = "ALL",
    val useExactDS: Boolean = false,
    val exactDS: Double = 1.0,
    val onlyFavorites: Boolean = false,
    val sortOption: SongSortOption = SongSortOption.DEFAULT_DESC
) {
    /** Never include `keyword` here — prevents CJK IME composition flicker on the active-filter bar. */
    val isActive: Boolean
        get() = selectedGenres.isNotEmpty() ||
            selectedVersions.isNotEmpty() ||
            selectedLevel != "ALL" ||
            useExactDS ||
            onlyFavorites ||
            sortOption != SongSortOption.DEFAULT_DESC
}

data class AppReleaseInfo(
    val version: String,
    val notes: String,
    val htmlUrl: String
) {
    fun isNewerThan(currentVersion: String): Boolean {
        val cleanedCurrent = currentVersion.trim().trimStart('v', 'V')
        return VersionString.isNewer(version, than = cleanedCurrent)
    }

    companion object {
        private const val ATOM_NAMESPACE = "http://www.w3.org/2005/Atom"
        private const val RELEASE_TAG_PREFIX = "https://github.com/SorrowRain/MaimaiData-ReMaster/releases/tag/v"
        private val stableVersion = Regex("""[0-9]+\.[0-9]+\.[0-9]+""")
        private val otherEncoding = Regex("""\A<\?xml\s+[^?]*\bencoding\s*=\s*(['"])(?!UTF-8\1)""", RegexOption.IGNORE_CASE)
        private val noteTypes = setOf(null, "text", "text/plain", "html")
        private val listStart = Regex("""(?i)(<li\b[^>]*>)\s*(?:<p\b[^>]*>\s*)?""")
        private val lineTags = setOf("p", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "div", "blockquote", "pre", "hr")
        private val tags = Regex("""(?is)<!--.*?-->|<(/?)([a-z][a-z0-9:-]*)(?:[^"'<>]++|"[^"]*"|'[^']*')*>""")
        private val entities = Regex("""&(amp|lt|gt|quot|apos|nbsp|#[0-9]+|#[xX][0-9a-fA-F]+);""")

        fun fromAtom(data: ByteArray): AppReleaseInfo {
            val xml = data.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
            require("<!DOCTYPE" !in xml && '\u0000' !in xml && !otherEncoding.containsMatchIn(xml)) {
                "Invalid GitHub release feed"
            }
            val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
            require(root.localName == "feed" && root.namespaceURI == ATOM_NAMESPACE) { "Invalid GitHub release feed" }

            var latest: AppReleaseInfo? = null
            for (entry in root.atomChildren("entry")) {
                val (version, url) = entry.atomChildren("link").firstNotNullOfOrNull { link ->
                    val href = link.getAttribute("href")
                    val tag = href.removePrefix(RELEASE_TAG_PREFIX)
                    if (link.getAttribute("rel") == "alternate" && href.startsWith(RELEASE_TAG_PREFIX) &&
                        stableVersion.matches(tag) && tag.split('.').all { it.toIntOrNull() != null }
                    ) tag to href else null
                } ?: continue
                if (latest != null && !VersionString.isNewer(version, than = latest.version)) continue
                val content = entry.atomChildren("content").firstOrNull { it.getAttributeNode("type")?.value in noteTypes }
                val rawNotes = content?.childNodes?.let { children ->
                    (0 until children.length).joinToString("") { (children.item(it) as? Text)?.data.orEmpty() }
                }.orEmpty()
                val notes = (if (content?.getAttribute("type") == "html") plainNotes(rawNotes) else rawNotes).trim()
                val cappedNotes = if (notes.codePointCount(0, notes.length) > 420) {
                    notes.substring(0, notes.offsetByCodePoints(0, 420)) + "…"
                } else notes
                latest = AppReleaseInfo(version, cappedNotes, url)
            }
            return requireNotNull(latest) { "No stable release in GitHub release feed" }
        }

        private fun Element.atomChildren(name: String): Sequence<Element> {
            val children = childNodes
            return (0 until children.length).asSequence().mapNotNull { children.item(it) as? Element }
                .filter { it.localName == name && it.namespaceURI == ATOM_NAMESPACE }
        }

        private fun plainNotes(html: String): String = html.replace(listStart, "\$1").replace(tags) { match ->
            val tag = match.groupValues[2].lowercase()
            when {
                tag == "li" && match.groupValues[1].isEmpty() -> "\n- "
                tag in lineTags -> "\n"
                else -> ""
            }
        }.replace(entities) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> {
                    val codePoint = if (entity.startsWith("#x", ignoreCase = true)) {
                        entity.drop(2).toIntOrNull(16)
                    } else entity.drop(1).toIntOrNull()
                    codePoint?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }
                        ?.let { String(Character.toChars(it)) } ?: match.value
                }
            }
        }.lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString("\n")
    }
}
