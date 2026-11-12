# API 102 minified entry probe

Compile `EntryProbe.java` with the Android SDK and D8 into a standalone helper dex jar.
Separately dex `classes.jar` from the exact compile-only `io.github.libxposed:api:102.0.0`
AAR. Retain its input hash and the official `102.0.0` source revision.

Run only on the selected validation AVD:

```sh
CLASSPATH=/data/local/tmp/keihome-entry-probe.jar app_process /system/bin EntryProbe \
  /absolute/on-device/installed-base.apk /data/local/tmp/keihome-api102-dex.jar
```

Use the authorized root shell if hidden-API filtering requires it. Keep the target
APK and API jar off the parent `CLASSPATH`. The probe obtains the entry name from
`META-INF/xposed/java_init.list`, verifies no API implementation classes are packaged
in the module, cold-constructs the entry through its public no-argument constructor,
and checks all six declared lifecycle signatures. A synthetic Launcher parameter
exercises module-loaded and empty-generation retirement behavior.

It does not attach a fake framework, install hooks, start target services, or change
preferences. It cannot establish live cold boot, framework attachment, real Hook
execution, native ownership or classloader collection. Those require actual
API102 activation/retirement acceptance. Remove only the two owned helper jars after use.
