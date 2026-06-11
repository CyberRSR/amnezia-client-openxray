#include <jni.h>
#include <stdint.h>

typedef int32_t GoInt;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_6;
}

GoInt Cloak_native_handle(void)
{
    return -1;
}

GoInt Initialize_cloak_c_client(char *base64Config)
{
    (void)base64Config;
    return -1;
}

void Cloak_listen(char *address_string)
{
    (void)address_string;
}

GoInt Cloak_dial(void)
{
    return -1;
}

GoInt Cloak_write(GoInt client_id, void *buffer, int buffer_length)
{
    (void)client_id;
    (void)buffer;
    (void)buffer_length;
    return -1;
}

GoInt Cloak_read(GoInt client_id, void *buffer, GoInt buffer_length)
{
    (void)client_id;
    (void)buffer;
    (void)buffer_length;
    return -1;
}

void Cloak_close_connection(GoInt client_id)
{
    (void)client_id;
}
