# NoteFlowAI Monetization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add freemium monetization with RevenueCat billing, Supabase credit tracking, feature gating, paywall UI, and BYO key support.

**Architecture:** RevenueCat handles Play Store billing (subscriptions + one-time purchases). Supabase provides user accounts, credit wallet, and API proxy. An `EntitlementManager` singleton checks local RevenueCat cached entitlements for instant feature gating. A `PaywallManager` controls two-step paywall display (soft modal, then full screen). Edge Functions proxy AI API calls using server-side keys.

**Tech Stack:** RevenueCat Android SDK, Supabase Kotlin SDK (auth + postgrest + realtime), Retrofit (existing), DataStore (existing), OkHttp (existing), Google Play Billing (via RevenueCat).

## Global Constraints

- minSdk 26, targetSdk 35, compileSdk 35
- No Hilt/Dagger — singletons via `companion object` + `getInstance(context)`
- Navigation via `Screen` enum in `NoteFlowApp.kt` (manual state-based, no Compose Navigation)
- ViewModels created via `viewModel()` factory (no DI)
- Settings in DataStore + EncryptedSharedPreferences
- No new Gradle plugin — RevenueCat and Supabase are regular dependencies
- Two remote APIs: mimo v2.5 Pro (chat) + Deepgram (transcription) only
- RevenueCat entitlements: `"starter"`, `"pro"`, `"lifetime"`, `"rag_addon"`

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `data/billing/EntitlementManager.kt` | RevenueCat init, entitlement checks, customer info caching |
| Create | `data/billing/BillingProducts.kt` | RevenueCat product identifiers and tier mapping |
| Create | `data/billing/PaywallManager.kt` | Controls paywall display state (soft vs full) |
| Create | `data/credit/CreditManager.kt` | Server-side credit balance, usage tracking, top-up |
| Create | `data/credit/CreditApiService.kt` | Retrofit interface for Supabase Edge Functions |
| Create | `data/auth/SupabaseAuthManager.kt` | Supabase Auth (email, Google, Apple sign-in) |
| Create | `ui/screens/paywall/SoftPaywallModal.kt` | Step 1: non-blocking modal when tapping locked feature |
| Create | `ui/screens/paywall/FullPaywallScreen.kt` | Step 2: full paywall with tier comparison |
| Create | `ui/screens/paywall/PaywallTierCard.kt` | Individual tier card composable |
| Create | `ui/screens/credits/CreditsScreen.kt` | Token wallet UI (balance, usage breakdown, top-up) |
| Create | `ui/screens/auth/AuthGate.kt` | Sign-in gate for subscription users |
| Modify | `app/build.gradle.kts` | Add RevenueCat + Supabase dependencies |
| Modify | `NoteFlowAIApplication.kt` | Initialize RevenueCat + Supabase on startup |
| Modify | `ui/NoteFlowApp.kt` | Add PAYWALL, CREDITS, AUTH screens to Screen enum; feature gate checks |
| Modify | `data/settings/SettingsManager.kt` | Add billing-related settings keys |
| Modify | `ui/screens/SettingsSections.kt` | Add billing section (subscription status, credits, manage subscription) |
| Modify | `ui/screens/chat/ChatScreen.kt` | Gate chat behind Starter tier; show paywall on free tier |
| Modify | `ui/screens/YouTubeScreen.kt` | Gate YouTube behind Pro tier |
| Modify | `viewmodel/MainViewModel.kt` | Route chat through credit system (credit check -> proxy or BYO key) |

---

## Task 1: Add Gradle Dependencies

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: RevenueCat SDK + Supabase SDK available project-wide

- [ ] **Step 1: Add RevenueCat and Supabase dependencies**

In `app/build.gradle.kts`, inside the `dependencies` block, add:

```kotlin
    // RevenueCat (billing + subscriptions)
    implementation("com.revenuecat.purchases:purchases:8.14.1")

    // Supabase (auth + postgrest + realtime + gotrue)
    implementation("io.github.jan-tennert.supabase:bom:2.6.1")
    implementation("io.github.jan-tennert.supabase:gotrue-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")

    // Coroutines integration for Supabase
    implementation("io.github.jan-tennert.supabase:supabase-kt-plugins-coroutines:2.6.1")
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (dependencies resolve, no code changes yet)

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "feat: add RevenueCat and Supabase dependencies"
```

---

## Task 2: Billing Products Configuration

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/billing/BillingProducts.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `BillingProducts.ENTITLEMENT_STARTER`, `ENTITLEMENT_PRO`, `ENTITLEMENT_LIFETIME`, `ENTITLEMENT_RAG_ADDON`, `PRODUCT_IDS` map

- [ ] **Step 1: Create BillingProducts.kt**

