from conan import ConanFile
from conan.errors import ConanInvalidConfiguration
from conan.tools.files import chdir, copy
from conan.tools.gnu import AutotoolsToolchain
from conan.tools.layout import basic_layout

import os


class AwgNetstackWindows(ConanFile):
    name = "awg-netstack-windows"
    version = "3.1.20260814"
    settings = "os", "arch"
    description = ("WWG entry layer for Windows: an AmneziaWG device on an "
                   "in-process netstack plus a loopback UDP relay")
    exports_sources = "go.mod", "go.sum", "*.go"

    @property
    def _goarch(self):
        return {
            "x86": "386",
            "x86_64": "amd64",
            "armv8": "arm64",
            "armv8_32": "arm64",
            "armv8.3": "arm64",
            "arm64ec": "arm64"
        }.get(str(self.settings.arch))

    def layout(self):
        basic_layout(self)

    def validate(self):
        if not str(self.settings.os).startswith("Windows"):
            raise ConanInvalidConfiguration(
                f"{self.name} v{self.version} is to be used on Windows only!"
            )
        if not self._goarch:
            raise ConanInvalidConfiguration(
                f"{self.name} v{self.version} does not support {self.settings.arch} architecture"
            )

    def build_requirements(self):
        self.tool_requires("mingw-builds/15.1.0")
        self.tool_requires("go/1.27.1")

    def generate(self):
        tc = AutotoolsToolchain(self)
        env = tc.environment()
        env.define("GOOS", "windows")
        env.define("GOARCH", self._goarch)
        env.define("CGO_ENABLED", "1")
        env.define("CGO_LDFLAGS", " ".join(tc.ldflags))
        env.define("CGO_CFLAGS", " ".join(tc.cflags))
        env.define("GOFLAGS", "-mod=mod")
        tc.generate(env)

    def build(self):
        with chdir(self, self.source_folder):
            self.run(f'go build -buildmode c-shared -trimpath -buildvcs=false '
                     f'-ldflags="-w -s -buildid=" -o "{os.path.join(self.build_folder, "awgnetstack.dll")}" .')

    def package(self):
        copy(self, "awgnetstack.dll", src=self.build_folder,
             dst=os.path.join(self.package_folder, "bin"))
        copy(self, "awgnetstack.h", src=self.build_folder,
             dst=os.path.join(self.package_folder, "include"))

    def package_info(self):
        self.cpp_info.exe = True
        self.cpp_info.location = os.path.join(self.package_folder, "bin", "awgnetstack.dll")
        self.cpp_info.set_property("cmake_target_name", "amnezia::awg-netstack-windows")
