from conan import ConanFile
from conan.tools.cmake import cmake_layout, CMake, CMakeToolchain
from conan.tools.files import copy, replace_in_file
from conan.errors import ConanInvalidConfiguration
from conan.tools.scm import Git

import os
import platform

class AwgAndroid(ConanFile):
    name = "awg-android"
    version = "1.1.7"
    settings = "os", "arch", "build_type", "compiler"

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
        git = Git(self)
        git.clone(
            url="https://github.com/amnezia-vpn/amneziawg-android.git",
            target=".",
            args=["--recurse-submodules", "--branch", f"v{self.version}"]
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
            makefile = os.path.join(self.source_folder, "tunnel", "tools", "libwg-go", "Makefile")
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

            cmake_lists = os.path.join(self.source_folder, "tunnel", "tools", "CMakeLists.txt")
            with open(cmake_lists, "r", encoding="utf-8") as file:
                contents = file.read()
            marker = "# Strip unwanted ELF sections to prevent DT_FLAGS_1 warnings on old Android versions"
            if marker in contents:
                contents = contents[:contents.index(marker)] + "# Elf cleaner is skipped for Windows-hosted Conan builds.\n"
                with open(cmake_lists, "w", encoding="utf-8") as file:
                    file.write(contents)

        if host_system == 'Darwin':
            replace_in_file(self,
                os.path.join(self.source_folder, "tunnel", "tools", "libwg-go", "Makefile"),
                'flock "$@.lock" -c \' \\\n',
                "",
            )
            replace_in_file(self,
                os.path.join(self.source_folder, "tunnel", "tools", "libwg-go", "Makefile"),
                'mv "$@.tmp" "$@"\'',
                'mv "$@.tmp" "$@"',
            )
            replace_in_file(self,
                os.path.join(self.source_folder, "tunnel", "tools", "libwg-go", "Makefile"),
                'touch "$@"\'',
                'touch "$@"',
            )
            replace_in_file(self,
                os.path.join(self.source_folder, "tunnel", "tools", "libwg-go", "Makefile"),
                'sha256sum -c',
                'shasum -a 256 -c'
            )

    def build(self):
        self._patch_sources()
        cmake = CMake(self)
        cmake.configure(build_script_folder=os.path.join(self.source_folder, "tunnel", "tools"))
        cmake.build(target=["libwg-go.so", "libwg.so", "libwg-quick.so"])

    def package(self):
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
