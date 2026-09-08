<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />

  <h3>An independent Nuvio TV fork with Seekr-powered seek-preview thumbnails</h3>

  <p>
    Bring your own sources and see a live preview frame while scrubbing through playback,<br />
    powered by the <a href="https://seekr.tv">seekr.tv</a> preview service.
  </p>

  <p>
    <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV-3DDC84?logo=android&logoColor=white" />
    <img alt="Built with" src="https://img.shields.io/badge/built%20with-Kotlin%20%7C%20Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" />
    <img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-blue" />
    <a href="https://github.com/AKhalil609/NuvioTV/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/AKhalil609/NuvioTV?include_prereleases&label=release" /></a>
  </p>

  <p>
    <a href="https://github.com/AKhalil609/NuvioTV/releases/latest"><strong>Download</strong></a> ·
    <a href="https://seekr.tv"><strong>Get a Seekr API key</strong></a> ·
    <a href="https://github.com/NuvioMedia/NuvioTV">Upstream project</a>
  </p>

</div>

---

## About this fork

This is an independent fork of [Nuvio TV](https://github.com/NuvioMedia/NuvioTV) created to demo integration with
[Seekr](https://seekr.tv), a service that generates seek-preview thumbnail sprites for video content. Everything else
about Nuvio TV — bring-your-own-sources, TV-first Compose UI, Media3 playback — comes from the upstream project. The
seek-preview feature and this README are the additions made in this fork.

> **Note:** Because this is an unofficial fork, users cannot log in to a Nuvio User account here — that
> requires the official app from the [upstream project](https://github.com/NuvioMedia/NuvioTV).

## Features

- **Seek preview thumbnails** — see a frame preview while scrubbing through a video, instead of a blank seek bar.
- **Preview Sync** — a manual nudge control to correct the thumbnail-to-frame offset if a source's preview timing
  drifts from the actual video, so what you see in the thumbnail matches what you land on.
- Everything from upstream Nuvio TV: bring-your-own-sources, Jetpack Compose TV UI, Media3 playback, and more.

## Screenshots

| Add your Seekr API key | Seek preview thumbnail | Preview Sync correction |
| :---: | :---: | :---: |
| _Settings → Playback → General → Seek Preview Thumbnails_ | _Scrubbing shows a live preview frame_ | _Nudge the thumbnail to match the frame behind it_ |
| <img width="1920" height="1080" alt="Screenshot_20260908_100532" src="https://github.com/user-attachments/assets/3e3b38d4-ad27-42b9-8140-d58d96f8abd9" /> | <img width="1920" height="1080" alt="Screenshot_20260907_210517" src="https://github.com/user-attachments/assets/78136f97-b45f-40f3-82b1-9da5f593dda3" /> | <img width="1920" height="1080" alt="Screenshot_20260907_210612" src="https://github.com/user-attachments/assets/21244845-abdd-4416-a723-94e90799b8eb" /> |

> Screenshots pending — see [Demo](#demo) below for the currently available captures.

### Demo

Seekr preview

<img width="1920" height="1080" alt="Screenshot_20260907_210517" src="https://github.com/user-attachments/assets/f14606be-4bbc-4835-99ad-4ec051b9cf00" />

Seekr Sync

<img width="1920" height="1080" alt="Screenshot_20260907_210612" src="https://github.com/user-attachments/assets/f4df254b-5369-40bb-88ab-e1443d813782" />

## Getting a Seekr API key

Seek preview thumbnails require a free API key from [seekr.tv](https://seekr.tv).

1. Go to [seekr.tv](https://seekr.tv) and sign up.
2. Copy your API key.
3. In Nuvio TV, open **Settings → Playback → General → Seek Preview Thumbnails** and paste the key.

> **Note:** Seekr key capacity is limited due to hosting resources. If keys are currently unavailable, you'll be
> added to a waitlist and notified once one frees up.

If a preview thumbnail looks slightly out of sync with the video underneath it, open **Preview Sync** during
playback and nudge left/right until the thumbnail matches the frame — see the [Demo](#demo) screenshots above.

## Get the app

- [Download the latest Android TV APK](https://github.com/AKhalil609/NuvioTV/releases/latest)

## Build from source

```bash
git clone https://github.com/AKhalil609/NuvioTV.git
cd NuvioTV
./gradlew :app:assembleFullDebug
```

Nuvio TV is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, a JDK, and the Android SDK.

## Credits

- [Nuvio TV](https://github.com/NuvioMedia/NuvioTV) — the upstream project this fork is based on.
- [Seekr](https://seekr.tv) — seek-preview thumbnail generation service.

## License

[GNU General Public License v3.0](./LICENSE)
