package dev.sorrowrain.maimaidata.services

import dev.sorrowrain.maimaidata.models.AppReleaseInfo
import dev.sorrowrain.maimaidata.models.ChartStatsResponse
import dev.sorrowrain.maimaidata.models.DivingFishUploadRecordDTO
import dev.sorrowrain.maimaidata.models.LXNSAPIEnvelope
import dev.sorrowrain.maimaidata.models.LXNSAliasListResponse
import dev.sorrowrain.maimaidata.models.LXNSConfiguration
import dev.sorrowrain.maimaidata.models.LXNSOAuthErrorResponse
import dev.sorrowrain.maimaidata.models.LXNSOAuthTokenResponse
import dev.sorrowrain.maimaidata.models.LXNSPlateListResponse
import dev.sorrowrain.maimaidata.models.LXNSPlayer
import dev.sorrowrain.maimaidata.models.LXNSScore
import dev.sorrowrain.maimaidata.models.LXNSSongListResponse
import dev.sorrowrain.maimaidata.models.LXNSUploadScoreDTO
import dev.sorrowrain.maimaidata.models.LXNSUploadScoresRequest
import dev.sorrowrain.maimaidata.models.LXNSUserInfo
import dev.sorrowrain.maimaidata.models.ProberRecordsResponse
import dev.sorrowrain.maimaidata.models.SelfHostedEndpointConfig
import dev.sorrowrain.maimaidata.models.SyncBackendBindRequest
import dev.sorrowrain.maimaidata.models.SyncBackendErrorEnvelope
import dev.sorrowrain.maimaidata.models.SyncBackendFullRequest
import dev.sorrowrain.maimaidata.models.SyncBackendResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

sealed class MaimaiAPIException(message: String) : Exception(message) {
    class InvalidUrl : MaimaiAPIException("无效的请求地址")
    class HttpError(val code: Int, val serverMessage: String? = null) :
        MaimaiAPIException(serverMessage ?: "网络请求失败 (HTTP $code)")
    class MissingCookie : MaimaiAPIException("登录未返回有效 Cookie")
    class Unauthorized(val detail: String) : MaimaiAPIException(detail)
    class OAuthStateMismatch : MaimaiAPIException("授权安全校验未通过（state 不一致），请重新发起授权")
    class OAuthDenied(val detail: String) : MaimaiAPIException(detail)
    class SyncInProgress : MaimaiAPIException("正在同步成绩，请稍后再试。")
    class RecordSyncFailedAfterLogin(val detail: String) :
        MaimaiAPIException("账号已添加成功，但首次成绩同步失败：$detail\n请稍后点击同步重试。")
    class BackendError(
        val statusCode: Int,
        val errorCode: String,
        val serverMessage: String,
        val retryAfterSec: Int? = null
    ) : MaimaiAPIException(localizedBackendMessage(errorCode, serverMessage, retryAfterSec))

    val recoveryMessage: String
        get() = message ?: "请求失败，请检查网络后重试。"

    companion object {
        private fun localizedBackendMessage(code: String, fallback: String, retryAfterSec: Int?): String =
            when (code) {
                "INVALID_ACCESS_KEY" -> "同步服务暂时不可用，请更新 App 后重试"
                "INVALID_SYNC_TOKEN", "MISSING_SYNC_TOKEN" -> "国服直连已失效，请重新绑定"
                "IP_BANNED", "IP_BLOCKED" -> if (retryAfterSec != null && retryAfterSec > 0) {
                    "请求过于频繁，请在 $retryAfterSec 秒后再试"
                } else {
                    "请求过于频繁，请稍后再试"
                }
                "USER_NOT_ALLOWED" -> "该账号暂未开放国服直连"
                "USER_ID_MISMATCH" -> "二维码对应的玩家与当前档案不一致，请确认微信账号"
                "INVALID_QR" -> "未能识别的二维码格式，请粘贴完整链接或 SGWCMAID 内容"
                "QR_EXPIRED", "AIMEDB_REJECTED" -> "二维码已过期或已被使用，请在微信公众号重新生成"
                "SYNC_IN_PROGRESS" -> "该玩家正在同步中，请稍候"
                "TITLE_LOGIN_REJECTED" -> "游戏端登录未通过：$fallback"
                "CLIENT_UPGRADE_REQUIRED" -> "当前客户端版本过低，请更新后再试"
                "RATE_LIMITED" -> "请求过于频繁，请 ${retryAfterSec ?: 60} 秒后再试"
                "UPSTREAM_CIRCUIT_OPEN", "UPSTREAM_ERROR" -> "上游服务器暂时不可用，请稍后再试"
                else -> fallback.ifEmpty { "同步服务请求失败 ($code)" }
            }
    }
}

