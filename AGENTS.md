# Folio

Android EPUB reader for phones and foldables: Kotlin, Jetpack Compose, one `:app` module.
It lays out and draws pages itself (no WebView), browses OPDS catalogs, and syncs reading
progress with KOReader sync servers such as Grimmory.

## Layout

All under `app/src/main/java/dev/cfbender/folio/`:

- `epub/`: reads the EPUB container, spine, and contents; `PartialMd5` is KOReader's document id.
- `render/`: XHTML and CSS to blocks (`Blocks.kt`, `Css.kt`), blocks to pages (`Paginator.kt`),
  and positions to and from KOReader XPointers (`XPointer.kt`).
- `reader/`: the reading screen. `ReaderViewModel` owns the position and sync, `Geometry.kt`
  decides one page or a two-page spread, `Curl.kt` and `Turning.kt` are the page-turn animation,
  `PagePainter.kt` draws.
- `opds/`, `sync/`: OPDS client and KOReader sync client. No Android dependencies.
- `net/`: `LocalNetworkAccess`, the OkHttp interceptor that asks for Android 17's local network
  permission and explains the timeout a refusal causes.
- `data/`: Room database, book files and covers (`Library`), settings (DataStore).
- `ui/`: library, catalogs, feed, and settings screens.

## Commands

```sh
./gradlew :app:assembleDebug            # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest        # all tests; runs on the JVM with Robolectric, about a minute
./gradlew :app:testDebugUnitTest --tests '*EngineTest*'
./gradlew :app:recordRoborazziDebug     # writes screenshots to app/build/outputs/roborazzi/
./gradlew :app:lintDebug
```

A fresh machine needs JDK 21 and the Android SDK; `.agents/setup` installs both.

## CI and releases

`.github/workflows/ci.yml` runs the tests and lint on pushes to `main` and on pull requests.

`.github/workflows/release.yml` runs the same checks, then builds a signed release APK. Pushing a `v*` tag publishes it as a GitHub Release, with the tag as `versionName` and the workflow run number as `versionCode`. A manual run only keeps the APK as a workflow artifact.

The signing key comes from four repository secrets: `KEYSTORE_BASE64` (the keystore file, base64-encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. Gradle reads the key from `FOLIO_KEYSTORE_FILE`, `FOLIO_KEYSTORE_PASSWORD`, `FOLIO_KEY_ALIAS`, and `FOLIO_KEY_PASSWORD`; without `FOLIO_KEYSTORE_FILE`, `:app:assembleRelease` produces an unsigned APK.

## Tests

- Tests run against the real screens through Compose's test rule under Robolectric with native
  graphics. `TestEpub` builds the book they read; `MockWebServer` stands in for servers.
- `ScreenshotTest` renders screens for a person to look at. Look at the pictures after any change
  to layout or drawing. They are not compared against stored images.
- A page turn is drawn on a canvas, so the words on screen are published as the semantics text
  of the node tagged `PAGE_TAG`; read them with `pageText()`.
- `compose.waitUntil` only pumps the main thread when its condition reads the UI. To wait for
  something off screen, such as a request reaching a server, use `waitOffScreen`.
- Never `runBlocking` a settings write on the test thread while a reader is open: DataStore runs
  the write in the caller's context and the two deadlock. Launch it on `app.container.scope` and
  wait with `waitOffScreen`.
- Robolectric draws only when a capture is taken, and images start loading when first drawn:
  capture once, call `settle()`, then capture the picture to keep.
- Robolectric cannot run the image decoder Coil uses from API 29, so tests that need cover
  images in lists run with `@Config(sdk = [28])`.
- To check against a real Grimmory server, pass `-Dfolio.grimmory.url=…`; tests receive it as the
  system property `folio.grimmory.url`. No committed test uses it yet.

## Conventions

- Book files are stored byte for byte as received. KOReader sync identifies a book by a digest
  of its file, so never rewrite one.
- A reading position is a `Locator` (spine item, block, character). Page numbers change with
  screen size and font, so nothing stores them.
- Every request goes through `AppContainer.http`. A client built elsewhere skips
  `LocalNetworkAccess`, and on Android 17 its requests to a server on the home network time out.
- Unsupported CSS is ignored, not approximated. Add a property to `Css.kt` only with a test in
  `EngineTest`.
