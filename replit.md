# PaddleOCR-VL Android App

## Overview
An Android application that uses multimodal LLMs (PaddleOCR-VL-1.5) to transcribe text from images and PDF documents. The app runs a local llama-server binary on the Android device for offline OCR processing and outputs results as `.docx` files.

## Tech Stack
- **Language**: Kotlin 1.9.0
- **Platform**: Android (Min SDK 24, Target SDK 34)
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

## Code Fixes Applied

### Import Fixes
1. **OcrEngine.kt**: Fixed broken `companion object` syntax — closing brace was missing, causing instance methods to be incorrectly scoped inside the companion object.
2. **MainActivity.kt**: Added missing import `kotlinx.coroutines.currentCoroutineContext` and fixed `ensureActive()` call to use `currentCoroutineContext().ensureActive()` since it's called in a suspend function.
3. **Resources**: Created missing mipmap icon files (`ic_launcher.png` / `ic_launcher_round.png`) in all density buckets (mdpi → xxxhdpi).

### Logic & Bug Fixes
4. **MainActivity.kt** (`setupButtons`): Removed dead `Intent` variable that was built but never passed to the file picker launcher — the MIME filter was silently ignored.
5. **MainActivity.kt** (`calculateSafeScale`): Parameters `pageWidth`/`pageHeight` were declared but unused. Fixed to actually cap bitmap scale based on page dimensions (max ~50 MB bitmap), preventing OutOfMemoryError on large PDFs.
6. **LlamaServerManager.kt** (`waitForServerReady`): Logic bug where the method didn't properly distinguish between server starting (503) and server ready (200). Also increased timeout from 15 s to 60 s to allow time for large model loading.
7. **app/build.gradle.kts**: Removed `ndkVersion` and `prefab = true` — these triggered NDK requirement without any actual native code, causing CI builds to fail.

### GitHub Actions
8. **`.github/workflows/build-apk.yml`**: Removed broken NDK installation step, removed unnecessary `gradle/actions/setup-gradle` duplication, added `--no-daemon --stacktrace` flags, added `workflow_dispatch` trigger, increased APK artifact retention to 30 days.

## App Workflow
1. User taps "Download" to fetch GGUF models from HuggingFace
2. App extracts and launches the bundled `llama-server` binary on port 8080
3. User selects a PDF or image file
4. PDFs are rendered page-by-page using Android's `PdfRenderer`
5. Bitmaps are base64-encoded and sent to `127.0.0.1:8080/completion`
6. Extracted text is compiled into a `.docx` file for sharing
