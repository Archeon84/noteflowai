# NoteFlowAI Monetization Design

## Overview

NoteFlowAI monetizes through a freemium model with four tiers: free, Starter, Pro, and Lifetime. Subscription users get a token wallet for remote AI API usage (mimo v2.5 Pro for chat, Deepgram for transcription), managed server-side via Supabase. Lifetime users get all features but must provide their own API keys.

## Stack

| Component | Technology | Purpose |
|---|---|---|
| Billing | RevenueCat + Google Play Billing | Subscription/purchase management |
| Backend | Supabase (PostgreSQL + Edge Functions) | Credit tracking, API proxy, user accounts |
| API proxy | Supabase Edge Functions (Deno) | Proxies AI API calls using server-side keys |
| Auth | Supabase Auth | User accounts (email, Google, Apple sign-in) |

## Pricing

| Tier | Price | Tokens | RAG/Second Brain |
|---|---|---|---|
| Free | $0 | None (on-device only) | No |
| Starter | $2.99/month | 600K mimo v2.5 Pro tokens/month (~150 turns) | No |
| Pro | $6.99/month | 1.5M mimo v2.5 Pro tokens/month (~750 turns) | Included |
| Annual | $49.99/year ($4.17/mo) | 2M tokens/month (30% bonus, ~1000 turns) | Included |
| Lifetime | $59.99 (launch $29.99) | None (BYO keys only) | Add-on ($12.99) |

**Top-up packs (any subscription tier):** $2.99 = 300K tokens, $9.99 = 1.2M tokens

## Feature Gating

### Free tier
- Core notes (create, edit, delete, organize, tags, categories) -- unlimited notes
- Markdown rendering
- Basic search
- Offline Whisper transcription: 5 uses/day (on-device, no API cost)
- On-device summarization via llama.cpp: 10 uses/day (no API cost)
- No storage cap (limited only by device storage)
- No remote AI features (no chat, no YouTube, no RAG, no translation)

### Starter ($2.99/month)
- Everything in Free
- 600K mimo v2.5 Pro tokens/month (capacity: ~150 conversation turns)
- Summarize/proofread/rewrite via mimo v2.5 Pro (cost-gated by token limit)
- Transcription via Deepgram online (cost-gated by token limit)
- BYO key support (if user provides own key, no credits deducted)
- Basic RAG disabled (reserved for Pro)
- No knowledge graph

**Use case:** Casual note-takers, students who want AI but don't need heavy chat.

