#include <jni.h>
#include <string>
#include <cstdint>
#include <android/bitmap.h>
#include <android/log.h>
#include "ocr_engine.h"

#define LOG_TAG "OcrEngine-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_ocr_OcrEngine_initModelNative(JNIEnv* env, jobject thiz, jstring model_dir) {
    const char* native_model_dir = env->GetStringUTFChars(model_dir, nullptr);
    if (native_model_dir == nullptr) {
        LOGE("Failed to get model dir string (OutOfMemoryError)");
        return 0;
    }
    
    OcrEngine* engine = new OcrEngine();
    bool result = engine->init(native_model_dir);
    
    env->ReleaseStringUTFChars(model_dir, native_model_dir);
    
    if (result) {
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(engine));
    } else {
        delete engine;
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ocr_OcrEngine_processImageNative(JNIEnv* env, jobject thiz, jlong enginePtr, jobject bitmap) {
    OcrEngine* engine = reinterpret_cast<OcrEngine*>(static_cast<uintptr_t>(enginePtr));
    if (engine == nullptr) {
        LOGE("Engine pointer is null");
        return env->NewStringUTF("");
    }

    AndroidBitmapInfo info;
    void* pixels;
    
    int ret = AndroidBitmap_getInfo(env, bitmap, &info);
    if (ret < 0) {
        LOGE("AndroidBitmap_getInfo failed with error code: %d", ret);
        return env->NewStringUTF("");
    }
    
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format: %d (expected RGBA_8888=%d)", 
             info.format, ANDROID_BITMAP_FORMAT_RGBA_8888);
        return env->NewStringUTF("");
    }
    
    ret = AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (ret < 0) {
        LOGE("AndroidBitmap_lockPixels failed with error code: %d", ret);
        return env->NewStringUTF("");
    }

    std::string text = engine->processImage((uint8_t*)pixels, info.width, info.height);

    AndroidBitmap_unlockPixels(env, bitmap);

    jstring result = env->NewStringUTF(text.c_str());
    if (result == nullptr) {
        LOGE("NewStringUTF failed (OutOfMemoryError)");
        return env->NewStringUTF("");
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ocr_OcrEngine_releaseNative(JNIEnv* env, jobject thiz, jlong enginePtr) {
    OcrEngine* engine = reinterpret_cast<OcrEngine*>(static_cast<uintptr_t>(enginePtr));
    if (engine != nullptr) {
        LOGI("Releasing OCR engine");
        delete engine;
    }
}
