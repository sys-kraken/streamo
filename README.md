# Streamo

A private, ad-free YouTube app for the family. No ads, no account, no tracking.

## What it does

**Watching**
- **No video ads**: videos are fetched directly, so YouTube's ad system is never asked for any
- **Background and screen-off playback**, with lock-screen and headphone controls
- **Pop-up (picture-in-picture)**: press Home while a video plays, or tap *Pop-up*
- **Skips sponsor messages** automatically ([SponsorBlock](https://sponsor.ajay.app)); can be turned off in Settings
- **Resumes where you left off**, with a progress bar on every video you've started
- **Next / Previous** on the player, in the pop-up and on the lock screen; when a video ends, *Up next* plays
- Gear on the video: quality and playback speed. Fullscreen when you rotate the phone; swipe down on the video to shrink it to a mini player
- **Comments** under every video, with replies and the commenters' pictures
- **Like** videos (kept on the phone) and find them again under *You → Liked videos*
- **Downloads** for watching offline, and **playlists** made in Streamo
- **Share to Streamo**: in the YouTube app or a browser, tap *Share*, then choose *Streamo*

**Recommendations that fit each person**

Home's *All* tab is put together on the phone, for whoever uses it, instead of showing everyone the same popular videos.
It learns from what you actually do:
- videos you finish or watch again count for a lot; videos you leave after a few seconds count against
- likes, searches and subscriptions
- what you usually watch at this time of day, and how long your videos usually are
- videos shown to you again and again that you never open
- *Not interested*, *Don't recommend channel*, and topics you remove

Each video on Home says why it's there (*Because you watched…*, *From your subscriptions*, *Something new for you*).
*You → Your interests* shows what Streamo has learned and lets you take things out. *Settings → Recommendations*
sets how many new channels to mix in, or resets everything. Nothing about you leaves the phone.

**History**
- Grouped by day, searchable; remove single videos or clear it all
- *Pause watch history* and *Pause search history*, like YouTube

**Look**
- Pure black (dark) or pure white (light) with no colour accents; follows the phone's theme or pick one in Settings
- Laid out like YouTube: filter chips, big video cards with channel pictures, mini player, *Home / Subscriptions / You* tabs

Subscriptions, history, likes, playlists and settings are all saved on the phone. No Google sign-in.

## Installing on a family phone

1. Download the newest `app-release.apk` from <https://github.com/sys-kraken/streamo/releases>
   (or copy it to the phone over USB, Google Drive, WhatsApp to yourself, etc.).
2. Open it on the phone. Android will ask to allow installing apps from that source: allow it once.
3. Tap **Install**.

Or, with the phone plugged in over USB:

```bash
adb install -r app-release.apk
```

Once installed, later versions can be installed from **Settings → Check for updates**.

## Building

Needs JDK 17 and the Android SDK (both are installed on this PC: Temurin JDK 17 and the SDK in
`%LOCALAPPDATA%\Android\Sdk`).

```bash
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`. A release build takes a few minutes.

Tests:

```bash
./gradlew :shared:testAndroidHostTest
```

These check the recommendations offline and the iPhone's YouTube client against live YouTube, so they need internet.

> **On this PC**, Gradle needs a short temp folder, or it fails with *"Unable to establish loopback connection"*.
> In PowerShell, before running Gradle:
> ```powershell
> $env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Franklin\.jtmp'; $env:TMP='C:\Franklin\.jtmp'; $env:TEMP='C:\Franklin\.jtmp'
> ```
> Create `C:\Franklin\.jtmp` first if it doesn't exist.
>
> Windows Smart App Control blocks the newest Android command-line tools (`android.exe`). If the SDK ever has to
> be reinstalled, use command-line tools version 13114758, whose `sdkmanager` still works.

### The signing key: keep it safe

`keystore/` holds the key that signs Streamo. **Back it up** (e.g. to a USB stick). Android only accepts an
update if it is signed with the same key, so without it you'd have to uninstall Streamo (losing subscriptions
and history) on every phone before installing a new version. Never share it publicly.

## Publishing an update (so phones can update from Settings)

Streamo's **Settings → Check for updates** looks at the newest release on
<https://github.com/sys-kraken/streamo/releases>, downloads its APK and opens Android's installer.
It compares the release's tag with the version inside the installed app.

1. In `app/build.gradle.kts`, raise `versionCode` by 1 and set `versionName` (e.g. `1.2.5`).
2. Build: `./gradlew :app:assembleRelease`
3. On GitHub, create a release with the tag `v1.2.5` (**exactly** `v` + `versionName`) and attach
   `app/build/outputs/apk/release/app-release.apk`. Write what changed in the description; the app shows it.

> If the tag is newer than the `versionName` built into the APK, phones keep offering the same update forever,
> even right after installing it. Always change the version in step 1 *before* building.

The APK **must** be signed with the key in `keystore/`, or phones will refuse to install it over the old one.
`keystore/` and `*.apk` are in `.gitignore`. Never commit the key to the public repo.

## When videos stop playing

YouTube changes its site every now and then, which can break video loading. The fix is almost always to update
the YouTube library: bump `newpipe-extractor` in `gradle/libs.versions.toml` to the newest release from
<https://github.com/TeamNewPipe/NewPipeExtractor/releases>, raise `versionCode` in `app/build.gradle.kts`,
rebuild, and install the new APK over the old one.

## How it's put together

Streamo is a Kotlin Multiplatform project: the screens and most of the logic are shared (Compose Multiplatform),
so an iPhone version can use the same code. Android uses NewPipeExtractor and Media3; the iPhone version
(branch `streamo-ios`, work in progress) talks to YouTube directly and uses Apple's player.

| Part | Where |
|------|-------|
| Screens and app frame (shared) | `shared/src/commonMain/kotlin/com/streamo/app/ui/` (`App.kt`, `Screens.kt`, `WatchScreen.kt`, `Comments.kt`, `TasteScreens.kt`) |
| Black-and-white theme and logo | `shared/.../ui/Theme.kt`, `shared/src/commonMain/composeResources/drawable/logo.png` |
| Recommendations (taste profile, ranking, reasons) | `shared/.../data/Recommender.kt`, `data/Taste.kt`, `ui/HomeFeed.kt` |
| Subscriptions, history, likes, settings (saved on the phone) | `shared/.../data/LibraryStore.kt` |
| Playlists in progress, sponsor skipping | `shared/.../player/PlayQueue.kt`, `shared/.../data/SponsorBlock.kt` |
| YouTube search, videos, channels, comments (Android) | `app/src/main/java/com/streamo/app/data/NewPipeRepo.kt` (uses [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)) |
| Player that keeps running with the screen off, up next, resume | `app/.../player/PlaybackService.kt` (Media3 `MediaSessionService`) |
| Plays YouTube's separate picture and sound files together | `app/.../player/StreamSourceFactory.kt` |
| Updates from GitHub releases | `app/.../data/Updater.kt` |
| iPhone parts | `shared/src/iosMain/`, `iosApp/` |

Licensing note: NewPipeExtractor is GPLv3, so if you ever share Streamo outside the family,
its source code has to be shared too.
