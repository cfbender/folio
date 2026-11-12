# Folio

An EPUB reader for Android that opens like a book when your phone does.

<p align="center">
  <img src="docs/images/page-turn.gif" width="800" alt="A two-page spread of Moby Dick. The right page curls over the spine to the next spread, then back.">
</p>

<table>
  <tr>
    <td><img src="docs/images/dark.png" alt="The spread in the dark theme, mid-turn"></td>
    <td><img src="docs/images/sepia.png" alt="The spread in the sepia theme, with a corner lifting"></td>
  </tr>
</table>

Folded, it is one page at a time, with your library and your OPDS catalogs a tap away.

<table>
  <tr>
    <td><img src="docs/images/phone-turn.png" alt="A single page of Pride and Prejudice curling up from the corner"></td>
    <td><img src="docs/images/phone-controls.png" alt="The reader with its contents and text buttons and the position slider showing"></td>
    <td><img src="docs/images/library.png" alt="The library: a grid of covers with reading progress under each"></td>
    <td><img src="docs/images/catalog.png" alt="An OPDS catalog named Grimmory listing books to read or download"></td>
  </tr>
</table>

It picks up where your other reader stopped, through a KOReader sync server such as [Grimmory](https://github.com/grimmory-tools/grimmory).

<p align="center">
  <img src="docs/images/sync.png" width="400" alt="A banner at the bottom of the page: Kobo is at 31%, with Stay and Jump there buttons">
</p>

## Install

Download the APK from the [latest release](https://github.com/cfbender/folio/releases/latest). It needs Android 8.0 or newer.

## Build

With JDK 21 and the Android SDK:

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # tests
./gradlew :app:lintDebug            # lint
```
