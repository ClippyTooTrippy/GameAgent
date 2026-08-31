# GameAgent

An Android app that watches whatever app you point it at, taps things, and
gets better over time by remembering what worked. This is a *learning*
agent, not a scripted macro bot — nothing in here is hardcoded to a
specific game.

## How it actually works

1. **Sees the screen** — `GameAgentAccessibilityService` uses Android's
   Accessibility API (the same one screen readers use) to read every
   tappable element on screen: its text, position, and type. It also
   scrapes any numbers visible on screen (coins, scores, etc.).
2. **Decides what to do** — `GeminiNanoBrain` asks on-device Gemini Nano
   which candidate action looks most sensible, then checks that choice
   against what's actually worked before. If Nano isn't available on your
   phone, `HeuristicFallbackBrain` takes over automatically — it picks
   whatever action has the best learned track record for this exact
   screen, with 15% random exploration so it keeps discovering new
   options instead of getting stuck.
3. **Learns** — after every action, it checks whether the on-screen
   numbers went up or down since the last action, and stores that as the
   reward for (this screen layout, that action) in a local Room database.
   Over many playthroughs the average reward per action gets more
   accurate — that's the "learning."

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
   in the list, and turn it on (Android will show a scary-looking warning
   about full screen access — that's normal for any accessibility
   service, it's what lets it work at all).
2. Back in GameAgent, type the target app's package name (e.g.
   `com.scopely.monopolygo`) and hit **Start**.
3. Switch to that app. GameAgent will start reading the screen and
   tapping things in the background.
4. Watch the "Learned screen/action pairs" counter on GameAgent's home
   screen grow as it plays.

Finding a package name: Settings → Apps → the app → the package name is
usually shown near the bottom, or search "`<app name>` package name" on
Google Play's web listing URL.

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
