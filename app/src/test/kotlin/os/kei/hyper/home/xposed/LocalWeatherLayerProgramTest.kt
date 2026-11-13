package os.kei.hyper.home.xposed

import org.junit.Assert.*
import org.junit.Test

class LocalWeatherLayerProgramTest {
  private val base = "layer_animating_icons/com.miui.weather2/1/fancy/"
  private val degree = '\u00b0'
  private val paths = (listOf("bg.png", "num_-.png", "num_$degree.png") +
    (0..9).map { "num_$it.png" }).mapTo(linkedSetOf()) { base + it }

  // Equivalent to generated.zip: layer_animating_icons/com.miui.weather2/1/fancy/manifest.xml.
  private val fixture = """
    <?xml version="1.0" encoding="utf-8"?>
    <Icon version="1" width="351" height="351" frameRate="0" useVariableUpdater="DateTime.Minute" screenWidth="1080">
      <VariableBinders>
        <ContentProviderBinder name="weather_provider" uri="content://weather/actualWeatherData/4" columns="temperature" countName="hasweather">
          <Variable name="temperature" type="int" column="temperature" />
          <Trigger>
            <!-- Record the successful update time. -->
            <VariableCommand name="success_time" type="number" expression="#time_sys" />
          </Trigger>
        </ContentProviderBinder>
      </VariableBinders>
      <ExternalCommands>
        <Trigger action="init,resume">
          <VariableCommand name="update_weather_time" type="number" expression="#time_sys" />
          <MultiCommand condition="(#time_sys - #success_time) } 600000">
            <BinderCommand name="weather_provider" command="refresh" delay="550" delayCondition="(#time_sys - #update_weather_time) } 500" />
          </MultiCommand>
        </Trigger>
      </ExternalCommands>
      <Image x="351 / 2" y="351 / 2" align="center" alignV="center" src="bg.png" />
      <Group visibility="#hasweather">
        <ImageChars x="351 / 2 - 84" y="351 / 2 - 102" src="num.png" string="#temperature + '$degree'" />
      </Group>
    </Icon>
  """.trimIndent()

  private fun resources(xml: String = fixture, available: Set<String> = paths, directory: String = base) =
    LocalWeatherLayerProgram.resources(xml.toByteArray(), directory, available)

  private fun reject(vararg mutations: String) {
    mutations.forEach { xml ->
      assertNotEquals("Mutation must change the fixture", fixture, xml)
      assertNull(xml, resources(xml))
    }
  }

  @Test fun generatedDarkDisplayRetainsTheOriginalDataAndRefreshContract() {
    val source = fixture.toByteArray()
    val before = source.copyOf()
    val derived = requireNotNull(os.kei.hyper.home.iconpack.NativeWeatherLayerProgram
      .darkVariant(source, base, paths))
    val nightPaths = paths.mapTo(linkedSetOf()) { base + "keihome_night/" + it.removePrefix(base) }
    val complete = paths + nightPaths
    assertArrayEquals(before, source)
    assertEquals(complete, LocalWeatherLayerProgram.resources(derived, base, complete))
    assertNull(os.kei.hyper.home.iconpack.NativeWeatherLayerProgram.darkVariant(derived, base, complete))
    val xml = derived.toString(Charsets.UTF_8)
    assertEquals(1, Regex("<ContentProviderBinder ").findAll(xml).count())
    assertEquals(1, Regex("<BinderCommand ").findAll(xml).count())
    assertEquals(2, Regex("<ImageChars ").findAll(xml).count())
    assertTrue("DateTime.Minute,DarkMode" in xml)
    for (missing in complete) {
      assertNull(missing, LocalWeatherLayerProgram.resources(derived, base, complete - missing))
    }
    for (changed in listOf(
      xml.replace("actualWeatherData/4", "actualWeatherData/5"),
      xml.replace("} 600000", "} 1"),
      xml.replace("delay=\"550\"", "delay=\"0\""),
      xml.replace("keihome_night/num.png", "../num.png"),
      xml.replace("#__darkmode", "#other"),
      xml.replace("#hasweather", "1"),
      xml.replace("</Icon>", "<BinderCommand command=\"refresh\"/></Icon>"),
    )) {
      assertNotEquals(xml, changed)
      assertNull(LocalWeatherLayerProgram.resources(changed.toByteArray(), base, complete))
    }
  }

  @Test fun weatherDerivationDoesNotRewriteUnrecognizedProgramsOrIncompleteSources() {
    val transform = os.kei.hyper.home.iconpack.NativeWeatherLayerProgram
    assertNull(transform.darkVariant(fixture.replace("actualWeatherData/4", "actualWeatherData/5")
      .toByteArray(), base, paths))
    assertNull(transform.darkVariant(fixture.toByteArray(), base, paths - (base + "num_0.png")))
    assertNull(transform.darkVariant(fixture.replace("delay=\"550\"", "delay=\"0\"")
      .toByteArray(), base, paths))
  }

