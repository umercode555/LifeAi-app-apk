# Life AI v2

Setup (once): run `supabase/schema.sql` in Supabase SQL Editor (safe to re-run). Install the APK, open **Settings**, paste Supabase URL + anon key + Gemini API key (model `gemini-3.5-flash-lite`). Load `LifeAI-Extension` in Chrome (Developer mode → Load unpacked), open its dashboard, save the same Supabase URL/key, scan the "Pair phone" QR from the app's **Screen** tab.

Phone permissions: Camera/Mic/Notifications (asked), Usage access (Screen tab), Display over other apps (screen flash), Battery → Allow.
PRESS "Start engine" in the extension dashboard and keep it running: it reads photos through Gemini web (no vision credits needed). Phone = decisions + text API (chat, calls, messages).

Design: Gemini sees photos and writes sentences. `Policy.kt` (pure Kotlin, unit-tested by 10 scenarios, 0 tokens) decides note / remind / remind-later / interrupt from priority, deadline slack, current activity, time off-task, cooldowns.


## v3 — what changed (reliability + UX)

**Why "Okay" did nothing (root causes found in the code)**
1. `Brain.CHAT_SYS` told the model *"nothing is saved until the user says send this to Gemini"*, so a chat instruction never became a rule/reminder — the model just said "Okay".
2. There was **no reminder concept at all**: no storage, no alarm, no delivery. `items.deadline` was never turned into a notification.
3. The only code that asked the Brain to react to an observation (`decide()` in the extension) was dead; the phone engine only fired for *matching goals/rules*. A burger with no rule = `NOTE` = silence, and chat photos replied with the raw verdict ("NOTE — nothing needs attention").
4. Rules only fired if the vision model volunteered the rule id in `hits`; "sitting" was not even an activity.
5. A failure after an observation was claimed was swallowed (observation lost, UI silent).

**Fixes**
- `Reminders.kt`: local store + `AlarmManager` exact alarms + boot restore + service backup poll + Supabase mirror. Created **only** after the alarm is armed.
- `Lang.kt` (pure, unit-tested): "remind me at 7:15 / in 10 minutes" and "whenever you see me sitting, tell me …" are parsed locally (0 tokens, works offline) and override/backstop the model.
- Chat now returns `actions`; the app executes them and posts a green **verified** card (or an orange warning). Nothing is "set" because the AI said so.
- Rules fire deterministically from vision (`acts` / `keywords`), then the Brain speaks (chat + notification, priority 3 = call). One-shot rules supported (`once`).
- Anything you show on purpose (chat / Show me / shake) always gets a Brain answer.
- Chat photo watchdog: after 100 s without analysis you are told whether the laptop engine is offline (extension now writes a heartbeat).
- Failed decisions are retried (3×) and reported instead of silently lost.

**UI**: Today dashboard (honest status, real progress – no fake 0 %), Coach chat with example prompts, Google-Calendar-style week/month agenda timeline, Memories gallery (grouped by day, filters, swipe viewer), Plans (reminders / rules / goals in plain words), onboarding, sectioned Settings (+ test-reminder button, exact-alarm permission).

No schema changes. Re-install the extension (new heartbeat + `sitting`).


## v4 — Companion plumbing
- **Device ledger**: `AppTrack.kt` (phone foreground-app segments → `app_log`, `AppTrack.ctx` one-liner attached to every photo/clip as `images.ctx`); `background.js` (laptop site + page title segments → `app_log`).
- **Contextual vision**: `dashboard.js` `deviceCtx()` + new `VISION_RULES` (curious, interpretive; returns `question`, shown by `Brain.curious`, max 1 / 40 min).
- **Ears**: `Ears.kt` (opt-in in Settings). 9-min RAM ring buffer → VAD (`EnergyVad`, swap for Silero via `Vad` interface) → on-device recognizer (Android 13+) → ~110 keywords → 90 s later cut -6.5/+1.5 min → AAC .m4a → `clipbox/` file queue → `clips` table → laptop Gemini (`processClip`) → `calls` row.
- **Live snapshot**: `LifeService.checkCalls` takes a `call` photo the moment a call rings; vision jumps the queue; `Brain.callReply` reads it + its ctx.
- **Memory**: `chats` table; `ask()` counts items, rotates at 48, primes the new chat; `recall()` asks the right old chat for "N weeks/months ago" questions.
- Run the updated `supabase/schema.sql` once (idempotent).

## v4.1 — Gemini is the only decision-maker
- Kotlin `Policy.kt` still runs for **bookkeeping only** (activity minutes, goal progress events). `Brain.observe` / `Brain.blind` no longer nudge, call or fire rules.
- Every decision (photo, audio clip, blind camera) is made by Gemini in `dashboard.js` (`COMPANION_RULES`, `deliver()`): `calls` row → phone rings; `decisions` row `made_by='gemini'` → `Brain.deliverNudges()` shows it; `action='reply'` → chat answer to a photo shown on purpose.
- Only guard in code: `MIN_GAP` (8 min) between nudges/calls, and no action on photos older than 25 min.
- After updating: run `schema.sql`, then press **Send vision setup** and **Send brain setup** in the extension dashboard so both Gemini chats get the new prompt.

## v4.2
- One-off photos (Coach "Show me", shake, manual) with no monitoring loop: `LifeService.awaitAnswer()` keeps the service alive ≤3 min so Gemini's reply / call / nudge arrives immediately.
- Coach chat → laptop Gemini via `messages` (`Brain.toBrain`, `Brain.pullReplies`). Plain clock reminders stay local; conditions go to Gemini. Offline laptop + API key → old phone chat.
- Free-form standing instructions: Gemini returns `directives` (text + `until` + `once`) → stored in `items` kind=rule (`details` = `INSTRUCTION:/UNTIL:/ONCE`), shown to Gemini with every photo/clip, auto-expire, `complete` closes them.