```kotlin
package com.noteflowai.app.data.billing

/**
 * RevenueCat product identifiers and entitlement mapping.
 * These must match the products configured in RevenueCat dashboard.
 */
object BillingProducts {

    // ── RevenueCat Entitlements ──────────────────────────────────────
    const val ENTITLEMENT_STARTER = "starter"
    const val ENTITLEMENT_PRO = "pro"
    const val ENTITLEMENT_LIFETIME = "lifetime"
    const val ENTITLEMENT_RAG_ADDON = "rag_addon"

    // ── Google Play Product IDs ──────────────────────────────────────
    const val PRODUCT_STARTER_MONTHLY = "noteflowai_starter_monthly"
    const val PRODUCT_PRO_MONTHLY = "noteflowai_pro_monthly"
    const val PRODUCT_ANNUAL = "noteflowai_annual"
    const val PRODUCT_LIFETIME = "noteflowai_lifetime"
    const val PRODUCT_RAG_ADDON = "noteflowai_rag_addon"

    // Top-up packs (one-time in-app purchases)
    const val PRODUCT_TOPUP_SMALL = "noteflowai_topup_300k"
    const val PRODUCT_TOPUP_LARGE = "noteflowai_topup_1200k"

    /** Map of product ID -> set of entitlement identifiers it unlocks. */
    val PRODUCT_ENTITLEMENTS: Map<String, Set<String>> = mapOf(
        PRODUCT_STARTER_MONTHLY to setOf(ENTITLEMENT_STARTER),
        PRODUCT_PRO_MONTHLY to setOf(ENTITLEMENT_PRO),
        PRODUCT_ANNUAL to setOf(ENTITLEMENT_PRO),
        PRODUCT_LIFETIME to setOf(ENTITLEMENT_LIFETIME),
        PRODUCT_RAG_ADDON to setOf(ENTITLEMENT_RAG_ADDON),
    )

    /** All product IDs that RevenueCat should offer. */
    val OFFERING_PRODUCT_IDS = listOf(
        PRODUCT_STARTER_MONTHLY,
        PRODUCT_PRO_MONTHLY,
        PRODUCT_ANNUAL,
        PRODUCT_LIFETIME,
    )

    /** Monthly credit allocations per tier (mimo v2.5 Pro tokens). */
    val MONTHLY_CREDITS: Map<String, Long> = mapOf(
        ENTITLEMENT_STARTER to 600_000L,
        ENTITLEMENT_PRO to 1_500_000L,
        // Annual gets 2M (30% bonus), mapped to same "pro" entitlement
        // The higher allocation is handled server-side by checking the product ID
    )

    /** Top-up pack sizes (tokens). */
    val TOPUP_TOKENS: Map<String, Long> = mapOf(
        PRODUCT_TOPUP_SMALL to 300_000L,
        PRODUCT_TOPUP_LARGE to 1_200_000L,
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/billing/BillingProducts.kt
git commit -m "feat: define RevenueCat product IDs and entitlement mapping"
```

---

## Task 3: Entitlement Manager

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/billing/EntitlementManager.kt`

**Interfaces:**
- Consumes: RevenueCat SDK (`Purchases.sharedInstance`)
- Produces: `EntitlementManager.getInstance(context)`, `.currentEntitlement: StateFlow<String>`, `.hasEntitlement(tier): Boolean`, `.isPro(): Boolean`, `.isStarterOrAbove(): Boolean`, `.isLifetime(): Boolean`

- [ ] **Step 1: Create EntitlementManager.kt**

```kotlin
package com.noteflowai.app.data.billing

import android.content.Context
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.models.StoreProduct
import com.revenuecat.purchases.restorePurchases
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton that wraps RevenueCat to expose entitlement state.
 * Uses cached CustomerInfo for instant checks (no network call).
 */
class EntitlementManager private constructor(context: Context) {

    private val _currentEntitlement = MutableStateFlow(ENTITLEMENT_FREE)
    val currentEntitlement: StateFlow<String> = _currentEntitlement.asStateFlow()

    private val _customerInfo = MutableStateFlow<CustomerInfo?>(null)
    val customerInfo: StateFlow<CustomerInfo?> = _customerInfo.asStateFlow()

    init {
        // Read cached entitlement on init (instant, no network)
        refreshFromCache()
    }

    /**
     * Re-read CustomerInfo from RevenueCat's local cache.
     * Call this after purchases, app start, or when coming back from background.
     */
    fun refreshFromCache() {
        val info = Purchases.sharedInstance.customerInfo
        _customerInfo.value = info
        _currentEntitlement.value = resolveTier(info)
    }

    /**
     * Fetch fresh CustomerInfo from RevenueCat servers.
     * Use after a purchase completes or on app foreground.
     */
    suspend fun syncWithServer() {
        try {
            val result = Purchases.sharedInstance.awaitCustomerInfo()
            _customerInfo.value = result
            _currentEntitlement.value = resolveTier(result)
        } catch (_: Exception) {
            // Keep cached value on network failure
        }
    }

    fun hasEntitlement(tier: String): Boolean {
        return _customerInfo.value?.entitlements?.active?.containsKey(tier) == true
    }

    fun isPro(): Boolean = hasEntitlement(BillingProducts.ENTITLEMENT_PRO)

    fun isLifetime(): Boolean = hasEntitlement(BillingProducts.ENTITLEMENT_LIFETIME)

    fun isStarterOrAbove(): Boolean {
        val tier = _currentEntitlement.value
        return tier != ENTITLEMENT_FREE
    }

    fun hasRagAddon(): Boolean = hasEntitlement(BillingProducts.ENTITLEMENT_RAG_ADDON)

    /**
     * Returns true if user has RAG access (either via Pro subscription or Lifetime + RAG add-on).
     */
    fun hasRagAccess(): Boolean = isPro() || (isLifetime() && hasRagAddon())

    fun isFree(): Boolean = _currentEntitlement.value == ENTITLEMENT_FREE

