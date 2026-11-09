add_executable(test_wwg_protocol_config
    testWwgProtocolConfig.cpp
)

target_link_libraries(test_wwg_protocol_config PRIVATE
    Qt6::Test
    test_common
)

add_test(NAME WwgProtocolConfigTest COMMAND test_wwg_protocol_config)

amnezia_copy_runtime_dlls()
