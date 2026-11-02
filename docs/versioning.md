# Build versions

KeiHome derives its version from `keihome.versionName` in `gradle.properties`,
the current Git revision, and a build iteration. For example:

```
versionName = 1.2.3+g0123456789ab.b0106.dirty
versionCode = 102030106
```

The integer is `major * 100000000 + minor * 1000000 + patch * 10000 + iteration`.
Major is 0–20, minor and patch are each 0–99, and the iteration is 1–9999.
This leaves the code within Android/Play's 2,100,000,000 limit. The suffix
`b0106` is the iteration, while `g0123456789ab` identifies the Git commit.
`.dirty` means the tracked or untracked worktree differed at configuration time;
it does not identify the content of those changes. A source archive without Git
metadata reports `gunknown` rather than inventing a commit.

The first iteration for a series starts at the local Git commit count, at least
1. Later Gradle configurations allocate a larger iteration under
`.gradle/keihome-version/`, with a file lock and atomic replacement so concurrent
builds in the same checkout cannot collide. There is no maintained commit-count
offset. The allocation also means `help` or other configuration-only Gradle
commands can consume an iteration. A new patch series has its own counter; a
full slot fails instead of silently wrapping. Configuration cache remains
disabled so distinct installs do not reuse a frozen generation.

The counter is local to a checkout. Independent clones or machines are not a
global sequence; use one authoritative release pipeline or provide an audited
`-Pkeihome.versionCode=<code>` when coordinating releases. An override is marked
`.override` in the version name and does not advance the local counter. Removing
the `.gradle` directory also removes its allocation history.

Older experimental KeiHome builds used Unix seconds directly and can therefore
have a larger code. The core-patched HyperOS AVD permits an in-place downgrade:
`scripts/deploy-hyperos-avd.py --allow-downgrade --apk <apk> --evidence <dir>`.
The script keeps the data, checks the API 102 Java reload and verifies process
identities. An ordinary unpatched Android device may reject that downgrade; a
physical-device release needs an explicit migration/versioning plan. No
uninstall is performed as part of this transition.
