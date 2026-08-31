# GameAgent

An Android app that watches whatever app you point it at, taps things, and
gets better over time by remembering what worked. This is a *learning*
agent, not a scripted macro bot — nothing in here is hardcoded to a
specific game.

## How it actually works (vision mode)

1. **Sees the screen** — `VisionCaptureService` captures real screen pixels via
   Android's `MediaProjection` API (a system screen-recording permission you
   grant once per session). This works on *any* app — native menus, a Unity
   game's rendered canvas, even a live video stream — because a screenshot
   doesn't care what drew it. On-device OCR (ML Kit Text Recognition) reads
   any visible text and its position. Anything without text — icons,
   sprites, arbitrary buttons — is still covered by a generic grid of
   tappable regions laid over the whole screen, so the agent can learn to
   tap things it has no label for at all.
2. **Decides what to do** — same as before: `GeminiNanoBrain` asks on-device
   Gemini Nano which candidate looks sensible, checked against what's
   worked before; falls back to `HeuristicFallbackBrain` (best learned
   action + 15% random exploration) on phones without Nano.
3. **Learns** — after every action, checks whether the biggest number OCR
   found on screen went up or down, and updates the (screen layout, action)
   reward table in a local Room database.

The old accessibility-tree reading (`GameAgentAccessibilityService`) is
still there, but now only as the "hands" — it dispatches the actual taps
and swipes, since only an AccessibilityService can inject touch input
without root — plus it cheaply tracks which app is in the foreground.

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
