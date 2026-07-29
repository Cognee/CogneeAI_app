package ai.cognee.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.*
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

// ============================================================================
// 1. КОНСТАНТЫ И НАСТРОЙКИ
// ============================================================================

object Constants {
    const val SUPABASE_URL = "https://lwhvvketuaordqylidfc.supabase.co"
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx3aHZ2a2V0dWFvcmRxeWxpZGZjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMzMzg5MzIsImV4cCI6MjA4ODkxNDkzMn0.Sof0GLAzk86Nn6mnx1hbVgZn_rBQJXAkgtBjVSsWjLo"
    const val GEMINI_PROXY_URL = "https://lwhvvketuaordqylidfc.supabase.co/functions/v1/gemini-proxy"

    val COLOR_FOCUS = Color(0xFF4FC3F7)   // КИМ > 70
    val COLOR_NORMAL = Color(0xFF81C784)  // 40 <= КИМ <= 70
    val COLOR_TIRED = Color(0xFFFFB74D)   // КИМ < 40

    val RESERVED_NAMES = setOf("admin", "cognee", "cogneeai", "moderator", "system", "support", "root", "superuser")
    val ALLOWED_TAGS = listOf(
        "технологии", "история", "наука", "бизнес", "образование", 
        "психология", "медицина", "философия", "искусство", "спорт", 
        "политика", "экономика", "экология", "культура", "юмор"
    )
}

val Context.dataStore by preferencesDataStore(name = "cognee_prefs")

// ============================================================================
// 2. СЕТЕВЫЕ И DTO МОДЕЛИ
// ============================================================================

@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val user: UserDto? = null
)

@Serializable
data class UserDto(
    val id: String,
    val email: String? = null,
    @SerialName("user_metadata") val userMetadata: JsonObject? = null
)

@Serializable
data class UserProfile(
    val id: String,
    @SerialName("display_name") val displayName: String
)

@Serializable
data class ArticleDto(
    val id: Long = 0,
    @SerialName("user_id") val userId: String = "",
    val title: String = "",
    val content: String = "",
    @SerialName("content_simple") val contentSimple: String? = null,
    val keywords: List<String> = emptyList(),
    val annotation: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("recommended_kim") val recommendedKim: Int = 65,
    @SerialName("read_minutes") val readMinutes: Int = 5,
    @SerialName("published_at") val publishedAt: String = "",
    val visibility: String = "public",
    @SerialName("is_draft") val isDraft: Boolean = false,
    val slug: String = "",
    val users: UserProfile? = null
)

@Serializable
data class FavoriteDto(
    val id: Long = 0,
    @SerialName("user_id") val userId: String,
    @SerialName("article_id") val articleId: Long,
    @SerialName("created_at") val createdAt: String? = null,
    val articles: ArticleDto? = null
)

@Serializable
data class ReportDto(
    val id: Long = 0,
    @SerialName("article_id") val articleId: Long,
    @SerialName("reporter_id") val reporterId: String,
    val reason: String,
    val comment: String? = null,
    val status: String = "pending",
    val articles: ArticleDto? = null
)

@Serializable
data class KimHistoryPoint(
    @SerialName("user_id") val userId: String,
    val kim: Float,
    val zone: String,
    val timestamp: String
)

@Serializable
data class FocusLogDto(
    @SerialName("focus_minutes") val focusMinutes: Int,
    @SerialName("user_id") val userId: String,
    val users: UserProfile? = null
)

@Serializable
data class GeminiProxyRequest(
    val task: String,
    val text: String,
    val lang: String = "ru"
)

@Serializable
data class GeminiProxyResponse(
    val simplified: String? = null,
    val keywords: List<String>? = null,
    val annotation: String? = null,
    val rephrased: String? = null,
    val tags: List<String>? = null,
    @SerialName("recommended_kim") val recommendedKim: Int? = null
)

// ============================================================================
// 3. API И СЕТЕВОЙ КЛИЕНТ
// ============================================================================

interface SupabaseApi {
    @POST("auth/v1/signup")
    suspend fun signUp(@Body body: JsonObject): AuthResponse

    @POST("auth/v1/token?grant_type=password")
    suspend fun signIn(@Body body: JsonObject): AuthResponse

    @POST("auth/v1/logout")
    suspend fun logout(): retrofit2.Response<Unit>

    @GET("rest/v1/users")
    suspend fun getUserProfile(@Query("id") idFilter: String): List<UserProfile>

    @POST("rest/v1/users")
    suspend fun createUserProfile(
        @Header("Prefer") prefer: String = "resolution=merge-duplicates",
        @Body profile: UserProfile
    ): retrofit2.Response<Unit>

    @PATCH("rest/v1/users")
    suspend fun updateUserProfile(
        @Query("id") idFilter: String,
        @Header("Prefer") prefer: String = "return=representation",
        @Body body: JsonObject
    ): List<UserProfile>

    @GET("rest/v1/articles")
    suspend fun getMyArticles(
        @Query("user_id") userIdFilter: String,
        @Query("select") select: String = "id,title,annotation,published_at,visibility,is_draft,slug",
        @Query("order") order: String = "published_at.desc"
    ): List<ArticleDto>

    @GET("rest/v1/articles")
    suspend fun getArticleById(
        @Query("id") idFilter: String,
        @Query("select") select: String = "id,title,content,content_simple,keywords,annotation,tags,recommended_kim,read_minutes,published_at,user_id,visibility,slug,is_draft,users(display_name)"
    ): List<ArticleDto>

    @POST("rest/v1/rpc/get_article_by_slug")
    suspend fun getArticleBySlug(@Body body: JsonObject): List<ArticleDto>

    @POST("rest/v1/articles")
    suspend fun createArticle(
        @Header("Prefer") prefer: String = "return=representation",
        @Body articles: List<ArticleDto>
    ): List<ArticleDto>

    @PATCH("rest/v1/articles")
    suspend fun updateArticle(
        @Query("id") idFilter: String,
        @Query("user_id") userIdFilter: String,
        @Header("Prefer") prefer: String = "return=representation",
        @Body body: JsonObject
    ): List<ArticleDto>

    @DELETE("rest/v1/articles")
    suspend fun deleteArticle(
        @Query("id") idFilter: String,
        @Query("user_id") userIdFilter: String
    ): retrofit2.Response<Unit>

    @POST("rest/v1/rpc/search_public_articles")
    suspend fun searchPublicArticles(@Body body: JsonObject): List<ArticleDto>

    @POST("rest/v1/favorites")
    suspend fun addFavorite(@Body body: List<JsonObject>): retrofit2.Response<Unit>

    @DELETE("rest/v1/favorites")
    suspend fun removeFavorite(
        @Query("article_id") articleFilter: String,
        @Query("user_id") userFilter: String
    ): retrofit2.Response<Unit>

    @GET("rest/v1/favorites")
    suspend fun checkFavorite(
        @Query("article_id") articleFilter: String,
        @Query("user_id") userFilter: String,
        @Query("select") select: String = "id"
    ): List<JsonObject>

    @GET("rest/v1/favorites")
    suspend fun getFavorites(
        @Query("user_id") userFilter: String,
        @Query("select") select: String = "id,article_id,created_at,articles(id,title,annotation,published_at)",
        @Query("order") order: String = "created_at.desc"
    ): List<FavoriteDto>

    @POST("rest/v1/reports")
    suspend fun sendReport(@Body body: List<JsonObject>): retrofit2.Response<Unit>

    @GET("rest/v1/moderators")
    suspend fun checkIsModerator(
        @Query("user_id") userFilter: String,
        @Query("is_active") activeFilter: String = "eq.true",
        @Query("select") select: String = "user_id"
    ): List<JsonObject>

    @POST("rest/v1/rpc/get_pending_reports")
    suspend fun getPendingReports(@Body body: JsonObject): List<ReportDto>

    @POST("rest/v1/rpc/resolve_report")
    suspend fun resolveReport(@Body body: JsonObject): retrofit2.Response<Unit>

    @POST("rest/v1/kim_history")
    suspend fun pushKimHistory(
        @Header("Prefer") prefer: String = "resolution=ignore-duplicates,return=minimal",
        @Body history: List<KimHistoryPoint>
    ): retrofit2.Response<Unit>

    @POST("rest/v1/rpc/add_focus_minutes")
    suspend fun addFocusMinutes(@Body body: JsonObject): retrofit2.Response<Unit>

    @GET("rest/v1/focus_log")
    suspend fun getLeaderboard(
        @Query("week_start") weekStartFilter: String,
        @Query("select") select: String = "focus_minutes,user_id,users(display_name)",
        @Query("order") order: String = "focus_minutes.desc",
        @Query("limit") limit: Int = 20
    ): List<FocusLogDto>

    @GET("rest/v1/focus_log")
    suspend fun getUserFocusRank(
        @Query("user_id") userFilter: String,
        @Query("week_start") weekStartFilter: String,
        @Query("select") select: String = "focus_minutes"
    ): List<JsonObject>

    @POST("rest/v1/rpc/is_display_name_available")
    suspend fun checkDisplayNameAvailable(@Body body: JsonObject): Boolean
}

interface GeminiApi {
    @POST("functions/v1/gemini-proxy")
    suspend fun executeTask(@Body request: GeminiProxyRequest): GeminiProxyResponse
}

