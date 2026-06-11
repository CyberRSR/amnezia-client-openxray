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