    /** Get available offerings from RevenueCat. */
    suspend fun getOfferings(): List<StoreProduct> {
        return try {
            val offerings = Purchases.sharedInstance.awaitOfferings()
            offerings.current?.availablePackages?.map { it.product } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun restorePurchases(callback: (Boolean) -> Unit) {
        Purchases.sharedInstance.restorePurchases { info, error ->
            if (error == null) {
                _customerInfo.value = info
                _currentEntitlement.value = resolveTier(info)
                callback(true)
            } else {
                callback(false)
            }
        }
    }

    private fun resolveTier(info: CustomerInfo): String {
        val active = info.entitlements.active
        return when {
            active.containsKey(BillingProducts.ENTITLEMENT_PRO) -> BillingProducts.ENTITLEMENT_PRO
            active.containsKey(BillingProducts.ENTITLEMENT_LIFETIME) -> BillingProducts.ENTITLEMENT_LIFETIME
            active.containsKey(BillingProducts.ENTITLEMENT_STARTER) -> BillingProducts.ENTITLEMENT_STARTER
            else -> ENTITLEMENT_FREE
        }
    }

    companion object {
        const val ENTITLEMENT_FREE = "free"

        @Volatile
        private var instance: EntitlementManager? = null

        fun getInstance(context: Context): EntitlementManager {
            return instance ?: synchronized(this) {
                instance ?: EntitlementManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/billing/EntitlementManager.kt
git commit -m "feat: add EntitlementManager for RevenueCat entitlement checks"
```

---

## Task 4: Paywall Manager

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/billing/PaywallManager.kt`

**Interfaces:**
- Consumes: `EntitlementManager`
- Produces: `PaywallManager.getInstance(context)`, `.showPaywall(target)`, `.dismissPaywall()`, `.paywallState: StateFlow<PaywallState>`

- [ ] **Step 1: Create PaywallManager.kt**

```kotlin
package com.noteflowai.app.data.billing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controls the two-step paywall flow:
 * 1. Soft paywall (modal) -- non-blocking, explains the feature
 * 2. Full paywall (screen) -- shows pricing tiers
 *
 * Tracks which feature triggered the paywall for personalized messaging.
 */
class PaywallManager private constructor(context: Context) {

    private val entitlementManager = EntitlementManager.getInstance(context)

    private val _paywallState = MutableStateFlow<PaywallState>(PaywallState.Hidden)
    val paywallState: StateFlow<PaywallState> = _paywallState.asStateFlow()

    /**
     * Show paywall for a specific feature. If user is already entitled, no-op.
     * First shows soft modal; user must tap "See Pricing" to reach full screen.
     */
    fun showPaywall(target: PaywallTarget) {
        if (entitlementManager.isStarterOrAbove()) return
        _paywallState.value = PaywallState.SoftModal(target)
    }

    /** User tapped "See Pricing" on the soft modal -> show full paywall. */
    fun showFullPaywall(target: PaywallTarget) {
        _paywallState.value = PaywallState.FullScreen(target)
    }

    /** Dismiss any paywall state. */
    fun dismissPaywall() {
        _paywallState.value = PaywallState.Hidden
    }

    /** Called after a successful purchase to hide paywall. */
    fun onPurchaseComplete() {
        entitlementManager.refreshFromCache()
        _paywallState.value = PaywallState.Hidden
    }

    companion object {
        @Volatile
        private var instance: PaywallManager? = null

        fun getInstance(context: Context): PaywallManager {
            return instance ?: synchronized(this) {
                instance ?: PaywallManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

/** What feature triggered the paywall. */
enum class PaywallTarget {
    CHAT,
    YOUTUBE,
    TRANSCRIPTION,
    RAG,
    TRANSLATION,
    DOCUMENT_IMPORT,
    GENERAL
}

/** Paywall UI state. */
sealed class PaywallState {
    data object Hidden : PaywallState()
    data class SoftModal(val target: PaywallTarget) : PaywallState()
    data class FullScreen(val target: PaywallTarget) : PaywallState()
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/billing/PaywallManager.kt
git commit -m "feat: add PaywallManager with two-step paywall flow"
```

---

## Task 5: Supabase Auth Manager

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/auth/SupabaseAuthManager.kt`

**Interfaces:**
- Consumes: Supabase GoTrue SDK
- Produces: `SupabaseAuthManager.getInstance(context)`, `.signIn(email, password)`, `.signUp(...)`, `.signInWithGoogle(...)`, `.signOut()`, `.currentSession: StateFlow<Session?>`

- [ ] **Step 1: Create SupabaseAuthManager.kt**

```kotlin
package com.noteflowai.app.data.auth

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.handleDeeplinks
import io.github.jan.supabase.gotrue.sessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Supabase authentication and client lifecycle.
 * Provides email/password, Google, and Apple sign-in.
 * Also owns the shared SupabaseClient instance for Postgrest and Edge Functions.
 */
class SupabaseAuthManager private constructor(context: Context) {

    // TODO: Replace with your actual Supabase project credentials
    // Store these in BuildConfig or a secrets file, NOT hardcoded
    private val supabaseUrl = "https://YOUR_PROJECT.supabase.co"
    private val supabaseAnonKey = "YOUR_ANON_KEY"

    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = supabaseUrl,
        supabaseAnonKey = supabaseAnonKey
    ) {
        install(Auth) {
            // Auto-load session from storage on init
        }
        install(Postgrest)
        install(Realtime)
    }

    private val _session = MutableStateFlow<io.github.jan.supabase.gotrue.user.UserSession?>(null)
    val session: StateFlow<io.github.jan.supabase.gotrue.user.UserSession?> = _session.asStateFlow()

    val isAuthenticated: Boolean
        get() = _session.value != null

    val userId: String?
        get() = _session.value?.user?.id

    val accessToken: String?
        get() = _session.value?.accessToken

    init {
        // Observe session changes
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            client.auth.sessionStatus.collect { status ->
                when (status) {
                    is io.github.jan.supabase.gotrue.SessionStatus.Authenticated -> {
                        _session.value = status.session
                    }
                    is io.github.jan.supabase.gotrue.SessionStatus.NotAuthenticated -> {
                        _session.value = null
                    }
                    is io.github.jan.supabase.gotrue.SessionStatus.LoadingFromStorage -> {
                        // Session being restored from local storage
                    }
                    is io.github.jan.supabase.gotrue.SessionStatus.NetworkError -> {
                        // Keep current session, don't clear on network error
                    }
                }
            }
        }

        // Restore session on init
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                client.auth.loadFromStorage()
            } catch (_: Exception) { }
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<String> {
        return try {
            val result = client.auth.signInWith(io.github.jan.supabase.gotrue.providers.builtin.Email) {
                this.email = email
                this.password = password
            }
            Result.success(result.accessToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<String> {
        return try {
            val result = client.auth.signUpWith(io.github.jan.supabase.gotrue.providers.builtin.Email) {
                this.email = email
                this.password = password
            }
            Result.success(result.accessToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signInWithGoogle(idToken: String): Result<String> {
        return try {
            val result = client.auth.signInWith(io.github.jan.supabase.gotrue.providers.Google) {
                // Google sign-in handled via Android Credential Manager / One Tap
                // Pass the ID token obtained from Google
            }
            Result.success(result.accessToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        client.auth.signOut()
        _session.value = null
    }

    companion object {
        @Volatile
        private var instance: SupabaseAuthManager? = null

        fun getInstance(context: Context): SupabaseAuthManager {
            return instance ?: synchronized(this) {
                instance ?: SupabaseAuthManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/auth/SupabaseAuthManager.kt
git commit -m "feat: add SupabaseAuthManager for user authentication"
```

---

## Task 6: Credit Manager and API Service

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/credit/CreditApiService.kt`
- Create: `app/src/main/java/com/noteflowai/app/data/credit/CreditManager.kt`

**Interfaces:**
- Consumes: `SupabaseAuthManager`, `EntitlementManager`
- Produces: `CreditManager.getInstance(context)`, `.balance: StateFlow<Long>`, `.checkAndConsumeCredits(amount): Boolean`, `.syncBalance()`, `.monthlyLimit: Long`

- [ ] **Step 1: Create CreditApiService.kt**

```kotlin
package com.noteflowai.app.data.credit

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** Request to the /api/chat Edge Function. */
data class ChatProxyRequest(
    val provider: String,
    val model: String,
    val messages: List<Map<String, String>>,
    val stream: Boolean = true
)

/** Request to the /api/transcribe Edge Function. */
data class TranscribeProxyRequest(
    val audio: String,  // base64 encoded
    val language: String = "en"
)

/** Response with credit balance after a consumption. */
data class CreditBalanceResponse(
    val balance: Long,
    val limit: Long,
    val tier: String
)

/** Response from the API key verification endpoint. */
data class ApiKeyVerifyResponse(
    val valid: Boolean,
    val error: String? = null
)

/**
 * Retrofit interface for Supabase Edge Function endpoints.
 * These are called through a Supabase-authenticated HTTP client.
 */
interface CreditApiService {

    /** Chat with credit check + server-side key proxy. */
    @POST("api/chat")
    suspend fun chat(
        @Header("Authorization") auth: String,
        @Body request: ChatProxyRequest
    ): retrofit2.Response<okhttp3.ResponseBody>  // Streaming response

    /** Transcription with credit check + server-side Deepgram proxy. */
    @POST("api/transcribe")
    suspend fun transcribe(
        @Header("Authorization") auth: String,
        @Body request: TranscribeProxyRequest
    ): retrofit2.Response<CreditBalanceResponse>

    /** Verify a BYO API key is valid. */
    @POST("api/verify-key")
    suspend fun verifyApiKey(
        @Header("Authorization") auth: String,
        @Body request: Map<String, String>
    ): ApiKeyVerifyResponse

    /** Get current credit balance. */
    @POST("api/balance")
    suspend fun getBalance(
        @Header("Authorization") auth: String
    ): CreditBalanceResponse

    /** Consume credits (called after a successful API call). */
    @POST("api/consume")
    suspend fun consumeCredits(
        @Header("Authorization") auth: String,
        @Body request: Map<String, Any>  // { "amount": Long, "type": String }
    ): CreditBalanceResponse
}
```

- [ ] **Step 2: Create CreditManager.kt**

```kotlin
package com.noteflowai.app.data.credit

import android.content.Context
import com.noteflowai.app.data.auth.SupabaseAuthManager
import com.noteflowai.app.data.billing.BillingProducts
import com.noteflowai.app.data.billing.EntitlementManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the server-side credit wallet.
 * Subscription users get monthly credits; lifetime users have no credits (BYO keys only).
 * BYO keys bypass the credit system entirely.
 */
class CreditManager private constructor(context: Context) {

    private val authManager = SupabaseAuthManager.getInstance(context)
    private val entitlementManager = EntitlementManager.getInstance(context)

    private val _balance = MutableStateFlow(0L)
    val balance: StateFlow<Long> = _balance.asStateFlow()

    private val _monthlyLimit = MutableStateFlow(0L)
    val monthlyLimit: StateFlow<Long> = _monthlyLimit.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Check if user has credits remaining (subscription only). */
    fun hasCredits(): Boolean {
        if (entitlementManager.isLifetime()) return false  // Lifetime users use BYO keys
        if (entitlementManager.isFree()) return false       // Free users have no credits
        return _balance.value > 0
    }

    /** Check if user is approaching their limit (80% soft cap). */
    fun isNearLimit(): Boolean {
        if (_monthlyLimit.value <= 0) return false
        return _balance.value < (_monthlyLimit.value * 0.2)
    }

    /** Check if user has hit their hard cap (100%). */
    fun isAtLimit(): Boolean {
        if (_monthlyLimit.value <= 0) return false
        return _balance.value <= 0
    }

    /** Get the remaining budget as a percentage (100 = full, 0 = empty). */
    fun remainingPercent(): Int {
        if (_monthlyLimit.value <= 0) return 100
        return ((_balance.value.toFloat() / _monthlyLimit.value) * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Attempt to consume credits for an operation.
     * Returns true if credits are available, false if at limit.
     * Actual deduction happens server-side after the API call succeeds.
     */
    fun canConsume(amount: Long): Boolean {
        if (entitlementManager.isLifetime()) return true  // Lifetime: BYO keys, no credit check
        if (entitlementManager.isFree()) return false     // Free: no credits
        return _balance.value >= amount
    }

    /** Sync balance from Supabase. */
    suspend fun syncBalance() {
        val token = authManager.accessToken ?: return
        _isLoading.value = true
        try {
            // TODO: Call CreditApiService.getBalance() via Supabase Edge Function
            // For now, read from local cache
            val cachedBalance = context.getSharedPreferences("credits", Context.MODE_PRIVATE)
                .getLong("balance", 0L)
            val cachedLimit = context.getSharedPreferences("credits", Context.MODE_PRIVATE)
                .getLong("monthly_limit", 0L)
            _balance.value = cachedBalance
            _monthlyLimit.value = cachedLimit
        } catch (_: Exception) {
            // Keep cached values on failure
        } finally {
            _isLoading.value = false
        }
    }

    /** Cache balance locally for offline display. */
    fun cacheBalance(balance: Long, limit: Long) {
        context.getSharedPreferences("credits", Context.MODE_PRIVATE).edit()
            .putLong("balance", balance)
            .putLong("monthly_limit", limit)
            .apply()
        _balance.value = balance
        _monthlyLimit.value = limit
    }

    private val context: Context = context.applicationContext

    companion object {
        @Volatile
        private var instance: CreditManager? = null

        fun getInstance(context: Context): CreditManager {
            return instance ?: synchronized(this) {
                instance ?: CreditManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/credit/CreditApiService.kt \
       app/src/main/java/com/noteflowai/app/data/credit/CreditManager.kt
git commit -m "feat: add CreditManager and Edge Function API service"
```

---

## Task 7: Application-Level Initialization

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/NoteFlowAIApplication.kt`

**Interfaces:**
- Consumes: `EntitlementManager`, `SupabaseAuthManager`
- Produces: RevenueCat and Supabase initialized on app start

- [ ] **Step 1: Initialize RevenueCat and Supabase in Application.onCreate**

Replace the content of `NoteFlowAIApplication.kt`:

```kotlin
package com.noteflowai.app

import android.app.Application
import android.os.Build
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.noteflowai.app.data.billing.EntitlementManager
import com.noteflowai.app.data.auth.SupabaseAuthManager
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoteFlowAIApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // ── Crash handler ───────────────────────────────────────────
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(filesDir, "crashlogs").also { it.mkdirs() }
                val file = File(dir, "crash_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt")
                PrintWriter(file).use { pw ->
                    pw.println("Thread: ${thread.name} (${thread.id})")
                    pw.println("OS: ${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}")
                    pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    pw.println()
                    throwable.printStackTrace(pw)
                }
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // ── RevenueCat ──────────────────────────────────────────────
        // TODO: Replace with your RevenueCat API key from app.revenuecat.com
        val revenueCatKey = "YOUR_REVENUECAT_API_KEY"
        Purchases.configure(
            PurchasesConfiguration.Builder(this, revenueCatKey)
                .appUserID(null)  // Anonymous until user signs in
                .build()
        )

        // ── Supabase ────────────────────────────────────────────────
        // Initialize eagerly so Auth session restore happens on startup
        SupabaseAuthManager.getInstance(this)

        // ── Entitlement cache ───────────────────────────────────────
        // Read cached entitlements instantly (no network)
        EntitlementManager.getInstance(this)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/NoteFlowAIApplication.kt
git commit -m "feat: initialize RevenueCat and Supabase in Application"
```

---

## Task 8: Feature Gate Helper

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/billing/FeatureGate.kt`

**Interfaces:**
- Consumes: `EntitlementManager`, `PaywallManager`
- Produces: `FeatureGate.isFeatureAvailable(context, feature)`, `FeatureGate.gateOrPaywall(context, feature)`

- [ ] **Step 1: Create FeatureGate.kt**

```kotlin
package com.noteflowai.app.data.billing

import android.content.Context

/**
 * Centralized feature gating logic.
 * Each feature maps to a minimum tier requirement.
 */
object FeatureGate {

    fun isFeatureAvailable(context: Context, feature: PaywallTarget): Boolean {
        val em = EntitlementManager.getInstance(context)
        return when (feature) {
            PaywallTarget.CHAT -> em.isStarterOrAbove()
            PaywallTarget.TRANSCRIPTION -> em.isStarterOrAbove()
            PaywallTarget.YOUTUBE -> em.isPro()
            PaywallTarget.RAG -> em.hasRagAccess()
            PaywallTarget.TRANSLATION -> em.isStarterOrAbove()
            PaywallTarget.DOCUMENT_IMPORT -> em.isPro()
            PaywallTarget.GENERAL -> em.isStarterOrAbove()
        }
    }

    /**
     * If feature is available, returns true.
     * If not, shows the paywall and returns false.
     */
    fun gateOrPaywall(context: Context, feature: PaywallTarget): Boolean {
        if (isFeatureAvailable(context, feature)) return true
        PaywallManager.getInstance(context).showPaywall(feature)
        return false
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/billing/FeatureGate.kt
git commit -m "feat: add FeatureGate helper for centralized tier checks"
```

---

## Task 9: Paywall UI -- Tier Card Composable

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/ui/screens/paywall/PaywallTierCard.kt`

**Interfaces:**
- Consumes: `BillingProducts` constants
- Produces: `PaywallTierCard` composable

- [ ] **Step 1: Create PaywallTierCard.kt**

```kotlin
package com.noteflowai.app.ui.screens.paywall

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PaywallTierCard(
    name: String,
    price: String,
    period: String,
    features: List<String>,
    badge: String? = null,
    isRecommended: Boolean = false,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRecommended)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isRecommended)
            CardDefaults.outlinedCardBorder().copy(
                width = 2.dp,
                brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
            )
        else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Badge row
            if (badge != null) {
                SuggestionChip(
                    onClick = {},
                    label = { Text(badge, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Name + price
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Column(horizontalAlignment = Alignment.End) {
                    Text(price, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(period, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Feature list
            features.forEach { feature ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("✓", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(20.dp))
                    Text(feature, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // CTA button
            Button(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth(),
                colors = if (isRecommended)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                else
                    ButtonDefaults.outlinedButtonColors()
            ) {
                Text("Subscribe")
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/paywall/PaywallTierCard.kt
git commit -m "feat: add PaywallTierCard composable for paywall UI"
```

---

## Task 10: Soft Paywall Modal

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/ui/screens/paywall/SoftPaywallModal.kt`

**Interfaces:**
- Consumes: `PaywallState.SoftModal`, `PaywallTarget`
- Produces: `SoftPaywallModal` composable (ModalBottomSheet)

- [ ] **Step 1: Create SoftPaywallModal.kt**

```kotlin
package com.noteflowai.app.ui.screens.paywall

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Step 1 of two-step paywall.
 * Non-blocking modal that explains WHY the feature is locked.
 * User can dismiss and continue with free features.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoftPaywallModal(
    target: PaywallTarget,
    onSeePricing: () -> Unit,
    onDismiss: () -> Unit
) {
    val (title, description, icon) = when (target) {
        PaywallTarget.CHAT -> Triple(
            "AI Chat",
            "Chat with AI about your notes, ask questions, get summaries, and brainstorm ideas.",
            Icons.Default.Chat
        )
        PaywallTarget.YOUTUBE -> Triple(
            "YouTube Summarization",
            "Summarize any YouTube video. Get key points, timestamps, and actionable insights.",
            Icons.Default.PlayCircle
        )
        PaywallTarget.TRANSCRIPTION -> Triple(
            "Audio Transcription",
            "Convert voice recordings to text with high accuracy. Supports 100+ languages.",
            Icons.Default.Mic
        )
        PaywallTarget.RAG -> Triple(
            "Second Brain RAG",
            "Semantic search across your notes, knowledge graph connections, and citations.",
            Icons.Default.Psychology
        )
        PaywallTarget.TRANSLATION -> Triple(
            "Translation",
            "Translate your notes and text between 100+ languages instantly.",
            Icons.Default.Translate
        )
        PaywallTarget.DOCUMENT_IMPORT -> Triple(
            "Document Import",
            "Import and parse PDFs, Word docs, HTML, and plain text files.",
            Icons.Default.Description
        )
        PaywallTarget.GENERAL -> Triple(
            "Unlock More Features",
            "Upgrade to access AI-powered features that supercharge your notes.",
            Icons.Default.Star
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onSeePricing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("See Pricing")
            }

            TextButton(onClick = onDismiss) {
                Text("Maybe Later")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/paywall/SoftPaywallModal.kt
git commit -m "feat: add SoftPaywallModal for step 1 of paywall flow"
```

---

## Task 11: Full Paywall Screen

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/ui/screens/paywall/FullPaywallScreen.kt`

**Interfaces:**
- Consumes: `PaywallManager`, `EntitlementManager`, `PaywallTarget`
- Produces: `FullPaywallScreen` composable

- [ ] **Step 1: Create FullPaywallScreen.kt**

```kotlin
package com.noteflowai.app.ui.screens.paywall

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noteflowai.app.R

/**
 * Step 2 of two-step paywall.
 * Full-screen tier comparison with pricing.
 * Triggered by "See Pricing" on the soft modal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPaywallScreen(
    target: PaywallTarget,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val featureMessage = when (target) {
        PaywallTarget.CHAT -> "AI Chat unlocks powerful conversations about your notes"
        PaywallTarget.YOUTUBE -> "YouTube summarization saves you hours of watching"
        PaywallTarget.TRANSCRIPTION -> "Audio transcription turns recordings into searchable text"
        PaywallTarget.RAG -> "Second Brain gives your notes semantic superpowers"
        PaywallTarget.TRANSLATION -> "Translate your notes across 100+ languages"
        PaywallTarget.DOCUMENT_IMPORT -> "Import PDFs, Word docs, and more"
        PaywallTarget.GENERAL -> "Unlock the full power of NoteFlowAI"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upgrade") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Feature headline
            Text(
                featureMessage,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Starter tier
            PaywallTierCard(
                name = "Starter",
                price = "$2.99",
                period = "/month",
                features = listOf(
                    "600K AI tokens/month (~150 chat turns)",
                    "Summarize, proofread, rewrite",
                    "Audio transcription",
                    "BYO API key support"
                ),
                onSelect = { /* TODO: trigger RevenueCat purchase for starter */ }
            )

            // Pro tier (recommended)
            PaywallTierCard(
                name = "Pro",
                price = "$6.99",
                period = "/month",
                features = listOf(
                    "1.5M AI tokens/month (~750 chat turns)",
                    "Everything in Starter",
                    "RAG second brain",
                    "YouTube summarization",
                    "Document import + OCR",
                    "Translation",
                    "Google Drive backup"
                ),
                badge = "Most Popular",
                isRecommended = true,
                onSelect = { /* TODO: trigger RevenueCat purchase for pro */ }
            )

            // Annual tier
            PaywallTierCard(
                name = "Annual",
                price = "$49.99",
                period = "/year ($4.17/mo)",
                features = listOf(
                    "2M AI tokens/month (30% bonus)",
                    "Everything in Pro",
                    "Save $33.88/year vs monthly"
                ),
                badge = "Best Value",
                onSelect = { /* TODO: trigger RevenueCat purchase for annual */ }
            )

            // Lifetime
            PaywallTierCard(
                name = "Lifetime",
                price = "$29.99",
                period = "one-time (launch price)",
                features = listOf(
                    "All features unlocked",
                    "BYO API keys required",
                    "No monthly credits"
                ),
                badge = "Launch Special",
                onSelect = { /* TODO: trigger RevenueCat purchase for lifetime */ }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Restore purchases
            TextButton(
                onClick = { /* TODO: restore purchases */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Restore Purchases")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/paywall/FullPaywallScreen.kt
git commit -m "feat: add FullPaywallScreen with tier comparison"
```

---

## Task 12: Wire Paywall into Navigation

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/NoteFlowApp.kt`

**Interfaces:**
- Consumes: `PaywallManager`, `PaywallState`, `SoftPaywallModal`, `FullPaywallScreen`
- Produces: Paywall screens appear when triggered

- [ ] **Step 1: Add PAYWALL and CREDITS to Screen enum**

In `NoteFlowApp.kt`, modify the `Screen` enum:

```kotlin
enum class Screen {
    HOME, RECORD, NOTES, NOTE_DETAIL, SCAN, YOUTUBE, DOCUMENT, CHAT, SETTINGS,
    PAYWALL, CREDITS
}
```

- [ ] **Step 2: Add paywall state observation and overlay**

In `NoteFlowApp.kt`, inside the `NoteFlowApp` composable, after the existing state declarations (around line 73), add:

```kotlin
    val paywallManager = remember { com.noteflowai.app.data.billing.PaywallManager.getInstance(context) }
    val paywallState by paywallManager.paywallState.collectAsStateWithLifecycle()
```

- [ ] **Step 3: Add paywall overlay after the Scaffold**

At the end of the `NoteFlowApp` composable (before the closing `}`), add:

```kotlin
    // ── Paywall overlay ──────────────────────────────────────────────
    when (val state = paywallState) {
        is com.noteflowai.app.data.billing.PaywallState.SoftModal -> {
            SoftPaywallModal(
                target = state.target,
                onSeePricing = { paywallManager.showFullPaywall(state.target) },
                onDismiss = { paywallManager.dismissPaywall() }
            )
        }
        is com.noteflowai.app.data.billing.PaywallState.FullScreen -> {
            FullPaywallScreen(
                target = state.target,
                onDismiss = { paywallManager.dismissPaywall() }
            )
        }
        is com.noteflowai.app.data.billing.PaywallState.Hidden -> { /* no-op */ }
    }
```

- [ ] **Step 4: Add required imports**

At the top of `NoteFlowApp.kt`, add:

```kotlin
import com.noteflowai.app.ui.screens.paywall.SoftPaywallModal
import com.noteflowai.app.ui.screens.paywall.FullPaywallScreen
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/NoteFlowApp.kt
git commit -m "feat: wire paywall overlay into NoteFlowApp navigation"
```

---

## Task 13: Gate Chat Screen Behind Starter Tier

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt`

**Interfaces:**
- Consumes: `FeatureGate`, `PaywallTarget`
- Produces: Chat shows paywall for free users

- [ ] **Step 1: Add feature gate check at the top of ChatScreen**

In `ChatScreen.kt`, at the beginning of the `ChatScreen` composable body (before the existing UI), add:

```kotlin
    // ── Feature gate: Chat requires Starter tier ─────────────────────
    val context = androidx.compose.ui.platform.LocalContext.current
    if (!com.noteflowai.app.data.billing.FeatureGate.isFeatureAvailable(
            context, com.noteflowai.app.data.billing.PaywallTarget.CHAT
        )) {
        // Show a locked state instead of the chat UI
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("AI Chat", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Upgrade to Starter ($2.99/mo) to chat with AI about your notes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    com.noteflowai.app.data.billing.PaywallManager.getInstance(context)
                        .showPaywall(com.noteflowai.app.data.billing.PaywallTarget.CHAT)
                }) {
                    Text("Upgrade")
                }
            }
        }
        return
    }
```

Note: This is a temporary gate. The existing chat screen code continues below this check.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt
git commit -m "feat: gate chat screen behind Starter tier"
```

---

## Task 14: Gate YouTube Screen Behind Pro Tier

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/YouTubeScreen.kt`

**Interfaces:**
- Consumes: `FeatureGate`, `PaywallTarget`
- Produces: YouTube shows paywall for non-Pro users

- [ ] **Step 1: Add feature gate check at the top of YouTubeScreen**

In `YouTubeScreen.kt`, at the beginning of the `YouTubeScreen` composable body, add:

```kotlin
    // ── Feature gate: YouTube requires Pro tier ──────────────────────
    val context = androidx.compose.ui.platform.LocalContext.current
    if (!com.noteflowai.app.data.billing.FeatureGate.isFeatureAvailable(
            context, com.noteflowai.app.data.billing.PaywallTarget.YOUTUBE
        )) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("YouTube Summarization", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Upgrade to Pro ($6.99/mo) to summarize YouTube videos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    com.noteflowai.app.data.billing.PaywallManager.getInstance(context)
                        .showPaywall(com.noteflowai.app.data.billing.PaywallTarget.YOUTUBE)
                }) {
                    Text("Upgrade to Pro")
                }
            }
        }
        return
    }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/YouTubeScreen.kt
git commit -m "feat: gate YouTube screen behind Pro tier"
```

---

## Task 15: Billing Section in Settings

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/SettingsSections.kt`

**Interfaces:**
- Consumes: `EntitlementManager`, `CreditManager`, `PaywallManager`
- Produces: `BillingSection` composable showing subscription status and credits

- [ ] **Step 1: Add BillingSection composable**

At the end of `SettingsSections.kt`, add:

```kotlin
// ── Billing & Subscription ──────────────────────────────────────────

@Composable
internal fun BillingSection(viewModel: MainViewModel) {
    var showBilling by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val entitlementManager = remember { com.noteflowai.app.data.billing.EntitlementManager.getInstance(context) }
    val creditManager = remember { com.noteflowai.app.data.credit.CreditManager.getInstance(context) }
    val entitlement by entitlementManager.currentEntitlement.collectAsStateWithLifecycle()
    val creditBalance by creditManager.balance.collectAsStateWithLifecycle()
    val monthlyLimit by creditManager.monthlyLimit.collectAsStateWithLifecycle()

    CollapsibleHeader(
        title = "Subscription & Credits",
        icon = Icons.Default.CreditCard,
        expanded = showBilling,
        onToggle = { showBilling = !showBilling }
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (showBilling) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Current tier
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Current plan: ${entitlement.replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                // Credit balance (subscription only)
                if (entitlement != "free" && entitlement != "lifetime") {
                    Spacer(modifier = Modifier.height(8.dp))
                    val used = monthlyLimit - creditBalance
                    LinearProgressIndicator(
                        progress = { (used.toFloat() / monthlyLimit.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = when {
                            creditManager.isAtLimit() -> MaterialTheme.colorScheme.error
                            creditManager.isNearLimit() -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${formatTokenCount(creditBalance)} / ${formatTokenCount(monthlyLimit)} tokens remaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entitlement == "free") {
                        Button(onClick = {
                            com.noteflowai.app.data.billing.PaywallManager.getInstance(context)
                                .showPaywall(com.noteflowai.app.data.billing.PaywallTarget.GENERAL)
                        }) {
                            Text("Upgrade")
                        }
                    } else {
                        OutlinedButton(onClick = { /* TODO: open Google Play subscription management */ }) {
                            Text("Manage")
                        }
                    }
                    OutlinedButton(onClick = {
                        entitlementManager.restorePurchases { success ->
                            // TODO: show toast
                        }
                    }) {
                        Text("Restore")
                    }
                }
            }
        }
    }
}

private fun formatTokenCount(tokens: Long): String {
    return when {
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format("%.0fK", tokens / 1_000.0)
        else -> tokens.toString()
    }
}
```

- [ ] **Step 2: Add BillingSection to SettingsScreen**

In `SettingsScreen.kt`, add `BillingSection(viewModel = viewModel)` in the column of settings sections (after existing sections).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/SettingsSections.kt \
       app/src/main/java/com/noteflowai/app/ui/screens/SettingsScreen.kt
git commit -m "feat: add billing section to Settings screen"
```

---

## Task 16: Route Chat Through Credit System

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt`

**Interfaces:**
- Consumes: `CreditManager`, `EntitlementManager`
- Produces: Chat messages route through credit check before API call

- [ ] **Step 1: Add credit check in sendChatMessage**

In `MainViewModel.kt`, at the beginning of `sendChatMessage()` (before the API call), add:

```kotlin
    // ── Credit check (subscription users) ────────────────────────────
    val context = application
    val entitlementManager = com.noteflowai.app.data.billing.EntitlementManager.getInstance(context)
    val creditManager = com.noteflowai.app.data.credit.CreditManager.getInstance(context)

    // Lifetime users: BYO keys only, no credit check needed
    // Free users: blocked by feature gate in ChatScreen
    // Subscription users: check credit balance
    if (entitlementManager.isStarterOrAbove() && !entitlementManager.isLifetime()) {
        if (creditManager.isAtLimit()) {
            // Show upgrade/top-up prompt
            com.noteflowai.app.data.billing.PaywallManager.getInstance(context)
                .showPaywall(com.noteflowai.app.data.billing.PaywallTarget.CHAT)
            return
        }
    }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "feat: route chat through credit check before API call"
```

---

## Task 17: Build Verification

- [ ] **Step 1: Run full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run lint**

Run: `./gradlew lintDebug`
Expected: No new errors (warnings OK)

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix: resolve lint and build issues from monetization integration"
```

---

## Implementation Order

| # | Task | Effort | Dependency | What ships |
|---|------|--------|------------|------------|
| 1 | Gradle dependencies | Low | None | SDK available |
| 2 | BillingProducts config | Low | #1 | Product constants |
| 3 | EntitlementManager | Medium | #1 | Entitlement checks |
| 4 | PaywallManager | Low | #3 | Paywall state machine |
| 5 | SupabaseAuthManager | Medium | #1 | User authentication |
| 6 | CreditManager + API | Medium | #5 | Credit wallet |
| 7 | Application init | Low | #3, #5 | SDK initialization |
| 8 | FeatureGate helper | Low | #3, #4 | Centralized gating |
| 9-11 | Paywall UI (cards, modal, screen) | Medium | #4 | Paywall visuals |
| 12 | Wire paywall into nav | Low | #9-11 | Paywall triggers |
| 13-14 | Gate Chat + YouTube | Low | #8 | Feature locks |
| 15 | Billing section in Settings | Low | #3, #6 | Subscription management UI |
| 16 | Route chat through credits | Medium | #6 | Credit consumption |
| 17 | Build verification | Low | All | Compiles |

**Milestone checkpoints:**
- After #8: Entitlement system works. Feature gates block free users.
- After #12: Paywall UI shows and triggers purchases.
- After #16: Full credit flow works end-to-end.

---

## Out of Scope (for now)

- Supabase Edge Functions (deployed separately, not in this Android codebase)
- Webhook handlers for RevenueCat (server-side, in Supabase Edge Functions)
- Google sign-in integration (requires Google Cloud Console setup)
- Apple sign-in (Android app, lower priority)
- Top-up purchase flow (add after core subscription works)
- BYO key verification UI (add after core subscription works)
- RAG add-on purchase flow (add after core subscription works)
