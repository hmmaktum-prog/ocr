# PaddleOCR-VL Android App

অফলাইন AI দিয়ে ছবি ও PDF থেকে টেক্সট বের করার Android অ্যাপ।  
ইন্টারনেট ছাড়াই কাজ করে — সব processing সরাসরি ফোনে হয়।

---

## অ্যাপ সম্পর্কে

| বিষয় | তথ্য |
|-------|------|
| **অ্যাপের নাম** | PaddleOCR-VL |
| **Package ID** | `com.example.ocr` |
| **Version** | 1.0 (versionCode 1) |
| **ন্যূনতম Android** | 9.0 (API 28) |
| **Target Android** | 15 (API 35) |
| **আর্কিটেকচার** | arm64-v8a |
| **AI Model** | PaddleOCR-VL-1.5-GGUF |
| **Inference Engine** | llama.cpp `b8683` |

### কী কী করতে পারে
- ছবি (JPG, PNG, WebP) থেকে টেক্সট বের করে
- PDF ফাইল page-by-page প্রসেস করে
- বের করা টেক্সট `.docx` ফাইলে সেভ করে
- সম্পূর্ণ offline — কোনো ডেটা server-এ যায় না

### প্রয়োজনীয়তা
- Android 9.0+ ফোন (2018 বা তার পরের যেকোনো ফোন)
- RAM: কমপক্ষে 4GB (8GB+ সুপারিশ করা হয়)
- Storage: ~2GB খালি জায়গা (model download-এর জন্য)
- প্রথমবার চালু করলে ~1.8GB model download হবে

---

## APK ডাউনলোড ও ইনস্টল

1. উপরের **Releases** সেকশন থেকে সর্বশেষ `app-release.apk` ডাউনলোড করুন
2. ফোনে **"Unknown sources" / "Install unknown apps"** চালু করুন
3. APK ইনস্টল করুন
4. প্রথম চালুতে model download হবে (WiFi-তে করুন)

---

## APK Signing তথ্য

> এই section-টি developers-দের জন্য। ব্যবহারকারীদের এটি জানার দরকার নেই।

Release APK স্বয়ংক্রিয়ভাবে sign হয় GitHub Actions-এ।  
**কোনো manual signing করতে হবে না।**

| বিষয় | মান |
|-------|-----|
| **Keystore file** | GitHub Secret: `KEYSTORE_BASE64` (base64 encoded) |
| **Keystore password** | `PaddleOCR2024` — GitHub Secret: `KEYSTORE_PASSWORD` |
| **Key alias** | `paddleocr` — GitHub Secret: `KEY_ALIAS` |
| **Key password** | `PaddleOCR2024` — GitHub Secret: `KEY_PASSWORD` |
| **Algorithm** | RSA 2048-bit |
| **Validity** | 10,000 দিন |
| **Certificate** | CN=PaddleOCR VL App, OU=Android, O=PaddleOCR, L=Dhaka, ST=Dhaka, C=BD |

### Keystore হারিয়ে গেলে / নতুন করতে হলে

```bash
keytool -genkey -v \
  -keystore paddleocr-release.jks \
  -alias paddleocr \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "PaddleOCR2024" \
  -keypass "PaddleOCR2024" \
  -dname "CN=PaddleOCR VL App, OU=Android, O=PaddleOCR, L=Dhaka, ST=Dhaka, C=BD"
```

তারপর base64 করে GitHub Secret-এ দিন:
```bash
base64 -w 0 paddleocr-release.jks
```
এই output-টি `KEYSTORE_BASE64` secret-এ paste করুন।

---

## CI/CD — GitHub Actions

### Workflow কীভাবে কাজ করে

`main` branch-এ push করলেই স্বয়ংক্রিয়ভাবে:

```
push to main
    │
    ▼
1. Keystore setup (GitHub Secret থেকে)
    │
    ▼
2. llama-server binary (arm64, static, CPU-only)
    ├─ এই repo-র Release-এ আছে? → ডাউনলোড করো (~1 মিনিট)
    └─ নেই? → Source থেকে build করো (~15-20 মিনিট) → Release-এ upload করো
    │
    ▼
3. APK Build
    ├─ Debug APK (test করার জন্য)
    └─ Signed Release APK (বিতরণের জন্য)
    │
    ▼
4. Artifacts হিসাবে সেভ (30 দিন)
```

**`v*` tag push করলে** GitHub Release-ও তৈরি হয়:
```bash
git tag v1.0
git push origin v1.0
```

### GitHub Secrets (ইতিমধ্যে সেট করা আছে)

| Secret নাম | বিষয় |
|------------|-------|
| `KEYSTORE_BASE64` | Keystore ফাইল (base64) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

> এই secrets ইতিমধ্যে GitHub-এ upload করা আছে। নতুন করে কিছু করতে হবে না।

---

## Local Build (Developer)

```bash
# Prerequisites: Android Studio, JDK 17

git clone https://github.com/hmmaktum-prog/ocr.git
cd ocr

# Debug build (signing ছাড়া)
./gradlew assembleDebug

# Signed Release build (keystore লাগবে)
export KEYSTORE_PATH=/path/to/paddleocr-release.jks
export KEYSTORE_PASSWORD="PaddleOCR2024"
export KEY_ALIAS="paddleocr"
export KEY_PASSWORD="PaddleOCR2024"
./gradlew assembleRelease
```

**নোট:** llama-server binary ছাড়া APK build হবে কিন্তু app কাজ করবে না।  
CI থেকে APK নামান অথবা নিজে llama-server cross-compile করুন।

---

## প্রজেক্ট কাঠামো

```
ocr/
├── app/
│   ├── src/main/java/com/example/ocr/
│   │   ├── MainActivity.kt          — মূল UI ও logic
│   │   ├── LlamaServerManager.kt    — llama-server চালু/বন্ধ
│   │   ├── ModelDownloader.kt       — HuggingFace থেকে model download
│   │   └── OcrEngine.kt             — HTTP দিয়ে OCR request
│   ├── src/main/jniLibs/arm64-v8a/
│   │   └── libllama_server.so       — Android arm64 binary (CI build-এ যোগ হয়)
│   └── build.gradle.kts
├── .github/workflows/
│   └── build-apk.yml                — CI/CD workflow
└── README.md                        — এই ফাইল
```

---

## লাইসেন্স

- **App code**: MIT License
- **llama.cpp**: MIT License  
- **PaddleOCR-VL model**: Apache 2.0 License
