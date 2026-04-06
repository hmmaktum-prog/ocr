#pragma once
#include <string>
#include <vector>
#include <cstdint>

class OcrEngine {
public:
    OcrEngine();
    ~OcrEngine();

    // Rule of Five: prevent accidental copy/move of engine instances
    OcrEngine(const OcrEngine&) = delete;
    OcrEngine& operator=(const OcrEngine&) = delete;
    OcrEngine(OcrEngine&&) = delete;
    OcrEngine& operator=(OcrEngine&&) = delete;

    bool init(const std::string& modelDir);
    std::string processImage(uint8_t* pixels, int width, int height);

private:
    std::string modelPath;
    bool initialized = false;
};
