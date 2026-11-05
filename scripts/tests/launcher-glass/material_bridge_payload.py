#!/usr/bin/env python3
"""Link only production material assembly and policy for host ARM64 execution."""
from contextlib import contextmanager
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
CPP = ROOT / "app/src/main/cpp"


@contextmanager
def standalone_payload(ndk=None):
    ndk = Path(ndk or os.environ.get("ANDROID_NDK_HOME",
                                    "/opt/homebrew/share/android-ndk"))
    compiler = ndk / "toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++"
    if not compiler.is_file():
        raise FileNotFoundError(f"NDK ARM64 compiler not found: {compiler}")
    with tempfile.TemporaryDirectory(prefix="keihome-material-arm64-") as directory:
        output = Path(directory) / "libmaterial_test.so"
        subprocess.run([
            str(compiler), "-shared", "-fPIC", "-O2", "-g", "-nostdlib",
            "-fno-exceptions", "-fno-rtti", "-fno-stack-protector",
            "-mno-outline-atomics", "-mbranch-protection=standard",
            "-Wl,-Bsymbolic,--no-undefined,--pack-dyn-relocs=none",
            "-I", str(CPP), str(CPP / "launcher_glass_material_bridge.S"),
            str(CPP / "launcher_glass_policy.cpp"), "-o", str(output),
        ], check=True)
        yield output
