# PaddleOCR-VL Android App

## Overview
An Android application that uses multimodal LLMs (PaddleOCR-VL-1.5) to transcribe text from images and PDF documents. The app runs a local llama-server binary on the Android device for offline OCR processing and outputs results as `.docx` files.

## Tech Stack
- **Language**: Kotlin 1.9.0
- **Platform**: Android (Min SDK 24, Target SDK 35, Compile SDK 35)
- **Build System**: Gradle with Kotlin DSL (build.gradle.kts)
- **UI**: Android XML Layouts with View Binding
- **Networking**: OkHttp 4.12.0 (local server communication)
- **AI Engine**: llama.cpp (llama-server binary in assets)
- **Models**: PaddleOCR-VL-1.5-GGUF from HuggingFace

## Project Structure
```
app/
  src/main/
    assets/llama-server          # Pre-compiled llama-server binary
    java/com/example/ocr/
      MainActivity.kt            # Main UI and workflow orchestration
      OcrEngine.kt               # HTTP calls to local server + .docx generation
      LlamaServerManager.kt      # Manages llama-server process lifecycle
      ModelDownloader.kt         # Downloads GGUF models from HuggingFace
    res/
      layout/activity_main.xml   # Main screen UI
      values/strings.xml         # String resources
      values/themes.xml          # App theme (Material Design)
      xml/file_paths.xml         # FileProvider paths config
      xml/data_extraction_rules.xml  # Android 12+ backup/transfer rules
      mipmap-*/ic_launcher.png   # App icons (all densities)
```

## Build Environment Setup
The Replit environment is configured with:
- **Java**: Temurin JDK 17 (set via JAVA_HOME env var)
- **Android SDK**: Installed at `/home/runner/android-sdk`
  - Platform: android-34
  - Build Tools: 34.0.0
  - Command-line tools: Latest
- **Gradle Wrapper**: 8.2 (gradlew script + gradle/wrapper/)
- **local.properties**: Points to `/home/runner/android-sdk`

