# Streamo

A private, ad-free YouTube app for the family. No ads, no account, no tracking.

- **Background and screen-off playback**, with lock-screen and headphone controls
- **Pop-up (picture-in-picture)**: press Home while a video plays, or tap *Pop-up*
- **No video ads**: videos are fetched directly, so YouTube's ad system is never asked for any
- **Skips sponsor messages** automatically ([SponsorBlock](https://sponsor.ajay.app)); can be turned off in Settings
- **Subscriptions and history** saved on the phone, no Google sign-in
- **Share to Streamo**: in the YouTube app or a browser, tap *Share*, then choose *Streamo*
- **Next / Previous** on the player, in the pop-up, and in the notification / lock screen; when a video ends the first "up next" video plays
- Gear on the video: quality and playback speed. Fullscreen when you rotate the phone; swipe down on the video to shrink it to a mini player

## Installing on a family phone

1. Copy `Streamo-1.0.0.apk` to the phone (USB, Google Drive, WhatsApp to yourself, etc.).
2. Open it on the phone. Android will ask to allow installing apps from that source: allow it once.
3. Tap **Install**.

Or, with the phone plugged in over USB:

```bash
adb install -r Streamo-1.0.0.apk
```

## Building

Needs JDK 17 and the Android SDK (both already set up on this PC).

```bash
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`.

> On this PC, Java needs a short temp folder, or Gradle fails with *"Unable to establish loopback connection"*:
> `set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\gtmp -Djava.io.tmpdir=C:\gtmp`

### The signing key: keep it safe

`keystore/` holds the key that signs Streamo. **Back it up** (e.g. to a USB stick). Android only accepts an
update if it is signed with the same key, so without it you'd have to uninstall Streamo (losing subscriptions
and history) on every phone before installing a new version. Never share it publicly.

## Publishing an update (so phones can update from Settings)

Streamo's **Settings → Check for updates** looks at the newest release on
<https://github.com/sys-kraken/streamo/releases>, downloads its APK and opens Android's installer.

1. In `app/build.gradle.kts`, raise `versionCode` by 1 and set `versionName` (e.g. `1.1.0`).
2. Build: `./gradlew :app:assembleRelease`
3. On GitHub, create a release with the tag `v1.1.0` (matching `versionName`) and attach
   `app/build/outputs/apk/release/app-release.apk`. Write what changed in the description; the app shows it.

The APK **must** be signed with the key in `keystore/`, or phones will refuse to install it over the old one.
`keystore/` and `*.apk` are in `.gitignore`. Never commit the key to the public repo.

## When videos stop playing

YouTube changes its site every now and then, which can break video loading. The fix is almost always to update
the YouTube library: bump `newpipe-extractor` in `gradle/libs.versions.toml` to the newest release from
<https://github.com/TeamNewPipe/NewPipeExtractor/releases>, raise `versionCode` in `app/build.gradle.kts`,
rebuild, and install the new APK over the old one.

## How it's put together

| Part | File |
|------|------|
| YouTube search, videos, channels, explore lists | `data/YouTubeRepo.kt` (uses [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)) |
| Player that keeps running with the screen off | `player/PlaybackService.kt` (Media3 `MediaSessionService`) |
| Plays YouTube's separate picture and sound files together | `player/StreamSourceFactory.kt` |
| Subscriptions, history, settings (saved on the phone) | `data/LibraryStore.kt` |
| Sponsor skipping | `data/SponsorBlock.kt` |
| Screens | `ui/` and `MainActivity.kt` |

Licensing note: NewPipeExtractor is GPLv3, so if you ever share Streamo outside the family,
its source code has to be shared too.
