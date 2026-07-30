cmake_minimum_required(VERSION 3.25)
project(wwg_protocol_config_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(Qt6 REQUIRED COMPONENTS Core Test)

add_executable(wwg_protocol_config_test
    test_wwg_protocol_config.cpp
    ../../client/core/models/protocols/awgProtocolConfig.cpp
    ../../client/core/models/protocols/wwgProtocolConfig.cpp
)

target_include_directories(wwg_protocol_config_test PRIVATE ../../client)
target_link_libraries(wwg_protocol_config_test PRIVATE Qt6::Core Qt6::Test)

enable_testing()
add_test(NAME wwg_protocol_config_test COMMAND wwg_protocol_config_test)
if(WIN32)
    set_tests_properties(wwg_protocol_config_test PROPERTIES
        ENVIRONMENT_MODIFICATION "PATH=path_list_prepend:$<TARGET_FILE_DIR:Qt6::Core>"
    )
endif()
