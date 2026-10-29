#!/usr/bin/env python3
"""Host checks for the image gate. No device access or proprietary fixtures needed."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/cpp"
HARNESS = r'''
#include "monet_image_identity.h"
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>
int main(int n,char** v) {
  if(n!=3)return 2;
  unsigned char digest[32];
  if(std::string(v[1])=="library") {
    if(!HashMonetLibrary(v[2],digest))return 3;
  } else {
    std::ifstream f(v[2],std::ios::binary);
    std::string b((std::istreambuf_iterator<char>(f)),{});
    MonetSha256(b.data(),b.size(),digest);
  }
  for(auto b:digest)std::printf("%02x",b);
  std::puts("");
}
'''


def main():
    with tempfile.TemporaryDirectory(prefix="keihome-image-test-") as temp:
        work = Path(temp)
        (work / "main.cpp").write_text(HARNESS)
        executable = work / "test"
        subprocess.run([
            os.environ.get("CXX", "c++"), "-std=c++17", "-Wall", "-Wextra", "-Werror",
            "-I", str(SOURCE), str(work / "main.cpp"),
            str(SOURCE / "monet_image_identity.cpp"), "-o", str(executable),
        ], check=True)
        def run(mode, path, accepted=True):
            result = subprocess.run([str(executable), mode, str(path)], capture_output=True, text=True)
            assert (result.returncode == 0) == accepted, (path, result.returncode, result.stderr)
            return result.stdout.strip()
        for length in [0, 1, 3, 55, 56, 63, 64, 65, 119, 120, 127, 128, 4096, 1_000_000]:
            value = bytes((i * 37 + 13) % 256 for i in range(length))
            fixture = work / "input"
            fixture.write_bytes(value)
            assert run("hash", fixture) == hashlib.sha256(value).hexdigest()
        library = b"\x7fELF" + bytes(range(256)) * 8
        lib = work / "libapp.so"
        lib.write_bytes(library)
        expected = hashlib.sha256(library).hexdigest()
        assert run("library", lib) == expected
        entry = "lib/arm64-v8a/libapp.so"
        apk = work / "example.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("assets/unrelated", b"other")
            archive.writestr(entry, library)
            archive.comment = b"comment"
        assert run("library", f"{apk}!/{entry}") == expected
        run("library", f"{apk}!/lib/arm64-v8a/missing.so", False)
        with zipfile.ZipFile(work / "compressed.apk", "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(entry, library)
        run("library", f"{work / 'compressed.apk'}!/{entry}", False)
        for cut in [1, 21, 64]:
            broken = work / f"truncated{cut}.apk"
            broken.write_bytes(apk.read_bytes()[:-cut])
            run("library", f"{broken}!/{entry}", False)
        lib.write_bytes(b"not an ELF")
        run("library", lib, False)
        print("SHA-256 vectors, stored APK, missing/compressed/truncated image gates passed")


if __name__ == "__main__":
    main()
