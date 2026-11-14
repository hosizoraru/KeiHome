# Published quiet icon shapes: November 14

The retained SystemUI source explains why an ordinary notification can be round
while its miniature in "More notifications" is square. `FoldNotifControllerImpl`
obtains `getAppIconInner()` Drawables and draws them directly into an aggregate
bitmap. That path does not call the ordinary `getAppIconBitmap()` bridge.
`AppIconsManager.loadAppIcon()` can also return a FancyDrawable's quiet Drawable
without applying another style mask.

The installed theme contained opaque corners in Wallet
(`com.mipay.wallet`), Bilibili (`tv.danmaku.bili`) and Weather
(`com.miui.weather2`) quiet images. The native-preserving Perfect Icons handoff
does not run the ordinary static renderer's `maskOutput()`. Shape metadata alone
therefore cannot protect every consumer.

`PublishedIconCompositeMask` runs on the staged final `icons` archive after
shape-policy completion and before the outer theme is published. It uses the
actual completed mask and transforms only final flat icons, recognized closed
day/night composites and proven Weather root quiet composites. Dynamic XML,
resource layers and the imported source archive retain their original bytes.
It adds no SystemUI hooks or per-frame image processing.

The completion switch, legacy styles and authored non-full-mask policies retain
their existing behavior. Images already contained by the mask are copied
unchanged. Bounded unsupported dimensions or formats pass through unchanged;
previously admitted WebP/JPEG data under a `.png` path is decoded when supported
and encoded as PNG only if clipping is needed. Unsafe archive paths and canceled
work fail without publishing a partial result.

Focused tests cover stock and actual custom masks, source immutability, original
and generated Weather closure, dynamic-layer preservation, off/legacy policy,
compatible image formats, bounded geometry, and cancellation cleanup. Device
results and the independently inspected installed archive are recorded in
`.artifacts/research/brand-dark-contrast-20261114/`.

The focused run passed all eight device cases after correcting the existing
manifest-schema assertion (4 to 9) and admitting Android's earlier
`ZipException` rejection of unsafe paths. Both failed and corrected runs remain
in the evidence directory.

The Phone AVD then generated and applied the existing selected Perfect Icons
style through the normal UI. The same retained Bilibili notification, timestamped
15:59, kept its rounded ordinary icon and gained a rounded folded miniature.
Installed Wallet day/night and Weather root quiet PNGs have transparent corners.
Comparing the generated source with the installed archive found only the two
Weather root quiet PNGs changed among 54 Weather entries; its program and
individual layers remained byte-identical. The ordinary Weather row was also
checked using an explicitly owned local QA notification. This fixture does not
substitute for the reporter's XMSF-proxied physical notification.

This repair requires generating and applying the icon pack again. Installing a
new module APK cannot rewrite an already installed theme's PNG files.

The first heads-up notification emitted during KeiHome's own API102 reload
remains a separate open issue. A successfully shaped later notification or an
updated miniature does not close that transient handoff defect.
