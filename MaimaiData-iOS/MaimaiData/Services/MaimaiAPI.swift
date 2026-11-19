import Foundation

enum MaimaiAPIError: LocalizedError {
    case invalidURL
    case httpError(Int, String?)
    case missingCookie
    case unauthorized(String)
    case oauthStateMismatch
    case oauthDenied(String)
    case syncInProgress
    case recordSyncFailedAfterLogin(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "无效的请求地址"
        case .httpError(let code, let message):
            return message ?? "网络请求失败 (HTTP \(code))"
        case .missingCookie:
            return "登录未返回有效 Cookie"
        case .unauthorized(let message):
            return message
        case .oauthStateMismatch:
            return "授权安全校验未通过（state 不一致），请重新发起授权"
        case .oauthDenied(let detail):
            return detail
        case .syncInProgress:
            return "正在同步成绩，请稍后再试。"
        case .recordSyncFailedAfterLogin(let detail):
            return "账号已添加成功，但首次成绩同步失败：\(detail)\n请稍后点击同步重试。"
        }
    }

    var recoveryMessage: String {
        errorDescription ?? "请求失败，请检查网络后重试。"
    }
}

enum MaimaiAPI {
    private static let zetarakuDataURL = URL(string: "https://dp4p6x0xfi5o9.cloudfront.net/maimai/data.json")!
    private static let dxDataURL = URL(string: "https://miruku.dxrating.net/api/v1/dxdata")!
    private static let divingFishBase = "https://www.diving-fish.com"
    private static let divingFishUA = "okhttp/4.9.3"
    private static let appUserAgent = "MaimaiData-iOS/1.0"

