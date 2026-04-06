cmake_minimum_required(VERSION 3.22.1)

project("ocr_engine")

# TODO: Add ONNXRuntime or PaddleLite library for actual ML inference
# find_package(onnxruntime REQUIRED)

add_library(${CMAKE_PROJECT_NAME} SHARED
            native-lib.cpp
            ocr_engine.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
                           ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(${CMAKE_PROJECT_NAME}
                      android
                      log
                      jnigraphics)
