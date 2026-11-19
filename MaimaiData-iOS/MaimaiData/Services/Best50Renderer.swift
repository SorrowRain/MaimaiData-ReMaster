import UIKit

enum Best50Renderer {
    private static let itemWidth: CGFloat = 200
    private static let itemHeight: CGFloat = 250
    private static let itemPadding: CGFloat = 10
    private static let versionPadding: CGFloat = 50
    private static let headerHeight: CGFloat = 250
    private static let containerWidth: CGFloat = itemWidth * 10 + itemPadding * 10 + versionPadding
    private static let containerHeight: CGFloat = itemHeight * 5 + itemPadding * 5

    static func render(
        playerName: String,
        songsById: [Int: SongData],
        b35: [ProberRecord],
        b15: [ProberRecord],
        region: MaimaiConstants.ServerRegion = .cn
    ) async -> UIImage? {
        let allRecords = b35 + b15
        guard !allRecords.isEmpty else { return nil }

        // Prefetch covers concurrently with a strict per-cover timeout so B50 never hangs
        var covers: [Int: UIImage] = [:]
        let uniqueIds = Array(Set(allRecords.map(\.songId)))

        for id in uniqueIds {
            if let song = songsById[id],
               let mem = CoverImageCache.shared.cachedMemoryImage(for: song) {
                covers[id] = mem
            }
        }

        let missingIds = uniqueIds.filter { covers[$0] == nil }
        if !missingIds.isEmpty {
            let maxConcurrency = 8
            await withTaskGroup(of: (Int, UIImage?).self) { group in
                var iterator = missingIds.makeIterator()
                var inFlight = 0
                while inFlight < maxConcurrency, let id = iterator.next() {
                    guard let song = songsById[id] else { continue }
                    inFlight += 1
                    group.addTask {
                        (id, await loadCoverWithTimeout(song: song, seconds: 8))
                    }
                }
                for await (id, img) in group {
                    if let img { covers[id] = img }
                    while let nextId = iterator.next() {
                        guard let song = songsById[nextId] else { continue }
                        group.addTask {
                            (nextId, await loadCoverWithTimeout(song: song, seconds: 8))
                        }
                        break
                    }
                }
            }
        }

        let frozenCovers = covers
        return await Task.detached(priority: .userInitiated) {
            drawBest50Canvas(
                playerName: playerName,
                covers: frozenCovers,
                b35: b35,
                b15: b15,
                region: region
            )
        }.value
    }

