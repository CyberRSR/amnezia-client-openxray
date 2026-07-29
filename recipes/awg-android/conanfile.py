from conan import ConanFile
from conan.tools.cmake import cmake_layout, CMake, CMakeToolchain
from conan.tools.files import copy, replace_in_file, save, patch
from conan.errors import ConanInvalidConfiguration
from conan.tools.scm import Git

import os
import platform

class AwgAndroid(ConanFile):
    name = "awg-android"
    version = "3.0.1"
    exports_sources = "patches/*"
    settings = "os", "arch", "build_type", "compiler"

    # Immutable v3.0.1 release commit.  Keeping the revision here makes the
    # Conan recipe reproducible even if the upstream tag is moved.
    _upstream_revision = "f82900455f1aceaa85658686dc2c5e32c2c42a73"

    @property
    def _source_root(self):
        return os.path.join(self.source_folder, "upstream")

    def configure(self):
        self.settings.rm_safe("compiler.libcxx")
        self.settings.rm_safe("compiler.cppstd")

    def layout(self):
        cmake_layout(self)

    def build_requirements(self):
        self.tool_requires("cmake/[>=3.4.1 <4]")
        self.tool_requires("go/1.26.0")
        if platform.system() == "Windows" and not self.conf.get("tools.microsoft.bash:path", check_type=str):
            self.tool_requires("msys2/cci.latest")

    def validate(self):
        if self.settings.os != "Android":
            raise ConanInvalidConfiguration(f"{self.name} v{self.version} does not support {self.settings.os}")

    def source(self):
        git = Git(self, folder=self._source_root)
        git.clone(
            url="https://github.com/amnezia-vpn/amneziawg-android.git",
            target=".",
            args=["--recurse-submodules"]
        )
        git.checkout(self._upstream_revision)
        git.run("submodule update --init --recursive")
        patch(
            self,
            patch_file=os.path.join(self.export_sources_folder, "patches", "0001-add-wwg-netstack-relay.patch"),
            base_path=self._source_root,
            strip=0,
        )

    def generate(self):
        tc = CMakeToolchain(self)
        tc.variables["GRADLE_USER_HOME"] = os.path.join(self.build_folder, "gradle_user_home").replace(os.sep, "/")
        tc.variables["CMAKE_LIBRARY_OUTPUT_DIRECTORY"] = os.path.join(self.build_folder, "out").replace(os.sep, "/")
        # not to warn in case of strtok() usage
        tc.extra_cflags = ["-Wno-deprecated-declarations"]
        tc.generate()

    def _patch_sources(self):
        host_system = platform.system()
        if host_system == "Windows":
            makefile = os.path.join(self._source_root, "tunnel", "tools", "libwg-go", "Makefile")
            with open(makefile, "r", encoding="utf-8") as file:
                contents = file.read()
            target = "$(BUILDDIR)/go-$(GO_VERSION)/.prepared:"
            start = contents.find(target)
            end = contents.find("\n\n$(DESTDIR)/libwg-go.so:", start)
            if start == -1 or end == -1:
                raise ConanInvalidConfiguration("Unable to patch libwg-go Makefile for Windows host")
            contents = (
                contents[:start]
                + '$(BUILDDIR)/go-$(GO_VERSION)/.prepared:\n'
                + '\tmkdir -p "$(dir $@)"\n'
                + '\ttouch "$@"'
                + contents[end:]
            )
            contents = contents.replace(
                '$(DESTDIR)/libwg-go.so: export PATH := $(BUILDDIR)/go-$(GO_VERSION)/bin/:$(PATH)',
                '$(DESTDIR)/libwg-go.so: export PATH := $(PATH)',
            )
            with open(makefile, "w", encoding="utf-8") as file:
                file.write(contents)

            cmake_lists = os.path.join(self._source_root, "tunnel", "tools", "CMakeLists.txt")
            with open(cmake_lists, "r", encoding="utf-8") as file:
                contents = file.read()
            marker = "# Strip unwanted ELF sections to prevent DT_FLAGS_1 warnings on old Android versions"
            if marker in contents:
                contents = contents[:contents.index(marker)] + "# Elf cleaner is skipped for Windows-hosted Conan builds.\n"
                with open(cmake_lists, "w", encoding="utf-8") as file:
                    file.write(contents)

        if host_system == 'Darwin':
            replace_in_file(self,
                os.path.join(self._source_root, "tunnel", "tools", "libwg-go", "Makefile"),
                'flock "$@.lock" -c \' \\\n',
                "",
            )
            replace_in_file(self,
                os.path.join(self._source_root, "tunnel", "tools", "libwg-go", "Makefile"),
                'mv "$@.tmp" "$@"\'',
                'mv "$@.tmp" "$@"',
            )
            replace_in_file(self,
                os.path.join(self._source_root, "tunnel", "tools", "libwg-go", "Makefile"),
                'touch "$@"\'',
                'touch "$@"',
            )
            replace_in_file(self,
                os.path.join(self._source_root, "tunnel", "tools", "libwg-go", "Makefile"),
                'sha256sum -c',
                'shasum -a 256 -c'
            )

    def _abi_name(self):
        return {
            "armv7": "armeabi-v7a",
            "armv8": "arm64-v8a",
            "x86": "x86",
            "x86_64": "x86_64",
        }.get(str(self.settings.arch), str(self.settings.arch))

    def _prebuilt_dir(self):
        root = os.getenv("AMNEZIA_AWG_ANDROID_PREBUILT_DIR")
        if not root:
            return None
        abi_dir = os.path.join(root, self._abi_name())
        if os.path.isfile(os.path.join(abi_dir, "libwg-go.so")):
            candidate = abi_dir
        elif os.path.isfile(os.path.join(root, "libwg-go.so")):
            candidate = root
        else:
            raise ConanInvalidConfiguration(
                f"AMNEZIA_AWG_ANDROID_PREBUILT_DIR does not contain {self._abi_name()} AWG libraries"
            )
        if not os.path.isfile(os.path.join(candidate, "wwg-netstack-v3.marker")):
            raise ConanInvalidConfiguration(
                "Refusing an unverified AWG prebuilt: WWG requires v3.0.1 with netstack JNI symbols"
            )
        return candidate

    def build(self):
        if self._prebuilt_dir():
            return
        self._patch_sources()
        cmake = CMake(self)
        cmake.configure(build_script_folder=os.path.join(self._source_root, "tunnel", "tools"))
        cmake.build(target=["libwg-go.so", "libwg.so", "libwg-quick.so"])

    def package(self):
        prebuilt_dir = self._prebuilt_dir()
        if prebuilt_dir:
            copy(self, "libwg-go.so", src=prebuilt_dir, dst=os.path.join(self.package_folder, "lib"))
            copy(self, "libwg.so", src=prebuilt_dir, dst=os.path.join(self.package_folder, "bin"))
            copy(self, "libwg-quick.so", src=prebuilt_dir, dst=os.path.join(self.package_folder, "bin"))
            save(self, os.path.join(self.package_folder, "include", "libwg-go.h"), """
#pragma once
#include <stddef.h>
#include <stdint.h>

typedef struct { const char *p; ptrdiff_t n; } GoString;
typedef int32_t GoInt32;

#ifdef __cplusplus
extern "C" {
#endif

extern GoInt32 awgTurnOn(GoString interfaceName, GoInt32 tunFd, GoString settings);
extern GoInt32 awgTurnOnNetstack(GoString interfaceName, GoString localAddresses,
    GoString dnsServers, GoInt32 mtu, GoString settings, GoString relayHost, GoInt32 relayPort);
extern void awgTurnOff(GoInt32 tunnelHandle);
extern GoInt32 awgGetSocketV4(GoInt32 tunnelHandle);
extern GoInt32 awgGetSocketV6(GoInt32 tunnelHandle);
extern char* awgGetConfig(GoInt32 tunnelHandle);
extern GoInt32 awgGetRelayPort(GoInt32 tunnelHandle);
extern GoInt32 awgIsRelayHealthy(GoInt32 tunnelHandle);
extern char* awgVersion(void);

#ifdef __cplusplus
}
#endif
""".lstrip())
            return

        copy(self, "libwg-go.h", src=os.path.join(self.build_folder, "out"), dst=os.path.join(self.package_folder, "include"))
        copy(self, "libwg-go.so", src=os.path.join(self.build_folder, "out"), dst=os.path.join(self.package_folder, "lib"))
        copy(self, "libwg.so", src=os.path.join(self.build_folder, "out"), dst=os.path.join(self.package_folder, "bin"))
        copy(self, "libwg-quick.so", src=os.path.join(self.build_folder, "out"), dst=os.path.join(self.package_folder, "bin"))

    def package_info(self):
        self.cpp_info.set_property("cmake_target_name", "amnezia::awg-android")
        self.cpp_info.libs = [ "wg-go" ]
        self.cpp_info.set_property("cmake_extra_variables", {
            "AMNEZIA_ANDROID_LIBWG_PATH": os.path.join(self.package_folder, "bin", "libwg.so").replace(os.sep, "/"),
            "AMNEZIA_ANDROID_LIBWG_QUICK_PATH": os.path.join(self.package_folder, "bin", "libwg-quick.so").replace(os.sep, "/"),
        })