### Pro ($6.99/month)
- Everything in Starter
- 1.5M mimo v2.5 Pro tokens/month (~750 conversation turns, 400+/day sustainable)
- Unlimited summarize/proofread/rewrite (cost-gated by token allocation)
- RAG second brain (hybrid search + knowledge graph)
- Per-message RAG attribution and citations
- Advanced search (semantic + BM25)
- YouTube video summarization (uses mimo v2.5 Pro tokens)
- Document import + OCR
- Translation (uses mimo v2.5 Pro tokens)
- Transcription via Deepgram online (uses tokens)
- Google Drive backup
- BYO key priority (user's key used first; no credits deducted for that provider)

**Use case:** Power users, professionals, content creators.

### Annual ($49.99/year = $4.17/month)
- Same as Pro
- 2M tokens/month (30% bonus for commitment)
- Effective cost: $2.78/month with token bonus
- Saves user $33.88/year vs. monthly ($83.88 - $49.99)

### Lifetime ($59.99, launch price $29.99)
- All features unlocked (excluding RAG add-on)
- No credit wallet (BYO keys only)
- No API proxy -- user must provide their own remote API keys
- No monthly refresh -- one-time purchase, lifetime access

### RAG Add-on ($12.99 one-time, available to Lifetime users)
- RAG second brain (hybrid search + knowledge graph)
- Per-message attribution and citations
- Advanced search (semantic + BM25)
- BYO key required for remote embeddings (or on-device embeddings included)
- One-time purchase, lifetime access

**Subscription RAG vs. Add-on RAG:**
| Feature | Subscription RAG | Lifetime RAG Add-on |
|---|---|---|
| Hybrid search | Yes | Yes |
| Knowledge graph | Yes | Yes |
| Auto-linking | Yes (managed) | Yes (on-device) |
| Remote embeddings | Credits included | BYO key required |
| On-device embeddings | Yes | Yes |
| Graph-enhanced retrieval | Yes | Yes |

## API Key Architecture

**Subscription users (Starter/Pro/Annual):**
- App calls Supabase Edge Function (no API key sent from app)
- Edge Function has YOUR mimo v2.5 Pro and Deepgram keys as environment secrets
- Edge Function checks credit balance, proxies API call, deducts tokens
- Response streams back: API -> Edge Function -> App

**Lifetime users:**
- App calls APIs directly using user's own keys (configured in Settings)
- No server involvement
- BYO keys stored locally in EncryptedSharedPreferences

**BYO key priority:**
- If user has their own key configured for a provider -> use their key (no credit cost)
- If no BYO key -> use credit wallet (subscription only)

## BYO Key Onboarding (Lifetime / Power Users)

BYO key setup is a churn vector -- users must paste API keys into Settings, which is friction. Reduce it:

**Guided setup wizard (first-run for Lifetime users):**
- On first use of AI feature: "Add your OpenAI key (takes 60 seconds)"
- Step-by-step with screenshots: where to get a key, how to paste it
- Inline help text: "Your key stays on your device, never sent to our servers"

**API key testing button:**
- "Verify Key" button next to each key field
- Calls a lightweight Supabase Edge Function endpoint to validate the key
- Shows green checkmark on success, clear error on failure ("Invalid key" / "Rate limited" / "Insufficient quota")

**Import preset (stretch goal):**
- Detect .env files or common config locations
- Auto-populate key fields from detected sources

**Subscription fallback CTA:**
- Below BYO key fields: "Want to skip this? Upgrade to subscription for managed credits"
- Non-intrusive, always available as an alternative

### Storage
- PostgreSQL table `users`: `current_credit_balance INT`, `current_period_end TIMESTAMP`
- PostgreSQL table `credit_transactions`: audit log of all credit operations
- RevenueCat webhook -> Supabase Edge Function -> updates balance

### Monthly refresh
- On subscription renewal: RevenueCat webhook fires -> Edge Function grants new monthly credits
- On app launch: Edge Function checks `current_period_end`, refreshes if month rolled over
- Old credits expire (no rollover beyond current month)

### Soft & hard caps

**At 80% of monthly limit (soft cap):**
- Show in-app warning: "You're using {X}% of your monthly chat limit"
- Suggest upgrade or top-up
- No functionality change

**At 100% of monthly limit (hard cap):**
- Block mimo v2.5 Pro chat (return 402 Payment Required)
- Allow BYO key if configured (bypasses credit system)
- Show upgrade prompt: "Top up: $2.99 = 300K tokens" or "Upgrade to Pro"

**At 110% (top-up pack used):**
- Continue allowing chat using top-up tokens
- When both base allocation AND top-up depleted -> block
- Streaming responses terminated if credits hit zero mid-stream

### Cost economics
- mimo v2.5 Pro: ~$0.003-0.005 per conversation turn (estimated)
- Deepgram transcription: ~$0.006/minute
- $2.99 Starter tier covers ~150 chat turns + summarization + transcription
- $6.99 Pro tier covers ~750 chat turns + heavy RAG + YouTube + docs + translation
- Effective cost per subscriber: <$2/month in API fees

## RevenueCat Integration

### Entitlements
- `"starter"` -- unlocks Starter tier features
- `"pro"` -- unlocks Pro tier features (includes Starter)
- `"lifetime"` -- unlocks all features, no credits
- `"rag_addon"` -- unlocks RAG for lifetime users (one-time in-app purchase)
- Packages defined in RevenueCat dashboard: starter monthly, pro monthly, annual, lifetime, rag_addon

### Purchase flow
1. User taps locked feature -> paywall screen
2. Paywall shows available tiers
3. User selects -> Google Play purchase sheet -> RevenueCat processes
4. On success: `Purchases.sharedInstance.getCustomerInfo()` updates entitlements
5. Feature gates unlock immediately

### Webhooks
- `INITIAL_PURCHASE` -> create user row, grant credits (subscription) or mark lifetime
- `RENEWAL` -> refresh monthly credits
- `CANCELLATION` -> mark subscription as active until period end
- `EXPIRATION` -> revoke entitlement, revert to free
- `BILLING_ISSUE` -> flag user, grace period before revocation

### Webhook reliability & billing grace

- **Webhook retry:** If RENEWAL webhook fails, RevenueCat retries automatically. App-side grace period (3 days) allows continued credit access while webhook is retried.
- **Billing issue handling:** When BILLING_ISSUE fires, show in-app message: "We couldn't charge your card -- update payment method to keep using credits." NOT silent revocation. User gets 7 days to fix before entitlement is removed.
- **Offline resilience:** If device is offline at renewal time, allow cached entitlement for 7 days. On next online sync, validate against RevenueCat and refresh credits.
- **Grace period implementation:** Backend stores `grace_period_end TIMESTAMP`. On webhook failure or billing issue, set grace period to now + 3 days. Feature gates check `NOW() < grace_period_end` before revoking access.

## Paywall UI

**Two-step conversion flow:**

**Step 1 -- Soft paywall (modal):**
- Triggered when user taps any locked feature
- Shows WHY the feature is locked + what they'd get
- Offers free alternatives: "Transcription is powerful -- try our free 2 transcriptions" with "See Pricing" button
- Non-blocking: user can dismiss and continue with free features

**Step 2 -- Full paywall (screen):**
- Triggered by "See Pricing" button or after exhausting free alternatives
- Shows available tiers with clear differentiation
- Annual highlighted as "Best Value" with savings badge + "Most popular" label
- Lifetime shows launch discount price
- Free tier summary at top: "You currently have: Notes, Transcription, 10 AI uses/day"
- Social proof: "Trusted by 50k+ note-takers" (updated as user base grows)
- One-tap purchase via Google Play sheet

**Paywall personalization:**
- If user has tried free transcription 2x -> show transcription-focused messaging
- If user has tried free summarization 2x -> show summarization-focused messaging
- Tailor the "why upgrade" copy to the feature they actually attempted

**Urgency (thoughtful, not aggressive):**
- "Launch pricing expires in 14 days" -- OK, honest deadline
- "Sale ends in 2 hours" -- NEVER, this annoys users and erodes trust

### Token wallet UI (subscription only)
- Settings > AI Credits
- Current balance: "1.2M / 1.5M tokens remaining"
- Tier badge: "Pro" or "Starter"
- Usage breakdown: chat vs. transcription vs. features
- "Top Up" or "Upgrade" button

## Backend Schema (Supabase PostgreSQL)

```sql
-- Subscription tiers
CREATE TABLE subscription_tiers (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,          -- 'starter', 'pro', 'annual', 'lifetime'
    monthly_credits INT NOT NULL DEFAULT 0,     -- token allocation
    price_usd NUMERIC(4, 2) NOT NULL
);

-- Seed tiers
INSERT INTO subscription_tiers (name, monthly_credits, price_usd) VALUES
('starter', 600000, 2.99),
('pro', 1500000, 6.99),
('annual', 2000000, 4.17),   -- effective monthly price
('lifetime', 0, 59.99);

-- Users
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    revenuecat_id VARCHAR(255) NOT NULL UNIQUE,
    tier_id INT REFERENCES subscription_tiers(id),
    current_credit_balance INT NOT NULL DEFAULT 0,
    subscription_status VARCHAR(50) NOT NULL DEFAULT 'active',
    current_period_end TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_users_rc_id ON users(revenuecat_id);
CREATE INDEX idx_users_balance ON users(id, current_credit_balance);

-- Credit transactions (audit log)
CREATE TABLE credit_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    amount INT NOT NULL,                        -- negative = consumption, positive = grant/top-up
    transaction_type VARCHAR(50) NOT NULL,      -- 'chat_usage', 'feature_usage', 'monthly_refresh', 'top_up'
    metadata JSONB,                             -- token breakdown, model used, etc.
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

## Edge Function: API Proxy

```
POST /api/chat
Authorization: Bearer <supabase_anon_key>

Body: { "provider": "mimo", "model": "v2.5-pro", "messages": [...] }

Logic:
1. Authenticate user via Supabase JWT
2. Look up user in DB
3. If user has BYO key for this provider -> use their key, skip credit check
4. If subscription user -> check credit balance
5. If insufficient -> return 402 with upgrade prompt
6. Proxy request to mimo v2.5 Pro using server-side API key
7. Deduct tokens from balance
8. Stream response back to app

POST /api/transcribe
Authorization: Bearer <supabase_anon_key>

Body: { "audio": "<base64>", "language": "en" }

Logic:
1. Authenticate user via Supabase JWT
2. Look up user in DB
3. Check credit balance
4. Proxy to Deepgram using server-side API key
5. Deduct tokens based on audio duration
6. Return transcription
```

## Upgrade Incentive Flow (Lifetime -> Subscription)

Lifetime users manage their own API keys. Some will hit friction (key setup, provider outages, model updates). Detect and offer the subscription path:

**Trigger conditions:**
- Lifetime user has configured 3+ different API keys (high maintenance burden)
- Lifetime user's BYO key fails 3+ times in a session (provider issues)
- Lifetime user attempts a feature that requires credits (e.g., Deepgram transcription without their own key)

**In-app nudge:**
- Non-intrusive banner: "Tired of managing API keys? Switch to subscription for hassle-free AI."
- Settings > API Keys section: "Or subscribe for built-in credits -- no keys needed"
- On BYO key failure: "Your key isn't working. Try subscription credits instead?"

**Upgrade path:**
- Lifetime user subscribes -> gets BOTH lifetime features AND subscription credits
- Their BYO keys remain configured (used first, credits as fallback)
- RevenueCat handles the hybrid entitlement (lifetime + subscription coexist)

**No downgrade pressure:**
- Lifetime features never removed
- Subscription is positioned as "convenience upgrade," not "you must pay again"

## Launch Strategy

1. **Pre-launch:** Dev blog series (building NoteFlowAI, RAG system, on-device AI). Build Twitter/X following.
2. **Launch week:** Lifetime at $29.99 (regular $59.99). Post on r/Android, r/productivity, r/selfhosted, Hacker News.
3. **Review push:** Free Pro access for Play Store reviewers (7-day access).
4. **Post-launch:** Monthly feature updates, community engagement, content marketing.

### Pricing clarity in marketing

Blog content and Play Store listing should answer these questions clearly:

- **"Why subscription vs. one-time?"** -- Recurring access to new RAG features, always-updated models, no API key headaches. Lifetime is for power users who already have their own keys.
- **"What's included in free?"** -- Explicit list + visual comparison table on Play Store listing and landing page.
- **"When does my credit month reset?"** -- On next billing cycle, clearly shown in Settings > AI Credits.

## Scope

This spec covers the monetization architecture, feature gating, and backend design. Implementation will be broken into:

1. RevenueCat integration (Gradle, entitlement checking, paywall UI)
2. Supabase backend (schema, Edge Functions, auth)
3. Feature gating in app (EntitlementManager, paywall triggers, RAG gate)
4. Credit wallet UI and API proxy
5. Launch preparation (pricing, blog content, reviewer program)

## Out of Scope

- Server-side receipt validation (RevenueCat handles this)
- Cross-platform billing (Android only for now)
- A/B testing of pricing (can add via RevenueCat later)
- Fraud detection beyond credit balance checks
