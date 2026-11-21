# ✨ Wingman for Android

A floating ✨ bubble that appears over Tinder, Bumble, Hinge, OkCupid, Aisle, TrulyMadly, Happn and Badoo. It **reads the profile as you scroll**, or the chat when you open one. When you stop scrolling, it writes opening lines or replies with Claude.

- Profile → 4 openers, each reacting to a different detail (prompt answer, interest, photo caption)
- Chat → 4 replies that fit the conversation
- Vibes: Natural, Playful, Flirty, Witty, Question, Thoughtful, Ask out, Wind down
- **Copy**, or **Put in chat box** (fills the app's message box). You always press send yourself.

## Build the APK (free, no computer needed)
Every push to GitHub builds the app automatically (`.github/workflows/build.yml`).
1. Put this folder in a GitHub repository.
2. Open the repo's **Actions** tab and wait about 5 minutes for "Build APK" to go green.
3. Open **Releases → Wingman (latest build)** on your phone and download `Wingman.apk`.

(Or open the folder in Android Studio and press Run.)

## Install & set up
1. Open `Wingman.apk` and allow "install unknown apps" for your browser when asked.
2. Open **Wingman**, paste your Claude API key (console.anthropic.com), add a line about yourself and tap **Save**.
3. Tap **Turn on Wingman** → Installed apps → Wingman → On.
   - If Android says **"Restricted setting"**: go back to Wingman, tap **Open App info → ⋮ (top right) → Allow restricted settings**, then turn it on.
4. Open your dating app. The ✨ bubble appears; drag it wherever you like.

Bubble states: ✨ reading · … writing · **4** = suggestions ready · ! = problem (tap to see).

## Notes
- The **Haiku** model is the default: fast and cheap, good for auto mode. Turn off "Auto-suggest" if you only want suggestions when you tap.
- Wingman works out who said what from bubble position: yours on the right, theirs on the left. Tap **What it read** to check.
- Privacy: only the dating app's on-screen text is sent to Claude (Anthropic), to write suggestions. Nothing is stored or sent anywhere else. Your key stays on the phone.
- Dating apps can change their screens. If one stops reading well, "What it read" shows what Wingman sees.
