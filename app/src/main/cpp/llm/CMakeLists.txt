cmake_minimum_required(VERSION 3.18)
project(llm_core CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# QNN SDK PATH
set(QNN_SDK_ROOT $ENV{QAIRT_PATH})
if(NOT QNN_SDK_ROOT)
    set(QNN_SDK_ROOT D:/dev/qairt/2.45.41.260507)
endif()

# Detect HOST OS
if(CMAKE_HOST_WIN32)
    set(HOST_TAG "windows-x86_64")
elseif(CMAKE_HOST_APPLE)
    set(HOST_TAG "darwin-x86_64")
else()
    set(HOST_TAG "linux-x86_64")
endif()

set(PLATFORM_LIBS log android)

add_compile_options(
    -O3
    -Wall
    -fPIC
    -fvisibility=hidden
)

# Genie SDK
set(GENIE_HEADERS_PATH "${QNN_SDK_ROOT}/include/Genie")
set(GENIE_LIB_PATH "${QNN_SDK_ROOT}/lib/aarch64-android/libGenie.so")

# Reuse 3rdparty libs from parent project
set(CPP_HTTPLIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../3rdparty/cpp-httplib)
set(JSON_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../3rdparty/json/include)

set(PACKAGE_INCLUDES
    ${GENIE_HEADERS_PATH}
    ${CPP_HTTPLIB_DIR}
    ${JSON_INCLUDE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

set(SOURCES
    src/main.cpp
    src/GenieWrapper.cpp
    src/PromptHandler.cpp
)

add_executable(${PROJECT_NAME} ${SOURCES})

target_include_directories(${PROJECT_NAME} PRIVATE ${PACKAGE_INCLUDES})

target_link_libraries(${PROJECT_NAME}
    PRIVATE
    "${GENIE_LIB_PATH}"
    ${PLATFORM_LIBS}
)

set_target_properties(${PROJECT_NAME}
    PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin/${CMAKE_ANDROID_ARCH_ABI}"
)

target_link_options(${PROJECT_NAME} PRIVATE "-Wl,-s")
