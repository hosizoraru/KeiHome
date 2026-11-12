# libxposed API 102 and R8

## Source boundary

Reviewed the official [`102.0.0` source](https://github.com/libxposed/api/tree/102.0.0),
revision `45e7c5cfe54725b6d828d8b7be65e22ce60c67e4`, alongside the exact
`io.github.libxposed:api:102.0.0` AAR used by this build. The source is retained under
`.artifacts/inputs/libxposed/api-102.0.0`; dependency hashes are in the dated receipt.

The [official README](https://github.com/libxposed/api/blob/102.0.0/README.md) uses a
public no-argument module constructor and allows optimization in its R8 example.
Its example can also rename an entry if `java_init.list` is adapted. KeiHome keeps
its existing full member/name fence for cross-generation compatibility. The example
is not evidence that every module's private members or JNI boundaries can be renamed.

[`XposedInterfaceWrapper`](https://github.com/libxposed/api/blob/102.0.0/api/src/main/java/io/github/libxposed/api/XposedInterfaceWrapper.java)
requires framework attachment before API use. Modules must not perform their own
attachment; a cold-construction probe must therefore avoid fabricating a framework.
The API remains a compile-only dependency, supplied by the actual framework in
injected processes.

[`XposedModuleInterface`](https://github.com/libxposed/api/blob/102.0.0/api/src/main/java/io/github/libxposed/api/XposedModuleInterface.java)
places retirement in old code and adoption in new code. Package lifecycle callbacks
are not automatically replayed after reload; modules must restore their hooks or
explicitly replace surviving handles. Retirement must also release the module's
own workers/resources. R8 name retention alone cannot establish lifecycle correctness.
KeiHome's generation handoff and fresh Hook installation remain necessary.

[`XposedInterface.deoptimize`](https://github.com/libxposed/api/blob/102.0.0/api/src/main/java/io/github/libxposed/api/XposedInterface.java)
addresses host runtime optimization that bypasses hooked call sites. Keeping all
module code unoptimized is not a substitute for selecting and deoptimizing those
host members where required.

## Accepted rule adjustment

```proguard
-keep,allowoptimization class os.kei.hyper.home.xposed.KeiHomeModule {
    *;
}
```

This enables R8 code optimization while keeping the complete entry and member
boundary. The AGP 9.5.1 analyzer confirms the difference:

| Constraint attributed to the module rule | Before | After |
| --- | --- | --- |
| Class / field / method retention | 1 / 8 / 26 | 1 / 8 / 26 |
| Prevent renaming | Yes | Yes |
| Prevent shrinking | Yes | Yes |
| Prevent optimization | Yes | No |

This is permission for the optimizer, not a measured APK-size or runtime-speed
improvement. The status version pill is part of the same candidate build, so whole
APK-size comparisons would not isolate this rule's effect.

The earlier callback-only experiment in [r8-boundaries.md](r8-boundaries.md) remains
inconclusive and is not reintroduced.

## Lessons from MiuiBackGestureHook

Reviewed the Apache-2.0 reference at `c4a39e0` and the R8-related build, rules,
consumer configuration and commit history. Its constructor-only entry fence fits
that module's design and is not a direct replacement for KeiHome's cross-generation
boundary. Source-level diagnostic gates are useful; no blanket logging removal is
needed. KeiHome's handoff INFO messages are part of deployment verification.

The reference's SDK assumptions also should not replace KeiHome's actual target and
ABI checks. Manifest-rule overlap alone is insufficient evidence to delete explicit
component guards. Native JNI contracts and the JGit NLS reflection fields have their
own retention requirements, which remain intact.

## Focused acceptance

Evidence: `.artifacts/research/status-version-r8-20261112/`.

* Current AGP `analyzeReleaseR8Config` reports before/after constraints. The installed
  skill's optional converter scripts are absent; the analysis uses the generated
  HTML report's bundled data/parser, with the raw PB and HTML retained.
* The [entry probe](../scripts/tests/libxposed-entry/README.md) reads the real APK
  `java_init.list`, verifies zero packaged API implementation classes, cold-constructs
  the entry, checks all six declared lifecycle signatures and exercises an empty
  synthetic Launcher retirement. Its API jar comes from the exact dependency AAR.
* The library probe checks all 927 JGit NLS string fields, the named probe-service
  constructor/lifecycle members, and DexKit native load/query/close in the correct
  APK child classloader. Service reflection does not constitute a service binding test.
* Real API102 acceptance must cover both a Debug-to-minified-Release transition and
  a subsequent minified-Release-to-Release transition, including complete
  system-server initialization and unchanged critical process identities.

The synthetic entry probe does not establish a live system cold boot, framework
attachment or classloader collection. The dated receipt records final validation;
do not replace actual reload acceptance with ABI inspection or Hook counts alone.