/**
 * Pure JVM RFC 7636 PKCE (S256) helper using `java.security.MessageDigest` + `java.util.Base64`.
 */
object PKCEHelper {
    private val secureRandom = SecureRandom()

    fun randomUrlSafeString(byteCount: Int = 32): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun generateCodeVerifier(): String = randomUrlSafeString(byteCount = 48)

    fun generateState(): String = randomUrlSafeString(byteCount = 16)

    fun codeChallengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}

/**
 * Stateless OkHttp networking layer for LXNS public catalogs & OAuth 2.0 + PKCE,
 * Diving-Fish login / Import-Token / fit stats, and Zetaraku + DXRating multi-region catalogs.
 */
object MaimaiAPI {
    private const val ZETARAKU_DATA_URL = "https://dp4p6x0xfi5o9.cloudfront.net/maimai/data.json"
    private const val DX_DATA_URL = "https://miruku.dxrating.net/api/v1/dxdata"
    private const val DIVING_FISH_BASE = "https://www.diving-fish.com"
    private const val DIVING_FISH_UA = "okhttp/4.9.3"
    const val APP_USER_AGENT = "MaimaiData-Android/1.0"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    data class RawZetarakuCatalog(
        val rawBytes: ByteArray,
        val catalog: ZetarakuCatalog
    )

    data class RawChartStats(
        val rawBytes: ByteArray,
        val stats: ChartStatsResponse
    )

    data class LXNSPlayerAndScores(
        val player: LXNSPlayer?,
        val scores: List<LXNSScore>
    )

    // 1. CN Public Catalog, Aliases & Plates (LXNS Official Public API)

    suspend fun fetchLXNSSongCatalog(): LXNSSongListResponse {
        val bytes = performLXNSGetWithRetry("${LXNSConfiguration.API_BASE}/maimai/song/list?notes=true")
        return withContext(Dispatchers.Default) {
            json.decodeFromString(bytes.toString(Charsets.UTF_8))
        }
    }

    suspend fun fetchLXNSAliases(): LXNSAliasListResponse {
        val bytes = performLXNSGetWithRetry("${LXNSConfiguration.API_BASE}/maimai/alias/list")
        return withContext(Dispatchers.Default) {
            json.decodeFromString(bytes.toString(Charsets.UTF_8))
        }
    }

    suspend fun fetchLXNSPlates(): LXNSPlateListResponse {
        val bytes = performLXNSGetWithRetry("${LXNSConfiguration.API_BASE}/maimai/plate/list")
        return withContext(Dispatchers.Default) {
            json.decodeFromString(bytes.toString(Charsets.UTF_8))
        }
    }

    // 2. JP / INTL Catalog & Diving-Fish Fit Stats

    suspend fun fetchZetarakuCatalog(): RawZetarakuCatalog {
        val request = Request.Builder()
            .url(ZETARAKU_DATA_URL)
            .header("User-Agent", APP_USER_AGENT)
            .get()
            .build()
        val (_, bytes) = executeAndRead(request, validate2xx = true)
        val decoded = withContext(Dispatchers.Default) {
            json.decodeFromString<ZetarakuCatalog>(bytes.toString(Charsets.UTF_8))
        }
        return RawZetarakuCatalog(rawBytes = bytes, catalog = decoded)
    }

    suspend fun fetchDXDataIdMap(): DXDataIdMap {
        val request = Request.Builder()
            .url(DX_DATA_URL)
            .header("User-Agent", APP_USER_AGENT)
            .get()
            .build()
        val (_, bytes) = executeAndRead(request, validate2xx = true)
        return withContext(Dispatchers.Default) {
            val rawCatalog = json.decodeFromString<DXDataRawCatalog>(bytes.toString(Charsets.UTF_8))
            DXDataIdMap.extract(rawCatalog)
        }
    }

