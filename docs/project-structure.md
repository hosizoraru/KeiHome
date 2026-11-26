# KeiHome project structure

| Path | Responsibility |
| --- | --- |
| `settings.gradle.kts` | Repository policy, the `build-logic` included build, and application module registration |
| `gradle/libs.versions.toml` | Application dependency versions and aliases |
| `build-logic/` | Android and Compose conventions, versioning, signing configuration, and generated asset tasks |
| `app/build.gradle.kts` | The application convention plugin, app-specific options, and dependencies |
| `app/src/main/kotlin/os/kei/hyper/home/` | Android entry points, UI, persistence, and runtime integration |
| `app/src/main/cpp/` | JNI and native runtime integration |
| `app/src/main/res/` | UI resources and localized text |
| `app/src/main/assets/` and `app/src/main/resources/` | Checked-in assets and JVM resources, including Xposed module metadata |
| `app/src/test/` and `app/src/androidTest/` | JVM and Android device checks |
| `scripts/` and `.github/` | Local checks, development and release tools, and CI orchestration |
| `docs/` | Maintained guides, release records, and durable research findings |
| `.artifacts/` | Ignored local builds, device evidence, and optional generated inputs |

## Application sources

- `ui/main/` owns shared app chrome, navigation destinations, and top-level
  state coordination. `ui/apple/` and `ui/perfect/` own their feature screens,
  controllers, preview presentation, and feature-specific controls.
- `ui/components/` contains reusable UI components. Theme and appearance
  primitives live in `ui/theme/`.
- `iconpack/` handles resource resolution, import, preview rendering, and
  generation. Feature artwork and pack logic live in its `apple/` and
  `perfect/` packages. See the [icon rendering contracts](icon-rendering-contracts.md).
- `data/` handles persisted settings and app-side data access. `status/`
  handles runtime state, system capability reporting, and publication.
  `xposed/` owns host-process integration.

Put new feature code in the package that owns the behavior. Place shared
components alongside their existing peers when multiple features use the same
contract. Keep the application in `:app` while these boundaries can be
expressed with packages.

## Build ownership

Declare dependencies in `app/build.gradle.kts` through the version catalog.
Use the existing included build for Android DSL conventions and typed task
implementations. `build-logic/.../assets/` registers the built-in icon store,
third-party notices, and per-variant dependency reports with the Android
Components API. Their output lives under `app/build/generated/` and is wired
into the app variants by the convention plugin.
See [build and release versioning](versioning.md) and [CI](ci.md).

For UI migration, start with the [MIUIX OS4 playbook](miuix-os4-playbook.md).
Version-specific source reviews and migration receipts live in
[`docs/miuix/snapshots/`](miuix/snapshots/). See the
[research index](research/README.md) for retained evidence and
[testing](testing.md) for focused checks and device prerequisites.
