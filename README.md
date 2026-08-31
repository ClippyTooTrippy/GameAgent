# GameAgent

An Android app that watches whatever app you point it at, taps things, and
gets better over time by remembering what worked. This is a *learning*
agent, not a scripted macro bot — nothing in here is hardcoded to a
specific game.

## How it actually works

1. **Sees the screen** — `VisionCaptureService` captures real screen pixels
   via Android's `MediaProjection` API (a system screen-recording permission
   you grant once per session). Works on any app - native menus, a game's
   rendered canvas, even video - because a screenshot doesn't care what drew
   it. On-device OCR (ML Kit Text Recognition) reads visible text and its
   position; a generic grid of tappable regions covers everything else
   (icons, sprites, unlabeled buttons) so nothing is invisible to it.
2. **Decides what to do** — two very different modes, depending on whether
   you've set an API key:
   - **With a key** (`CloudVisionBrain`): the actual screenshot is sent to
     Google's Gemini vision-language model along with the candidate actions
     and your instruction, and it picks one with a real one-line reason.
     This is genuine reasoning about what's on screen, not pattern
     averaging.
   - **Without a key** (`HeuristicFallbackBrain`): picks whatever action has
     the best learned track record for this exact screen, with 15% random
     exploration. Fully local, fully private, free - and noticeably dumber.
   - A simple keyword match against your typed/spoken instruction sits
     between the two as a fallback if the cloud call fails on a given
     frame.
3. **Learns** — regardless of which brain picked the action, every outcome
   (did the score go up or down) gets recorded in a local Room database
   keyed by screen layout + action. This happens either way, so even cloud
   mode builds real local memory over time.

`GameAgentAccessibilityService` is just the "hands" now - it dispatches
taps/swipes/back/home (only an AccessibilityService can inject touch input
without root) and tracks which app is in the foreground.

### Setting up real AI reasoning

1. Go to https://aistudio.google.com/apikey and generate a free API key
   (Google's free tier covers this kind of light use, but check current
   limits/pricing yourself since that can change).
2. Paste it into the "Real AI reasoning" field in the app, tap Save key.
3. Restart vision mode if it was already running.

Without a key, everything works exactly as it did before - fully offline,
nothing ever leaves your phone. With a key, screenshots of whatever app
you point it at (never excluded apps - those are never even captured) get
sent to Google's API to be analyzed. That trade-off is the whole point:
real understanding of the screen, at the cost of it no longer being fully
local for the app(s) you're actually running it against.

## What's real here vs. what to expect

- This **is** genuinely adaptive: it builds real experience specific to
  the app you run it on, and its choices change based on outcomes, not
  just pattern-matched clicks.
- This is **not** a large language model training itself on your phone.
  Gemini Nano is a small, fixed, pre-trained model — it supplies
  judgement, not learning. The learning happens in the lightweight
  action-value table (`memory/`), which is closer to a simple
  reinforcement-learning bandit than to an LLM.
- **Gemini Nano on-device availability is limited** — currently Pixel 9/10
  and a handful of Samsung Galaxy S24+ devices on the right OS build. If
  your phone doesn't support it, the app still works, just using the
  fallback brain (which is dumber but perfectly functional and still
  learns).
- The `askNano()` function in `GeminiNanoBrain.kt` is left as a stub with
  a TODO, because the on-device GenAI API surface has been changing
  through 2026. Check https://developer.android.com/ai/aicore for the
  current API before filling it in — everything else in the app talks to
  the `DecisionEngine` interface, so that's the only file you need to
  touch.

## Building it

You need Android Studio (free, from developer.android.com) — this can't
be compiled to an APK from a plain text editor.

1. Open Android Studio → **Open** → select the `GameAgent` folder.
2. Let Gradle sync (first time will download dependencies — needs
   internet access to `dl.google.com` and Maven Central).
3. Connect your phone via USB with Developer Options + USB debugging on,
   or use an emulator.
4. Click **Run**.

## Free-roam mode with exclusions

GameAgent now roams the whole phone by default - it's not locked to one
app. Each app gets its own learned action table automatically (the
screen fingerprint includes the package name), so experience in one app
never bleeds into another.

Instead of confining it to one app, you give it a **blocklist**: apps it
must never act inside at all - banking apps, messaging, anything
sensitive. This is a hard boundary, checked before anything else every
frame: the moment the excluded app is in the foreground, GameAgent
doesn't capture, doesn't OCR, doesn't tap - it just presses Home and
leaves. It's the safer default when the agent is roaming freely rather
than staying inside one app you're watching closely.

Comma-separate multiple packages in the field, e.g.
`com.yourbank.app,com.whatsapp,com.google.android.apps.messaging`.

## Using it

1. Launch GameAgent, tap **Enable Accessibility Service**, find "GameAgent"
   in the list, and turn it on. If it's greyed out saying "Controlled by
   Restricted Setting" (Android blocks this by default for sideloaded
   apps), go to Settings → Apps → GameAgent → three-dot menu → **Allow
   restricted settings**, then come back and enable it.
2. Type the target app's package name (e.g. `com.scopely.monopolygo`) and
   hit **Start vision mode on target app**.
3. Android will show a system dialog asking to allow screen capture/
   recording — approve it. This is normal and required; it's how the app
   sees the screen. You'll get a persistent notification while it's
   running (Android requires this for any app capturing the screen).
4. Switch to the target app. GameAgent starts reading the screen roughly
   once a second and tapping things.
5. Watch the "Learned screen/action pairs" counter grow.

Set GameAgent's battery mode to **Unrestricted** (Settings → Apps →
GameAgent → Battery) so Android doesn't kill it in the background.

## Things worth knowing before you run this on a real account

- **Terms of service**: most mobile games explicitly ban bots/automation
  in their ToS. Running this against Monopoly Go, Freecash, etc. on your
  main account risks a ban. Consider a throwaway account first.
- **It will misclick.** Especially early on, before it's learned
  anything, expect it to tap ads, open menus it can't get out of, or sit
  there doing nothing useful. Keep an eye on it, don't leave it running
  fully unsupervised for the first few sessions.
- **Reward signal is crude.** It only knows "the biggest number on
  screen went up = good." Games where the important progress isn't shown
  as a number (e.g. board position) will confuse it. You can extend
  `readScreen()` in `GameAgentAccessibilityService.kt` to parse specific
  things (like a particular coin counter's `viewIdResourceName`) for a
  cleaner signal, once you've inspected that game's layout.
- **Battery/data.** Nano runs on-device so no data use for decisions, but
  the accessibility service polls the screen continuously while running.

## Project layout

```
app/src/main/java/com/joel/gameagent/
  MainActivity.kt                     - start/stop UI
  GameAgentAccessibilityService.kt    - reads screen, dispatches taps, drives the loop
  model/ScreenState.kt                - what the agent "sees"
  brain/DecisionEngine.kt             - interface
  brain/HeuristicFallbackBrain.kt     - works everywhere, no LLM needed
  brain/GeminiNanoBrain.kt            - Nano-backed, needs the TODO filled in
  memory/                             - Room DB storing learned action values
```
