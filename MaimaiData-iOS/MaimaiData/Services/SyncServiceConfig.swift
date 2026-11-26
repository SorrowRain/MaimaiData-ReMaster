import Foundation

enum SyncServiceConfig {
    static func builtIn(infoDictionary: [String: Any]? = Bundle.main.infoDictionary) -> SelfHostedEndpointConfig {
        let host = cleanedValue(infoDictionary?["SyncServiceHost"] as? String)
        let accessKey = cleanedValue(infoDictionary?["SyncServiceAccessKey"] as? String)
        return resolve(hostOrURL: host, accessKey: accessKey)
    }

    static func resolve(hostOrURL: String, accessKey: String) -> SelfHostedEndpointConfig {
        let cleanedHost = cleanedValue(hostOrURL)
        let cleanedKey = cleanedValue(accessKey)
        guard !cleanedHost.isEmpty, !cleanedKey.isEmpty else {
            return SelfHostedEndpointConfig()
        }
        let baseURL: String
        if cleanedHost.contains("://") {
            baseURL = cleanedHost
        } else {
            let hostOnly = cleanedHost.split(separator: ":").first.map(String.init) ?? cleanedHost
            let lower = hostOnly.lowercased()
            let scheme = (lower == "localhost" || lower == "127.0.0.1") ? "http" : "https"
            baseURL = "\(scheme)://\(cleanedHost)"
        }
        return SelfHostedEndpointConfig(serverBaseURL: baseURL, accessKey: cleanedKey)
    }

    private static func cleanedValue(_ raw: String?) -> String {
        let trimmed = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed.hasPrefix("$(") {
            return ""
        }
        return trimmed
    }
}