## Building the APK
The "Build APK" workflow compiles the debug APK:
```
export ANDROID_HOME=/home/runner/android-sdk && ./gradlew assembleDebug --no-daemon
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

## Model Files (HuggingFace)
The correct filenames in `PaddlePaddle/PaddleOCR-VL-1.5-GGUF`:
- **Main model**: `PaddleOCR-VL-1.5.gguf` (~935 MB)
- **Multimodal projector**: `PaddleOCR-VL-1.5-mmproj.gguf` (~882 MB)

Note: There are no quantized (Q4/Q8) versions in this repo. Both "Standard" modes download the same full-precision files.

## Code Fixes Applied

### Import Fixes
1. **OcrEngine.kt**: Fixed broken `companion object` syntax — closing brace was missing, causing instance methods to be incorrectly scoped inside the companion object.
2. **MainActivity.kt**: Added missing import `kotlinx.coroutines.currentCoroutineContext` and fixed `ensureActive()` call to use `currentCoroutineContext().ensureActive()` since it's called in a suspend function.
3. **Resources**: Created missing mipmap icon files (`ic_launcher.png` / `ic_launcher_round.png`) in all density buckets (mdpi → xxxhdpi).

### Download & URL Fixes
9. **ModelDownloader.kt**: Fixed HTTP 404 errors — updated model URLs to use actual filenames (`PaddleOCR-VL-1.5.gguf`, `PaddleOCR-VL-1.5-mmproj.gguf`) instead of non-existent quantized versions. Added manual redirect handling with User-Agent header for HuggingFace CDN compatibility. Increased timeouts (connect: 30s, read: 60s) for large model files. Added zero-byte file check on re-download.
10. **MainActivity.kt**: Updated `initEngine()` to reference `ModelDownloader.MAIN_MODEL_FILE` / `ModelDownloader.MMPROJ_FILE` constants instead of hardcoded wrong filenames.
11. **AndroidManifest.xml**: Added `android:enableOnBackInvokedCallback="true"` to fix predictive back gesture warning.
12. **activity_main.xml**: Updated radio button labels to remove misleading Q4/Q8 quantization references.

### Logic & Bug Fixes
4. **MainActivity.kt** (`setupButtons`): Removed dead `Intent` variable that was built but never passed to the file picker launcher — the MIME filter was silently ignored.
5. **MainActivity.kt** (`calculateSafeScale`): Parameters `pageWidth`/`pageHeight` were declared but unused. Fixed to actually cap bitmap scale based on page dimensions (max ~50 MB bitmap), preventing OutOfMemoryError on large PDFs.
6. **LlamaServerManager.kt** (`waitForServerReady`): Logic bug where the method didn't properly distinguish between server starting (503) and server ready (200). Also increased timeout from 15 s to 60 s to allow time for large model loading.
7. **app/build.gradle.kts**: Removed `ndkVersion` and `prefab = true` — these triggered NDK requirement without any actual native code, causing CI builds to fail.

### GitHub Actions
8. **`.github/workflows/build-apk.yml`**: Removed broken NDK installation step, removed unnecessary `gradle/actions/setup-gradle` duplication, added `--no-daemon --stacktrace` flags, added `workflow_dispatch` trigger, increased APK artifact retention to 30 days.

### API Compatibility & Lint Fixes (Latest)
13. **`LlamaServerManager.kt`**: Replaced `Process.isAlive()` (API 26+) with `isAliveCompat()` extension helper using `exitValue()` try/catch — works from API 24. Also replaced `InputStream.readNBytes()` (API 33+) with a manual read loop.
14. **`app/build.gradle.kts`**: Updated `compileSdk`/`targetSdk` from 34 → 35 (Android 15). Updated all dependencies to latest stable versions. Added lint `disable` block for non-functional icon warnings.
15. **`AndroidManifest.xml`**: Added `xmlns:tools` namespace + `tools:targetApi="33"` on `<application>` to suppress `UnusedAttribute` lint warning for `enableOnBackInvokedCallback`. Added `android:dataExtractionRules` reference for Android 12+ backup compliance.
16. **`res/xml/data_extraction_rules.xml`**: New file — defines Android 12+ (API 31+) data extraction rules, excluding all domains from cloud backup and device transfer.
17. **`res/values/strings.xml`**: Added 4 missing string resources (`btn_download_model`, `label_select_model_type`, `label_radio_standard`, `label_radio_standard_full`) to fix `HardcodedText` lint warnings.
18. **`res/layout/activity_main.xml`**: Replaced 4 hardcoded text attributes with `@string/` references.
19. **`.github/workflows/build-apk.yml`** (re-fix): Added `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true` env var (Node.js 20 → 24 opt-in, mandatory June 2026). Added `permissions: contents: write` to the `release` job — without this, `softprops/action-gh-release` would fail with 403 when pushing a version tag. Changed artifact `if-no-files-found` from `warn` → `ignore` for lint/test reports.

## App Workflow
1. User taps "Download" to fetch GGUF models from HuggingFace
2. App extracts and launches the bundled `llama-server` binary on port 8080
3. User selects a PDF or image file
4. PDFs are rendered page-by-page using Android's `PdfRenderer`
5. Bitmaps are base64-encoded and sent to `127.0.0.1:8080/completion`
6. Extracted text is compiled into a `.docx` file for sharing

## Hardware / Benchmark Features (Added)

### Device Info Dialog (Toolbar menu → ℹ icon)
Accessible at any time via the toolbar. Shows:
- **Device model & Android version, CPU architecture**
- **CPU**: total core count, big core count used for inference, max frequency (MHz)
- **GPU**: Vulkan availability, number of layers offloaded to GPU (out of 32)
- **Memory**: Total RAM & currently available RAM
- **Inference Config**: CPU threads used, context size (tokens), GPU layers

### OCR Processing Time Display
- PDF processing: live status shows "Page X/Y (Ns elapsed)" during multi-page processing
- After completion: status shows filename and total time taken in seconds

### Key files modified
- `LlamaServerManager.kt`: Added `DeviceReport` data class + `buildDeviceReport()` method
- `MainActivity.kt`: Added `showDeviceInfoDialog()`, timing tracking, updated toolbar menu handler
- `menu_main.xml`: Added Device Info menu item with info icon
- `ic_device_info.xml`: New info icon drawable
- `strings.xml` / `values-bn/strings.xml`: All new UI strings in English + Bengali