    suspend fun fetchChartStats(): RawChartStats {
        val request = Request.Builder()
            .url("$DIVING_FISH_BASE/api/maimaidxprober/chart_stats")
            .header("User-Agent", DIVING_FISH_UA)
            .get()
            .build()
        val (_, bytes) = executeAndRead(request, validate2xx = true)
        val decoded = withContext(Dispatchers.Default) {
            json.decodeFromString<ChartStatsResponse>(bytes.toString(Charsets.UTF_8))
        }
        return RawChartStats(rawBytes = bytes, stats = decoded)
    }

    // 3. Diving-Fish Authentication & Full Records

    fun shouldRetryLogin(error: Throwable): Boolean = when (error) {
        is MaimaiAPIException.Unauthorized -> true
        is MaimaiAPIException.HttpError -> error.code == 400 || error.code == 401 || error.code == 403
        else -> false
    }

    fun shouldRetryUploadLogin(error: Throwable): Boolean = when (error) {
        is MaimaiAPIException.Unauthorized -> true
        is MaimaiAPIException.HttpError -> error.code == 401 || error.code == 403
        else -> false
    }

    suspend fun loginDivingFish(username: String, password: String): String {
        val payload = json.encodeToString(mapOf("username" to username, "password" to password))
        val request = Request.Builder()
            .url("$DIVING_FISH_BASE/api/maimaidxprober/login")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("User-Agent", DIVING_FISH_UA)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val (code, headers, bytes) = executeRaw(request)
        if (code != 200) {
            val defaultMsg = if (code >= 500) {
                "水鱼服务器暂时异常 (HTTP $code)"
            } else {
                "用户名或密码错误 (HTTP $code)"
            }
            val errMsg = extractErrorMessage(bytes) ?: defaultMsg
            throw MaimaiAPIException.HttpError(code, errMsg)
        }
        val setCookies = headers.values("Set-Cookie")
        val jwtCookie = setCookies
            .flatMap { it.split(";") }
            .map { it.trim() }
            .firstOrNull { it.startsWith("jwt_token=") && it.substringAfter("jwt_token=").isNotEmpty() }
            ?: setCookies.firstOrNull()?.substringBefore(";")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw MaimaiAPIException.MissingCookie()
        return jwtCookie
    }

    suspend fun fetchDivingFishRecordsByCookie(cookie: String): ProberRecordsResponse {
        val request = Request.Builder()
            .url("$DIVING_FISH_BASE/api/maimaidxprober/player/records")
            .header("Cookie", cookie)
            .header("User-Agent", DIVING_FISH_UA)
            .get()
            .build()
        val (code, bytes) = executeAndRead(request, validate2xx = false)
        if (code == 400 || code == 401 || code == 403) {
            throw MaimaiAPIException.Unauthorized(extractErrorMessage(bytes) ?: "水鱼登录已过期，请解绑后重新绑定")
        }
        if (code !in 200..299) {
            throw MaimaiAPIException.HttpError(code, extractErrorMessage(bytes))
        }
        val decoded = withContext(Dispatchers.Default) {
            json.decodeFromString<ProberRecordsResponse>(bytes.toString(Charsets.UTF_8))
        }
        if (decoded.status == "error") {
            throw MaimaiAPIException.Unauthorized(decoded.message ?: "水鱼登录已过期，请解绑后重新绑定")
        }
        return decoded
    }

    suspend fun fetchDivingFishRecordsByImportToken(importToken: String): ProberRecordsResponse {
        val request = Request.Builder()
            .url("$DIVING_FISH_BASE/api/maimaidxprober/player/records")
            .header("Import-Token", importToken.trim())
            .header("User-Agent", DIVING_FISH_UA)
            .get()
            .build()
        val (code, bytes) = executeAndRead(request, validate2xx = false)
        if (code == 400 || code == 401 || code == 403) {
            val msg = extractErrorMessage(bytes) ?: "成绩导入 Token 无效，请前往水鱼个人资料页核对"
            throw MaimaiAPIException.Unauthorized(msg)
        }
        if (code !in 200..299) {
            throw MaimaiAPIException.HttpError(code, extractErrorMessage(bytes))
        }
        val decoded = withContext(Dispatchers.Default) {
            json.decodeFromString<ProberRecordsResponse>(bytes.toString(Charsets.UTF_8))
        }
        if (decoded.status == "error") {
            throw MaimaiAPIException.Unauthorized(decoded.message ?: "成绩导入 Token 无效")
        }
        return decoded
    }

