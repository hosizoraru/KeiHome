# Continuous integration

`Pull request checks` and `Main build` are separate workflows so the Actions
page makes review validation and main-branch packaging easy to distinguish.

`Pull request checks` runs for opened, reopened, ready-for-review and updated
(`synchronize`) pull requests. A contributor can push more commits to the same
PR branch and GitHub starts a new run for that commit. The workflow also has a
manual `workflow_dispatch` entry, so a standalone feature branch can be checked
without opening a temporary PR. It runs the same wrapper, resources,
build-logic, JVM and Debug checks as a PR.

`Main build` runs for pushes to `main` and manual dispatches. It runs the same
validation gates and builds the Release APK. A push to a non-main branch does
not start a second automatic copy of the PR checks. This avoids duplicate
runner usage when that branch already belongs to a PR.

Both workflows use `gradle/actions/setup-gradle@v6` as the single owner of
the Gradle User Home cache. `actions/setup-java` only installs the requested
JDK, so downloaded dependencies are not stored by two independent cache
providers.

`setup-gradle` uses its enhanced cache provider with `cache-cleanup: on-success`.
After a successful Gradle job it removes unused entries from the restored Gradle
User Home before saving the next cache state. Pull requests and non-default
branches read the cache without writing a new entry. `main` may refresh the
shared cache after a successful job. A failed build keeps the restored state
available for diagnosis and retry.

Debug APK artifacts are retained for seven days. Their names identify whether
they came from `main`, a pull request, or a manual branch run, and include the
Actions run number instead of an opaque commit hash. Release APK artifacts from
`main` are retained for thirty days. The runner workspace is disposable, so no
workflow step needs to preserve `build/` or a second local Gradle cache.

Local builds keep the project build cache enabled through `gradle.properties`.
`clean` remains an explicit opt-in for reproducing a clean-build issue. Normal
incremental and CI builds should use the wrapper without `clean` so Gradle can
reuse task outputs.
