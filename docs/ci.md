# Continuous integration

The build workflow uses `gradle/actions/setup-gradle@v6` as the single owner of
the Gradle User Home cache. `actions/setup-java` only installs the requested
JDK, so downloaded dependencies are not stored by two independent cache
providers.

`setup-gradle` uses its enhanced cache provider with `cache-cleanup: on-success`.
After a successful Gradle job it removes unused entries from the restored Gradle
User Home before saving the next cache state. Pull requests and non-default
branches read the cache without writing a new entry. `main` may refresh the
shared cache after a successful job. A failed build keeps the restored state
available for diagnosis and retry.

Debug APK artifacts are retained for seven days. Release APK artifacts from
`main` are retained for thirty days. The runner workspace is disposable, so no
workflow step needs to preserve `build/` or a second local Gradle cache.

Local builds keep the project build cache enabled through `gradle.properties`.
`clean` remains an explicit opt-in for reproducing a clean-build issue. Normal
incremental and CI builds should use the wrapper without `clean` so Gradle can
reuse task outputs.