    // 4. LXNS OAuth 2.0 + PKCE & Personal API Key (`X-User-Token`)

    fun buildLXNSAuthorizeUrl(
        codeChallenge: String,
        state: String,
        nonce: String = PKCEHelper.generateState()
    ): String {
        val params = listOf(
            "response_type" to "code",
            "client_id" to LXNSConfiguration.CLIENT_ID,
            "redirect_uri" to LXNSConfiguration.REDIRECT_URI,
            "scope" to LXNSConfiguration.SCOPES,
            "state" to state,
            "nonce" to nonce,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256"
        )
        val query = params.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
        return "${LXNSConfiguration.AUTHORIZE_URL}?$query"
    }

    /**
     * Pure `java.net.URI` OAuth callback parser (runs in pure JVM unit tests without `android.net.Uri`).
     */
    fun parseOAuthCallback(uriString: String, expectedState: String): String {
        val uri = try {
            URI(uriString.trim())
        } catch (_: Exception) {
            throw MaimaiAPIException.InvalidUrl()
        }
        if (uri.scheme.isNullOrEmpty()) {
            throw MaimaiAPIException.InvalidUrl()
        }
        val params = parseQueryParameters(uri.rawQuery)
        val state = params["state"]
        if (state != expectedState) {
            throw MaimaiAPIException.OAuthStateMismatch()
        }
        val err = params["error"]
        if (!err.isNullOrEmpty()) {
            val desc = params["error_description"]
            if (err == "access_denied") {
                throw MaimaiAPIException.OAuthDenied("已拒绝授权申请")
            }
            throw MaimaiAPIException.OAuthDenied(desc?.takeIf { it.isNotEmpty() } ?: "授权未完成（$err）")
        }
        val code = params["code"]?.takeIf { it.isNotEmpty() }
            ?: throw MaimaiAPIException.OAuthDenied("授权回调中未包含有效授权码")
        return code
    }

    suspend fun exchangeLXNSCode(code: String, codeVerifier: String): LXNSOAuthTokenResponse {
        val payload = mapOf(
            "client_id" to LXNSConfiguration.CLIENT_ID,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to LXNSConfiguration.REDIRECT_URI,
            "code_verifier" to codeVerifier
        )
        return postLXNSOAuthToken(payload)
    }

    suspend fun refreshLXNSToken(refreshToken: String): LXNSOAuthTokenResponse {
        val payload = mapOf(
            "client_id" to LXNSConfiguration.CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken
        )
        return postLXNSOAuthToken(payload)
    }

    suspend fun fetchLXNSUserInfo(accessToken: String): LXNSUserInfo {
        val request = Request.Builder()
            .url("${LXNSConfiguration.API_BASE}/oauth/userinfo")
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", APP_USER_AGENT)
            .get()
            .build()
        val (_, bytes) = executeAndRead(request, validate2xx = true)
        return withContext(Dispatchers.Default) {
            json.decodeFromString(bytes.toString(Charsets.UTF_8))
        }
    }

    suspend fun fetchLXNSOAuthPlayerAndScores(accessToken: String): LXNSPlayerAndScores =
        fetchLXNSPlayerAndScores(headerField = "Authorization", headerValue = "Bearer $accessToken")

    suspend fun fetchLXNSPersonalTokenPlayerAndScores(personalToken: String): LXNSPlayerAndScores =
        fetchLXNSPlayerAndScores(headerField = "X-User-Token", headerValue = personalToken.trim())

    // Internal Networking Helpers