  @Test fun admitsTheCompleteSourceWeatherProgramWithoutChangingInputs() {
    val bytes = fixture.toByteArray()
    val original = bytes.copyOf()
    val available = paths.toMutableSet()
    val before = available.toSet()
    val required = LocalWeatherLayerProgram.resources(bytes, base, available)
    assertEquals(paths, required)
    assertEquals(13, requireNotNull(required).size)
    assertFalse(base + "num.png" in required)
    assertArrayEquals(original, bytes)
    assertEquals(before, available)
    assertEquals(paths, resources(available = paths + (base + "unrelated.png")))
    val night = "nightmode/$base"
    assertEquals(paths.mapTo(linkedSetOf()) { "nightmode/$it" },
      resources(directory = night, available = paths.mapTo(linkedSetOf()) { "nightmode/$it" }))
  }

  @Test fun permitsXmlFormattingAttributeOrderCommentsAndEquivalentEscaping() {
    val reordered = fixture
      .replace("version=\"1\" width=\"351\"", "width = '351'\nversion = '1'")
      .replace("name=\"weather_provider\" uri=", "uri=")
      .replace("columns=\"temperature\"", "columns=\"temperature\" name=\"weather_provider\"")
      .replace("<VariableBinders>", "<!-- <?ignored in comment?> -->\n<VariableBinders>")
      .replace("<Group visibility=\"#hasweather\">", "<Group\n visibility = '#hasweather' >")
    // Explicit end tags are equivalent to self-closing tags.
    val expanded = Regex("<([A-Za-z]+)([^<>]*) />").replace(reordered) {
      "<${it.groupValues[1]}${it.groupValues[2]}></${it.groupValues[1]}>"
    }.replace(degree.toString(), "&#176;")
    assertEquals(paths, resources(expanded))
    assertEquals(paths, resources(fixture.substringAfter("?>").trim()))
    val utf16 = fixture.replace("encoding=\"utf-8\"", "encoding=\"utf-16\"").toByteArray(Charsets.UTF_16)
    assertEquals(paths, LocalWeatherLayerProgram.resources(utf16, base, paths))
  }

  @Test fun rejectsAnyOtherProviderUriOrQueryShape() {
    val uri = "content://weather/actualWeatherData/4"
    for (replacement in listOf(
      "content://weather/actualWeatherData/5", "content://weather/actualWeatherData",
      "content://other/actualWeatherData/4", "$uri?temperature=1", "$uri/",
      "https://weather/actualWeatherData/4", "file:///data/local/tmp/weather",
    )) reject(fixture.replace(uri, replacement))
    reject(
      fixture.replace("columns=\"temperature\"", "columns=\"temperature,city\""),
      fixture.replace("columns=\"temperature\"", "columns=\"*\""),
      fixture.replace("column=\"temperature\"", "column=\"humidity\""),
      fixture.replace("columns=\"temperature\"", ""),
      fixture.replace("countName=\"hasweather\"", "countName=\"hasweather\" where=\"1=1\""),
      fixture.replace("countName=\"hasweather\"", "countName=\"hasweather\" selection=\"temperature > 0\""),
      fixture.replace("countName=\"hasweather\"", "countName=\"hasweather\" order=\"temperature\""),
      fixture.replace("countName=\"hasweather\"", "countName=\"hasweather\" updateInterval=\"1\""),
    )
  }

  @Test fun requiresTheOriginalRefreshGateAndBothDelaysExactly() {
    for (value in listOf("0", "1", "599999", "600001", "#minute", "600000+0")) {
      reject(fixture.replace("} 600000", "} $value"))
    }
    for (value in listOf("0", "549", "551", "#second")) {
      reject(fixture.replace("delay=\"550\"", "delay=\"$value\""))
    }
    for (value in listOf("0", "499", "501", "#second")) {
      reject(fixture.replace("} 500\"", "} $value\""))
    }
    reject(
      fixture.replace("condition=\"(#time_sys - #success_time) } 600000\"", ""),
      fixture.replace("delayCondition=\"(#time_sys - #update_weather_time) } 500\"", ""),
      fixture.replace("delay=\"550\"", ""),
      fixture.replace("command=\"refresh\"", "command=\"query\""),
      fixture.replace("#time_sys - #success_time", "#time_sys + #success_time"),
      fixture.replace("#time_sys - #update_weather_time", "#time_sys - #success_time"),
    )
  }

  @Test fun rejectsAddedRemovedReorderedOrReparentedElements() {
    val command = """<VariableCommand name="update_weather_time" type="number" expression="#time_sys" />"""
    val variable = """<Variable name="temperature" type="int" column="temperature" />"""
    val binder = fixture.substringAfter("<VariableBinders>").substringBefore("</VariableBinders>")
    reject(
      fixture.replace(command, command + command),
      fixture.replace(command, ""),
      fixture.replace("<MultiCommand", "<BinderCommand name='weather_provider' command='refresh'/><MultiCommand"),
      fixture.replace("</VariableBinders>", "$binder</VariableBinders>"),
      fixture.replace("</VariableBinders>", "<ContentProviderBinder/></VariableBinders>"),
      fixture.replace("</Icon>", "<VariableBinders/></Icon>"),
      fixture.replace("</Icon>", "<Var name='other' expression='0'/></Icon>"),
      fixture.replace("</Icon>", "<Text text='weather'/></Icon>"),
      fixture.replace("</Icon>", "<Function name='run'/></Icon>"),
      fixture.replace("</Icon>", "<Script>run()</Script></Icon>"),
      fixture.replace("<ExternalCommands>", "<ExternalCommands><Trigger action='pause'/>"),
      fixture.replace(variable, "").replace("<Trigger>", "<Trigger>$variable"),
      fixture.replace(variable, "").replace("</Trigger>", "</Trigger>$variable"),
      fixture.replace("</Icon>", "</Icon><Icon/>"),
      fixture.replace("</Group>", ""),
    )
  }

