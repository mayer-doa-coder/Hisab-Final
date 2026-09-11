# Localization

How Hisab handles Bangla and English. Full detail: `PRD.md`, sections 13 and 21. Why: `../DECISIONS.md` D009–D011.

## Languages
- Bangla is shown by default, on first run, before the user chooses anything.
- English is always available and can be switched to at any time, without reinstalling or losing data.
- Every user-facing string (screens, labels, buttons, dialogs, notifications, error messages, onboarding, Ask Hisab responses) must have both a Bangla and an English version before it ships. A feature with only one language is not done.
- No user-facing string is ever hard-coded outside the resource files — every string goes through `bn`/`en` resources, even ones that feel "temporary."

## Mechanism (Built in M0, Before Any Feature)
- Language switching and persistence use Android's built-in per-app language support: AndroidX `AppCompatDelegate` per-app language APIs, backed by `LocaleManager` on Android versions that support it. Do not build a custom language-state system — see `../DECISIONS.md` D014.
- The chosen language persists across app restarts automatically through this mechanism (it is a solved problem — don't re-solve it in app code).
- Resource files: `values/strings.xml` (Bangla, since Bangla is default) and `values-en/strings.xml` (English), or the equivalent AndroidX resource-qualifier structure — one string, one key, two language files.
- A localization completeness check runs in CI: fail the build if a string key exists in one language file but not the other. This is what makes "a feature with only one language is not done" enforceable rather than just a rule people forget.

## Fonts
- Bangla text: Noto Sans Bengali. No exceptions.
- English text: Merriweather. No exceptions.
- No other font family without approval.
- Applies everywhere: every screen, every state (loading, empty, error, disabled, success, offline), and any labels on maps or embedded views.
- If a string mixes both scripts (e.g. a Bangla sentence with an English product name), each part renders in its own correct font — never one font for the whole string.

## Input Understanding
Hisab must also read Bangla, Romanized Bangla ("banglish"), and mixed Bangla-English typed or spoken by the user (product searches, retail questions). This is separate from UI display language — see `PRD.md` section 13.1 and 14.

## Safety and Status Codes
- Internal codes (e.g. `SYNC_CONFLICT`, `AUTH_INVALID`, `DUPLICATE_EVENT`) never change based on the display language.
- Each code maps to a Bangla message and an English message that mean the same thing — translation must never soften, drop, or change safety-critical meaning.
- Logs, telemetry, and sync payloads always use the code, never the localized text.

## Checklist Before Shipping a Screen
- [ ] Bangla text present
- [ ] English text present
- [ ] Bangla text uses Noto Sans Bengali
- [ ] English text uses Merriweather
- [ ] Mixed-language strings render each part in the correct font
- [ ] All UI states (loading/empty/error/disabled/success/offline) covered in both languages
- [ ] Any status/error code shown to the user maps to an equivalent-meaning message in both languages
