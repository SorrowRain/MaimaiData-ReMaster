package dev.sorrowrain.maimaidata.models

import androidx.compose.ui.graphics.Color
import dev.sorrowrain.maimaidata.theme.AppTheme

enum class DifficultyType(val rawValue: Int) {
    BASIC(0),
    ADVANCED(1),
    EXPERT(2),
    MASTER(3),
    REMASTER(4),
    UTAGE(10),
    UTAGE_PLAYER2(11),
    UNKNOWN(-1);

    /** Canonical level index used by `ProberRecord.levelIndex` and prober payloads. */
    val recordLevelIndex: Int
        get() = rawValue

    val shortName: String
        get() = when (this) {
            BASIC -> "BAS"
            ADVANCED -> "ADV"
            EXPERT -> "EXP"
            MASTER -> "MAS"
            REMASTER -> "Re:MAS"
            UTAGE -> "宴会場"
            UTAGE_PLAYER2 -> "2P"
            UNKNOWN -> "?"
        }

    val color: Color
        get() = when (this) {
            BASIC -> AppTheme.Palette.diffBasic
            ADVANCED -> AppTheme.Palette.diffAdvanced
            EXPERT -> AppTheme.Palette.diffExpert
            MASTER -> AppTheme.Palette.diffMaster
            REMASTER -> AppTheme.Palette.diffRemaster
            UTAGE, UTAGE_PLAYER2 -> AppTheme.Palette.diffUtage
            UNKNOWN -> Color.Gray
        }

    val readableForeground: Color
        get() = when (this) {
            BASIC -> AppTheme.Palette.diffBasicText
            ADVANCED -> AppTheme.Palette.diffAdvancedText
            EXPERT -> AppTheme.Palette.diffExpertText
            else -> Color.White
        }

    val boardMainColor: Color
        get() = when (this) {
            BASIC -> AppTheme.Palette.boardMainBasic
            ADVANCED -> AppTheme.Palette.boardMainAdvanced
            EXPERT -> AppTheme.Palette.boardMainExpert
            MASTER -> AppTheme.Palette.boardMainMaster
            REMASTER -> AppTheme.Palette.boardMainRemaster
            else -> AppTheme.Palette.diffUtage.brightened(minimumBrightness = 1.0f, maxSaturation = 0.5f)
        }

    fun solidFill(isDark: Boolean): Color {
        return if (isDark && (this == BASIC || this == ADVANCED)) {
            color.copy(alpha = 0.9f)
        } else {
            color
        }
    }

    val boardDarkColor: Color
        get() = when (this) {
            BASIC -> AppTheme.Palette.diffBasicDark
            ADVANCED -> AppTheme.Palette.diffAdvancedDark
            EXPERT -> AppTheme.Palette.diffExpertDark
            MASTER -> AppTheme.Palette.diffMasterDark
            REMASTER -> AppTheme.Palette.diffRemasterDark
            else -> AppTheme.Palette.diffOtherDark
        }

    companion object {
        fun fromRawValue(index: Int): DifficultyType =
            entries.firstOrNull { it.rawValue == index } ?: UNKNOWN

        fun from(genre: String, index: Int): DifficultyType {
            if (genre == MaimaiConstants.GENRE_UTAGE) {
                return when (index) {
                    0, 10 -> UTAGE
                    1, 11 -> UTAGE_PLAYER2
                    else -> UNKNOWN
                }
            }
            return fromRawValue(index)
        }
    }
}
