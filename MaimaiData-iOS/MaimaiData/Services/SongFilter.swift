import Foundation

/// Pure filtering/sorting logic for the song list (no store state), so it can be unit tested.
enum SongFilter {
    static func prunedCriteria(
        _ criteria: SongFilterCriteria,
        for region: MaimaiConstants.ServerRegion
    ) -> SongFilterCriteria {
        var copy = criteria
        if !copy.selectedVersions.isEmpty {
            let allowed = Set(MaimaiConstants.searchVersionChips(for: region))
            copy.selectedVersions = copy.selectedVersions.intersection(allowed)
        }
        if copy.useExactDS {
            copy.exactDS = MaimaiConstants.clampExactDS(copy.exactDS)
        }
        return copy
    }

    static func apply(
        _ criteria: SongFilterCriteria,
        to songs: [SongData],
        favoriteIds: Set<String>,
        enableAliasSearch: Bool,
        enableCharterSearch: Bool,
        region: MaimaiConstants.ServerRegion = .cn
    ) -> [SongData] {
        let query = normalizeSearchText(criteria.keyword)
        let queryNumericId = Int(query).flatMap { $0 > 0 ? $0 : nil }
        let expandedVersions = MaimaiConstants.expandVersionFilter(
            Array(criteria.selectedVersions),
            region: region
        )
        let targetDS = criteria.useExactDS ? (criteria.exactDS * 10).rounded() / 10 : nil
        let levelFilter = criteria.useExactDS ? nil : criteria.selectedLevel
        let seqDiff = criteria.sortOption.filterDifficulty

        let filtered = songs.filter { song in
            if song.isDisabled == true {
                return false
            }

            // 1. Genre (default excludes 宴会場 when no genre selected, matching Android DAO)
            if criteria.selectedGenres.isEmpty {
                if song.isUtage { return false }
            } else if !criteria.selectedGenres.contains(song.basicInfo.genre) {
                return false
            }

            // 2. Version
            if !expandedVersions.isEmpty && !expandedVersions.contains(song.basicInfo.from) {
                return false
            }

            // 3. Favorites
            if criteria.onlyFavorites && !favoriteIds.contains(song.id) {
                return false
            }

            // 4. Keyword (title, alias, charter, or exact ID / numericId)
            if !query.isEmpty {
                let titleMatch = normalizeSearchText(song.title).contains(query)
                let aliasMatch = enableAliasSearch && (song.alias?.contains { normalizeSearchText($0).contains(query) } ?? false)
                let charterMatch = enableCharterSearch && song.charts.contains { normalizeSearchText($0.charter).contains(query) }
                let idMatch = normalizeSearchText(song.id) == query
                    || (queryNumericId != nil && song.numericId == queryNumericId)
                if !(titleMatch || aliasMatch || charterMatch || idMatch) {
                    return false
                }
            }

            // 5. Level or DS
            if let targetDS {
                if !song.ds.contains(where: { abs($0 - targetDS) < 0.001 }) {
                    return false
                }
            } else if let levelFilter, levelFilter != "ALL" {
                if let seqDiff {
                    let idx = seqDiff.rawValue
                    guard song.level.indices.contains(idx), song.level[idx] == levelFilter else {
                        return false
                    }
                } else if !song.level.contains(levelFilter) {
                    return false
                }
            }

            return true
        }

        switch criteria.sortOption {
        case .defaultDesc:
            return filtered.sorted { SongData.isOrderedBeforeInDefaultSort($0, $1) }
        case .expertAsc:
            return filtered.sorted { ($0.ds[safe: 2] ?? 0) < ($1.ds[safe: 2] ?? 0) }
        case .expertDesc:
            return filtered.sorted { ($0.ds[safe: 2] ?? 0) > ($1.ds[safe: 2] ?? 0) }
        case .masterAsc:
            return filtered.sorted { ($0.ds[safe: 3] ?? 0) < ($1.ds[safe: 3] ?? 0) }
        case .masterDesc:
            return filtered.sorted { ($0.ds[safe: 3] ?? 0) > ($1.ds[safe: 3] ?? 0) }
        case .remasterAsc:
            return filtered.sorted { Self.compareRemaster($0, $1, ascending: true) }
        case .remasterDesc:
            return filtered.sorted { Self.compareRemaster($0, $1, ascending: false) }
        }
    }

    private static func compareRemaster(_ a: SongData, _ b: SongData, ascending: Bool) -> Bool {
        let aHas = a.ds.count >= 5
        let bHas = b.ds.count >= 5
        if aHas != bHas { return aHas }
        let aDs = a.ds[safe: 4] ?? 0
        let bDs = b.ds[safe: 4] ?? 0
        if aDs == bDs { return SongData.isOrderedBeforeInDefaultSort(a, b) }
        return ascending ? aDs < bDs : aDs > bDs
    }

    /// Normalizes full-width ASCII (`Ａ`..`Ｚ`, `０`..`９`, `　`) to half-width and lowercases.
    static func normalizeSearchText(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        var scalars = String.UnicodeScalarView()
        scalars.reserveCapacity(trimmed.unicodeScalars.count)
        for scalar in trimmed.unicodeScalars {
            let v = scalar.value
            if v == 0x3000 {
                scalars.append(UnicodeScalar(0x20)!)
            } else if v >= 0xFF01 && v <= 0xFF5E, let mapped = UnicodeScalar(v - 0xFEE0) {
                scalars.append(mapped)
            } else {
                scalars.append(scalar)
            }
        }
        return String(scalars)
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
    }
}
