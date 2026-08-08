message("Client android ${CMAKE_ANDROID_ARCH_ABI} build")

set(APP_ANDROID_MIN_SDK 28 CACHE STRING
    "The minimum Android API level supported by the application or library")
set(ANDROID_PLATFORM "android-${APP_ANDROID_MIN_SDK}" CACHE STRING
    "The minimum API level supported by the application or library" FORCE)

# set QTP0002 policy: target properties that specify Android-specific paths may contain generator expressions
qt_policy(SET QTP0002 NEW)

set_target_properties(${PROJECT} PROPERTIES
    QT_ANDROID_VERSION_NAME ${CMAKE_PROJECT_VERSION}
    QT_ANDROID_VERSION_CODE ${APP_ANDROID_VERSION_CODE}
    QT_ANDROID_MIN_SDK_VERSION ${APP_ANDROID_MIN_SDK}
    QT_ANDROID_TARGET_SDK_VERSION 36
    QT_ANDROID_COMPILE_SDK_VERSION 36
    QT_ANDROID_SDK_BUILD_TOOLS_REVISION 36.0.0
    QT_ANDROID_PACKAGE_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/android
)

set(QT_ANDROID_MULTI_ABI_FORWARD_VARS "QT_NO_GLOBAL_APK_TARGET_PART_OF_ALL;CMAKE_BUILD_TYPE")

# We need to include qtprivate api's
# As QAndroidBinder is not yet implemented with a public api
# Check if Qt6::CorePrivate is available (may not be in all Qt versions/configurations)
if(TARGET Qt6::CorePrivate)
    set(LIBS ${LIBS} Qt6::CorePrivate)
endif()
set(LIBS ${LIBS} -ljnigraphics)

link_directories(${CMAKE_CURRENT_SOURCE_DIR}/platforms/android)

set(HEADERS ${HEADERS}
    ${CMAKE_CURRENT_SOURCE_DIR}/platforms/android/android_controller.h
    ${CMAKE_CURRENT_SOURCE_DIR}/platforms/android/android_utils.h
    ${CMAKE_CURRENT_SOURCE_DIR}/core/protocols/androidVpnProtocol.h
    ${CMAKE_CURRENT_SOURCE_DIR}/core/utils/installedAppsImageProvider.h
)

set(SOURCES ${SOURCES}
    ${CMAKE_CURRENT_SOURCE_DIR}/platforms/android/android_controller.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/platforms/android/android_utils.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/core/protocols/androidVpnProtocol.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/core/utils/installedAppsImageProvider.cpp
)

if(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    add_library(android8compat SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/android/compat/android8_getentropy.c
    )
    set_target_properties(android8compat PROPERTIES
        OUTPUT_NAME android8compat
    )
    target_link_options(android8compat PRIVATE
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/android/compat/android8_getentropy.version"
        "-Wl,-z,global"
    )
    add_dependencies(${PROJECT} android8compat)
    set_property(TARGET ${PROJECT} APPEND PROPERTY QT_ANDROID_EXTRA_LIBS $<TARGET_FILE:android8compat>)
endif()


find_package(awg-android REQUIRED)
set_property(TARGET ${PROJECT} APPEND PROPERTY QT_ANDROID_EXTRA_LIBS ${AMNEZIA_ANDROID_LIBWG_PATH} ${AMNEZIA_ANDROID_LIBWG_QUICK_PATH})

# Qt's Android TLS plugin loads OpenSSL dynamically.  The Conan dependency is
# intentionally static for libssh, so package the pinned Android binaries
# recommended by Qt in addition to the linked dependency.
include(FetchContent)
FetchContent_Declare(
    android_openssl
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    URL https://github.com/KDAB/android_openssl/archive/b71f1470962019bd89534a2919f5925f93bc5779.zip
    URL_HASH SHA256=9277d62ecdb4809801e2c369e0a639c154e0d9137e8d60863b44bf07d16ed5b3
)
FetchContent_MakeAvailable(android_openssl)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(_amnezia_android_openssl_root "${android_openssl_SOURCE_DIR}/no-asm")
else()
    set(_amnezia_android_openssl_root "${android_openssl_SOURCE_DIR}")
endif()
set(_amnezia_android_openssl_dir
    "${_amnezia_android_openssl_root}/ssl_3/${CMAKE_ANDROID_ARCH_ABI}")
set(_amnezia_android_libcrypto "${_amnezia_android_openssl_dir}/libcrypto_3.so")
set(_amnezia_android_libssl "${_amnezia_android_openssl_dir}/libssl_3.so")
if(NOT EXISTS "${_amnezia_android_libcrypto}" OR NOT EXISTS "${_amnezia_android_libssl}")
    message(FATAL_ERROR "Pinned Android OpenSSL libraries are missing for ${CMAKE_ANDROID_ARCH_ABI}")
endif()
set_property(TARGET ${PROJECT} APPEND PROPERTY QT_ANDROID_EXTRA_LIBS
    "${_amnezia_android_libcrypto}"
    "${_amnezia_android_libssl}"
)

if(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a" AND APP_ANDROID_MIN_SDK LESS_EQUAL 26)
    string(TOUPPER "${CMAKE_BUILD_TYPE}" _awg_config)
    get_target_property(AMNEZIA_ANDROID_LIBWG_GO_PATH amnezia::awg-android IMPORTED_LOCATION_${_awg_config})
    if(NOT AMNEZIA_ANDROID_LIBWG_GO_PATH)
        get_target_property(AMNEZIA_ANDROID_LIBWG_GO_PATH amnezia::awg-android IMPORTED_LOCATION)
    endif()
    set(AMNEZIA_ANDROID_LIBWG_GO_STAGED_PATH "${CMAKE_CURRENT_BINARY_DIR}/libwg-go.so")
    configure_file(${AMNEZIA_ANDROID_LIBWG_GO_PATH} ${AMNEZIA_ANDROID_LIBWG_GO_STAGED_PATH} COPYONLY)
    message(WARNING "Packaging libwg-go.so for lazy loading on armeabi-v7a Android 8 build")
    set_property(TARGET ${PROJECT} APPEND PROPERTY QT_ANDROID_EXTRA_LIBS ${AMNEZIA_ANDROID_LIBWG_GO_STAGED_PATH})
else()
    set(LIBS ${LIBS} amnezia::awg-android)
endif()

find_package(amnezia-libxray REQUIRED)
file(COPY ${AMNEZIA_LIBXRAY_PATH} DESTINATION ${CMAKE_CURRENT_SOURCE_DIR}/android/xray/libXray)

find_package(openvpn-pt-android REQUIRED)
set(LIBS ${LIBS} amnezia::openvpn-pt-android)
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a" AND APP_ANDROID_MIN_SDK LESS_EQUAL 26)
    message(WARNING "Using Android 8 libck-ovpn-plugin.so stub for armeabi-v7a build: Go runtime uses time64 syscalls blocked by Android 8 seccomp")
    add_library(ckovpncompat SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/android/compat/ck_ovpn_plugin_stub.c
    )
    set_target_properties(ckovpncompat PROPERTIES
        OUTPUT_NAME ck-ovpn-plugin
    )
    add_dependencies(${PROJECT} ckovpncompat)
    set_property(TARGET ${PROJECT} APPEND PROPERTY QT_ANDROID_EXTRA_LIBS $<TARGET_FILE:ckovpncompat>)
else()
    set_property(TARGET ${PROJECT} APPEND PROPERTY QT_ANDROID_EXTRA_LIBS ${OPENVPN_PT_ANDROID_LIBCK_OVPN_PLUGIN_PATH})
endif()
