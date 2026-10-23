# Earful

YouTube, as sound only. No ads, no Shorts, no account.

## Install

Copy `Earful.apk` to your phone and open it (allow "Install unknown apps" for your file manager
when Android asks). Android 7.0 or newer.

## Build it yourself

- **Android Studio:** open this folder, press Run.
- **No computer setup:** push the folder to a GitHub repository; the included workflow
  (`.github/workflows/build.yml`) builds the APK. Download it from the run's *Artifacts*.
- **Command line:** `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

## Automatic updates (keeps working when YouTube changes)

YouTube changes its pages every few weeks. The NewPipe team fixes their YouTube reader within
days, and this project can pick that fix up **by itself**:

1. Every day, `.github/workflows/update.yml` checks the reader version the NewPipe app uses.
2. If it's newer, it switches to it, builds a signed APK and publishes it as a GitHub Release.
3. The app checks for a new release (every 6 hours, or immediately when a video fails in a way
   that looks like "YouTube changed"). Home then shows **Update ready · Install**: one tap to
   download, one tap on Android's confirmation. Android never lets an app update itself silently.

### One-time setup (about 10 minutes, free)

1. Create a free account on https://github.com and a new **public** repository (e.g. `earful`).
   Public is needed so the app can see the releases; your signing key stays secret (step 3).
2. Upload everything in this folder to the repository (drag and drop on the repository page
   works). Do **not** upload the `signing` folder if you have one.
3. In the repository: **Settings → Secrets and variables → Actions → New repository secret**.
   Name: `EARFUL_KEYSTORE_BASE64`. Value: the whole content of `earful-signing-key.txt`
   (sent separately). This is the key every Earful APK so far was signed with, so updates install
   over the app you already have, keeping your saved videos.
4. Open the **Actions** tab, choose **Update & release**, press **Run workflow**.
5. When it finishes (~5 min), open **Releases**, download `Earful-101.apk` (or similar) on your
   phone and install it once. From then on, updates appear inside the app.

If nothing happens for a while, the reader is simply up to date: the daily check only builds when
there's something new. You can always press **Run workflow** to build right away.

## How it works

| | |
|---|---|
| Source | Reads youtube.com directly with NewPipeExtractor (the engine behind NewPipe). No Google ad or tracking endpoints are ever contacted, so there's nothing to block. |
| Audio | Picks the smallest stream that still sounds good: ~70 kbps Opus (≈ 0.5 MB/min, sounds like 128 kbps AAC). Video is never downloaded. |
| Offline-safe | On tap, the **whole** audio file downloads in 1 MB pieces while it already plays. Lose signal after that and nothing stops. A dropped download resumes by itself. |
| Storage | Audio lives in the app's cache. Leaving the app with a video open keeps the queue, your position and the current + next audio, so you resume instantly (even offline). Closing the video (swipe the mini player away) or starting a video from outside the queue deletes all of it. "Clear cache" also deletes the audio. Thumbnails are capped at 40 MB. |
| Battery | Audio-only decoder, playback reads from disk in big rare batches, the UI stops updating the moment it's off screen, and the network goes idle once a track is saved. |
| Sponsor skipping | Sponsor reads and self-promotion inside videos are skipped automatically, to the second, using [SponsorBlock](https://sponsor.ajay.app) (community-marked sections, data licensed CC BY-NC-SA 4.0). Only a 4-character hash prefix of the video id is sent. Skipped sections show in yellow on the progress bar; every skip offers Undo. |
| Background | A media session keeps playing with the app closed or the screen off, with a notification and lock-screen controls (−10 s, play/pause, +10 s, draggable progress, artwork). |

## Gestures on the picture

| | Left | Middle | Right |
|---|---|---|---|
| Top half, double-tap | −10 s | play / pause | +10 s |
| Bottom half, double-tap | −5 s | play / pause | +5 s |

Keep tapping the same side after a double-tap to keep skipping. Works after the track has
finished too (there is no replay button by design).

Player: drag down (or Back) to minimise. Mini player: tap or swipe up to open, swipe down to close.

## Design notes

- **One continuous surface.** The mini player *becomes* Now Playing; the artwork physically travels
  between them. Spatial continuity means the eye never has to re-find anything.
- **Thumb zone (Fitts's law).** Every control in Now Playing sits in the lower half; targets are
  ≥ 44 dp even when the glyph is small (the scrubber is a 6 dp bar inside a 32 dp touch strip).
- **Fewer choices (Hick's law).** Two tabs. No settings screen. One button to save.
- **Instant response (Doherty threshold, < 400 ms).** Tapping a video opens the player immediately
  with what's already known (title, picture) while the audio is fetched.
- **State without reading (pre-attentive cues).** Artwork shrinks when paused and springs back on
  play; the scrubber shows how much is already safe offline; a status line says
  "Available offline" — the one anxiety this app exists to remove.
- **Familiar where it helps (Jakob's law).** Home looks like YouTube; Now Playing like Apple Music.
- **Feedback for every gesture.** Ripples, running "+20 s" totals and light haptic ticks.
- **Motion with mass.** Springs, never linear easing; predictive Back drags the player with your thumb.
- **Teach once, then get out of the way.** The double-tap zones and the swipe-to-close gesture are
  each shown exactly one time.