    private static func loadCoverWithTimeout(song: SongData, seconds: Double) async -> UIImage? {
        await withTaskGroup(of: UIImage?.self) { group in
            group.addTask {
                await CoverImageCache.shared.loadCover(song: song)
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    private struct CardTextStyles {
        let titleAttrs: [NSAttributedString.Key: Any]
        let leftMetricAttrs: [NSAttributedString.Key: Any]
        let centerMetricAttrs: [NSAttributedString.Key: Any]
        let rightMetricAttrs: [NSAttributedString.Key: Any]

        init() {
            let titleStyle = NSMutableParagraphStyle()
            titleStyle.alignment = .center
            titleStyle.lineBreakMode = .byTruncatingTail
            self.titleAttrs = [
                .font: UIFont.boldSystemFont(ofSize: 14),
                .foregroundColor: UIColor.white,
                .paragraphStyle: titleStyle
            ]

            let metricFont = UIFont.boldSystemFont(ofSize: 11.5)
            self.leftMetricAttrs = [
                .font: metricFont,
                .foregroundColor: UIColor.white
            ]

            let centerStyle = NSMutableParagraphStyle()
            centerStyle.alignment = .center
            self.centerMetricAttrs = [
                .font: metricFont,
                .foregroundColor: UIColor.white,
                .paragraphStyle: centerStyle
            ]

            let rightStyle = NSMutableParagraphStyle()
            rightStyle.alignment = .right
            self.rightMetricAttrs = [
                .font: metricFont,
                .foregroundColor: UIColor.white,
                .paragraphStyle: rightStyle
            ]
        }
    }

    private static func drawBest50Canvas(
        playerName: String,
        covers: [Int: UIImage],
        b35: [ProberRecord],
        b15: [ProberRecord],
        region: MaimaiConstants.ServerRegion
    ) -> UIImage {
        let totalSize = CGSize(width: containerWidth, height: containerHeight + headerHeight)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        format.opaque = true

        var spriteCache: [String: UIImage] = [:]
        func sprite(_ name: String) -> UIImage? {
            if let cached = spriteCache[name] { return cached }
            guard let img = UIImage(named: name) else { return nil }
            spriteCache[name] = img
            return img
        }

        let styles = CardTextStyles()
        let placeholderCover = sprite("mmd_song_jacket_placeholder")

        let renderer = UIGraphicsImageRenderer(size: totalSize, format: format)
        return renderer.image { ctx in
            let cgContext = ctx.cgContext
            if let bg = sprite("mmd_player_best50") {
                bg.draw(in: CGRect(origin: .zero, size: totalSize))
            } else {
                UIColor(red: 0.25, green: 0.58, blue: 0.94, alpha: 1).setFill()
                UIRectFill(CGRect(origin: .zero, size: totalSize))
            }

            // Old Version (B35: 7 columns x 5 rows)
            for (idx, record) in b35.enumerated() {
                let x = CGFloat(idx % 7) * (itemWidth + itemPadding)
                let y = headerHeight + CGFloat(idx / 7) * (itemHeight + itemPadding)
                drawRecordCard(
                    record,
                    cover: covers[record.songId] ?? placeholderCover,
                    region: region,
                    at: CGPoint(x: x, y: y),
                    sprite: sprite,
                    styles: styles,
                    context: cgContext
                )
            }

            // New Version (B15: 3 columns x 5 rows)
            let newOriginX = (itemWidth + itemPadding) * 7 + versionPadding
            for (idx, record) in b15.enumerated() {
                let x = newOriginX + CGFloat(idx % 3) * (itemWidth + itemPadding)
                let y = headerHeight + CGFloat(idx / 3) * (itemHeight + itemPadding)
                drawRecordCard(
                    record,
                    cover: covers[record.songId] ?? placeholderCover,
                    region: region,
                    at: CGPoint(x: x, y: y),
                    sprite: sprite,
                    styles: styles,
                    context: cgContext
                )
            }

            let oldRating = b35.reduce(0) { $0 + $1.computedRating }
            let newRating = b15.reduce(0) { $0 + $1.computedRating }
            let totalRating = oldRating + newRating

            // Rating Plate
            if let plate = sprite(ratingPlateName(for: totalRating)) {
                plate.draw(in: CGRect(x: 432, y: 49, width: 203, height: 52))
            }

            // Name Box + Rating Box
            sprite("mmd_player_name_box")?.draw(in: CGRect(x: 432, y: 110, width: 302, height: 46))
            sprite("mmd_player_rating_box")?.draw(in: CGRect(x: 432, y: 165, width: 302, height: 41))

            // Player Name (full-width arcade style when fitting within 274pt, tail-truncated otherwise)
            let nameStyle = NSMutableParagraphStyle()
            nameStyle.lineBreakMode = .byTruncatingTail
            let nameAttrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.boldSystemFont(ofSize: 26),
                .foregroundColor: UIColor.black,
                .kern: 1.6,
                .paragraphStyle: nameStyle
            ]
            let fullWidthCandidate = toFullWidthName(playerName)
            let candidateWidth = (fullWidthCandidate as NSString).size(withAttributes: nameAttrs).width
            let displayName = candidateWidth <= 274 ? fullWidthCandidate : playerName
            (displayName as NSString).draw(
                with: CGRect(x: 446, y: 117, width: 274, height: 34),
                options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine],
                attributes: nameAttrs,
                context: nil
            )

            // Total Rating Digits (right-to-left)
            var rem = totalRating
            var digitIdx = 0
            if rem == 0 {
                sprite("mmd_player_num_drating_0")?.draw(in: CGRect(x: 598, y: 58, width: 28, height: 34))
            } else {
                while rem > 0 {
                    let digit = rem % 10
                    rem /= 10
                    sprite("mmd_player_num_drating_\(digit)")?
                        .draw(in: CGRect(x: 598 - 21 * CGFloat(digitIdx), y: 58, width: 28, height: 34))
                    digitIdx += 1
                }
            }

            // Breakdown Text
            let subAttrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 14, weight: .bold),
                .foregroundColor: UIColor.black
            ]
            let breakdown = "旧版本：\(oldRating)   现行版本：\(newRating)" as NSString
            breakdown.draw(at: CGPoint(x: 472, y: 176), withAttributes: subAttrs)
        }
    }

    private static func drawRecordCard(
        _ record: ProberRecord,
        cover: UIImage?,
        region: MaimaiConstants.ServerRegion,
        at origin: CGPoint,
        sprite: (String) -> UIImage?,
        styles: CardTextStyles,
        context: CGContext
    ) {
        let cardRect = CGRect(origin: origin, size: CGSize(width: itemWidth, height: itemHeight))

        sprite(record.ratingBoardAssetName)?.draw(in: cardRect)

        // Cover jacket (center-crop matching Android)
        let jacketRect = CGRect(x: origin.x + 21, y: origin.y + 24, width: 158, height: 155)
        if let cover {
            drawCenterCropped(cover, in: jacketRect, context: context)
        }

        // Difficulty tag
        if let diffImg = sprite(record.ratingDiffAssetName) {
            let w = diffImg.size.width * 0.5
            let h = diffImg.size.height * 0.5
            diffImg.draw(in: CGRect(x: origin.x + itemWidth - w - 25, y: origin.y + 30, width: w, height: h))
        }

        // DX / SD type badge
        sprite(record.typeAssetName)?.draw(in: CGRect(x: origin.x + 101, y: origin.y, width: 96, height: 27))

        // Rank badge
        sprite(record.rankAssetName(for: region))?.draw(in: CGRect(x: origin.x + 22, y: origin.y + 145, width: 84, height: 32))

        // FC / FS badges (supports FC+FS, FC-only, and FS-only)
        let fcImg = record.fcAssetName.flatMap(sprite)
        let fsImg = record.fsAssetName.flatMap(sprite)
        if let fcImg, let fsImg {
            fcImg.draw(in: CGRect(x: origin.x + 115, y: origin.y + 145, width: 34, height: 36))
            fsImg.draw(in: CGRect(x: origin.x + 145, y: origin.y + 145, width: 34, height: 36))
        } else if let fcImg {
            fcImg.draw(in: CGRect(x: origin.x + 145, y: origin.y + 145, width: 34, height: 36))
        } else if let fsImg {
            fsImg.draw(in: CGRect(x: origin.x + 145, y: origin.y + 145, width: 34, height: 36))
        }

        // Title
        (record.title as NSString).draw(
            in: CGRect(x: origin.x + 16, y: origin.y + 186, width: itemWidth - 32, height: 20),
            withAttributes: styles.titleAttrs
        )

        // Bottom metrics (Lv, Achievement, Ra)
        ("Lv:\(String(format: "%.1f", record.ds))" as NSString)
            .draw(at: CGPoint(x: origin.x + 18, y: origin.y + 214), withAttributes: styles.leftMetricAttrs)
        (String(format: "%.4f%%", record.achievements) as NSString)
            .draw(in: CGRect(x: origin.x, y: origin.y + 214, width: itemWidth, height: 18), withAttributes: styles.centerMetricAttrs)
        ("Ra:\(record.computedRating)" as NSString)
            .draw(in: CGRect(x: origin.x, y: origin.y + 214, width: itemWidth - 18, height: 18), withAttributes: styles.rightMetricAttrs)
    }

    private static func drawCenterCropped(_ image: UIImage, in rect: CGRect, context: CGContext) {
        let size = image.size
        guard size.width > 0, size.height > 0 else { return }
        let scale = max(rect.width / size.width, rect.height / size.height)
        let drawSize = CGSize(width: size.width * scale, height: size.height * scale)
        let drawOrigin = CGPoint(
            x: rect.midX - drawSize.width / 2,
            y: rect.midY - drawSize.height / 2
        )
        context.saveGState()
        context.clip(to: rect)
        image.draw(in: CGRect(origin: drawOrigin, size: drawSize))
        context.restoreGState()
    }

    private static func toFullWidthName(_ raw: String) -> String {
        var scalars = String.UnicodeScalarView()
        scalars.reserveCapacity(raw.unicodeScalars.count)
        for scalar in raw.unicodeScalars {
            let v = scalar.value
            if v == 0x20 {
                scalars.append(UnicodeScalar(0x3000)!)
            } else if v >= 0x21 && v <= 0x7E, let mapped = UnicodeScalar(v + 0xFEE0) {
                scalars.append(mapped)
            } else {
                scalars.append(scalar)
            }
        }
        return String(scalars)
    }

    private static func ratingPlateName(for rating: Int) -> String {
        switch rating {
        case 0...999: return "mmd_rating_plate_normal"
        case 1000...1999: return "mmd_rating_plate_blue"
        case 2000...3999: return "mmd_rating_plate_green"
        case 4000...6999: return "mmd_rating_plate_orange"
        case 7000...9999: return "mmd_rating_plate_red"
        case 10000...11999: return "mmd_rating_plate_purple"
        case 12000...12999: return "mmd_rating_plate_bronze"
        case 13000...13999: return "mmd_rating_plate_silver"
        case 14000...14499: return "mmd_rating_plate_gold"
        case 14500...14999: return "mmd_rating_plate_platinum"
        default: return "mmd_rating_plate_rainbow"
        }
    }
}