class NetworkModule(private val sessionManager: SessionManager) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val token = sessionManager.accessToken.value
        val builder = original.newBuilder()
            .header("apikey", Constants.SUPABASE_ANON_KEY)
            .header("Content-Type", "application/json")

        if (!token.isNullOrEmpty()) {
            builder.header("Authorization", "Bearer $token")
        }
        chain.proceed(builder.build())
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .build()

    val supabaseApi: SupabaseApi = Retrofit.Builder()
        .baseUrl(Constants.SUPABASE_URL + "/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(SupabaseApi::class.java)

    val geminiApi: GeminiApi = Retrofit.Builder()
        .baseUrl(Constants.SUPABASE_URL + "/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GeminiApi::class.java)
}

// ============================================================================
// 4. МЕНЕДЖЕР СЕССИИ И ХРАНИЛИЩЕ DATASTORE
// ============================================================================

class SessionManager(private val context: Context) {
    private val TOKEN_KEY = stringPreferencesKey("access_token")
    private val REFRESH_KEY = stringPreferencesKey("refresh_token")
    private val USER_ID_KEY = stringPreferencesKey("user_id")
    private val DISPLAY_NAME_KEY = stringPreferencesKey("display_name")
    private val EMAIL_KEY = stringPreferencesKey("email")

    val accessToken = MutableStateFlow<String?>(null)
    val refreshToken = MutableStateFlow<String?>(null)
    val userId = MutableStateFlow<String?>(null)
    val displayName = MutableStateFlow<String?>(null)
    val email = MutableStateFlow<String?>(null)

    private val refreshMutex = Mutex()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            context.dataStore.data.collect { prefs ->
                accessToken.value = prefs[TOKEN_KEY]
                refreshToken.value = prefs[REFRESH_KEY]
                userId.value = prefs[USER_ID_KEY]
                displayName.value = prefs[DISPLAY_NAME_KEY]
                email.value = prefs[EMAIL_KEY]
            }
        }
    }

    fun saveSession(token: String, refresh: String, uid: String, name: String, mail: String, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[TOKEN_KEY] = token
                prefs[REFRESH_KEY] = refresh
                prefs[USER_ID_KEY] = uid
                prefs[DISPLAY_NAME_KEY] = name
                prefs[EMAIL_KEY] = mail
            }
            accessToken.value = token
            refreshToken.value = refresh
            userId.value = uid
            displayName.value = name
            email.value = mail
        }
    }

    fun clearSession(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        scope.launch {
            context.dataStore.edit { it.clear() }
            accessToken.value = null
            refreshToken.value = null
            userId.value = null
            displayName.value = null
            email.value = null
        }
    }

    fun startAutoRefresh(api: SupabaseApi, scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(50 * 60 * 1000L) // Раз в 50 минут
                val currentRefresh = refreshToken.value
                if (!currentRefresh.isNullOrEmpty()) {
                    refreshMutex.withLock {
                        try {
                            val body = JsonObject(mapOf("refresh_token" to JsonPrimitive(currentRefresh)))
                            val res = api.signIn(body)
                            if (res.accessToken != null && res.refreshToken != null) {
                                context.dataStore.edit { prefs ->
                                    prefs[TOKEN_KEY] = res.accessToken
                                    prefs[REFRESH_KEY] = res.refreshToken
                                }
                                accessToken.value = res.accessToken
                                refreshToken.value = res.refreshToken
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 5. ДВИЖОК КИМ (KimEngine)
// ============================================================================

class KimEngine(private val context: Context) {
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // 16 Сенсоров
    private var scrollIntervals = mutableListOf<Long>()
    private var lastScrollTime = 0L
    private var lastTapTime = System.currentTimeMillis()
    private var scrollReturnCount = 0
    private val sessionStartTime = System.currentTimeMillis()
    private var paragraphRevisitCount = 0
    private var idleBursts = 0
    private var lastActivityTime = System.currentTimeMillis()
    private var currentParagraphDwellSec = 0f
    private var scrollDirectionChanges = 0
    private var lastScrollDirection = 0 // 1: down, -1: up
    private var paragraphViewportRevisits = 0
    private var microPauseCount = 0
    private var totalWordsRead = 0
    private var fingerVelocity = 0f
    private var focusLossCount = 0

    val currentKim = MutableStateFlow(75f)
    val currentZone = MutableStateFlow("focus") // focus, normal, tired
    val isManualLockActive = MutableStateFlow(false)

    private var manualLockUntil = 0L
    private var smoothedKim = 75f

    init {
        initONNX()
    }

    private fun initONNX() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open("model/cognee_ai.onnx").readBytes()
            ortSession = ortEnv?.createSession(modelBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback эвристика если модели нет в assets
        }
    }

    fun registerTouch(velocity: Float) {
        lastTapTime = System.currentTimeMillis()
        lastActivityTime = lastTapTime
        fingerVelocity = velocity
    }

    fun registerScroll(dy: Float) {
        val now = System.currentTimeMillis()
        if (lastScrollTime > 0) {
            val delta = now - lastScrollTime
            scrollIntervals.add(delta)
            if (scrollIntervals.size > 20) scrollIntervals.removeAt(0)
            if (delta in 300..1500) microPauseCount++
        }
        lastScrollTime = now
        lastActivityTime = now

        val dir = if (dy > 0) 1 else -1
        if (lastScrollDirection != 0 && dir != lastScrollDirection) {
            scrollDirectionChanges++
            if (dir == -1) scrollReturnCount++
        }
        lastScrollDirection = dir
    }

    fun registerFocusLoss() {
        focusLossCount++
    }

    fun registerParagraphStay(sec: Float) {
        currentParagraphDwellSec += sec
    }

    fun manualSetZone(zone: String) {
        currentZone.value = zone
        manualLockUntil = System.currentTimeMillis() + 180000L // 3 минуты блокировки
        isManualLockActive.value = true
    }

    fun computeKIM(): Pair<Float, String> {
        val now = System.currentTimeMillis()
        if (now < manualLockUntil) {
            isManualLockActive.value = true
        } else {
            isManualLockActive.value = false
        }

        // Проверка всплесков бездействия
        if (now - lastActivityTime > 3500) {
            idleBursts++
            lastActivityTime = now
        }

        val f0 = clamp((if (scrollIntervals.isNotEmpty()) scrollIntervals.average().toFloat() else 250f) / 500f, 0f, 1f)
        val f1 = clamp((if (scrollIntervals.isNotEmpty()) variance(scrollIntervals).toFloat() else 50f) / 200f, 0f, 1f)
        val f2 = clamp((now - lastTapTime).toFloat() / 1000f, 0f, 1f)
        val f3 = clamp(scrollReturnCount.toFloat() / 10f, 0f, 1f)
        val f4 = clamp((now - sessionStartTime).toFloat() / 3600000f, 0f, 1f)

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val f5 = hour.toFloat() / 24f
        val f6 = clamp(paragraphRevisitCount.toFloat() / 5f, 0f, 1f)
        val f7 = clamp(idleBursts.toFloat() / 5f, 0f, 1f)
        val f8 = clamp(currentParagraphDwellSec / 120f, 0f, 1f)
        val f9 = clamp(scrollDirectionChanges.toFloat() / 10f, 0f, 1f)
        val f10 = clamp(paragraphViewportRevisits.toFloat() / 8f, 0f, 1f)
        val f11 = clamp(microPauseCount.toFloat() / 20f, 0f, 1f)
        val f12 = clamp(220f / 400f, 0f, 1f) // Стандартная скорость чтения
        val f13 = clamp(fingerVelocity / 1000f, 0f, 1f)
        val f14 = clamp(focusLossCount.toFloat() / 5f, 0f, 1f)
        val f15 = 1.0f // Android Touch Device

        val vector = floatArrayOf(f0, f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15)

        var rawKim = 0f
        var isNN = false

        if (ortSession != null && ortEnv != null) {
            try {
                val inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(vector), longArrayOf(1, 16))
                val output = ortSession?.run(mapOf("input" to inputTensor))
                val rawOutput = output?.get(0)?.value
                if (rawOutput is Array<*>) {
                    val probs = (rawOutput as Array<FloatArray>)[0]
                    rawKim = probs[0] * 95f + probs[1] * 65f + probs[2] * 25f + probs[3] * 35f + probs[4] * 10f
                    isNN = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!isNN) {
            // Fallback Эвристика
            val scrollScore = 1f - f0
            val clickScore = 1f - f2
            val returnScore = 1f - f3
            val dwellPenalty = clamp(currentParagraphDwellSec / 120f, 0f, 1f) * 20f
            val dirPenalty = clamp(scrollDirectionChanges.toFloat() / 10f, 0f, 1f) * 15f
            val revisitPenalty = clamp(paragraphViewportRevisits.toFloat() / 8f, 0f, 1f) * 15f
            val microPausePenalty = f11 * 10f

            val raw = (scrollScore * 0.35f + clickScore * 0.25f + returnScore * 0.25f) * 100f - dwellPenalty - dirPenalty - revisitPenalty - microPausePenalty

            val chronoBonus = when (hour) {
                in 9..11, in 17..19 -> 8f
                in 13..15 -> -10f
                in 0..5 -> -15f
                else -> 0f
            }
            rawKim = clamp(raw + chronoBonus, 0f, 100f)
        }

        val alpha = if (isNN) 0.25f else 0.35f
        smoothedKim = alpha * rawKim + (1f - alpha) * smoothedKim
        val finalKim = clamp(smoothedKim, 0f, 100f)

        // Порог обновления 8 пунктов
        if (abs(finalKim - currentKim.value) >= 8f && !isManualLockActive.value) {
            currentKim.value = finalKim
            val newZone = when {
                finalKim > 70f -> "focus"
                finalKim >= 40f -> "normal"
                else -> "tired"
            }
            currentZone.value = newZone
        }

        return Pair(currentKim.value, currentZone.value)
    }

    private fun clamp(value: Float, min: Float, max: Float) = max(min, min(max, value))

    private fun variance(list: List<Long>): Double {
        val avg = list.average()
        return list.sumOf { (it - avg).pow(2) } / list.size
    }
}

// ============================================================================
// 6. GEMINI PROXY & RATE LIMITER
// ============================================================================

class GeminiProxyClient(private val api: GeminiApi) {
    private val cache = mutableMapOf<String, String>()
    private val mutex = Mutex()
    private var lastCallTime = 0L

    suspend fun simplifyParagraph(text: String): String {
        if (cache.containsKey("simp_$text")) return cache["simp_$text"]!!
        val res = executeWithRateLimit { api.executeTask(GeminiProxyRequest("simplify", text)) }
        val result = res?.simplified ?: fallbackSimplify(text)
        cache["simp_$text"] = result
        return result
    }

    suspend fun rephraseParagraph(text: String): String {
        if (cache.containsKey("rephrase_$text")) return cache["rephrase_$text"]!!
        val res = executeWithRateLimit { api.executeTask(GeminiProxyRequest("rephrase", text)) }
        val result = res?.rephrased ?: "Простыми словами: $text"
        cache["rephrase_$text"] = result
        return result
    }

    suspend fun generateTagsAndKim(text: String): Pair<List<String>, Int> {
        val res = executeWithRateLimit { api.executeTask(GeminiProxyRequest("tags", text)) }
        val tags = res?.tags ?: listOf("наука", "технологии")
        val kim = res?.recommendedKim ?: 65
        return Pair(tags, kim)
    }

    private suspend fun <T> executeWithRateLimit(block: suspend () -> T): T? {
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLast = now - lastCallTime
            if (timeSinceLast < 4000) {
                delay(4000 - timeSinceLast)
            }
            lastCallTime = System.currentTimeMillis()
            try {
                block()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun fallbackSimplify(text: String): String {
        val sentences = text.split(Regex("[.!?]+")).filter { it.isNotBlank() }
        return sentences.take(2).joinToString(". ") + "."
    }
}

// ============================================================================
// 7. VIEWMODELS
// ============================================================================

class MainViewModel(
    val sessionManager: SessionManager,
    val networkModule: NetworkModule,
    val kimEngine: KimEngine,
    val geminiClient: GeminiProxyClient
) : ViewModel() {

    val isModerator = MutableStateFlow(false)
    val myArticles = MutableStateFlow<List<ArticleDto>>(emptyList())
    val publicArticles = MutableStateFlow<List<ArticleDto>>(emptyList())
    val favoriteArticles = MutableStateFlow<List<FavoriteDto>>(emptyList())
    val leaderboard = MutableStateFlow<List<FocusLogDto>>(emptyList())
    val pendingReports = MutableStateFlow<List<ReportDto>>(emptyList())

    init {
        sessionManager.startAutoRefresh(networkModule.supabaseApi, viewModelScope)
        checkModeratorStatus()
    }

    fun checkModeratorStatus() {
        val uid = sessionManager.userId.value ?: return
        viewModelScope.launch {
            try {
                val res = networkModule.supabaseApi.checkIsModerator("eq.$uid")
                isModerator.value = res.isNotEmpty()
            } catch (e: Exception) {
                isModerator.value = false
            }
        }
    }

    fun loadCatalog(query: String = "") {
        viewModelScope.launch {
            try {
                if (query.isBlank()) {
                    val res = networkModule.supabaseApi.searchPublicArticles(
                        JsonObject(mapOf("p_query" to JsonPrimitive(""), "p_limit" to JsonPrimitive(20), "p_offset" to JsonPrimitive(0)))
                    )
                    publicArticles.value = res
                } else {
                    val res = networkModule.supabaseApi.searchPublicArticles(
                        JsonObject(mapOf("p_query" to JsonPrimitive(query), "p_limit" to JsonPrimitive(20), "p_offset" to JsonPrimitive(0)))
                    )
                    publicArticles.value = res
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadMyArticles() {
        val uid = sessionManager.userId.value ?: return
        viewModelScope.launch {
            try {
                myArticles.value = networkModule.supabaseApi.getMyArticles("eq.$uid")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadFavorites() {
        val uid = sessionManager.userId.value ?: return
        viewModelScope.launch {
            try {
                favoriteArticles.value = networkModule.supabaseApi.getFavorites("eq.$uid")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadLeaderboard() {
        val mondayDate = getMondayDate()
        viewModelScope.launch {
            try {
                leaderboard.value = networkModule.supabaseApi.getLeaderboard("eq.$mondayDate")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadPendingReports() {
        viewModelScope.launch {
            try {
                pendingReports.value = networkModule.supabaseApi.getPendingReports(
                    JsonObject(mapOf("p_limit" to JsonPrimitive(50), "p_offset" to JsonPrimitive(0)))
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun resolveReport(reportId: Long, hideArticle: Boolean) {
        viewModelScope.launch {
            try {
                networkModule.supabaseApi.resolveReport(
                    JsonObject(mapOf(
                        "p_report_id" to JsonPrimitive(reportId),
                        "p_status" to JsonPrimitive("resolved"),
                        "p_hide_article" to JsonPrimitive(hideArticle)
                    ))
                )
                loadPendingReports()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getMondayDate(): String {
        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysToSubtract = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        cal.add(Calendar.DAY_OF_MONTH, -daysToSubtract)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(cal.time)
    }
}

// ============================================================================
// 8. JETPACK COMPOSE UI ЭКРАНЫ
// ============================================================================

@Composable
fun CogneeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Constants.COLOR_FOCUS,
            secondary = Constants.COLOR_NORMAL,
            tertiary = Constants.COLOR_TIRED,
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@Composable
fun SplashScreen(navController: NavHostController, sessionManager: SessionManager) {
    val token by sessionManager.accessToken.collectAsState()
    LaunchedEffect(token) {
        delay(1500)
        if (token.isNullOrEmpty()) {
            navController.navigate("auth") { popUpTo("splash") { inclusive = true } }
        } else {
            navController.navigate("catalog") { popUpTo("splash") { inclusive = true } }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = "Cognee Logo",
                tint = Constants.COLOR_FOCUS,
                modifier = Modifier.size(96.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("CogneeAI", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Адаптивная платформа чтения", fontSize = 14.sp, color = Color.Gray)
        }
    }
}

@Composable
fun AuthScreen(navController: NavHostController, viewModel: MainViewModel) {
    var isLogin by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isLogin) "Вход в Cognee" else "Регистрация",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (!isLogin) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Отображаемое имя") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMsg.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            isLoading = true
                            errorMsg = ""
                            try {
                                if (isLogin) {
                                    val body = JsonObject(mapOf("email" to JsonPrimitive(email), "password" to JsonPrimitive(password)))
                                    val res = viewModel.networkModule.supabaseApi.signIn(body)
                                    val uid = res.user?.id ?: ""
                                    val prof = viewModel.networkModule.supabaseApi.getUserProfile("eq.$uid").firstOrNull()
                                    viewModel.sessionManager.saveSession(
                                        res.accessToken ?: "",
                                        res.refreshToken ?: "",
                                        uid,
                                        prof?.displayName ?: "User",
                                        email,
                                        coroutineScope
                                    )
                                    viewModel.checkModeratorStatus()
                                    navController.navigate("catalog") { popUpTo("auth") { inclusive = true } }
                                } else {
                                    if (displayName.length !in 2..30 || Constants.RESERVED_NAMES.contains(displayName.lowercase())) {
                                        errorMsg = "Недопустимое отображаемое имя"
                                        isLoading = false
                                        return@launch
                                    }
                                    val avail = viewModel.networkModule.supabaseApi.checkDisplayNameAvailable(
                                        JsonObject(mapOf("p_name" to JsonPrimitive(displayName), "p_user_id" to JsonNull))
                                    )
                                    if (!avail) {
                                        errorMsg = "Имя уже занято"
                                        isLoading = false
                                        return@launch
                                    }
                                    val body = JsonObject(mapOf(
                                        "email" to JsonPrimitive(email),
                                        "password" to JsonPrimitive(password),
                                        "data" to JsonObject(mapOf("display_name" to JsonPrimitive(displayName)))
                                    ))
                                    val res = viewModel.networkModule.supabaseApi.signUp(body)
                                    val uid = res.user?.id ?: ""
                                    viewModel.networkModule.supabaseApi.createUserProfile(profile = UserProfile(uid, displayName))
                                    viewModel.sessionManager.saveSession(
                                        res.accessToken ?: "",
                                        res.refreshToken ?: "",
                                        uid,
                                        displayName,
                                        email,
                                        coroutineScope
                                    )
                                    navController.navigate("catalog") { popUpTo("auth") { inclusive = true } }
                                }
                            } catch (e: Exception) {
                                errorMsg = "Ошибка авторизации: ${e.localizedMessage}"
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    Text(if (isLogin) "Войти" else "Зарегистрироваться")
                }

                TextButton(onClick = { isLogin = !isLogin }) {
                    Text(if (isLogin) "Нет аккаунта? Зарегистрироваться" else "Уже есть аккаунт? Войти")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(navController: NavHostController, viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val publicArticles by viewModel.publicArticles.collectAsState()
    val myArticles by viewModel.myArticles.collectAsState()
    val favorites by viewModel.favoriteArticles.collectAsState()
    val isModerator by viewModel.isModerator.collectAsState()

    LaunchedEffect(selectedTab, searchQuery) {
        when (selectedTab) {
            0 -> viewModel.loadCatalog(searchQuery)
            1 -> viewModel.loadMyArticles()
            2 -> viewModel.loadFavorites()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cognee Каталог") },
                actions = {
                    if (isModerator) {
                        IconButton(onClick = { navController.navigate("moderation") }) {
                            Icon(Icons.Default.Shield, contentDescription = "Модерация", tint = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    IconButton(onClick = { navController.navigate("leaderboard") }) {
                        Icon(Icons.Default.Leaderboard, contentDescription = "Лидерборд")
                    }
                    IconButton(onClick = { navController.navigate("profile") }) {
                        Icon(Icons.Default.Person, contentDescription = "Профиль")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("editor/0") }) {
                Icon(Icons.Default.Add, contentDescription = "Создать статью")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("Публичные", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("Мои статьи", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Text("Избранное", modifier = Modifier.padding(12.dp))
                }
            }

            if (selectedTab == 0) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Поиск статей...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                if (selectedTab == 0) {
                    items(publicArticles) { article -> ArticleItem(article) { navController.navigate("reader/${article.id}") } }
                } else if (selectedTab == 1) {
                    items(myArticles) { article -> ArticleItem(article) { navController.navigate("reader/${article.id}") } }
                } else {
                    items(favorites) { fav ->
                        fav.articles?.let { article ->
                            ArticleItem(article) { navController.navigate("reader/${article.id}") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArticleItem(article: ArticleDto, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(article.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(article.annotation, fontSize = 14.sp, color = Color.LightGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("КИМ: ${article.recommendedKim}", fontSize = 12.sp, color = Constants.COLOR_FOCUS, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(12.dp))
                Text("${article.readMinutes} мин чтения", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(articleId: Long, navController: NavHostController, viewModel: MainViewModel) {
    var article by remember { mutableStateOf<ArticleDto?>(null) }
    val currentKim by viewModel.kimEngine.currentKim.collectAsState()
    val currentZone by viewModel.kimEngine.currentZone.collectAsState()
    val isLocked by viewModel.kimEngine.isManualLockActive.collectAsState()

    var paragraphTexts by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var reportModalOpen by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(articleId) {
        try {
            val list = viewModel.networkModule.supabaseApi.getArticleById("eq.$articleId")
            if (list.isNotEmpty()) {
                article = list.first()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Таймер КИМ 20 сек
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(20000L)
            val (kim, zone) = viewModel.kimEngine.computeKIM()
            viewModel.sessionManager.userId.value?.let { uid ->
                try {
                    viewModel.networkModule.supabaseApi.pushKimHistory(
                        history = listOf(KimHistoryPoint(uid, kim, zone, SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())))
                    )
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    val zoneColor = when (currentZone) {
        "focus" -> Constants.COLOR_FOCUS
        "normal" -> Constants.COLOR_NORMAL
        else -> Constants.COLOR_TIRED
    }

    val paragraphs = remember(article, currentZone) {
        article?.content?.split("\n\n")?.filter { it.isNotBlank() } ?: emptyList()
    }

    Scaffold(
        topBar = {
            Surface(color = Color(0xFF1E1E1E), shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(16.dp).clip(CircleShape).background(zoneColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "КИМ: ${currentKim.toInt()} (${when (currentZone) { "focus" -> "⚡ Поток"; "normal" -> "Норма"; else -> "🌙 Устал" }})",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Row {
                        IconButton(onClick = {
                            viewModel.sessionManager.userId.value?.let { uid ->
                                coroutineScope.launch {
                                    viewModel.networkModule.supabaseApi.addFavorite(
                                        listOf(JsonObject(mapOf("user_id" to JsonPrimitive(uid), "article_id" to JsonPrimitive(articleId))))
                                    )
                                }
                            }
                        }) {
                            Icon(Icons.Default.Bookmark, contentDescription = "Избранное", tint = Color.White)
                        }
                        IconButton(onClick = { reportModalOpen = true }) {
                            Icon(Icons.Default.Flag, contentDescription = "Пожаловаться", tint = Color.Gray)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF121212))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, _, _ ->
                        viewModel.kimEngine.registerScroll(pan.y)
                        viewModel.kimEngine.registerTouch(sqrt(pan.x * pan.x + pan.y * pan.y))
                    }
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Переключение зоны вручную
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilterChip(
                        selected = currentZone == "focus",
                        onClick = { viewModel.kimEngine.manualSetZone("focus") },
                        label = { Text("⚡ Поток") }
                    )
                    FilterChip(
                        selected = currentZone == "normal",
                        onClick = { viewModel.kimEngine.manualSetZone("normal") },
                        label = { Text("Норма") }
                    )
                    FilterChip(
                        selected = currentZone == "tired",
                        onClick = { viewModel.kimEngine.manualSetZone("tired") },
                        label = { Text("🌙 Устал") }
                    )
                }

                if (isLocked) {
                    Text("Ручной режим активирован на 3 мин", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        Text(article?.title ?: "", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Автор: ${article?.users?.displayName ?: "Неизвестен"}", fontSize = 12.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    items(paragraphs.size) { index ->
                        val text = paragraphTexts[index] ?: paragraphs[index]
                        val fontSize = if (currentZone == "tired") 18.sp else 15.sp

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(text, fontSize = fontSize, color = Color.White, lineHeight = (fontSize.value * 1.4).sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = {
                                        coroutineScope.launch {
                                            val simp = viewModel.geminiClient.simplifyParagraph(paragraphs[index])
                                            paragraphTexts = paragraphTexts.toMutableMap().apply { put(index, simp) }
                                        }
                                    }) { Text("Упростить", fontSize = 12.sp) }

                                    TextButton(onClick = {
                                        coroutineScope.launch {
                                            val rephrase = viewModel.geminiClient.rephraseParagraph(paragraphs[index])
                                            paragraphTexts = paragraphTexts.toMutableMap().apply { put(index, rephrase) }
                                        }
                                    }) { Text("Объясни иначе", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }

            if (reportModalOpen) {
                AlertDialog(
                    onDismissRequest = { reportModalOpen = false },
                    title = { Text("Пожаловаться на статью") },
                    text = {
                        OutlinedTextField(
                            value = reportReason,
                            onValueChange = { reportReason = it },
                            label = { Text("Причина жалобы") }
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            viewModel.sessionManager.userId.value?.let { uid ->
                                coroutineScope.launch {
                                    viewModel.networkModule.supabaseApi.sendReport(
                                        listOf(JsonObject(mapOf(
                                            "article_id" to JsonPrimitive(articleId),
                                            "reporter_id" to JsonPrimitive(uid),
                                            "reason" to JsonPrimitive(reportReason),
                                            "status" to JsonPrimitive("pending")
                                        )))
                                    )
                                    reportModalOpen = false
                                }
                            }
                        }) { Text("Отправить") }
                    },
                    dismissButton = {
                        TextButton(onClick = { reportModalOpen = false }) { Text("Отмена") }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(articleId: Long, navController: NavHostController, viewModel: MainViewModel) {
    var title by remember { mutableStateOf("") }
    var annotation by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var recommendedKim by remember { mutableIntStateOf(65) }
    var isAIWorking by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (articleId == 0L) "Новая статья" else "Редактирование") })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Заголовок") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = annotation, onValueChange = { annotation = it }, label = { Text("Аннотация") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("Текст статьи (абзацы через пустую строку)") },
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        isAIWorking = true
                        val (aiTags, aiKim) = viewModel.geminiClient.generateTagsAndKim(content)
                        tags = aiTags
                        recommendedKim = aiKim
                        isAIWorking = false
                    }
                },
                enabled = !isAIWorking && content.isNotBlank()
            ) {
                Text(if (isAIWorking) "Анализ AI..." else "Сгенерировать теги и КИМ")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Теги: ${tags.joinToString(", ")}", color = Color.LightGray)
            Text("Рекомендуемый КИМ: $recommendedKim", color = Constants.COLOR_FOCUS)

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val uid = viewModel.sessionManager.userId.value ?: return@Button
                    coroutineScope.launch {
                        val slug = UUID.randomUUID().toString().take(8)
                        val dto = ArticleDto(
                            userId = uid,
                            title = title,
                            content = content,
                            annotation = annotation,
                            tags = tags,
                            recommendedKim = recommendedKim,
                            readMinutes = max(1, content.split("\\s+".toRegex()).size / 200),
                            publishedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
                            visibility = "public",
                            isDraft = false,
                            slug = slug
                        )
                        viewModel.networkModule.supabaseApi.createArticle(articles = listOf(dto))
                        navController.popBackStack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank() && content.isNotBlank()
            ) {
                Text("Опубликовать")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(navController: NavHostController, viewModel: MainViewModel) {
    val displayName by viewModel.sessionManager.displayName.collectAsState()
    val email by viewModel.sessionManager.email.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(topBar = { TopAppBar(title = { Text("Профиль") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(80.dp), tint = Constants.COLOR_FOCUS)
            Spacer(modifier = Modifier.height(16.dp))
            Text(displayName ?: "Пользователь", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(email ?: "", fontSize = 14.sp, color = Color.Gray)

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    viewModel.sessionManager.clearSession(coroutineScope)
                    navController.navigate("auth") { popUpTo(0) }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Выйти из аккаунта")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen(navController: NavHostController, viewModel: MainViewModel) {
    val list by viewModel.leaderboard.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadLeaderboard() }

    Scaffold(topBar = { TopAppBar(title = { Text("Лидерборд недели") }) }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(16.dp)) {
            items(list.size) { index ->
                val item = list[index]
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("#${index + 1}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Constants.COLOR_FOCUS)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(item.users?.displayName ?: "Пользователь", fontSize = 16.sp, color = Color.White)
                        }
                        Text("${item.focusMinutes} мин в фокусе", fontSize = 14.sp, color = Constants.COLOR_NORMAL)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModerationScreen(navController: NavHostController, viewModel: MainViewModel) {
    val reports by viewModel.pendingReports.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadPendingReports() }

    Scaffold(topBar = { TopAppBar(title = { Text("Модерация") }) }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(16.dp)) {
            items(reports) { report ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Причина: ${report.reason}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Статья ID: ${report.articleId}", fontSize = 12.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { viewModel.resolveReport(report.id, false) }) {
                                Text("Отклонить", color = Color.Gray)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { viewModel.resolveReport(report.id, true) }) {
                                Text("Скрыть статью")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 9. MAIN ACTIVITY & NAV GRAPH
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionManager = SessionManager(applicationContext)
        val networkModule = NetworkModule(sessionManager)
        val kimEngine = KimEngine(applicationContext)
        val geminiClient = GeminiProxyClient(networkModule.geminiApi)

        val viewModel = MainViewModel(sessionManager, networkModule, kimEngine, geminiClient)

        setContent {
            CogneeTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "splash") {
                    composable("splash") { SplashScreen(navController, sessionManager) }
                    composable("auth") { AuthScreen(navController, viewModel) }
                    composable("catalog") { CatalogScreen(navController, viewModel) }
                    composable("reader/{articleId}") { backStackEntry ->
                        val id = backStackEntry.arguments?.getString("articleId")?.toLongOrNull() ?: 0L
                        ReaderScreen(id, navController, viewModel)
                    }
                    composable("editor/{articleId}") { backStackEntry ->
                        val id = backStackEntry.arguments?.getString("articleId")?.toLongOrNull() ?: 0L
                        EditorScreen(id, navController, viewModel)
                    }
                    composable("profile") { ProfileScreen(navController, viewModel) }
                    composable("leaderboard") { LeaderboardScreen(navController, viewModel) }
                    composable("moderation") { ModerationScreen(navController, viewModel) }
                }
            }
        }
    }
}import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.*
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

// ============================================================================
// 1. КОНСТАНТЫ И НАСТРОЙКИ
// ============================================================================

object Constants {
    const val SUPABASE_URL = "https://lwhvvketuaordqylidfc.supabase.co"
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx3aHZ2a2V0dWFvcmRxeWxpZGZjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMzMzg5MzIsImV4cCI6MjA4ODkxNDkzMn0.Sof0GLAzk86Nn6mnx1hbVgZn_rBQJXAkgtBjVSsWjLo"
    const val GEMINI_PROXY_URL = "https://lwhvvketuaordqylidfc.supabase.co/functions/v1/gemini-proxy"

    val COLOR_FOCUS = Color(0xFF4FC3F7)   // КИМ > 70
    val COLOR_NORMAL = Color(0xFF81C784)  // 40 <= КИМ <= 70
    val COLOR_TIRED = Color(0xFFFFB74D)   // КИМ < 40

    val RESERVED_NAMES = setOf("admin", "cognee", "cogneeai", "moderator", "system", "support", "root", "superuser")
    val ALLOWED_TAGS = listOf(
        "технологии", "история", "наука", "бизнес", "образование", 
        "психология", "медицина", "философия", "искусство", "спорт", 
        "политика", "экономика", "экология", "культура", "юмор"
    )
}

val Context.dataStore by preferencesDataStore(name = "cognee_prefs")

// ============================================================================
// 2. СЕТЕВЫЕ И DTO МОДЕЛИ
// ============================================================================

@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val user: UserDto? = null
)

@Serializable
data class UserDto(
    val id: String,
    val email: String? = null,
    @SerialName("user_metadata") val userMetadata: JsonObject? = null
)

@Serializable
data class UserProfile(
    val id: String,
    @SerialName("display_name") val displayName: String
)

@Serializable
data class ArticleDto(
    val id: Long = 0,
    @SerialName("user_id") val userId: String = "",
    val title: String = "",
    val content: String = "",
    @SerialName("content_simple") val contentSimple: String? = null,
    val keywords: List<String> = emptyList(),
    val annotation: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("recommended_kim") val recommendedKim: Int = 65,
    @SerialName("read_minutes") val readMinutes: Int = 5,
    @SerialName("published_at") val publishedAt: String = "",
    val visibility: String = "public",
    @SerialName("is_draft") val isDraft: Boolean = false,
    val slug: String = "",
    val users: UserProfile? = null
)

@Serializable
data class FavoriteDto(
    val id: Long = 0,
    @SerialName("user_id") val userId: String,
    @SerialName("article_id") val articleId: Long,
    @SerialName("created_at") val createdAt: String? = null,
    val articles: ArticleDto? = null
)

@Serializable
data class ReportDto(
    val id: Long = 0,
    @SerialName("article_id") val articleId: Long,
    @SerialName("reporter_id") val reporterId: String,
    val reason: String,
    val comment: String? = null,
    val status: String = "pending",
    val articles: ArticleDto? = null
)

@Serializable
data class KimHistoryPoint(
    @SerialName("user_id") val userId: String,
    val kim: Float,
    val zone: String,
    val timestamp: String
)

@Serializable
data class FocusLogDto(
    @SerialName("focus_minutes") val focusMinutes: Int,
    @SerialName("user_id") val userId: String,
    val users: UserProfile? = null
)

@Serializable
data class GeminiProxyRequest(
    val task: String,
    val text: String,
    val lang: String = "ru"
)

@Serializable
data class GeminiProxyResponse(
    val simplified: String? = null,
    val keywords: List<String>? = null,
    val annotation: String? = null,
    val rephrased: String? = null,
    val tags: List<String>? = null,
    @SerialName("recommended_kim") val recommendedKim: Int? = null
)

// ============================================================================
// 3. API И СЕТЕВОЙ КЛИЕНТ
// ============================================================================

interface SupabaseApi {
    @POST("auth/v1/signup")
    suspend fun signUp(@Body body: JsonObject): AuthResponse

    @POST("auth/v1/token?grant_type=password")
    suspend fun signIn(@Body body: JsonObject): AuthResponse

    @POST("auth/v1/logout")
    suspend fun logout(): retrofit2.Response<Unit>

    @GET("rest/v1/users")
    suspend fun getUserProfile(@Query("id") idFilter: String): List<UserProfile>

    @POST("rest/v1/users")
    suspend fun createUserProfile(
        @Header("Prefer") prefer: String = "resolution=merge-duplicates",
        @Body profile: UserProfile
    ): retrofit2.Response<Unit>

    @PATCH("rest/v1/users")
    suspend fun updateUserProfile(
        @Query("id") idFilter: String,
        @Header("Prefer") prefer: String = "return=representation",
        @Body body: JsonObject
    ): List<UserProfile>

    @GET("rest/v1/articles")
    suspend fun getMyArticles(
        @Query("user_id") userIdFilter: String,
        @Query("select") select: String = "id,title,annotation,published_at,visibility,is_draft,slug",
        @Query("order") order: String = "published_at.desc"
    ): List<ArticleDto>

    @GET("rest/v1/articles")
    suspend fun getArticleById(
        @Query("id") idFilter: String,
        @Query("select") select: String = "id,title,content,content_simple,keywords,annotation,tags,recommended_kim,read_minutes,published_at,user_id,visibility,slug,is_draft,users(display_name)"
    ): List<ArticleDto>

    @POST("rest/v1/rpc/get_article_by_slug")
    suspend fun getArticleBySlug(@Body body: JsonObject): List<ArticleDto>

    @POST("rest/v1/articles")
    suspend fun createArticle(
        @Header("Prefer") prefer: String = "return=representation",
        @Body articles: List<ArticleDto>
    ): List<ArticleDto>

    @PATCH("rest/v1/articles")
    suspend fun updateArticle(
        @Query("id") idFilter: String,
        @Query("user_id") userIdFilter: String,
        @Header("Prefer") prefer: String = "return=representation",
        @Body body: JsonObject
    ): List<ArticleDto>

    @DELETE("rest/v1/articles")
    suspend fun deleteArticle(
        @Query("id") idFilter: String,
        @Query("user_id") userIdFilter: String
    ): retrofit2.Response<Unit>

    @POST("rest/v1/rpc/search_public_articles")
    suspend fun searchPublicArticles(@Body body: JsonObject): List<ArticleDto>

    @POST("rest/v1/favorites")
    suspend fun addFavorite(@Body body: List<JsonObject>): retrofit2.Response<Unit>

    @DELETE("rest/v1/favorites")
    suspend fun removeFavorite(
        @Query("article_id") articleFilter: String,
        @Query("user_id") userFilter: String
    ): retrofit2.Response<Unit>

    @GET("rest/v1/favorites")
    suspend fun checkFavorite(
        @Query("article_id") articleFilter: String,
        @Query("user_id") userFilter: String,
        @Query("select") select: String = "id"
    ): List<JsonObject>

    @GET("rest/v1/favorites")
    suspend fun getFavorites(
        @Query("user_id") userFilter: String,
        @Query("select") select: String = "id,article_id,created_at,articles(id,title,annotation,published_at)",
        @Query("order") order: String = "created_at.desc"
    ): List<FavoriteDto>

    @POST("rest/v1/reports")
    suspend fun sendReport(@Body body: List<JsonObject>): retrofit2.Response<Unit>

    @GET("rest/v1/moderators")
    suspend fun checkIsModerator(
        @Query("user_id") userFilter: String,
        @Query("is_active") activeFilter: String = "eq.true",
        @Query("select") select: String = "user_id"
    ): List<JsonObject>

    @POST("rest/v1/rpc/get_pending_reports")
    suspend fun getPendingReports(@Body body: JsonObject): List<ReportDto>

    @POST("rest/v1/rpc/resolve_report")
    suspend fun resolveReport(@Body body: JsonObject): retrofit2.Response<Unit>

    @POST("rest/v1/kim_history")
    suspend fun pushKimHistory(
        @Header("Prefer") prefer: String = "resolution=ignore-duplicates,return=minimal",
        @Body history: List<KimHistoryPoint>
    ): retrofit2.Response<Unit>

    @POST("rest/v1/rpc/add_focus_minutes")
    suspend fun addFocusMinutes(@Body body: JsonObject): retrofit2.Response<Unit>

    @GET("rest/v1/focus_log")
    suspend fun getLeaderboard(
        @Query("week_start") weekStartFilter: String,
        @Query("select") select: String = "focus_minutes,user_id,users(display_name)",
        @Query("order") order: String = "focus_minutes.desc",
        @Query("limit") limit: Int = 20
    ): List<FocusLogDto>

    @GET("rest/v1/focus_log")
    suspend fun getUserFocusRank(
        @Query("user_id") userFilter: String,
        @Query("week_start") weekStartFilter: String,
        @Query("select") select: String = "focus_minutes"
    ): List<JsonObject>

    @POST("rest/v1/rpc/is_display_name_available")
    suspend fun checkDisplayNameAvailable(@Body body: JsonObject): Boolean
}

interface GeminiApi {
    @POST("functions/v1/gemini-proxy")
    suspend fun executeTask(@Body request: GeminiProxyRequest): GeminiProxyResponse
}

class NetworkModule(private val sessionManager: SessionManager) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val token = sessionManager.accessToken.value
        val builder = original.newBuilder()
            .header("apikey", Constants.SUPABASE_ANON_KEY)
            .header("Content-Type", "application/json")

        if (!token.isNullOrEmpty()) {
            builder.header("Authorization", "Bearer $token")
        }
        chain.proceed(builder.build())
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .build()

    val supabaseApi: SupabaseApi = Retrofit.Builder()
        .baseUrl(Constants.SUPABASE_URL + "/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(SupabaseApi::class.java)

    val geminiApi: GeminiApi = Retrofit.Builder()
        .baseUrl(Constants.SUPABASE_URL + "/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GeminiApi::class.java)
}

// ============================================================================
// 4. МЕНЕДЖЕР СЕССИИ И ХРАНИЛИЩЕ DATASTORE
// ============================================================================

class SessionManager(private val context: Context) {
    private val TOKEN_KEY = stringPreferencesKey("access_token")
    private val REFRESH_KEY = stringPreferencesKey("refresh_token")
    private val USER_ID_KEY = stringPreferencesKey("user_id")
    private val DISPLAY_NAME_KEY = stringPreferencesKey("display_name")
    private val EMAIL_KEY = stringPreferencesKey("email")

    val accessToken = MutableStateFlow<String?>(null)
    val refreshToken = MutableStateFlow<String?>(null)
    val userId = MutableStateFlow<String?>(null)
    val displayName = MutableStateFlow<String?>(null)
    val email = MutableStateFlow<String?>(null)

    private val refreshMutex = Mutex()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            context.dataStore.data.collect { prefs ->
                accessToken.value = prefs[TOKEN_KEY]
                refreshToken.value = prefs[REFRESH_KEY]
                userId.value = prefs[USER_ID_KEY]
                displayName.value = prefs[DISPLAY_NAME_KEY]
                email.value = prefs[EMAIL_KEY]
            }
        }
    }

    fun saveSession(token: String, refresh: String, uid: String, name: String, mail: String, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[TOKEN_KEY] = token
                prefs[REFRESH_KEY] = refresh
                prefs[USER_ID_KEY] = uid
                prefs[DISPLAY_NAME_KEY] = name
                prefs[EMAIL_KEY] = mail
            }
            accessToken.value = token
            refreshToken.value = refresh
            userId.value = uid
            displayName.value = name
            email.value = mail
        }
    }

    fun clearSession(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        scope.launch {
            context.dataStore.edit { it.clear() }
            accessToken.value = null
            refreshToken.value = null
            userId.value = null
            displayName.value = null
            email.value = null
        }
    }

    fun startAutoRefresh(api: SupabaseApi, scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(50 * 60 * 1000L) // Раз в 50 минут
                val currentRefresh = refreshToken.value
                if (!currentRefresh.isNullOrEmpty()) {
                    refreshMutex.withLock {
                        try {
                            val body = JsonObject(mapOf("refresh_token" to JsonPrimitive(currentRefresh)))
                            val res = api.signIn(body)
                            if (res.accessToken != null && res.refreshToken != null) {
                                context.dataStore.edit { prefs ->
                                    prefs[TOKEN_KEY] = res.accessToken
                                    prefs[REFRESH_KEY] = res.refreshToken
                                }
                                accessToken.value = res.accessToken
                                refreshToken.value = res.refreshToken
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 5. ДВИЖОК КИМ (KimEngine)
// ============================================================================

class KimEngine(private val context: Context) {
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // 16 Сенсоров
    private var scrollIntervals = mutableListOf<Long>()
    private var lastScrollTime = 0L
    private var lastTapTime = System.currentTimeMillis()
    private var scrollReturnCount = 0
    private val sessionStartTime = System.currentTimeMillis()
    private var paragraphRevisitCount = 0
    private var idleBursts = 0
    private var lastActivityTime = System.currentTimeMillis()
    private var currentParagraphDwellSec = 0f
    private var scrollDirectionChanges = 0
    private var lastScrollDirection = 0 // 1: down, -1: up
    private var paragraphViewportRevisits = 0
    private var microPauseCount = 0
    private var totalWordsRead = 0
    private var fingerVelocity = 0f
    private var focusLossCount = 0

    val currentKim = MutableStateFlow(75f)
    val currentZone = MutableStateFlow("focus") // focus, normal, tired
    val isManualLockActive = MutableStateFlow(false)

    private var manualLockUntil = 0L
    private var smoothedKim = 75f

    init {
        initONNX()
    }

    private fun initONNX() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open("model/cognee_ai.onnx").readBytes()
            ortSession = ortEnv?.createSession(modelBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback эвристика если модели нет в assets
        }
    }

    fun registerTouch(velocity: Float) {
        lastTapTime = System.currentTimeMillis()
        lastActivityTime = lastTapTime
        fingerVelocity = velocity
    }

    fun registerScroll(dy: Float) {
        val now = System.currentTimeMillis()
        if (lastScrollTime > 0) {
            val delta = now - lastScrollTime
            scrollIntervals.add(delta)
            if (scrollIntervals.size > 20) scrollIntervals.removeAt(0)
            if (delta in 300..1500) microPauseCount++
        }
        lastScrollTime = now
        lastActivityTime = now

        val dir = if (dy > 0) 1 else -1
        if (lastScrollDirection != 0 && dir != lastScrollDirection) {
            scrollDirectionChanges++
            if (dir == -1) scrollReturnCount++
        }
        lastScrollDirection = dir
    }

    fun registerFocusLoss() {
        focusLossCount++
    }

    fun registerParagraphStay(sec: Float) {
        currentParagraphDwellSec += sec
    }

    fun manualSetZone(zone: String) {
        currentZone.value = zone
        manualLockUntil = System.currentTimeMillis() + 180000L // 3 минуты блокировки
        isManualLockActive.value = true
    }

    fun computeKIM(): Pair<Float, String> {
        val now = System.currentTimeMillis()
        if (now < manualLockUntil) {
            isManualLockActive.value = true
        } else {
            isManualLockActive.value = false
        }

        // Проверка всплесков бездействия
        if (now - lastActivityTime > 3500) {
            idleBursts++
            lastActivityTime = now
        }

        val f0 = clamp((if (scrollIntervals.isNotEmpty()) scrollIntervals.average().toFloat() else 250f) / 500f, 0f, 1f)
        val f1 = clamp((if (scrollIntervals.isNotEmpty()) variance(scrollIntervals).toFloat() else 50f) / 200f, 0f, 1f)
        val f2 = clamp((now - lastTapTime).toFloat() / 1000f, 0f, 1f)
        val f3 = clamp(scrollReturnCount.toFloat() / 10f, 0f, 1f)
        val f4 = clamp((now - sessionStartTime).toFloat() / 3600000f, 0f, 1f)

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val f5 = hour.toFloat() / 24f
        val f6 = clamp(paragraphRevisitCount.toFloat() / 5f, 0f, 1f)
        val f7 = clamp(idleBursts.toFloat() / 5f, 0f, 1f)
        val f8 = clamp(currentParagraphDwellSec / 120f, 0f, 1f)
        val f9 = clamp(scrollDirectionChanges.toFloat() / 10f, 0f, 1f)
        val f10 = clamp(paragraphViewportRevisits.toFloat() / 8f, 0f, 1f)
        val f11 = clamp(microPauseCount.toFloat() / 20f, 0f, 1f)
        val f12 = clamp(220f / 400f, 0f, 1f) // Стандартная скорость чтения
        val f13 = clamp(fingerVelocity / 1000f, 0f, 1f)
        val f14 = clamp(focusLossCount.toFloat() / 5f, 0f, 1f)
        val f15 = 1.0f // Android Touch Device

        val vector = floatArrayOf(f0, f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15)

        var rawKim = 0f
        var isNN = false

        if (ortSession != null && ortEnv != null) {
            try {
                val inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(vector), longArrayOf(1, 16))
                val output = ortSession?.run(mapOf("input" to inputTensor))
                val rawOutput = output?.get(0)?.value
                if (rawOutput is Array<*>) {
                    val probs = (rawOutput as Array<FloatArray>)[0]
                    rawKim = probs[0] * 95f + probs[1] * 65f + probs[2] * 25f + probs[3] * 35f + probs[4] * 10f
                    isNN = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!isNN) {
            // Fallback Эвристика
            val scrollScore = 1f - f0
            val clickScore = 1f - f2
            val returnScore = 1f - f3
            val dwellPenalty = clamp(currentParagraphDwellSec / 120f, 0f, 1f) * 20f
            val dirPenalty = clamp(scrollDirectionChanges.toFloat() / 10f, 0f, 1f) * 15f
            val revisitPenalty = clamp(paragraphViewportRevisits.toFloat() / 8f, 0f, 1f) * 15f
            val microPausePenalty = f11 * 10f

            val raw = (scrollScore * 0.35f + clickScore * 0.25f + returnScore * 0.25f) * 100f - dwellPenalty - dirPenalty - revisitPenalty - microPausePenalty

            val chronoBonus = when (hour) {
                in 9..11, in 17..19 -> 8f
                in 13..15 -> -10f
                in 0..5 -> -15f
                else -> 0f
            }
            rawKim = clamp(raw + chronoBonus, 0f, 100f)
        }

        val alpha = if (isNN) 0.25f else 0.35f
        smoothedKim = alpha * rawKim + (1f - alpha) * smoothedKim
        val finalKim = clamp(smoothedKim, 0f, 100f)

        // Порог обновления 8 пунктов
        if (abs(finalKim - currentKim.value) >= 8f && !isManualLockActive.value) {
            currentKim.value = finalKim
            val newZone = when {
                finalKim > 70f -> "focus"
                finalKim >= 40f -> "normal"
                else -> "tired"
            }
            currentZone.value = newZone
        }

        return Pair(currentKim.value, currentZone.value)
    }

    private fun clamp(value: Float, min: Float, max: Float) = max(min, min(max, value))

    private fun variance(list: List<Long>): Double {
        val avg = list.average()
        return list.sumOf { (it - avg).pow(2) } / list.size
    }
}

// ============================================================================
// 6. GEMINI PROXY & RATE LIMITER
// ============================================================================

class GeminiProxyClient(private val api: GeminiApi) {
    private val cache = mutableMapOf<String, String>()
    private val mutex = Mutex()
    private var lastCallTime = 0L

    suspend fun simplifyParagraph(text: String): String {
        if (cache.containsKey("simp_$text")) return cache["simp_$text"]!!
        val res = executeWithRateLimit { api.executeTask(GeminiProxyRequest("simplify", text)) }
        val result = res?.simplified ?: fallbackSimplify(text)
        cache["simp_$text"] = result
        return result
    }

    suspend fun rephraseParagraph(text: String): String {
        if (cache.containsKey("rephrase_$text")) return cache["rephrase_$text"]!!
        val res = executeWithRateLimit { api.executeTask(GeminiProxyRequest("rephrase", text)) }
        val result = res?.rephrased ?: "Простыми словами: $text"
        cache["rephrase_$text"] = result
        return result
    }

    suspend fun generateTagsAndKim(text: String): Pair<List<String>, Int> {
        val res = executeWithRateLimit { api.executeTask(GeminiProxyRequest("tags", text)) }
        val tags = res?.tags ?: listOf("наука", "технологии")
        val kim = res?.recommendedKim ?: 65
        return Pair(tags, kim)
    }

    private suspend fun <T> executeWithRateLimit(block: suspend () -> T): T? {
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLast = now - lastCallTime
            if (timeSinceLast < 4000) {
                delay(4000 - timeSinceLast)
            }
            lastCallTime = System.currentTimeMillis()
            try {
                block()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun fallbackSimplify(text: String): String {
        val sentences = text.split(Regex("[.!?]+")).filter { it.isNotBlank() }
        return sentences.take(2).joinToString(". ") + "."
    }
}

// ============================================================================
// 7. VIEWMODELS
// ============================================================================

class MainViewModel(
    val sessionManager: SessionManager,
    val networkModule: NetworkModule,
    val kimEngine: KimEngine,
    val geminiClient: GeminiProxyClient
) : ViewModel() {

    val isModerator = MutableStateFlow(false)
    val myArticles = MutableStateFlow<List<ArticleDto>>(emptyList())
    val publicArticles = MutableStateFlow<List<ArticleDto>>(emptyList())
    val favoriteArticles = MutableStateFlow<List<FavoriteDto>>(emptyList())
    val leaderboard = MutableStateFlow<List<FocusLogDto>>(emptyList())
    val pendingReports = MutableStateFlow<List<ReportDto>>(emptyList())

    init {
        sessionManager.startAutoRefresh(networkModule.supabaseApi, viewModelScope)
        checkModeratorStatus()
    }

    fun checkModeratorStatus() {
        val uid = sessionManager.userId.value ?: return
        viewModelScope.launch {
            try {
                val res = networkModule.supabaseApi.checkIsModerator("eq.$uid")
                isModerator.value = res.isNotEmpty()
            } catch (e: Exception) {
                isModerator.value = false
            }
        }
    }

    fun loadCatalog(query: String = "") {
        viewModelScope.launch {
            try {
                if (query.isBlank()) {
                    val res = networkModule.supabaseApi.searchPublicArticles(
                        JsonObject(mapOf("p_query" to JsonPrimitive(""), "p_limit" to JsonPrimitive(20), "p_offset" to JsonPrimitive(0)))
                    )
                    publicArticles.value = res
                } else {
                    val res = networkModule.supabaseApi.searchPublicArticles(
                        JsonObject(mapOf("p_query" to JsonPrimitive(query), "p_limit" to JsonPrimitive(20), "p_offset" to JsonPrimitive(0)))
                    )
                    publicArticles.value = res
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadMyArticles() {
        val uid = sessionManager.userId.value ?: return
        viewModelScope.launch {
            try {
                myArticles.value = networkModule.supabaseApi.getMyArticles("eq.$uid")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadFavorites() {
        val uid = sessionManager.userId.value ?: return
        viewModelScope.launch {
            try {
                favoriteArticles.value = networkModule.supabaseApi.getFavorites("eq.$uid")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadLeaderboard() {
        val mondayDate = getMondayDate()
        viewModelScope.launch {
            try {
                leaderboard.value = networkModule.supabaseApi.getLeaderboard("eq.$mondayDate")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadPendingReports() {
        viewModelScope.launch {
            try {
                pendingReports.value = networkModule.supabaseApi.getPendingReports(
                    JsonObject(mapOf("p_limit" to JsonPrimitive(50), "p_offset" to JsonPrimitive(0)))
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun resolveReport(reportId: Long, hideArticle: Boolean) {
        viewModelScope.launch {
            try {
                networkModule.supabaseApi.resolveReport(
                    JsonObject(mapOf(
                        "p_report_id" to JsonPrimitive(reportId),
                        "p_status" to JsonPrimitive("resolved"),
                        "p_hide_article" to JsonPrimitive(hideArticle)
                    ))
                )
                loadPendingReports()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getMondayDate(): String {
        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysToSubtract = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        cal.add(Calendar.DAY_OF_MONTH, -daysToSubtract)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(cal.time)
    }
}

// ============================================================================
// 8. JETPACK COMPOSE UI ЭКРАНЫ
// ============================================================================

@Composable
fun CogneeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Constants.COLOR_FOCUS,
            secondary = Constants.COLOR_NORMAL,
            tertiary = Constants.COLOR_TIRED,
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@Composable
fun SplashScreen(navController: NavHostController, sessionManager: SessionManager) {
    val token by sessionManager.accessToken.collectAsState()
    LaunchedEffect(token) {
        delay(1500)
        if (token.isNullOrEmpty()) {
            navController.navigate("auth") { popUpTo("splash") { inclusive = true } }
        } else {
            navController.navigate("catalog") { popUpTo("splash") { inclusive = true } }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = "Cognee Logo",
                tint = Constants.COLOR_FOCUS,
                modifier = Modifier.size(96.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("CogneeAI", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Адаптивная платформа чтения", fontSize = 14.sp, color = Color.Gray)
        }
    }
}

@Composable
fun AuthScreen(navController: NavHostController, viewModel: MainViewModel) {
    var isLogin by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isLogin) "Вход в Cognee" else "Регистрация",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (!isLogin) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Отображаемое имя") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMsg.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            isLoading = true
                            errorMsg = ""
                            try {
                                if (isLogin) {
                                    val body = JsonObject(mapOf("email" to JsonPrimitive(email), "password" to JsonPrimitive(password)))
                                    val res = viewModel.networkModule.supabaseApi.signIn(body)
                                    val uid = res.user?.id ?: ""
                                    val prof = viewModel.networkModule.supabaseApi.getUserProfile("eq.$uid").firstOrNull()
                                    viewModel.sessionManager.saveSession(
                                        res.accessToken ?: "",
                                        res.refreshToken ?: "",
                                        uid,
                                        prof?.displayName ?: "User",
                                        email,
                                        coroutineScope
                                    )
                                    viewModel.checkModeratorStatus()
                                    navController.navigate("catalog") { popUpTo("auth") { inclusive = true } }
                                } else {
                                    if (displayName.length !in 2..30 || Constants.RESERVED_NAMES.contains(displayName.lowercase())) {
                                        errorMsg = "Недопустимое отображаемое имя"
                                        isLoading = false
                                        return@launch
                                    }
                                    val avail = viewModel.networkModule.supabaseApi.checkDisplayNameAvailable(
                                        JsonObject(mapOf("p_name" to JsonPrimitive(displayName), "p_user_id" to JsonNull))
                                    )
                                    if (!avail) {
                                        errorMsg = "Имя уже занято"
                                        isLoading = false
                                        return@launch
                                    }
                                    val body = JsonObject(mapOf(
                                        "email" to JsonPrimitive(email),
                                        "password" to JsonPrimitive(password),
                                        "data" to JsonObject(mapOf("display_name" to JsonPrimitive(displayName)))
                                    ))
                                    val res = viewModel.networkModule.supabaseApi.signUp(body)
                                    val uid = res.user?.id ?: ""
                                    viewModel.networkModule.supabaseApi.createUserProfile(profile = UserProfile(uid, displayName))
                                    viewModel.sessionManager.saveSession(
                                        res.accessToken ?: "",
                                        res.refreshToken ?: "",
                                        uid,
                                        displayName,
                                        email,
                                        coroutineScope
                                    )
                                    navController.navigate("catalog") { popUpTo("auth") { inclusive = true } }
                                }
                            } catch (e: Exception) {
                                errorMsg = "Ошибка авторизации: ${e.localizedMessage}"
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    Text(if (isLogin) "Войти" else "Зарегистрироваться")
                }

                TextButton(onClick = { isLogin = !isLogin }) {
                    Text(if (isLogin) "Нет аккаунта? Зарегистрироваться" else "Уже есть аккаунт? Войти")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(navController: NavHostController, viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val publicArticles by viewModel.publicArticles.collectAsState()
    val myArticles by viewModel.myArticles.collectAsState()
    val favorites by viewModel.favoriteArticles.collectAsState()
    val isModerator by viewModel.isModerator.collectAsState()

    LaunchedEffect(selectedTab, searchQuery) {
        when (selectedTab) {
            0 -> viewModel.loadCatalog(searchQuery)
            1 -> viewModel.loadMyArticles()
            2 -> viewModel.loadFavorites()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cognee Каталог") },
                actions = {
                    if (isModerator) {
                        IconButton(onClick = { navController.navigate("moderation") }) {
                            Icon(Icons.Default.Shield, contentDescription = "Модерация", tint = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    IconButton(onClick = { navController.navigate("leaderboard") }) {
                        Icon(Icons.Default.Leaderboard, contentDescription = "Лидерборд")
                    }
                    IconButton(onClick = { navController.navigate("profile") }) {
                        Icon(Icons.Default.Person, contentDescription = "Профиль")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("editor/0") }) {
                Icon(Icons.Default.Add, contentDescription = "Создать статью")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("Публичные", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("Мои статьи", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Text("Избранное", modifier = Modifier.padding(12.dp))
                }
            }

            if (selectedTab == 0) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Поиск статей...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                if (selectedTab == 0) {
                    items(publicArticles) { article -> ArticleItem(article) { navController.navigate("reader/${article.id}") } }
                } else if (selectedTab == 1) {
                    items(myArticles) { article -> ArticleItem(article) { navController.navigate("reader/${article.id}") } }
                } else {
                    items(favorites) { fav ->
                        fav.articles?.let { article ->
                            ArticleItem(article) { navController.navigate("reader/${article.id}") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArticleItem(article: ArticleDto, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(article.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(article.annotation, fontSize = 14.sp, color = Color.LightGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("КИМ: ${article.recommendedKim}", fontSize = 12.sp, color = Constants.COLOR_FOCUS, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(12.dp))
                Text("${article.readMinutes} мин чтения", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun ReaderScreen(articleId: Long, navController: NavHostController, viewModel: MainViewModel) {
    var article by remember { mutableStateOf<ArticleDto?>(null) }
    val currentKim by viewModel.kimEngine.currentKim.collectAsState()
    val currentZone by viewModel.kimEngine.currentZone.collectAsState()
    val isLocked by viewModel.kimEngine.isManualLockActive.collectAsState()

    var paragraphTexts by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var reportModalOpen by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(articleId) {
        try {
            val list = viewModel.networkModule.supabaseApi.getArticleById("eq.$articleId")
            if (list.isNotEmpty()) {
                article = list.first()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Таймер КИМ 20 сек
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(20000L)
            val (kim, zone) = viewModel.kimEngine.computeKIM()
            viewModel.sessionManager.userId.value?.let { uid ->
                try {
                    viewModel.networkModule.supabaseApi.pushKimHistory(
                        history = listOf(KimHistoryPoint(uid, kim, zone, SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())))
                    )
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    val zoneColor = when (currentZone) {
        "focus" -> Constants.COLOR_FOCUS
        "normal" -> Constants.COLOR_NORMAL
        else -> Constants.COLOR_TIRED
    }

    val paragraphs = remember(article, currentZone) {
        article?.content?.split("\n\n")?.filter { it.isNotBlank() } ?: emptyList()
    }

    Scaffold(
        topBar = {
            Surface(color = Color(0xFF1E1E1E), shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(16.dp).clip(CircleShape).background(zoneColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "КИМ: ${currentKim.toInt()} (${when (currentZone) { "focus" -> "⚡ Поток"; "normal" -> "Норма"; else -> "🌙 Устал" }})",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Row {
                        IconButton(onClick = {
                            viewModel.sessionManager.userId.value?.let { uid ->
                                coroutineScope.launch {
                                    viewModel.networkModule.supabaseApi.addFavorite(
                                        listOf(JsonObject(mapOf("user_id" to JsonPrimitive(uid), "article_id" to JsonPrimitive(articleId))))
                                    )
                                }
                            }
                        }) {
                            Icon(Icons.Default.Bookmark, contentDescription = "Избранное", tint = Color.White)
                        }
                        IconButton(onClick = { reportModalOpen = true }) {
                            Icon(Icons.Default.Flag, contentDescription = "Пожаловаться", tint = Color.Gray)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF121212))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, _, _ ->
                        viewModel.kimEngine.registerScroll(pan.y)
                        viewModel.kimEngine.registerTouch(sqrt(pan.x * pan.x + pan.y * pan.y))
                    }
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Переключение зоны вручную
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilterChip(
                        selected = currentZone == "focus",
                        onClick = { viewModel.kimEngine.manualSetZone("focus") },
                        label = { Text("⚡ Поток") }
                    )
                    FilterChip(
                        selected = currentZone == "normal",
                        onClick = { viewModel.kimEngine.manualSetZone("normal") },
                        label = { Text("Норма") }
                    )
                    FilterChip(
                        selected = currentZone == "tired",
                        onClick = { viewModel.kimEngine.manualSetZone("tired") },
                        label = { Text("🌙 Устал") }
                    )
                }

                if (isLocked) {
                    Text("Ручной режим активирован на 3 мин", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        Text(article?.title ?: "", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Автор: ${article?.users?.displayName ?: "Неизвестен"}", fontSize = 12.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    items(paragraphs.size) { index ->
                        val text = paragraphTexts[index] ?: paragraphs[index]
                        val fontSize = if (currentZone == "tired") 18.sp else 15.sp

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(text, fontSize = fontSize, color = Color.White, lineHeight = (fontSize.value * 1.4).sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = {
                                        coroutineScope.launch {
                                            val simp = viewModel.geminiClient.simplifyParagraph(paragraphs[index])
                                            paragraphTexts = paragraphTexts.toMutableMap().apply { put(index, simp) }
                                        }
                                    }) { Text("Упростить", fontSize = 12.sp) }

                                    TextButton(onClick = {
                                        coroutineScope.launch {
                                            val rephrase = viewModel.geminiClient.rephraseParagraph(paragraphs[index])
                                            paragraphTexts = paragraphTexts.toMutableMap().apply { put(index, rephrase) }
                                        }
                                    }) { Text("Объясни иначе", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }

            if (reportModalOpen) {
                AlertDialog(
                    onDismissRequest = { reportModalOpen = false },
                    title = { Text("Пожаловаться на статью") },
                    text = {
                        OutlinedTextField(
                            value = reportReason,
                            onValueChange = { reportReason = it },
                            label = { Text("Причина жалобы") }
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            viewModel.sessionManager.userId.value?.let { uid ->
                                coroutineScope.launch {
                                    viewModel.networkModule.supabaseApi.sendReport(
                                        listOf(JsonObject(mapOf(
                                            "article_id" to JsonPrimitive(articleId),
                                            "reporter_id" to JsonPrimitive(uid),
                                            "reason" to JsonPrimitive(reportReason),
                                            "status" to JsonPrimitive("pending")
                                        )))
                                    )
                                    reportModalOpen = false
                                }
                            }
                        }) { Text("Отправить") }
                    },
                    dismissButton = {
                        TextButton(onClick = { reportModalOpen = false }) { Text("Отмена") }
                    }
                )
            }
        }
    }
}

@Composable
fun EditorScreen(articleId: Long, navController: NavHostController, viewModel: MainViewModel) {
    var title by remember { mutableStateOf("") }
    var annotation by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var recommendedKim by remember { mutableIntStateOf(65) }
    var isAIWorking by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (articleId == 0L) "Новая статья" else "Редактирование") })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Заголовок") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = annotation, onValueChange = { annotation = it }, label = { Text("Аннотация") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("Текст статьи (абзацы через пустую строку)") },
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        isAIWorking = true
                        val (aiTags, aiKim) = viewModel.geminiClient.generateTagsAndKim(content)
                        tags = aiTags
                        recommendedKim = aiKim
                        isAIWorking = false
                    }
                },
                enabled = !isAIWorking && content.isNotBlank()
            ) {
                Text(if (isAIWorking) "Анализ AI..." else "Сгенерировать теги и КИМ")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Теги: ${tags.joinToString(", ")}", color = Color.LightGray)
            Text("Рекомендуемый КИМ: $recommendedKim", color = Constants.COLOR_FOCUS)

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val uid = viewModel.sessionManager.userId.value ?: return@Button
                    coroutineScope.launch {
                        val slug = UUID.randomUUID().toString().take(8)
                        val dto = ArticleDto(
                            userId = uid,
                            title = title,
                            content = content,
                            annotation = annotation,
                            tags = tags,
                            recommendedKim = recommendedKim,
                            readMinutes = max(1, content.split("\\s+".toRegex()).size / 200),
                            publishedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
                            visibility = "public",
                            isDraft = false,
                            slug = slug
                        )
                        viewModel.networkModule.supabaseApi.createArticle(articles = listOf(dto))
                        navController.popBackStack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank() && content.isNotBlank()
            ) {
                Text("Опубликовать")
            }
        }
    }
}

@Composable
fun ProfileScreen(navController: NavHostController, viewModel: MainViewModel) {
    val displayName by viewModel.sessionManager.displayName.collectAsState()
    val email by viewModel.sessionManager.email.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(topBar = { TopAppBar(title = { Text("Профиль") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(80.dp), tint = Constants.COLOR_FOCUS)
            Spacer(modifier = Modifier.height(16.dp))
            Text(displayName ?: "Пользователь", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(email ?: "", fontSize = 14.sp, color = Color.Gray)

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    viewModel.sessionManager.clearSession(coroutineScope)
                    navController.navigate("auth") { popUpTo(0) }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Выйти из аккаунта")
            }
        }
    }
}

@Composable
fun LeaderboardScreen(navController: NavHostController, viewModel: MainViewModel) {
    val list by viewModel.leaderboard.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadLeaderboard() }

    Scaffold(topBar = { TopAppBar(title = { Text("Лидерборд недели") }) }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(16.dp)) {
            items(list.size) { index ->
                val item = list[index]
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("#${index + 1}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Constants.COLOR_FOCUS)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(item.users?.displayName ?: "Пользователь", fontSize = 16.sp, color = Color.White)
                        }
                        Text("${item.focusMinutes} мин в фокусе", fontSize = 14.sp, color = Constants.COLOR_NORMAL)
                    }
                }
            }
        }
    }
}

@Composable
fun ModerationScreen(navController: NavHostController, viewModel: MainViewModel) {
    val reports by viewModel.pendingReports.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadPendingReports() }

    Scaffold(topBar = { TopAppBar(title = { Text("Модерация") }) }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(16.dp)) {
            items(reports) { report ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Причина: ${report.reason}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Статья ID: ${report.articleId}", fontSize = 12.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { viewModel.resolveReport(report.id, false) }) {
                                Text("Отклонить", color = Color.Gray)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { viewModel.resolveReport(report.id, true) }) {
                                Text("Скрыть статью")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 9. MAIN ACTIVITY & NAV GRAPH
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionManager = SessionManager(applicationContext)
        val networkModule = NetworkModule(sessionManager)
        val kimEngine = KimEngine(applicationContext)
        val geminiClient = GeminiProxyClient(networkModule.geminiApi)

        val viewModel = MainViewModel(sessionManager, networkModule, kimEngine, geminiClient)

        setContent {
            CogneeTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "splash") {
                    composable("splash") { SplashScreen(navController, sessionManager) }
                    composable("auth") { AuthScreen(navController, viewModel) }
                    composable("catalog") { CatalogScreen(navController, viewModel) }
                    composable("reader/{articleId}") { backStackEntry ->
                        val id = backStackEntry.arguments?.getString("articleId")?.toLongOrNull() ?: 0L
                        ReaderScreen(id, navController, viewModel)
                    }
                    composable("editor/{articleId}") { backStackEntry ->
                        val id = backStackEntry.arguments?.getString("articleId")?.toLongOrNull() ?: 0L
                        EditorScreen(id, navController, viewModel)
                    }
                    composable("profile") { ProfileScreen(navController, viewModel) }
                    composable("leaderboard") { LeaderboardScreen(navController, viewModel) }
                    composable("moderation") { ModerationScreen(navController, viewModel) }
                }
            }
        }
    }
}