    private static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        config.httpShouldSetCookies = false
        return URLSession(configuration: config)
    }()

    // MARK: - 1. CN Public Catalog, Aliases & Plates (LXNS Official Public API)

    static func fetchLXNSSongCatalog(version: Int? = nil) async throws -> LXNSSongListResponse {
        let urlString: String
        if let version {
            urlString = "\(LXNSConfiguration.apiBase)/maimai/song/list?version=\(version)"
        } else {
            urlString = "\(LXNSConfiguration.apiBase)/maimai/song/list?notes=true"
        }
        guard let url = URL(string: urlString) else { throw MaimaiAPIError.invalidURL }
        let data = try await performLXNSGETWithRetry(url: url)
        return try await Task.detached(priority: .utility) {
            try JSONDecoder().decode(LXNSSongListResponse.self, from: data)
        }.value
    }

    static func fetchLXNSAliases() async throws -> LXNSAliasListResponse {
        guard let url = URL(string: "\(LXNSConfiguration.apiBase)/maimai/alias/list") else {
            throw MaimaiAPIError.invalidURL
        }
        let data = try await performLXNSGETWithRetry(url: url)
        return try await Task.detached(priority: .utility) {
            try JSONDecoder().decode(LXNSAliasListResponse.self, from: data)
        }.value
    }

    static func fetchLXNSPlates() async throws -> LXNSPlateListResponse {
        guard let url = URL(string: "\(LXNSConfiguration.apiBase)/maimai/plate/list") else {
            throw MaimaiAPIError.invalidURL
        }
        let data = try await performLXNSGETWithRetry(url: url)
        return try await Task.detached(priority: .utility) {
            try JSONDecoder().decode(LXNSPlateListResponse.self, from: data)
        }.value
    }

    // MARK: - 2. JP / INTL Catalog & Diving-Fish Fit Stats

    static func fetchZetarakuCatalog() async throws -> (raw: Data, catalog: ZetarakuCatalog) {
        var req = URLRequest(url: zetarakuDataURL)
        req.setValue(appUserAgent, forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await session.data(for: req)
        try validateHTTP(resp, data: data)
        let decoded = try await Task.detached(priority: .utility) {
            try JSONDecoder().decode(ZetarakuCatalog.self, from: data)
        }.value
        return (data, decoded)
    }

    static func fetchDXDataIdMap() async throws -> DXDataIdMap {
        var req = URLRequest(url: dxDataURL)
        req.setValue(appUserAgent, forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await session.data(for: req)
        try validateHTTP(resp, data: data)
        return try await Task.detached(priority: .utility) {
            let rawCatalog = try JSONDecoder().decode(DXDataRawCatalog.self, from: data)
            return DXDataIdMap.extract(from: rawCatalog)
        }.value
    }

    static func fetchChartStats() async throws -> (raw: Data, stats: ChartStatsResponse) {
        let url = URL(string: "\(divingFishBase)/api/maimaidxprober/chart_stats")!
        var req = URLRequest(url: url)
        req.setValue(divingFishUA, forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await session.data(for: req)
        try validateHTTP(resp, data: data)
        let decoded = try JSONDecoder().decode(ChartStatsResponse.self, from: data)
        return (data, decoded)
    }

    // MARK: - 3. Diving-Fish Authentication & Full Records

    static func shouldRetryLogin(after error: Error) -> Bool {
        switch error {
        case MaimaiAPIError.unauthorized:
            return true
        case let MaimaiAPIError.httpError(code, _):
            return code == 400 || code == 401 || code == 403
        default:
            return false
        }
    }

    static func login(username: String, password: String) async throws -> String {
        let url = URL(string: "\(divingFishBase)/api/maimaidxprober/login")!
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json;charset=UTF-8", forHTTPHeaderField: "Content-Type")
        req.setValue(divingFishUA, forHTTPHeaderField: "User-Agent")
        req.httpBody = try JSONEncoder().encode(["username": username, "password": password])

        let (data, resp) = try await session.data(for: req)
        guard let httpResp = resp as? HTTPURLResponse else {
            throw MaimaiAPIError.httpError(-1, "无效的服务器响应")
        }
        if httpResp.statusCode != 200 {
            let errMsg = extractErrorMessage(from: data) ?? "用户名或密码错误 (HTTP \(httpResp.statusCode))"
            throw MaimaiAPIError.httpError(httpResp.statusCode, errMsg)
        }
        let rawSetCookie = httpResp.value(forHTTPHeaderField: "Set-Cookie") ?? ""
        let extractedCookie = extractDivingFishCookie(from: rawSetCookie)
        guard !extractedCookie.isEmpty else {
            throw MaimaiAPIError.missingCookie
        }
        return extractedCookie
    }

    private static func extractDivingFishCookie(from setCookieHeader: String) -> String {
        let segments = setCookieHeader
            .components(separatedBy: CharacterSet(charactersIn: ",;"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        if let jwt = segments.first(where: { $0.hasPrefix("jwt_token=") && $0.count > "jwt_token=".count }) {
            return jwt
        }
        return setCookieHeader
            .split(separator: ";", maxSplits: 1)
            .first
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) } ?? ""
    }

    static func fetchPlayerRecords(cookie: String) async throws -> ProberRecordsResponse {
        let url = URL(string: "\(divingFishBase)/api/maimaidxprober/player/records")!
        var req = URLRequest(url: url)
        req.setValue(cookie, forHTTPHeaderField: "Cookie")
        req.setValue(divingFishUA, forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await session.data(for: req)
        if let httpResp = resp as? HTTPURLResponse,
           httpResp.statusCode == 400 || httpResp.statusCode == 401 || httpResp.statusCode == 403 {
            throw MaimaiAPIError.unauthorized(extractErrorMessage(from: data) ?? "登录已过期，请重新登录")
        }
        try validateHTTP(resp, data: data)
        let decoded = try JSONDecoder().decode(ProberRecordsResponse.self, from: data)
        if decoded.status == "error" {
            throw MaimaiAPIError.unauthorized(decoded.message ?? "登录已过期，请重新登录")
        }
        return decoded
    }

    static func fetchPlayerRecords(importToken: String) async throws -> ProberRecordsResponse {
        let url = URL(string: "\(divingFishBase)/api/maimaidxprober/player/records")!
        var req = URLRequest(url: url)
        req.setValue(importToken.trimmingCharacters(in: .whitespacesAndNewlines), forHTTPHeaderField: "Import-Token")
        req.setValue(divingFishUA, forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await session.data(for: req)
        guard let httpResp = resp as? HTTPURLResponse else {
            throw MaimaiAPIError.httpError(-1, "无效响应")
        }
        if httpResp.statusCode == 400 || httpResp.statusCode == 401 || httpResp.statusCode == 403 {
            let msg = extractErrorMessage(from: data) ?? "成绩导入 Token 无效，请前往水鱼个人资料页核对"
            throw MaimaiAPIError.unauthorized(msg)
        }
        try validateHTTP(resp, data: data)
        let decoded = try JSONDecoder().decode(ProberRecordsResponse.self, from: data)
        if decoded.status == "error" {
            throw MaimaiAPIError.unauthorized(decoded.message ?? "成绩导入 Token 无效")
        }
        return decoded
    }

    // MARK: - 4. LXNS OAuth 2.0 + PKCE & Personal API Key (`X-User-Token`)

    static func buildLXNSAuthorizeURL(
        state: String,
        nonce: String,
        codeChallenge: String
    ) -> URL? {
        guard var components = URLComponents(string: LXNSConfiguration.authorizeURLString) else {
            return nil
        }
        components.queryItems = [
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "client_id", value: LXNSConfiguration.clientId),
            URLQueryItem(name: "redirect_uri", value: LXNSConfiguration.redirectURI),
            URLQueryItem(name: "scope", value: LXNSConfiguration.scopes),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "nonce", value: nonce),
            URLQueryItem(name: "code_challenge", value: codeChallenge),
            URLQueryItem(name: "code_challenge_method", value: "S256")
        ]
        return components.url
    }

    static func parseOAuthCallback(url: URL, expectedState: String) throws -> String {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw MaimaiAPIError.invalidURL
        }
        let items = components.queryItems ?? []
        let state = items.first(where: { $0.name == "state" })?.value
        guard state == expectedState else {
            throw MaimaiAPIError.oauthStateMismatch
        }
        if let err = items.first(where: { $0.name == "error" })?.value {
            let desc = items.first(where: { $0.name == "error_description" })?.value
            if err == "access_denied" {
                throw MaimaiAPIError.oauthDenied("已拒绝授权申请")
            }
            throw MaimaiAPIError.oauthDenied(desc ?? "授权未完成（\(err)）")
        }
        guard let code = items.first(where: { $0.name == "code" })?.value, !code.isEmpty else {
            throw MaimaiAPIError.oauthDenied("授权回调中未包含有效授权码")
        }
        return code
    }

    static func exchangeLXNSCode(
        code: String,
        codeVerifier: String
    ) async throws -> LXNSOAuthTokenResponse {
        let payload: [String: String] = [
            "client_id": LXNSConfiguration.clientId,
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": LXNSConfiguration.redirectURI,
            "code_verifier": codeVerifier
        ]
        return try await postLXNSOAuthToken(payload: payload)
    }

    static func refreshLXNSToken(refreshToken: String) async throws -> LXNSOAuthTokenResponse {
        let payload: [String: String] = [
            "client_id": LXNSConfiguration.clientId,
            "grant_type": "refresh_token",
            "refresh_token": refreshToken
        ]
        return try await postLXNSOAuthToken(payload: payload)
    }

    static func fetchLXNSUserInfo(accessToken: String) async throws -> LXNSUserInfo {
        guard let url = URL(string: "\(LXNSConfiguration.apiBase)/oauth/userinfo") else {
            throw MaimaiAPIError.invalidURL
        }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        req.setValue(appUserAgent, forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await session.data(for: req)
        try validateHTTP(resp, data: data)
        return try JSONDecoder().decode(LXNSUserInfo.self, from: data)
    }

    static func fetchLXNSPlayerAndScores(bearerToken: String) async throws -> (player: LXNSPlayer?, scores: [LXNSScore]) {
        try await fetchLXNSPlayerAndScores(
            headerField: "Authorization",
            headerValue: "Bearer \(bearerToken)"
        )
    }

    static func fetchLXNSPlayerAndScores(personalToken: String) async throws -> (player: LXNSPlayer?, scores: [LXNSScore]) {
        try await fetchLXNSPlayerAndScores(
            headerField: "X-User-Token",
            headerValue: personalToken.trimmingCharacters(in: .whitespacesAndNewlines)
        )
    }

    // MARK: - Internal Networking Helpers

    private static func fetchLXNSPlayerAndScores(
        headerField: String,
        headerValue: String
    ) async throws -> (player: LXNSPlayer?, scores: [LXNSScore]) {
        guard let playerURL = URL(string: "\(LXNSConfiguration.apiBase)/user/maimai/player"),
              let scoresURL = URL(string: "\(LXNSConfiguration.apiBase)/user/maimai/player/scores") else {
            throw MaimaiAPIError.invalidURL
        }

        // 1. Fetch player profile
        var playerReq = URLRequest(url: playerURL)
        playerReq.setValue(headerValue, forHTTPHeaderField: headerField)
        playerReq.setValue(appUserAgent, forHTTPHeaderField: "User-Agent")
        let (playerData, playerResp) = try await session.data(for: playerReq)
        guard let playerHTTP = playerResp as? HTTPURLResponse else {
            throw MaimaiAPIError.httpError(-1, "无效响应")
        }
        if playerHTTP.statusCode == 401 || playerHTTP.statusCode == 403 {
            let msg = extractErrorMessage(from: playerData) ?? "落雪凭据无效或已过期，请重新授权或检查个人密钥"
            throw MaimaiAPIError.unauthorized(msg)
        }
        if playerHTTP.statusCode == 404 {
            // Valid LXNS account that has not bound / uploaded maimai DX player data yet.
            return (nil, [])
        }
        try validateHTTP(playerResp, data: playerData)
        let playerEnv = try JSONDecoder().decode(LXNSAPIEnvelope<LXNSPlayer>.self, from: playerData)
        if !playerEnv.success {
            if playerEnv.code == 404 {
                return (nil, [])
            }
            throw MaimaiAPIError.httpError(playerEnv.code, playerEnv.message)
        }

        // 2. Fetch full score array
        var scoresReq = URLRequest(url: scoresURL)
        scoresReq.setValue(headerValue, forHTTPHeaderField: headerField)
        scoresReq.setValue(appUserAgent, forHTTPHeaderField: "User-Agent")
        let (scoresData, scoresResp) = try await session.data(for: scoresReq)
        guard let scoresHTTP = scoresResp as? HTTPURLResponse else {
            throw MaimaiAPIError.httpError(-1, "无效响应")
        }
        if scoresHTTP.statusCode == 401 || scoresHTTP.statusCode == 403 {
            let msg = extractErrorMessage(from: scoresData) ?? "落雪凭据缺少成绩读取权限或已过期"
            throw MaimaiAPIError.unauthorized(msg)
        }
        try validateHTTP(scoresResp, data: scoresData)
        let scoresEnv = try await Task.detached(priority: .utility) {
            try JSONDecoder().decode(LXNSAPIEnvelope<[LXNSScore]>.self, from: scoresData)
        }.value
        if !scoresEnv.success {
            throw MaimaiAPIError.httpError(scoresEnv.code, scoresEnv.message)
        }
        return (playerEnv.data, scoresEnv.data ?? [])
    }

    private static func postLXNSOAuthToken(payload: [String: String]) async throws -> LXNSOAuthTokenResponse {
        guard let url = URL(string: "\(LXNSConfiguration.apiBase)/oauth/token") else {
            throw MaimaiAPIError.invalidURL
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(appUserAgent, forHTTPHeaderField: "User-Agent")
        req.httpBody = try JSONEncoder().encode(payload)

        let (data, resp) = try await session.data(for: req)
        guard let httpResp = resp as? HTTPURLResponse else {
            throw MaimaiAPIError.httpError(-1, "无效响应")
        }
        if !(200...299).contains(httpResp.statusCode) {
            if let oauthErr = try? JSONDecoder().decode(LXNSOAuthErrorResponse.self, from: data) {
                if oauthErr.error == "invalid_grant" || httpResp.statusCode == 401 {
                    throw MaimaiAPIError.unauthorized(oauthErr.errorDescription ?? "落雪授权已失效，请重新授权登录")
                }
                throw MaimaiAPIError.httpError(httpResp.statusCode, oauthErr.errorDescription ?? oauthErr.error)
            }
            throw MaimaiAPIError.httpError(httpResp.statusCode, extractErrorMessage(from: data))
        }
        return try JSONDecoder().decode(LXNSOAuthTokenResponse.self, from: data)
    }

    private static func performLXNSGETWithRetry(url: URL, maxAttempts: Int = 3) async throws -> Data {
        var lastError: Error?
        for attempt in 0..<maxAttempts {
            do {
                var req = URLRequest(url: url)
                req.setValue(appUserAgent, forHTTPHeaderField: "User-Agent")
                let (data, resp) = try await session.data(for: req)
                if let httpResp = resp as? HTTPURLResponse,
                   (httpResp.statusCode == 429 || (500...599).contains(httpResp.statusCode)),
                   attempt + 1 < maxAttempts {
                    try await Task.sleep(nanoseconds: UInt64(attempt + 1) * 900_000_000)
                    continue
                }
                try validateHTTP(resp, data: data)
                return data
            } catch is CancellationError {
                throw CancellationError()
            } catch let apiError as MaimaiAPIError {
                if case let .httpError(code, _) = apiError,
                   (400...499).contains(code), code != 429 {
                    throw apiError
                }
                lastError = apiError
                if attempt + 1 < maxAttempts {
                    try await Task.sleep(nanoseconds: UInt64(attempt + 1) * 700_000_000)
                }
            } catch {
                lastError = error
                if attempt + 1 < maxAttempts {
                    try await Task.sleep(nanoseconds: UInt64(attempt + 1) * 700_000_000)
                }
            }
        }
        throw lastError ?? MaimaiAPIError.httpError(-1, "请求落雪接口失败")
    }

    private static func validateHTTP(_ resp: URLResponse, data: Data) throws {
        guard let httpResp = resp as? HTTPURLResponse else {
            throw MaimaiAPIError.httpError(-1, "无效响应")
        }
        guard (200...299).contains(httpResp.statusCode) else {
            let fallbackMsg: String? = (httpResp.statusCode == 429) ? "请求过于频繁，请稍后再试" : nil
            throw MaimaiAPIError.httpError(httpResp.statusCode, extractErrorMessage(from: data) ?? fallbackMsg)
        }
    }

    private static func extractErrorMessage(from data: Data) -> String? {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        if let msg = obj["message"] as? String, !msg.isEmpty { return msg }
        if let desc = obj["error_description"] as? String, !desc.isEmpty { return desc }
        if let err = obj["error"] as? String, !err.isEmpty { return err }
        return nil
    }

    // MARK: - 5. GitHub Latest Release Update Check

    private static let githubLatestReleaseURL = URL(
        string: "https://api.github.com/repos/SorrowRain/MaimaiData/releases/latest"
    )!

    static func fetchLatestGitHubRelease(timeout: TimeInterval = 6) async throws -> AppReleaseInfo? {
        var req = URLRequest(url: githubLatestReleaseURL, timeoutInterval: timeout)
        req.setValue(appUserAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let (data, resp) = try await session.data(for: req)
        guard let httpResp = resp as? HTTPURLResponse else {
            throw MaimaiAPIError.httpError(-1, "无效响应")
        }
        if httpResp.statusCode == 404 {
            return nil
        }
        guard (200...299).contains(httpResp.statusCode) else {
            throw MaimaiAPIError.httpError(httpResp.statusCode, extractErrorMessage(from: data))
        }
        let payload = try JSONDecoder().decode(GitHubLatestReleasePayload.self, from: data)
        return AppReleaseInfo.from(payload: payload)
    }
}
