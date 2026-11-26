import Foundation

/// Serialized append-only JSONL history store (`history_identity_<uuid>.jsonl`) for Rating/score trend tracking.
final class SyncHistoryStore {
    private let queue = DispatchQueue(label: "dev.0xsr.maimaidata.syncHistoryStore")
    private let directoryURL: URL
    private let encoder: JSONEncoder = {
        let enc = JSONEncoder()
        return enc
    }()
    private let decoder = JSONDecoder()

    init(directoryURL: URL) {
        self.directoryURL = directoryURL
    }

    func historyFileURL(for identityId: String) -> URL {
        directoryURL.appendingPathComponent("history_identity_\(identityId.lowercased()).jsonl")
    }

    func appendSnapshot(
        identityId: String,
        syncMode: SyncMode,
        records: [ProberRecord],
        songsById: [Int: SongData],
        timestampEpochMs: Int64 = Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
    ) throws {
        let lowerId = identityId.lowercased()
        let snapshot = ScoreSyncEngine.computeCNRatingSnapshot(records: records, songsById: songsById)
        let playedSongsCount = Set(records.map(\.songId)).count

        try queue.sync {
            let fm = FileManager.default
            if !fm.fileExists(atPath: directoryURL.path) {
                try fm.createDirectory(at: directoryURL, withIntermediateDirectories: true)
            }
            let fileURL = historyFileURL(for: lowerId)
            let existing = loadEntriesUnlocked(from: fileURL)
            let hasAuthoritativeBaseline = existing.contains(where: \.isBadgeBaseline)
            let isBaseline = (syncMode == .full) && !hasAuthoritativeBaseline

            let entry = SyncHistoryEntry(
                identityId: lowerId,
                timestampEpochMs: timestampEpochMs,
                syncMode: syncMode,
                cnRating: snapshot.cnRating,
                b35Rating: snapshot.b35Rating,
                b15Rating: snapshot.b15Rating,
                recordCount: records.count,
                playedSongsCount: playedSongsCount,
                isBadgeBaseline: isBaseline
            )

            var line = try encoder.encode(entry)
            line.append(0x0A) // '\n'

            if fm.fileExists(atPath: fileURL.path) {
                let handle = try FileHandle(forWritingTo: fileURL)
                defer { try? handle.close() }
                let fileSize = try handle.seekToEnd()
                if fileSize > 0 {
                    try handle.seek(toOffset: fileSize - 1)
                    let lastByte = try handle.read(upToCount: 1)
                    if lastByte?.first != 0x0A {
                        try handle.seekToEnd()
                        try handle.write(contentsOf: Data([0x0A]))
                    }
                }
                try handle.write(contentsOf: line)
            } else {
                try line.write(to: fileURL, options: .atomic)
            }
        }
    }

    func loadHistory(for identityId: String) -> [SyncHistoryEntry] {
        let fileURL = historyFileURL(for: identityId.lowercased())
        return queue.sync {
            loadEntriesUnlocked(from: fileURL)
        }
    }

    func deleteHistory(for identityId: String) throws {
        let fileURL = historyFileURL(for: identityId.lowercased())
        try queue.sync {
            let fm = FileManager.default
            if fm.fileExists(atPath: fileURL.path) {
                try fm.removeItem(at: fileURL)
            }
        }
    }

    private func loadEntriesUnlocked(from fileURL: URL) -> [SyncHistoryEntry] {
        guard let data = try? Data(contentsOf: fileURL),
              let content = String(data: data, encoding: .utf8) else {
            return []
        }
        return content
            .split(whereSeparator: \.isNewline)
            .compactMap { line -> SyncHistoryEntry? in
                let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty, let lineData = trimmed.data(using: .utf8) else { return nil }
                return try? decoder.decode(SyncHistoryEntry.self, from: lineData)
            }
    }
}
