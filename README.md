# Hisab

An offline-first retail assistant for small shops (দোকান) in Bangladesh. See `docs/PRD.md` for the full product requirements.

## Repository Layout

```text
/                       ← files read on every task, kept short
  CLAUDE.md             ← rules for working on this codebase
  CURRENT_PHASE.md      ← what is allowed to be worked on right now
  DECISIONS.md          ← settled decisions and why (do not reopen without a new entry)
  README.md             ← this file
  LICENSE

/docs                   ← the actual specifications, one file per topic
  PRD.md                ← full product requirements (the single source of truth for scope)
  PHASE_GUIDE.md         ← the build plan as small numbered steps, one at a time, Android + backend together
  VIBE_CODING_GUIDE.md   ← how to work with Claude on this project
  ARCHITECTURE.md        ← short system overview, points back to PRD.md for detail
  DATA_MODEL.md          ← the tables/entities Hisab uses
  SECURITY.md            ← security requirements, points back to PRD.md for detail
  RESEARCH_PLAN.md       ← how each research question gets tested
  LOCALIZATION.md        ← bilingual UI and font rules
```

## Rule: One Document, One Home

Every topic has exactly one file. Nothing is duplicated:

- Root files are short and answer "what can I do right now / what have we decided."
- Everything under `docs/` describes *what Hisab is and how it is built*. If you need detail on a topic, there is exactly one file for it — never a second copy or summary of an existing one.
- There is no separate `/ADR` folder — `DECISIONS.md` at the root is the one decision log.
- If a new topic needs a document, add exactly one file for it and link to it from here. Do not create a second file that duplicates or summarizes an existing one.

## Run It Yourself

Everything below was checked on a fresh copy of the repository (`docs/PHASE_GUIDE.md` Step 22). On Windows, use Git Bash for these commands.

### What you need
- **Git**.
- **Node.js 22 or newer** (npm comes with it).
- **JDK 17 or newer**, with `JAVA_HOME` pointing to it. Android Studio's built-in JDK works: `C:\Program Files\Android\Android Studio\jbr` on Windows. If Gradle needs JDK 17 for compiling and doesn't find it, it downloads it by itself.
- **Android SDK** with `platforms;android-36` and `build-tools;36.0.0`. Installing Android Studio is the easiest way. Then tell Gradle where the SDK is — either set `ANDROID_HOME`, or create `android/local.properties` containing `sdk.dir=/path/to/Android/Sdk`. This file is personal and is never committed.
- **Optional:** an Android phone (Android 8.0 or newer) and a USB cable.

### 1. Get the code
```bash
git clone https://github.com/mayer-doa-coder/Hisab-Final.git
cd Hisab-Final
```

### 2. Server
```bash
cd server
npm ci                 # install exactly the locked versions
npm run build          # compile TypeScript
npm test               # every test must pass, "fail 0"
npm run lint
npm run format:check
npm start              # runs on http://127.0.0.1:3000 — stop with Ctrl+C
```

With the server running, open a second terminal:
```bash
curl http://127.0.0.1:3000/
# {"status":"ok"}

curl http://127.0.0.1:3000/health
# {"code":"AUTH_INVALID"}   ← no token, so it's refused

curl -X POST http://127.0.0.1:3000/auth/login -H "Content-Type: application/json" -d '{"email":"rahim@example.com","password":"correct-horse-1"}'
# {"token":"..."}

curl http://127.0.0.1:3000/health -H "Authorization: Bearer PASTE_TOKEN_HERE"
# {"status":"ok"}
```
`rahim@example.com` is a made-up demo account that lives only in memory while the server runs (`DECISIONS.md` D027). It is not a real account, and it goes away when real user storage arrives.

### 3. Android app
```bash
bash scripts/check-localization.sh     # run from the repository root

cd android
./gradlew assembleDebug                # builds android/app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
./gradlew lintDebug spotlessCheck
```

**On a real phone:**
1. On the phone, open Settings → About phone, and tap **Build number** 7 times. Then open Developer options and turn on **USB debugging**.
2. Plug in the USB cable, and tap **Allow** on the phone's popup.
3. Check that `adb devices` lists the phone as `device`, not `unauthorized`. `adb` is in the SDK's `platform-tools` folder.
4. Run `./gradlew installDebug`, then open **হিসাব** on the phone. It opens in Bangla, even if the phone itself is set to English. Tap **English** to switch.
5. Run `./gradlew connectedDebugAndroidTest` for the on-phone tests. This removes the app when it finishes, so run `./gradlew installDebug` again afterwards.

### 4. CI
Every push to `main` runs the same checks in GitHub Actions (`.github/workflows/ci.yml`): the localization check; Android build, unit tests, and lint; and server build, tests, lint, and format. The on-phone tests are not in CI, because there is no phone there — run them yourself before calling an Android step done.

## Where To Start
1. Read `docs/PRD.md` for what Hisab must do.
2. Read `CURRENT_PHASE.md` for what is in scope right now.
3. Read `docs/PHASE_GUIDE.md` for the build plan — small numbered steps, checked off one at a time, Android and backend together, not backend-then-frontend or the reverse.
