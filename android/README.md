# Android reader

The Java APK targets Android 4.4 (API 19), the Faust 3's `armeabi-v7a` ABI, and uses MuPDF `fitz` 1.15.0 to read PDF and reflowable EPUB documents. The Android SDK API 35 is used for compilation.

The library scans available shared-storage roots for valid PDF and EPUB files. Documents can also be opened through Android `ACTION_VIEW` or the file picker. The library header reads “Библиотека”. The reader copies an opened document to private cache without changing the source, renders the requested page, and stores reading progress in `SharedPreferences`. EPUB layout uses MuPDF's reflowable-document API; EPUB-only A-/A+ controls change the font size and reflow the current location, with the selected size saved separately for each book. Per-book page bookmarks persist separately from the last reading position; the upper-right flag opens a left-side bookmark panel where the current page can be added or removed and saved locations reopened. Tapping an EPUB image opens a full-screen enlargement; tapping again closes it.

For PDF, the original page is shown first. After it appears, a background task processes every source page sequentially: it rasterizes each page at up to 120 DPI (bounded to 3 megapixels and 2048 pixels per dimension), passes it to the bundled k2pdfopt JNI library, and caches the resulting PNG pages. The cache is capped at 1 GiB. The reader footer shows a stationary filled dot and the percentage of source pages processed; both disappear together when processing reaches 100%. If adaptation fails, the original page remains available without a conversion error dialog. Full-book processing and its progress indicator were smoke-tested with a synthetic four-page PDF; representative real books, performance, and readability across document types have not yet been validated.

On the connected Faust 3, synthetic tests verified:

- EPUB rendering, page navigation, and restoration at page 2 after closing and reopening.
- On a synthetic EPUB with an embedded image, tapping the image opened a full-screen enlargement and a subsequent tap closed it. A page bookmark survived an app-process restart and reopened the bookmarked page.
- The original PDF page appears before background conversion completes.
- Native k2pdfopt conversion and cached page generation for a synthetic two-page PDF; each source page produced five adapted pages, and navigation continued from source page 1 to source page 2.
- Full-book conversion progress was observed at 0% and 25% for a synthetic four-page PDF; the progress indicator disappeared on completion, and page navigation remained responsive during conversion.
- Restoration at an adapted PDF page after restarting the app.

These are smoke tests only. No personal books were opened. Real-book quality, memory/battery use, conversion latency, physical page keys, the device-specific E-Ink refresh API, password-protected PDFs, and behavior on older Android versions remain unverified.

Build from the project root:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\android\gradlew.bat -p .\android assembleDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Install or update it with:

```powershell
adb install -r .\android\app\build\outputs\apk\debug\app-debug.apk
```

`android.overridePathCheck=true` is set because the workspace path contains non-ASCII characters on Windows.

MuPDF's `fitz` 1.15.0 AAR declares AGPL-3.0. k2pdfopt's vendored `LICENSE` states GPLv3, while some source files carry additional or differing license notices; review the exact notices and obligations for both components before distributing the APK. This build packages only `armeabi-v7a`.
