find_program(CONAN_COMMAND "conan" REQUIRED
    HINTS
        "${CMAKE_SOURCE_DIR}/.venv/bin"
        "/opt/homebrew/bin"
)

set(AMNEZIA_ENABLE_CONAN_PREBUILTS_REMOTE ON CACHE BOOL
    "Use the Amnezia prebuilt Conan remote when resolving dependencies")

file(GLOB_RECURSE LOCAL_RECIPES "${CMAKE_SOURCE_DIR}/recipes/*/conanfile.py")
foreach(RECIPE ${LOCAL_RECIPES})
    get_filename_component(RECIPE_DIR ${RECIPE} DIRECTORY)
    execute_process(
        COMMAND ${CONAN_COMMAND} export ${RECIPE_DIR}
    )
endforeach()

if(AMNEZIA_ENABLE_CONAN_PREBUILTS_REMOTE)
    execute_process(
        COMMAND ${CONAN_COMMAND} remote add amnezia "https://artifactory.amnezia.org/artifactory/api/conan/client-prebuilts" --force
    )
    execute_process(
        COMMAND ${CONAN_COMMAND} remote enable amnezia
        OUTPUT_QUIET
        ERROR_QUIET
    )
else()
    execute_process(
        COMMAND ${CONAN_COMMAND} remote disable amnezia
        OUTPUT_QUIET
        ERROR_QUIET
    )
endif()