    private suspend fun fetchLXNSPlayerAndScores(
        headerField: String,
        headerValue: String
    ): LXNSPlayerAndScores {
        val playerReq = Request.Builder()
            .url("${LXNSConfiguration.API_BASE}/user/maimai/player")
            .header(headerField, headerValue)
            .header("User-Agent", APP_USER_AGENT)
            .get()
            .build()

        val (playerCode, playerBytes) = executeAndRead(playerReq, validate2xx = false)
        if (playerCode == 401 || playerCode == 403) {
            val msg = extractErrorMessage(playerBytes) ?: "落雪凭据无效或已过期，请重新授权或检查个人密钥"
            throw MaimaiAPIException.Unauthorized(msg)
        }
        if (playerCode == 404) {
            return LXNSPlayerAndScores(player = null, scores = emptyList())
        }
        if (playerCode !in 200..299) {
            throw MaimaiAPIException.HttpError(playerCode, extractErrorMessage(playerBytes))
        }
        val playerEnv = withContext(Dispatchers.Default) {
            json.decodeFromString<LXNSAPIEnvelope<LXNSPlayer>>(playerBytes.toString(Charsets.UTF_8))
        }
        if (!playerEnv.success) {
            if (playerEnv.code == 404) {
                return LXNSPlayerAndScores(player = null, scores = emptyList())
            }
            throw MaimaiAPIException.HttpError(playerEnv.code, playerEnv.message)
        }

        val scoresReq = Request.Builder()
            .url("${LXNSConfiguration.API_BASE}/user/maimai/player/scores")
            .header(headerField, headerValue)
            .header("User-Agent", APP_USER_AGENT)
            .get()
            .build()

        val (scoresCode, scoresBytes) = executeAndRead(scoresReq, validate2xx = false)
        if (scoresCode == 401 || scoresCode == 403) {
            val msg = extractErrorMessage(scoresBytes) ?: "落雪凭据缺少成绩读取权限或已过期"
            throw MaimaiAPIException.Unauthorized(msg)
        }
        if (scoresCode !in 200..299) {
            throw MaimaiAPIException.HttpError(scoresCode, extractErrorMessage(scoresBytes))
        }
        val scoresEnv = withContext(Dispatchers.Default) {
            json.decodeFromString<LXNSAPIEnvelope<List<LXNSScore>>>(scoresBytes.toString(Charsets.UTF_8))
        }
        if (!scoresEnv.success) {
            throw MaimaiAPIException.HttpError(scoresEnv.code, scoresEnv.message)
        }
        return LXNSPlayerAndScores(
            player = playerEnv.data,
            scores = scoresEnv.data.orEmpty()
        )
    }

    private suspend fun postLXNSOAuthToken(payload: Map<String, String>): LXNSOAuthTokenResponse {
        val bodyJson = json.encodeToString(payload)
        val request = Request.Builder()
            .url("${LXNSConfiguration.API_BASE}/oauth/token")
            .header("Content-Type", "application/json")
            .header("User-Agent", APP_USER_AGENT)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val (code, bytes) = executeAndRead(request, validate2xx = false)
        if (code !in 200..299) {
            val oauthErr = runCatching {
                json.decodeFromString<LXNSOAuthErrorResponse>(bytes.toString(Charsets.UTF_8))
            }.getOrNull()
            if (oauthErr != null && oauthErr.error.isNotEmpty()) {
                if (oauthErr.error == "invalid_grant" || code == 401) {
                    throw MaimaiAPIException.Unauthorized(
                        oauthErr.errorDescription ?: "落雪授权已失效，请解绑后重新绑定"
                    )
                }
                throw MaimaiAPIException.HttpError(code, oauthErr.errorDescription ?: oauthErr.error)
            }
            throw MaimaiAPIException.HttpError(code, extractErrorMessage(bytes))
        }
        return withContext(Dispatchers.Default) {
            json.decodeFromString(bytes.toString(Charsets.UTF_8))
        }
    }

