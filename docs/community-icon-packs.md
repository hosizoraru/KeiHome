# Community icon-pack compatibility

These are reproducible observations for a future README, not a claim of universal
support. On 2026-11-02/03 the five APKs below were indexed, converted and applied
through native Themes on HyperOS AVD `emulator-5580`. The desktop was inspected
in both system light and dark modes. KeiHome was release 100000119 for the first
four samples and 100000124 for Cuscon. Installed packs and source archives were
not bundled in KeiHome. ColorOS device `emulator-5574` was not modified.

| Pack and official source | Source scale | Mappings / matched installed apps | Observed desktop behavior |
| --- | --- | --- | --- |
| [Arcticons Day & Night 15.3.2](https://github.com/Arcticons-Team/Arcticons/releases/tag/15.3.2) | 15,322 icons reported by author | 49,252 / 33 | Black and white line artwork changed with system light/dark. Transparent line icons can have low contrast on some wallpapers. |
| [Arcticons Material You 15.3.2](https://f-droid.org/packages/com.donnnno.arcticons.you/) | Same 15,322-icon family | 49,252 / 33 | Light/dark artwork switched; its wallpaper-dependent source colors were captured at conversion time. Enabling KeiHome Monet did not recolor the tested applied icons live. |
| [Delta 2.21.0](https://github.com/Delta-Icons/android/releases/tag/v2.21.0) | 14,431 icons reported by author | 19,477 / 26 | Color artwork applied. The inspected light/dark desktop looked the same with Follow system; no native dark-artwork claim is made. |
| [Lawnicons 2.19.0](https://github.com/LawnchairLauncher/lawnicons/releases/tag/v2.19.0) | Material You-style community icon pack | 22,676 / 32 | Light/dark switched; turning on KeiHome Monet visibly changed the applied foreground/background colors for the tested icons. |
| [Cuscon 4.1.2.3](https://f-droid.org/packages/com.froxot.cuscon.foss/) | More than 5,000 icons reported by author | 7,295 / 20 | Color artwork applied in both modes; the examined icons looked the same. Its declared clock artwork is intentionally transparent and appears as hands over the wallpaper. |

Counts in the third column are KeiHome's deduplicated component mappings and
distinct launcher packages matched on this particular emulator at the time of
each test. They are neither the upstream icon count nor proof that every app or
entry was rendered. The inventory changed as new sample APKs were installed.
APK conversion includes the currently installed launcher targets; it does not
copy all thousands of source icons into the native component. Some native
calendar/clock effects and masks require their own contracts. The Default system
fallback was selected; the applied Lawnicons archive recorded 36 fallback names
and retained visible stock artwork for uncovered system apps.

Lawnicons' tested adaptive resources declare a monochrome layer. The generated
archive contained 60 palette-mask entries and the desktop changed when Monet was
enabled. This is different from implementing every part of Lawnchair's separate
themed-icon-source protocol. Arcticons Material You's examined adaptive resource
has a wallpaper-color-based foreground and background but no declared Android
monochrome layer; the generated archive contained no palette masks. Its source
colors therefore remain the converted snapshots until regenerated. Do not claim
live wallpaper recoloring for it or infer that every adaptive foreground is a
safe monochrome mask. ColorOS-specific live clock/calendar templates apply to
the inspected ColorOS profile, not automatically to arbitrary third-party APKs.

APK provenance (SHA-256; the installed versions were pulled read-only):

- Arcticons Day & Night: `d5aec9fa1e02718743c216193006bfa0ba71a6ade74504d06e046a66b29ed4b8`
- Arcticons Material You, official F-Droid: `40a96043517115baab67008e3719286c04c38dca08c13c615e6ed1dab4dc5b5c`
- Delta, official GitHub release: `087094a327ded703969996d92115904e951002724e39dd7dd44c8227388570a8`
- Lawnicons: `5ed67a0338c207bd0f8c44392dedbae8d0449fef5c8d773a202451d42bbd8cdd`
- Cuscon, official F-Droid: `37beccb49bd00f29e42afb92d9c0dbaf63f8025826d9bcb12e8be48d6c8400ac`

The Arcticons APKs shared signer certificate SHA-256 `299bb9312e39130d0d33ed42bf98567f726ab11a04dc950c5b6c162e28c869c5`;
Delta's GitHub APK matched the official F-Droid APK signer
`e072d5b76d4133d85b762633a750871848f41ced3d6d7282339108e9bed15e2e`.
Respect each project's artwork license; in particular, Delta's F-Droid page
lists noncommercial/no-derivatives terms for its icon assets. The locally
downloaded APKs and screenshots are ignored evidence under
`.artifacts/research/community-iconpacks/`, not distributed project assets.

The initial ColorOS Classic ART+ component was restored byte-for-byte
(`355802d3375f836e40fa303bc9727b48cb58e61e9aa7760925c1844079f5b945`),
with system dark mode and Monet off. The existing launcher, system server and
SystemUI process identities remained unchanged across the recorded deployments.
Physical devices and wallpaper-color changes were not accepted in this run.
