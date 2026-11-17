package os.kei.hyper.home.iconpack

/** Event-driven palette template; exact legacy variants remain available for import validation. */
internal object NativePaletteIcon {
  fun recognizes(
    nativeName: String, manifest: ByteArray, day: ByteArray, night: ByteArray,
    mask: ByteArray, background: ByteArray,
  ): Boolean = listOf(false, true).any { expanded -> listOf(false, true).any { scalar ->
    try {
      entries(nativeName, day, night, mask, background,
        legacyExpanded = expanded, legacyScalarBinders = scalar)
        .getValue("fancy_icons/$nativeName/manifest.xml").contentEquals(manifest)
    } catch (_: IllegalArgumentException) { false }
  } }

  const val FOREGROUND_KEY = "keihome_maml_fg_hex"
  const val BACKGROUND_KEY = "keihome_maml_bg_hex"
  const val ENABLED_KEY = "keihome_maml_enabled"
  const val STATE_KEY = "keihome_maml_palette_state"

  /** Opaque RGB pair plus enable at bit 48; every value is exactly representable as a double. */
  fun packedState(foreground: Int, background: Int): Long =
    (1L shl 48) or ((foreground.toLong() and 0xffffff) shl 24) or (background.toLong() and 0xffffff)

  fun entries(
    nativeName: String, lightPng: ByteArray, darkPng: ByteArray,
    monochromePng: ByteArray, backgroundMaskPng: ByteArray,
    legacyExpanded: Boolean = false, legacyScalarBinders: Boolean = false,
  ): Map<String, ByteArray> {
    val pair = NativeDayNightIcon.entries(nativeName, lightPng, darkPng, legacyExpanded = legacyExpanded)
    val size = GeneratedIconPng.squareSize(lightPng)
    require(GeneratedIconPng.squareSize(darkPng) == size) { "Day/night PNG dimensions must match" }
    GeneratedIconPng.requireAlphaMask(monochromePng, size)
    GeneratedIconPng.requireAlphaMask(backgroundMaskPng, size)
    val root = "fancy_icons/$nativeName"
    val compact = "$root/night.png" !in pair
    val enabled = if (legacyScalarBinders) "#palette_enabled" else "int(#palette_state/281474976710656)"
    val dayVisibility = if (compact) "(1-$enabled)" else "(1-$enabled)*(1-#__darkmode)"
    val nightImage = if (compact) "" else
      "\n        <Image x=\"0\" y=\"0\" w=\"$size\" h=\"$size\" src=\"night.png\" visibility=\"(1-$enabled)*#__darkmode\" />"
    // Retained Rust MAML: tint -> ColorParser::new -> argb build_multiple_inner;
    // AnimatedScreenElement::do_tick calls get_color again. '#' itself is literal hex.
    // Reduce modulo before int() so even a 32-bit integer conversion cannot overflow.
    val fgTint = if (legacyScalarBinders) "@palette_fg" else
      "argb(255,int((#palette_state%281474976710656)/1099511627776)," +
        "int((#palette_state%1099511627776)/4294967296),int((#palette_state%4294967296)/16777216))"
    val bgTint = if (legacyScalarBinders) "@palette_bg" else
      "argb(255,int((#palette_state%16777216)/65536),int((#palette_state%65536)/256),#palette_state%256)"
    // Keep this exact scalar template available for already-applied archive validation.
    val binders = if (legacyScalarBinders) """
      <ContentProviderBinder name="palette_fg" uri="content://settings/system/$FOREGROUND_KEY" columns="value" updateInterval="-1" queryAtStart="true">
        <Variable name="palette_fg" type="string" column="value" row="0" />
      </ContentProviderBinder>
      <ContentProviderBinder name="palette_bg" uri="content://settings/system/$BACKGROUND_KEY" columns="value" updateInterval="-1" queryAtStart="true">
        <Variable name="palette_bg" type="string" column="value" row="0" />
      </ContentProviderBinder>
      <ContentProviderBinder name="palette_enabled" uri="content://settings/system/$ENABLED_KEY" columns="value" updateInterval="-1" queryAtStart="true">
        <Variable name="palette_enabled" type="int" column="value" row="0" />
      </ContentProviderBinder>
    """.trimIndent() else """
      <ContentProviderBinder name="palette_state" uri="content://settings/system/$STATE_KEY" columns="value" updateInterval="-1" queryAtStart="true">
        <Variable name="palette_state" type="double" column="value" row="0" />
      </ContentProviderBinder>
    """.trimIndent()
    val manifest = """
      <?xml version="1.0" encoding="utf-8"?>
      <Icon version="1" width="$size" height="$size" screenWidth="1080" resDensity="480" frameRate="0" useVariableUpdater="none">
        <VariableBinders>
${binders.prependIndent("          ")}
        </VariableBinders>
        <Image x="0" y="0" w="$size" h="$size" src="day.png" visibility="$dayVisibility" />$nightImage
        <Image x="0" y="0" w="$size" h="$size" src="bg.png" tint="$bgTint" tintmode="5" visibility="$enabled" />
        <Image x="0" y="0" w="$size" h="$size" src="mask.png" tint="$fgTint" tintmode="5" visibility="$enabled" />
      </Icon>
    """.trimIndent() + "\n"
    return pair + linkedMapOf(
      "$root/manifest.xml" to manifest.toByteArray(Charsets.UTF_8),
      "$root/mask.png" to monochromePng.copyOf(),
      "$root/bg.png" to backgroundMaskPng.copyOf(),
    )
  }
}
