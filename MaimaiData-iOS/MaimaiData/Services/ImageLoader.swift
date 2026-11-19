import SwiftUI
import UIKit

final class CoverImageCache: @unchecked Sendable {
    static let shared = CoverImageCache()

    private static let maxMemoryCostBytes = 80 * 1024 * 1024
    private static let maxDiskCacheBytes: Int64 = 256 * 1024 * 1024

    private let memory = NSCache<NSString, UIImage>()
    private let diskDir: URL
    private let session: URLSession
    private let coordinator = InFlightCoordinator()
    private let diskMaintenance = DiskCacheMaintenance()

    private init() {
        memory.countLimit = 400
        memory.totalCostLimit = Self.maxMemoryCostBytes
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        diskDir = caches.appendingPathComponent("MaimaiCovers", isDirectory: true)
        try? FileManager.default.createDirectory(at: diskDir, withIntermediateDirectories: true)

        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 12
        config.timeoutIntervalForResource = 30
        config.httpMaximumConnectionsPerHost = 8
        session = URLSession(configuration: config)
    }

    func clearCache() {
        memory.removeAllObjects()
        try? FileManager.default.removeItem(at: diskDir)
        try? FileManager.default.createDirectory(at: diskDir, withIntermediateDirectories: true)
    }

    func cacheSizeBytes() -> Int64 {
        guard let files = try? FileManager.default.contentsOfDirectory(at: diskDir, includingPropertiesForKeys: [.fileSizeKey]) else {
            return 0
        }
        return files.reduce(0) { sum, url in
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return sum + Int64(size)
        }
    }

    private func cacheKey(for song: SongData) -> String {
        if song.numericId > 0, song.preferRemoteCover != true {
            return "jacket_\(song.baseJacketId)"
        }
        if let remote = song.remoteCoverName, !remote.isEmpty {
            let sanitized = remote.replacingOccurrences(of: "/", with: "_")
            let stem = (sanitized as NSString).deletingPathExtension
            return "jacket_remote_\(stem.isEmpty ? sanitized : stem)"
        }
        if song.numericId > 0 {
            return "jacket_\(song.baseJacketId)"
        }
        return "jacket_\(song.id)"
    }

    func cachedMemoryImage(for song: SongData) -> UIImage? {
        memory.object(forKey: cacheKey(for: song) as NSString)
    }

    func loadCover(song: SongData) async -> UIImage? {
        let keyString = cacheKey(for: song)
        let key = keyString as NSString

        if let cached = memory.object(forKey: key) {
            return cached
        }

        let urls = song.coverCandidateURLs
        let diskFile = diskDir.appendingPathComponent("\(keyString).png")
        let loaded = await coordinator.coalesce(key: keyString) { [session, diskDir, diskMaintenance] in
            await Self.fetchFromDiskOrNetwork(
                diskFile: diskFile,
                diskDir: diskDir,
                urls: urls,
                session: session,
                diskMaintenance: diskMaintenance
            )
        }
        if let loaded {
            memory.setObject(loaded, forKey: key, cost: Self.imageMemoryCost(loaded))
        }
        return loaded
    }

    private static func imageMemoryCost(_ image: UIImage) -> Int {
        let pixelsW = Int(image.size.width * image.scale)
        let pixelsH = Int(image.size.height * image.scale)
        return max(1, pixelsW * pixelsH * 4)
    }

    private static func fetchFromDiskOrNetwork(
        diskFile: URL,
        diskDir: URL,
        urls: [URL],
        session: URLSession,
        diskMaintenance: DiskCacheMaintenance
    ) async -> UIImage? {
        if let diskImage = await Task.detached(priority: .utility, operation: { () -> UIImage? in
            guard let data = try? Data(contentsOf: diskFile) else { return nil }
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: diskFile.path)
            return UIImage(data: data)
        }).value {
            return await diskImage.byPreparingForDisplay() ?? diskImage
        }

        for url in urls {
            if let data = await downloadImageData(url: url, session: session),
               let rawImg = UIImage(data: data) {
                try? data.write(to: diskFile, options: .atomic)
                Task.detached(priority: .background) {
                    await diskMaintenance.recordWriteAndTrimIfNeeded(
                        diskDir: diskDir,
                        maxBytes: CoverImageCache.maxDiskCacheBytes
                    )
                }
                return await rawImg.byPreparingForDisplay() ?? rawImg
            }
        }
        return nil
    }

    private static func downloadImageData(url: URL, session: URLSession) async -> Data? {
        do {
            var req = URLRequest(url: url)
            req.setValue("MaimaiData-iOS/1.0", forHTTPHeaderField: "User-Agent")
            let (data, resp) = try await session.data(for: req)
            guard let http = resp as? HTTPURLResponse,
                  (200...299).contains(http.statusCode),
                  !data.isEmpty else {
                return nil
            }
            return data
        } catch {
            return nil
        }
    }
}

private actor InFlightCoordinator {
    private var tasks: [String: Task<UIImage?, Never>] = [:]

    func coalesce(key: String, operation: @escaping @Sendable () async -> UIImage?) async -> UIImage? {
        if let existing = tasks[key] {
            return await existing.value
        }
        let task = Task {
            await operation()
        }
        tasks[key] = task
        let result = await task.value
        tasks[key] = nil
        return result
    }
}

private actor DiskCacheMaintenance {
    private var writesSinceTrim = 0

    func recordWriteAndTrimIfNeeded(diskDir: URL, maxBytes: Int64) {
        writesSinceTrim += 1
        guard writesSinceTrim >= 25 else { return }
        writesSinceTrim = 0

        let keys: Set<URLResourceKey> = [.fileSizeKey, .contentModificationDateKey]
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: diskDir,
            includingPropertiesForKeys: Array(keys)
        ) else { return }

        var entries: [(url: URL, size: Int64, mtime: Date)] = []
        var totalSize: Int64 = 0
        for url in urls {
            guard let values = try? url.resourceValues(forKeys: keys),
                  let size = values.fileSize else { continue }
            let mtime = values.contentModificationDate ?? .distantPast
            entries.append((url, Int64(size), mtime))
            totalSize += Int64(size)
        }
        guard totalSize > maxBytes else { return }

        let targetSize = (maxBytes * 8) / 10
        entries.sort { $0.mtime < $1.mtime }
        for entry in entries {
            guard totalSize > targetSize else { break }
            try? FileManager.default.removeItem(at: entry.url)
            totalSize -= entry.size
        }
    }
}

struct CoverImageView: View {
    let song: SongData?

    @State private var image: UIImage?

    private var taskKey: String {
        guard let song else { return "" }
        return "\(song.id)|\(song.remoteCoverName ?? "")|\(song.preferRemoteCover ?? false)"
    }

    var body: some View {
        let displayed = image ?? song.flatMap { CoverImageCache.shared.cachedMemoryImage(for: $0) }
        Group {
            if let displayed {
                Image(uiImage: displayed)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                Image("mmd_song_jacket_placeholder")
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            }
        }
        .task(id: taskKey) {
            guard let song else {
                image = nil
                return
            }
            if let mem = CoverImageCache.shared.cachedMemoryImage(for: song) {
                image = mem
                return
            }
            image = nil
            if let loaded = await CoverImageCache.shared.loadCover(song: song) {
                withAnimation(.easeOut(duration: 0.18)) {
                    image = loaded
                }
            }
        }
    }
}
