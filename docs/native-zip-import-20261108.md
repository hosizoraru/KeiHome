# Standalone native ZIP import report

## Sample and result

The user supplied `icons.zip` (SHA-256
`b75814b2670a9d441305abb4200c6ebf50d84e05bdcb657a518e7438e10de4ea`).
It is a standalone MIUI icons module, not an APK/appfilter pack or a complete
MTZ theme. Its name and extension are not used to choose its format.

- Compressed size: 74,910,464 bytes.
- Expanded payload: 100,638,169 bytes.
- 3,084 ZIP entries: 2,496 files and 588 directories.
- Roots: `fancy_icons`, `res`, and `transform_config.xml`.
- 583 MAML manifests; these include calendar/date behavior and animations.
- Two fancy-icon assets named `.png` actually contain WebP. Their bytes belong
  to the native module and are not rewritten based on their suffix.
- The current index exposes 582 valid native icon names. An unsupported native
  name does not cause its original files to be removed from the module.

The supplied file passed current archive admission, including CRC, path, entry,
expansion, and XML bounds. The unmodified installed Release
`1.5.0+g7311a70.b0296` imported it successfully on the Phone AVD. No production
compatibility fix was required to admit this sample.

## Device boundary

Only `emulator-5580` was used. The real file-picker flow added the sample to the
local catalog (582 mappings, 41 installed targets). A subsequent native import
completed and opened its system Themes detail page with an Apply button.

Automatic application was temporarily disabled for this test and then restored
to its original enabled state. The sample was **not applied**: the existing
ColorOS Classic ART+ theme remains active. The imported sample remains available
locally and in Themes; its latest receipt therefore represents an imported,
not-yet-applied theme. The temporary public Downloads copy was removed.

Launcher PID 9500/start 3785, SystemUI PID 9494/start 3783, system_server PID
8660/start 2869, and Themes PID 26876/start 34858 remained unchanged. No native
activation, core-process restart, physical-device installation, or theme reset
was performed.

This proves import into the current AVD's Themes environment. It does not prove
application or playback of every third-party MAML effect on a different device.
The original user's failure remains unconfirmed without that device's actual
error and logs; special icons alone are not evidence of a rejection.

## Regression coverage

`IconPackArchiveTest` includes a standalone-module fixture with directory
entries, component-style native names, a DateTime updater, BroadcastBinder,
CDATA, transform metadata, and opaque fancy-icon assets. It verifies native
copying preserves every byte without interpreting MAML commands.

The optional local-sample test imports a supplied file twice, checks deduplication,
copies its native module, compares indexed names, and verifies source and output
SHA-256. Third-party artwork is not committed into the repository:

```bash
KEIHOME_TEST_ICON_ARCHIVE=/absolute/path/icons.zip \
  ./gradlew :app:testDebugUnitTest --tests '*IconPackArchiveTest' --console=plain
```

All 14 tests passed with this exact sample, including existing malformed ZIP,
traversal, CRC, quota, nesting, and cancellation cases. The
`NATIVE_ANIMATION_PREVIEW_UNSUPPORTED` notice describes preview limitations; it
does not reject native import or strip dynamic resources.

Machine-local evidence is under
`.artifacts/research/native-zip-20261108/`, including `archive-audit.json`,
`archive-tests.log`, `native-theme-detail.png`, `final-identities.json`, and
`acceptance.json`.

## Follow-up evidence for an actual failure

Obtain the precise failing stage, installed KeiHome version, and error text.
Local file selection/catalog failures need App logs; system Themes import,
application, or Hook failures need LSPosed logs. Do not attribute an unobserved
failure to MAML or relax archive safety limits without reproducing the rejection.
