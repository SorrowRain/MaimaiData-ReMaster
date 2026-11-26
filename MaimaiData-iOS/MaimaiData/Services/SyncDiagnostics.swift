import Foundation
import os

final class SyncDiagnostics {
    static let shared = SyncDiagnostics()

    private let queue = DispatchQueue(label: "dev.0xsr.maimaidata.syncDiagnostics")
    private let logger = Logger(subsystem: "dev.0xsr.maimaidata", category: "Sync")
    private var fileURL: URL?
    private var entries: [String] = []

    private let maxEntries = 200
    private let maxFileBytes = 64 * 1024
    private let trimmedFileBytes = 48 * 1024

    private static let formatter: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return df
    }()

    private init() {}

    func configure(directoryURL: URL) {
        let url = directoryURL.appendingPathComponent("sync_diagnostics.log")
        queue.sync {
            self.fileURL = url
            if let data = try? Data(contentsOf: url),
               let text = String(data: data, encoding: .utf8) {
                let loaded = text.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
                self.entries = Array(loaded.suffix(maxEntries))
            }
        }
    }

    func record(
        operation: String,
        channel: String? = nil,
        statusCode: Int? = nil,
        errorCode: String? = nil,
        message: String,
        uploadedCount: Int? = nil,
        skippedCount: Int? = nil,
        sampleSkippedIds: [Int] = []
    ) {
        let timestamp = Self.formatter.string(from: Date())
        var parts = ["[\(timestamp)]", "op=\(sanitize(operation, limit: 40))"]
        if let channel, !channel.isEmpty {
            parts.append("ch=\(sanitize(channel, limit: 30))")
        }
        if let statusCode {
            parts.append("http=\(statusCode)")
        }
        if let errorCode, !errorCode.isEmpty {
            parts.append("code=\(sanitize(errorCode, limit: 60))")
        }
        if let uploadedCount {
            parts.append("up=\(uploadedCount)")
        }
        if let skippedCount, skippedCount > 0 {
            parts.append("skip=\(skippedCount)")
        }
        if !sampleSkippedIds.isEmpty {
            let ids = sampleSkippedIds.prefix(5).map(String.init).joined(separator: ",")
            parts.append("skipIds=[\(ids)]")
        }
        let cleanedMessage = sanitize(message, limit: 280)
        if !cleanedMessage.isEmpty {
            parts.append("msg=\"\(cleanedMessage)\"")
        }
        let line = parts.joined(separator: " ")

        logger.log("\(line, privacy: .public)")

        queue.async { [weak self] in
            guard let self else { return }
            self.entries.append(line)
            if self.entries.count > self.maxEntries {
                self.entries.removeFirst(self.entries.count - self.maxEntries)
            }
            self.appendLineToFileLocked(line)
        }
    }

    func exportText() -> String {
        queue.sync {
            if !entries.isEmpty {
                return entries.joined(separator: "\n")
            }
            guard let fileURL,
                  let data = try? Data(contentsOf: fileURL),
                  let text = String(data: data, encoding: .utf8) else {
                return ""
            }
            return text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    private func appendLineToFileLocked(_ line: String) {
        guard let fileURL, let payload = (line + "\n").data(using: .utf8) else { return }
        let fm = FileManager.default
        if !fm.fileExists(atPath: fileURL.path) {
            try? payload.write(to: fileURL, options: .atomic)
            return
        }
        if let handle = try? FileHandle(forWritingTo: fileURL) {
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: payload)
            try? handle.close()
        }
        guard let attrs = try? fm.attributesOfItem(atPath: fileURL.path),
              let size = attrs[.size] as? Int,
              size > maxFileBytes,
              let data = try? Data(contentsOf: fileURL) else {
            return
        }
        let suffix = data.suffix(trimmedFileBytes)
        if let firstNewline = suffix.firstIndex(of: UInt8(ascii: "\n")) {
            let trimmed = Data(suffix[suffix.index(after: firstNewline)...])
            try? trimmed.write(to: fileURL, options: .atomic)
        } else {
            try? Data(suffix).write(to: fileURL, options: .atomic)
        }
    }

    func sanitize(_ raw: String, limit: Int = 280) -> String {
        var singleLine = raw
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\"", with: "'")
        singleLine = Self.redactSensitiveTokens(in: singleLine)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if singleLine.count <= limit { return singleLine }
        return String(singleLine.prefix(limit)) + "…"
    }

    private static let redactionRegexes: [(regex: NSRegularExpression, template: String)] = {
        let specs: [(String, String)] = [
            ("SGWCMAID[A-Za-z0-9+/=_-]*", "[REDACTED]"),
            ("(?i)Bearer\\s+\\S+", "Bearer [REDACTED]"),
            ("(?i)jwt_token=[^;\\s]+", "jwt_token=[REDACTED]"),
            ("(?i)\\b(token|secret|password|cookie)\\s*[:=]\\s*\\S+", "$1=[REDACTED]"),
            ("\\b[A-Fa-f0-9]{32,}\\b", "[REDACTED]")
        ]
        return specs.compactMap { pattern, template in
            guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
            return (regex, template)
        }
    }()

    private static func redactSensitiveTokens(in text: String) -> String {
        var current = text
        for entry in redactionRegexes {
            let range = NSRange(current.startIndex..., in: current)
            current = entry.regex.stringByReplacingMatches(
                in: current,
                options: [],
                range: range,
                withTemplate: entry.template
            )
        }
        return current
    }
}
