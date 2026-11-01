# Optional Image ownership diagnostic host suite

See [the ownership note](../../../docs/monet-image-ownership-8593.md) for the
reviewed ABI, observed ownership/in-flight paths, limitations and Main's runtime
procedure. This is instrumentation, not a disposal fix.

```bash
/tmp/keihome-capstone-venv/bin/python scripts/tests/monet-image-trace/validate.py \
  --library .artifacts/inputs/launchers/8593/libapp.so \
  --library .artifacts/inputs/launchers/7722/libapp.so \
  --output /tmp/keihome-image-trace-validation
```

See [the research index](../../../docs/research/README.md) for retained local
fixtures and old `/tmp` evidence. The fixtures are not committed.

The runner needs Python with Capstone/Unicorn/pyelftools, a host C++17 compiler,
and an installed NDK with API 37 wrappers. `CXX` and `NDK_DIR` override discovery.
No dependency is downloaded; missing tools fail the run. Fixtures stay external.
Outputs default to a new temporary directory. The script only compiles standalone
host/NDK harness objects; it does not run Gradle, install an APK, patch a process,
invoke ADB, or create a launcher/VM.

`generate_contracts.py` regenerates the production fingerprints, with observed
RVAs written only to an external evidence JSON. `report.py` consumes one PID's
saved threadtime log and the matching ELF; it prints conditional address
correlations and actual live State dispose targets. It reports no fix verdict.

`diagnostic.init.gradle` is a separate, optional input for Main's disposable APK
build, taking `-PkeihomeImageTrace=on` or `off`. The host suite does not run this
Gradle integration. The production CMake default is OFF.
