Verbatim XML fixtures from pzcn/Perfect-Icons-Completion-Project:
Themes tree object: 8c720a164fa0e7f2a4945589d819aa6bd672c39f

music-config.xml: common/layer_animating_icons/com.apple.android.music/config.xml
music-manifest.xml: common/layer_animating_icons/com.apple.android.music/1/fancy/manifest.xml
browser-config.xml: Hyper/layer_animating_icons/com.android.browser/config.xml
browser-lottie-manifest.xml: Hyper/layer_animating_icons/com.android.browser/1/fancy/manifest.xml

Pinned original blob URLs, in the same order:
https://api.github.com/repos/pzcn/Perfect-Icons-Completion-Project/git/blobs/2d758bb82d08391dddd54c94c0ee2083cbd2ad53
https://api.github.com/repos/pzcn/Perfect-Icons-Completion-Project/git/blobs/ebe41e36e7562fe8d560ea22c89105b2289c3069
https://api.github.com/repos/pzcn/Perfect-Icons-Completion-Project/git/blobs/1916e5c179ded84c0f8a449161f0703b775dab9c
https://api.github.com/repos/pzcn/Perfect-Icons-Completion-Project/git/blobs/969fda4b78b1c024688b58b6462f0e17f48427a8

HyperOS4 configs from the main task's external fixture, revision:
fb8b5c9224510fedb653a7dceafdcb364b4e2263
hyperos4-calendar-config.xml: themes/HyperOS4/layer_animating_icons/com.android.calendar/config.xml
hyperos4-clock-config.xml: themes/HyperOS4/layer_animating_icons/com.android.deskclock/config.xml

Read-only GitHub blob API downloads. Tests use synthetic PNGs rather than bundling
third-party artwork. The music manifest exercises local visual Folme admission;
the Lottie manifest is intentionally ineligible for LOCAL admission.
Set KEIHOME_TEST_PERFECT_HYPEROS4 to an external source-fixture ZIP to check the
actual static pairs and calendar/clock manifests without vendoring PNG assets.
Set KEIHOME_TEST_PERFECT_SAMPLE to a directory containing the small real music
layer module (config.xml and layer folders) to exercise positive real admission.

Current destination evidence and the source/input boundary:
docs/perfect-icons-provenance.md

The previous revision of this README recorded PerfectIconsPacker's format and
assembler as research references. That history remains in Git and in the
provenance document; these are not current build/runtime inputs or test oracles.