  @Test fun fixesAllNamesTypesEventsAndAuthoredCanvasValues() {
    for (name in listOf("weather_provider", "temperature", "hasweather", "success_time", "update_weather_time")) {
      reject(fixture.replace(name, "${name}_other"))
    }
    reject(
      fixture.replace("action=\"init,resume\"", "action=\"init,resume,present\""),
      fixture.replace("action=\"init,resume\"", "action=\"resume,init\""),
      fixture.replace("type=\"int\"", "type=\"string\""),
      fixture.replace("type=\"number\"", "type=\"int\""),
      fixture.replace("expression=\"#time_sys\"", "expression=\"0\""),
      fixture.replace("width=\"351\"", "width=\"1080\""),
      fixture.replace("height=\"351\"", "height=\"352\""),
      fixture.replace("screenWidth=\"1080\"", "screenWidth=\"351\""),
      fixture.replace("frameRate=\"0\"", "frameRate=\"60\""),
      fixture.replace("DateTime.Minute", "DateTime.Second"),
      fixture.replace("<Icon ", "<Icon debug='true' "),
      fixture.replace("<Icon ", "<Icon xmlns='urn:other' "),
      fixture.replace("visibility=\"#hasweather\"", "visibility=\"1\""),
      fixture.replace("#temperature + '$degree'", "@temperature"),
      fixture.replace("351 / 2 - 84", "351 / 2 - 85"),
    )
  }

  @Test fun requiresLocalFixedImageSourcesAndEveryTexture() {
    for (source in listOf("../bg.png", "/bg.png", "file:/bg.png", "https://example.com/bg.png",
      "sub/bg.png", "BG.png", "bg%2epng", "bg\\png")) {
      reject(fixture.replace("src=\"bg.png\"", "src=\"$source\""))
    }
    reject(
      fixture.replace("src=\"num.png\"", "src=\"../num.png\""),
      fixture.replace("src=\"num.png\"", "src=\"other.png\""),
      fixture.replace("src=\"bg.png\"", "src=\"bg.png\" srcExp=\"@other\""),
    )
    paths.forEach { missing ->
      assertNull(missing, resources(available = paths - missing))
      val alias = base + "./" + missing.removePrefix(base)
      assertNull(missing, resources(available = paths - missing + alias))
    }
    assertNull(resources(available = paths - (base + "num_$degree.png") + (base + "num_?.png")))
  }

  @Test fun rejectsUnsafeArchiveBasePaths() {
    for (directory in listOf("", "/", base.removeSuffix("/"), "../$base", "/$base",
      "a/../$base", "a/./$base", "a//$base", "a\\$base", "file:/$base",
      "a/%2e%2e/$base", "a/\u0000/$base", "a/".repeat(600))) {
      val available = paths.mapTo(linkedSetOf()) { directory + it.removePrefix(base) }
      assertNull(directory, resources(directory = directory, available = available))
    }
  }

  @Test fun rejectsDtdEntitiesInstructionsExtraTextAndMalformedOrOversizedXml() {
    val body = fixture.substringAfter("?>").trim()
    reject(
      "<!DOCTYPE Icon>$body",
      "<!DOCTYPE Icon SYSTEM 'file:///etc/passwd'>$body",
      "<!DOCTYPE Icon [<!ENTITY temp 'temperature'>]>" + body.replace("columns=\"temperature\"", "columns=\"&temp;\""),
      "<?instruction run='true'?>$body",
      fixture.replace("<VariableBinders>", "<?instruction?><VariableBinders>"),
      fixture.replace("<Group", "extra<Group"),
      fixture.replace("<Group", "<![CDATA[extra]]><Group"),
      fixture.replace("<Group", "\u00a0<Group"),
      fixture.replace("</Icon>", "</Icon>extra"),
      fixture.replace("<Icon ", "<Icon version='1' "),
      fixture.replace("bg.png", "bg&unknown;.png"),
      fixture.replace("</Icon>", "<!--" + "x".repeat(16 * 1024) + "--></Icon>"),
      "",
    )
    val dtd16 = ("<!DOCTYPE Icon>$body").toByteArray(Charsets.UTF_16)
    assertNull(LocalWeatherLayerProgram.resources(dtd16, base, paths))
    val malformedUtf8 = fixture.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28)
    assertNull(LocalWeatherLayerProgram.resources(malformedUtf8, base, paths))
    assertNull(LocalWeatherLayerProgram.resources(byteArrayOf(), base, paths))
  }
}