    private suspend fun performLXNSGetWithRetry(url: String, maxAttempts: Int = 3): ByteArray {
        var lastError: Throwable? = null
        for (attempt in 0 until maxAttempts) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", APP_USER_AGENT)
                    .get()
                    .build()
                val (code, bytes) = executeAndRead(request, validate2xx = false)
                if ((code == 429 || code in 500..599) && attempt + 1 < maxAttempts) {
                    delay((attempt + 1) * 900L)
                    continue
                }
                if (code !in 200..299) {
                    val fallbackMsg = if (code == 429) "请求过于频繁，请稍后再试" else null
                    throw MaimaiAPIException.HttpError(code, extractErrorMessage(bytes) ?: fallbackMsg)
                }
                return bytes
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is MaimaiAPIException.HttpError && e.code in 400..499 && e.code != 429) {
                    throw e
                }
                lastError = e
                if (attempt + 1 < maxAttempts) {
                    delay((attempt + 1) * 700L)
                }
            }
        }
        throw lastError ?: MaimaiAPIException.HttpError(-1, "请求落雪接口失败")
    }

    private data class RawHttpResponse(
        val code: Int,
        val headers: okhttp3.Headers,
        val bytes: ByteArray
    )

    private suspend fun executeAndRead(request: Request, validate2xx: Boolean): Pair<Int, ByteArray> {
        val (code, _, bytes) = executeRaw(request)
        if (validate2xx && code !in 200..299) {
            val fallback = if (code == 429) "请求过于频繁，请稍后再试" else null
            throw MaimaiAPIException.HttpError(code, extractErrorMessage(bytes) ?: fallback)
        }
        return code to bytes
    }

    private suspend fun executeRaw(request: Request): RawHttpResponse {
        val response = httpClient.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                RawHttpResponse(
                    code = resp.code,
                    headers = resp.headers,
                    bytes = bytes
                )
            }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) {
                    cont.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!cont.isActive) {
                    response.close()
                } else {
                    cont.resume(response) { _, res, _ ->
                        res.close()
                    }
                }
            }
        })
    }

    private fun extractErrorMessage(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val obj = runCatching {
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: return null
        for (key in listOf("message", "error_description", "error")) {
            val text = obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            if (text != null) return text
        }
        return null
    }

    private fun urlEncode(raw: String): String =
        URLEncoder.encode(raw, Charsets.UTF_8.name()).replace("+", "%20")

    private fun parseQueryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isEmpty()) continue
            val eqIdx = pair.indexOf('=')
            val rawKey = if (eqIdx >= 0) pair.substring(0, eqIdx) else pair
            val rawVal = if (eqIdx >= 0) pair.substring(eqIdx + 1) else ""
            val key = try {
                URLDecoder.decode(rawKey, Charsets.UTF_8.name())
            } catch (_: IllegalArgumentException) {
                throw MaimaiAPIException.InvalidUrl()
            }
            val value = try {
                URLDecoder.decode(rawVal, Charsets.UTF_8.name())
            } catch (_: IllegalArgumentException) {
                throw MaimaiAPIException.InvalidUrl()
            }
            result.putIfAbsent(key, value)
        }
        return result
    }

    // 5. GitHub Latest Release Update Check (short 6s timeout)

    private const val GITHUB_RELEASE_FEED_URL =
        "https://github.com/SorrowRain/MaimaiData-ReMaster/releases.atom"

    private val shortTimeoutClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private val manualTimeoutClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchLatestGitHubRelease(manual: Boolean = false): AppReleaseInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(GITHUB_RELEASE_FEED_URL)
            .header("User-Agent", APP_USER_AGENT)
            .header("Accept", "application/atom+xml")
            .get()
            .build()

        val client = if (manual) manualTimeoutClient else shortTimeoutClient
        client.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw MaimaiAPIException.HttpError(resp.code)
            AppReleaseInfo.fromAtom(resp.body?.bytes() ?: ByteArray(0))
        }
    }

    // 6. Third-Party Prober Writeback & Diving-Fish Titles

    suspend fun fetchDivingFishMusicTitles(): Map<Int, String> {
        val request = Request.Builder()
            .url("$DIVING_FISH_BASE/api/maimaidxprober/music_data")
            .header("User-Agent", DIVING_FISH_UA)
            .get()
            .build()
        val (_, bytes) = executeAndRead(request, validate2xx = true)
        return withContext(Dispatchers.Default) {
            val arr = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonArray
                ?: return@withContext emptyMap()
            val result = HashMap<Int, String>(arr.size)
            for (elem in arr) {
                val obj = elem.jsonObject
                val idVal = obj["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
                val titleVal = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: continue
                result[idVal] = titleVal
            }
            result
        }
    }

    suspend fun uploadDivingFishRecordsByCookie(records: List<DivingFishUploadRecordDTO>, cookie: String) {
        uploadDivingFishRecords(records = records, authHeader = "Cookie", authValue = cookie)
    }

    suspend fun uploadDivingFishRecordsByImportToken(records: List<DivingFishUploadRecordDTO>, importToken: String) {
        uploadDivingFishRecords(records = records, authHeader = "Import-Token", authValue = importToken.trim())
    }

    private suspend fun uploadDivingFishRecords(
        records: List<DivingFishUploadRecordDTO>,
        authHeader: String,
        authValue: String
    ) {
        if (records.isEmpty()) return
        val payload = json.encodeToString(records)
        val request = Request.Builder()
            .url("$DIVING_FISH_BASE/api/maimaidxprober/player/update_records")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header(authHeader, authValue)
            .header("User-Agent", DIVING_FISH_UA)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val (code, bytes) = executeAndRead(request, validate2xx = false)
        if (code == 401 || code == 403) {
            throw MaimaiAPIException.Unauthorized(extractErrorMessage(bytes) ?: "水鱼登录或导入 Token 已失效")
        }
        if (code !in 200..299) {
            throw MaimaiAPIException.HttpError(code, extractErrorMessage(bytes))
        }
    }

    suspend fun uploadLXNSScoresByBearer(scores: List<LXNSUploadScoreDTO>, bearerToken: String) {
        uploadLXNSScores(scores = scores, headerField = "Authorization", headerValue = "Bearer $bearerToken")
    }

    suspend fun uploadLXNSScoresByPersonalToken(scores: List<LXNSUploadScoreDTO>, personalToken: String) {
        uploadLXNSScores(scores = scores, headerField = "X-User-Token", headerValue = personalToken.trim())
    }

    private suspend fun uploadLXNSScores(
        scores: List<LXNSUploadScoreDTO>,
        headerField: String,
        headerValue: String
    ) {
        if (scores.isEmpty()) return
        val body = json.encodeToString(LXNSUploadScoresRequest(scores = scores))
        val request = Request.Builder()
            .url("${LXNSConfiguration.API_BASE}/user/maimai/player/scores")
            .header("Content-Type", "application/json")
            .header(headerField, headerValue)
            .header("User-Agent", APP_USER_AGENT)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val (code, bytes) = executeAndRead(request, validate2xx = false)
        if (code == 401) {
            throw MaimaiAPIException.Unauthorized(extractErrorMessage(bytes) ?: "落雪凭据已过期，请重新授权")
        }
        if (code == 403) {
            throw MaimaiAPIException.HttpError(
                403,
                extractErrorMessage(bytes) ?: "落雪账号缺少成绩写入权限，请重新授权"
            )
        }
        if (code !in 200..299) {
            throw MaimaiAPIException.HttpError(code, extractErrorMessage(bytes))
        }
        val env = runCatching {
            json.decodeFromString<LXNSAPIEnvelope<String>>(bytes.toString(Charsets.UTF_8))
        }.getOrNull()
        if (env != null && !env.success && env.code != 0) {
            throw MaimaiAPIException.HttpError(env.code, env.message)
        }
    }

    // 7. Self-Hosted Sync Backend Client

    private const val BACKEND_CLIENT_VERSION = "2.0.0"

    private val syncBackendClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(50, TimeUnit.SECONDS)
            .build()
    }

    private fun isLoopbackHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "localhost" || lower == "127.0.0.1" || lower == "::1" || lower == "[::1]"
    }

    private fun normalizeBackendBaseUrl(raw: String): String {
        var trimmed = raw.trim()
        if (trimmed.isEmpty()) throw MaimaiAPIException.InvalidUrl()
        if (!trimmed.contains("://")) {
            trimmed = "https://$trimmed"
        }
        trimmed = trimmed.trimEnd('/')
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw MaimaiAPIException.InvalidUrl()
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (host.isNullOrEmpty()) {
            throw MaimaiAPIException.InvalidUrl()
        }
        if (scheme == "http") {
            if (!isLoopbackHost(host)) {
                throw MaimaiAPIException.HttpError(400, "同步服务地址必须使用 HTTPS")
            }
        } else if (scheme != "https") {
            throw MaimaiAPIException.InvalidUrl()
        }
        return trimmed
    }

    suspend fun syncBackendBind(
        config: SelfHostedEndpointConfig,
        qrContent: String,
        fullSync: Boolean
    ): SyncBackendResponse {
        val body = json.encodeToString(SyncBackendBindRequest(qrContent = qrContent, fullSync = fullSync))
        return performBackendPost(config = config, path = "/v1/sync/bind", syncToken = null, bodyJson = body)
    }

    suspend fun syncBackendQuick(
        config: SelfHostedEndpointConfig,
        syncToken: String
    ): SyncBackendResponse {
        return performBackendPost(config = config, path = "/v1/sync/quick", syncToken = syncToken, bodyJson = null)
    }

    suspend fun syncBackendFull(
        config: SelfHostedEndpointConfig,
        syncToken: String,
        qrContent: String
    ): SyncBackendResponse {
        val body = json.encodeToString(SyncBackendFullRequest(qrContent = qrContent))
        return performBackendPost(config = config, path = "/v1/sync/full", syncToken = syncToken, bodyJson = body)
    }

    suspend fun syncBackendUnbind(
        config: SelfHostedEndpointConfig,
        syncToken: String
    ) {
        val base = normalizeBackendBaseUrl(config.serverBaseURL)
        val req = buildBackendRequest(
            url = "$base/v1/sync/unbind",
            config = config,
            syncToken = syncToken,
            bodyJson = null
        )
        executeAndReadWithClient(syncBackendClient, req, validateBackend = true)
    }

    private suspend fun performBackendPost(
        config: SelfHostedEndpointConfig,
        path: String,
        syncToken: String?,
        bodyJson: String?
    ): SyncBackendResponse {
        val base = normalizeBackendBaseUrl(config.serverBaseURL)
        val req = buildBackendRequest(
            url = "$base$path",
            config = config,
            syncToken = syncToken,
            bodyJson = bodyJson
        )
        val bytes = executeAndReadWithClient(syncBackendClient, req, validateBackend = true)
        return withContext(Dispatchers.Default) {
            json.decodeFromString<SyncBackendResponse>(bytes.toString(Charsets.UTF_8))
        }
    }

    private fun buildBackendRequest(
        url: String,
        config: SelfHostedEndpointConfig,
        syncToken: String?,
        bodyJson: String?
    ): Request {
        val body = (bodyJson ?: "").toRequestBody(if (bodyJson != null) JSON_MEDIA_TYPE else null)
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", APP_USER_AGENT)
            .header("X-Access-Key", config.accessKey.trim())
            .header("X-Client-Version", BACKEND_CLIENT_VERSION)
            .header("X-Client-Platform", "android")
            .post(body)
        if (!syncToken.isNullOrEmpty()) {
            builder.header("Authorization", "Bearer $syncToken")
        }
        return builder.build()
    }

    private suspend fun executeAndReadWithClient(
        client: OkHttpClient,
        request: Request,
        validateBackend: Boolean
    ): ByteArray {
        val response = client.newCall(request).await()
        val (code, bytes) = withContext(Dispatchers.IO) {
            response.use { resp ->
                resp.code to (resp.body?.bytes() ?: ByteArray(0))
            }
        }
        if (code !in 200..299) {
            if (validateBackend) {
                val env = runCatching {
                    json.decodeFromString<SyncBackendErrorEnvelope>(bytes.toString(Charsets.UTF_8))
                }.getOrNull()
                if (env?.error != null) {
                    throw MaimaiAPIException.BackendError(
                        statusCode = code,
                        errorCode = env.error.code,
                        serverMessage = env.error.message,
                        retryAfterSec = env.error.retryAfterSec
                    )
                }
            }
            throw MaimaiAPIException.HttpError(code, extractErrorMessage(bytes))
        }
        return bytes
    }
}
