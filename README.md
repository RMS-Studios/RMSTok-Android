# RMSTok (Android)

A minimal Android WebView client that loads **TikTok** and injects **RMSTok**.

It pulls the web bundle (`browser.js` + `browser.css`) at runtime from the
`RMS-Studios/RMSTok` **`devbuild`** GitHub release, so the app itself never
needs rebuilding when the mod updates — just push a new `devbuild`.

## How it works

- Loads `https://www.tiktok.com/` with a desktop user-agent (so TikTok serves the
  desktop layout the mod targets).
- `MainActivity` fetches `browser.js` from the devbuild release and injects it on
  every page start; `browser.css` is injected as a `<link>` on page finish.
- A small JS **bridge** implements RMSTok's settings protocol over the WebView's
  `localStorage` (there's no `chrome.storage` here), so settings persist.
- `shouldInterceptRequest` strips TikTok's `Content-Security-Policy` header so the
  injected script/styles run.

## Build

Open in Android Studio and Run, or from the command line:

```
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Requirements for it to actually work

`RMS-Studios/RMSTok` must be **public** and have a **`devbuild`** release
containing `browser.js` and `browser.css`. While the repo is private, GitHub
returns 404 for the unauthenticated download and the app loads plain TikTok with
a "couldn't load the mod bundle" toast.

- Config: `JS_URL` / `CSS_URL` in `MainActivity.java`.
- Package: `com.rmstudios.rmstok` · minSdk 26 · targetSdk 33.
