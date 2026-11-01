package os.kei.hyper.home.iconpack

/** Fixed local MAML template. Provider delivery and visible live tint still require device proof. */
internal object NativePaletteIcon {
  const val FOREGROUND_KEY = "keihome_maml_fg_hex"
  const val BACKGROUND_KEY = "keihome_maml_bg_hex"
  const val ENABLED_KEY = "keihome_maml_enabled"

  fun entries(
    nativeName: String, lightPng: ByteArray, darkPng: ByteArray,
    monochromePng: ByteArray, backgroundMaskPng: ByteArray,
  ): Map<String, ByteArray> {
    val pair = NativeDayNightIcon.entries(nativeName, lightPng, darkPng)
    val size = GeneratedIconPng.squareSize(lightPng)
    require(GeneratedIconPng.squareSize(darkPng) == size) { "Day/night PNG dimensions must match" }
    GeneratedIconPng.requireAlphaMask(monochromePng, size)
    GeneratedIconPng.requireAlphaMask(backgroundMaskPng, size)
    val root = "fancy_icons/$nativeName"
    val manifest = """
      <?xml version="1.0" encoding="utf-8"?>
      <Icon version="1" width="$size" height="$size" screenWidth="1080" resDensity="480" frameRate="0" useVariableUpdater="none">
        <VariableBinders>
          <ContentProviderBinder name="palette_fg" uri="content://settings/system/$FOREGROUND_KEY" columns="value" updateInterval="-1" queryAtStart="true">
            <Variable name="palette_fg" type="string" column="value" row="0" />
          </ContentProviderBinder>
          <ContentProviderBinder name="palette_bg" uri="content://settings/system/$BACKGROUND_KEY" columns="value" updateInterval="-1" queryAtStart="true">
            <Variable name="palette_bg" type="string" column="value" row="0" />
          </ContentProviderBinder>
          <ContentProviderBinder name="palette_enabled" uri="content://settings/system/$ENABLED_KEY" columns="value" updateInterval="-1" queryAtStart="true">
            <Variable name="palette_enabled" type="int" column="value" row="0" />
          </ContentProviderBinder>
        </VariableBinders>
        <Image x="0" y="0" w="$size" h="$size" src="day.png" visibility="(1-#palette_enabled)*(1-#__darkmode)" />
        <Image x="0" y="0" w="$size" h="$size" src="night.png" visibility="(1-#palette_enabled)*#__darkmode" />
        <Image x="0" y="0" w="$size" h="$size" src="bg.png" tint="@palette_bg" tintmode="5" visibility="#palette_enabled" />
        <Image x="0" y="0" w="$size" h="$size" src="mask.png" tint="@palette_fg" tintmode="5" visibility="#palette_enabled" />
      </Icon>
    """.trimIndent() + "\n"
    return pair + linkedMapOf(
      "nightmode/res/drawable-xxhdpi/$nativeName.png" to darkPng.copyOf(),
      "$root/manifest.xml" to manifest.toByteArray(Charsets.UTF_8),
      "$root/mask.png" to monochromePng.copyOf(),
      "$root/bg.png" to backgroundMaskPng.copyOf(),
    )
  }
}
