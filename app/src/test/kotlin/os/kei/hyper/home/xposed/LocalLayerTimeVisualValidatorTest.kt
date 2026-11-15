package os.kei.hyper.home.xposed

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import os.kei.hyper.home.iconpack.NativeIconPngFixtures

class LocalLayerTimeVisualValidatorTest {
  @Test fun layeredCalendarAdmitsOnlyTheExistingReadOnlyClockBroadcasts() {
    admit("<VariableBinders/>")
    val actions = listOf("TIME_SET", "DATE_CHANGED", "TIMEZONE_CHANGED", "LOCALE_CHANGED")
    admit("<VariableBinders>" + actions.joinToString("") {
      "<BroadcastBinder action='android.intent.action.$it'/>"
    } + "</VariableBinders>")
    for (binder in listOf(
      "<BroadcastBinder action='android.intent.action.BATTERY_CHANGED'/>",
      "<BroadcastBinder action='android.intent.action.DATE_CHANGED' name='extra'/>",
      "<ContentProviderBinder uri='content://weather'/>",
      "<SettingsBinder/>",
      "<BroadcastBinder action='android.intent.action.DATE_CHANGED'><Variable name='payload'/></BroadcastBinder>",
      "<BroadcastBinder action='android.intent.action.DATE_CHANGED'/><BroadcastBinder action='android.intent.action.DATE_CHANGED'/>",
    )) reject("<VariableBinders>$binder</VariableBinders>")
    reject("<Group><VariableBinders/></Group>")
    reject("<VariableBinders/><VariableBinders/>")
    reject("<BroadcastBinder action='android.intent.action.DATE_CHANGED'/>")
  }
  @get:Rule val temporary = TemporaryFolder()
  private val base = "layer_animating_icons/com.example/0/fancy/"
  private val png = NativeIconPngFixtures.rgba(4)
  private val image = "<Image name='hand' src='icon.png'/>"
  private val commands = setOf("init", "present", "resume", "pause", "back_home_start",
    "back_home_finish", "back_home_cancel", "open_second", "close_second",
    "open_second_instant", "close_second_instant")

  @Test fun frameworkStaticPairsCoexistWithFlatArtworkInMixedArchives() {
    val paths = setOf("res/drawable-xxhdpi/com.example.png",
      "nightmode/res/drawable-xxhdpi/com.example.png",
      "res/drawable-xxhdpi/android/0.png", "res/drawable-xxhdpi/android/1.png")
    val checked = mutableSetOf<String>()
    val admitted = LocalLayerIconValidator.validate(paths, { path, _ ->
      checked += path
      png
    }, {})
    assertEquals(paths.filter { it.endsWith("/0.png") || it.endsWith("/1.png") }.toSet(), admitted)
    assertEquals(admitted, checked)
    assertThrows(IllegalArgumentException::class.java) {
      LocalLayerIconValidator.validate(paths + "res/drawable-xxhdpi/android/nested/0.png",
        { _, _ -> png }, {})
    }
    assertThrows(IllegalArgumentException::class.java) {
      LocalLayerIconValidator.validate(paths - "res/drawable-xxhdpi/android/1.png",
        { _, _ -> png }, {})
    }
  }

  @Test fun isolatedBoundedGroupsAdmitPureBitmapCompositingModes() {
    for (mode in listOf("clear", "src", "dst", "src_over", "dst_over", "src_in", "dst_in",
      "src_out", "dst_out", "src_atop", "dst_atop", "xor", "darken", "lighten",
      "multiply", "screen", "add", "overlay")) {
      admit("<Group layered='true' w='324' h='324'><Image src='icon.png' xfermode='$mode'/></Group>")
    }
    admit("<Group layered='false'><Image src='icon.png'/></Group>")
  }

  @Test fun canvasNamesDoNotChangeTheRootRoleOrPermitUnknownChildren() {
    fun renamed(name: String, body: String = "", attributes: String = "") =
      resources(body, attributes, emptyMap(), true).mapValues { (path, bytes) ->
        if (path == base + "manifest.xml") bytes.toString(Charsets.UTF_8)
          .replaceFirst("<Icon ", "<$name ").replace("</Icon>", "</$name>").toByteArray() else bytes
      }
    for (name in listOf("bg", "fg", "CustomCanvas", "_canvas.layer-1")) {
      LocalNativeIconValidator.validate(archive(renamed(name)))
      for (body in listOf("<bg><Image src='icon.png'/></bg>", "<IntentCommand action='test'/>",
        "<VariableBinders><ContentProviderBinder/></VariableBinders>", "<Script/>")) {
        assertThrows(Exception::class.java) { LocalNativeIconValidator.validate(archive(renamed(name, body))) }
      }
      assertThrows(Exception::class.java) {
        LocalNativeIconValidator.validate(archive(renamed(name, attributes = "src='file:///tmp/icon'")))
      }
    }
    assertThrows(Exception::class.java) { LocalNativeIconValidator.validate(archive(renamed("a".repeat(65)))) }
  }

  @Test fun numericVisualIdsResolveWithoutPermittingNumericFunctionsOrVariables() {
    fun scene(target: String, imageName: String) =
      trigger("<FolmeCommand target='$target' states=\"'end'\" command='setTo' config=\"'cfg'\"/>") +
        "<Image name='$imageName' src='icon.png'/><FolmeState name='end' x='0'/><FolmeConfig name='cfg'/>"
    admit(scene("1", "1"))
    admit(scene("0003", "0003"))
    admit(scene("1_icon", "1_icon"))
    reject(scene("2", "1"))
    reject(scene("1", "1") + "<Image name='1' src='icon.png'/>")
    for (invalid in listOf("1.x", "1,2", "1+2", "1".repeat(65))) {
      reject(scene(invalid, invalid))
    }
    reject("<Var name='1' expression='0'/>")
    reject("<Function name='1'/>")
    reject("<FolmeState name='1' x='0'/>")
  }

  @Test fun missingSingleLiteralSetToStateKeepsNativeNoOpSemantics() {
    fun command(states: String = "'absent'", action: String = "setTo",
      target: String = "hand", config: String = "") =
      trigger("<FolmeCommand target='$target' states=\"$states\" command='$action' $config/>")
    admit(command())
    admit(command(config = "config=\"'cfg'\"") + "<FolmeConfig name='cfg'/>")
    reject(command(action = "to"))
    reject(command(action = "fromTo"))
    reject(command(states = "'absent','present'") + "<FolmeState name='present' x='0'/>")
    reject(command(states = "#dynamic"))
    reject(command(states = "''"))
    reject(command(states = "'${"x".repeat(65)}'"))
    reject(command(target = "missing"))
    reject(command(config = "config=\"'missing'\""))
  }

  @Test fun mamlComparisonAliasesKeepTheSameRestrictedExpressionCapabilities() {
    for (operator in listOf("}", "{", "}=", "{=")) {
      admit("<Var name='side' expression='ifelse(#ratio${operator}0,1,-1)'/>")
      reject("<Var name='side' expression='queryProvider(#ratio${operator}0)'/>")
      reject("<Var name='side' expression='ifelse(#unknown${operator}0,1,-1)'/>")
    }
    reject("<Var name='side' expression='#ratio{{1'/>")
    reject("<Var name='side' expression='#ratio}}1'/>")
  }

  @Test fun blendModesRequireIsolationAndLiteralKnownValues() {
    reject("<Image src='icon.png' xfermode='src_atop'/>")
    reject("<Group layered='false'><Image src='icon.png' xfermode='dst_out'/></Group>")
    for (mode in listOf("SRC_ATOP", "unknown", "#mode", "src_atop,clear")) {
      reject("<Group layered='true' w='32' h='32'><Image src='icon.png' xfermode='$mode'/></Group>")
    }
    reject("<Group layered='1' w='32' h='32'><Image src='icon.png'/></Group>")
    reject("<Group layeredExp='1' w='32' h='32'><Image src='icon.png'/></Group>")
  }

  @Test fun isolatedGroupDimensionsCountsNestingAndAggregateSurfacesStayBounded() {
    for (dimensions in listOf("", "w='32'", "w='0' h='32'", "w='-1' h='32'",
      "w='2049' h='1'", "w='32' h='2049'", "w='#time_sys' h='32'")) {
      reject("<Group layered='true' $dimensions><Image src='icon.png'/></Group>")
    }
    val tiny = "<Group layered='true' w='1' h='1'><Image src='icon.png'/></Group>"
    admit(tiny.repeat(8))
    reject(tiny.repeat(9))
    fun nested(count: Int) = "<Group layered='true' w='1' h='1'>".repeat(count) +
      "<Image src='icon.png'/>" + "</Group>".repeat(count)
    admit(nested(4))
    reject(nested(5))
    admit("<Group layered='true' w='2048' h='2048'><Image src='icon.png'/></Group>")
    reject("<Group layered='true' w='2048' h='2048'><Image src='icon.png'/></Group>$tiny")
  }

  @Test fun admitsTimeUpdatersOnlyFromTheExactEnum() {
    for (updater in listOf("Day", "Hour", "Minute", "Second")) admit("", "useVariableUpdater='DateTime.$updater'")
    for (updater in listOf("DateTime", "DateTime.Millisecond", "Battery", "DateTime.Day,Content",
      "DateTime.Second;Intent", "dateTime.Second")) {
      reject("", "useVariableUpdater='$updater'")
    }
  }

  @Test fun localDarkModeSelectorsRequireTheirUpdaterAndCompleteTexturePair() {
    val body = """<Image name="hand" src="icon.png"
      srcExp="ifelse(#__darkmode,'keihome_night/icon.png','icon.png')"/>"""
    val textures = mapOf(base + "keihome_night/icon.png" to png)
    for (updater in listOf("DarkMode", "DateTime.Minute,DarkMode", "DateTime.Second,DarkMode")) {
      admit(body, "frameRate='0' useVariableUpdater='$updater'", textures, includeImage = false)
    }
    for (changed in listOf(
      body.replace("#__darkmode", "#second"),
      body.replace("keihome_night/icon.png", "../icon.png"),
      body.replace("keihome_night/icon.png", "content://weather/icon.png"),
      body.replace("ifelse(", "eval("),
      body.replace("src=\"icon.png\"", "src=\"icon.png\" srcid=\"#date\""),
    )) assertThrows(Exception::class.java) {
      admit(changed, "useVariableUpdater='DarkMode'", textures, includeImage = false)
    }
    assertThrows(Exception::class.java) {
      admit(body, "useVariableUpdater='DarkMode'", includeImage = false)
    }
    assertThrows(Exception::class.java) {
      admit(body, "useVariableUpdater='DateTime.Minute'", textures, includeImage = false)
    }
  }

  @Test fun appearanceNumbersAreReadOnlyAndDoNotEnableArbitraryUpdaters() {
    admit("<Image src='icon.png' visibility='not(#__darkmode)'/>", "useVariableUpdater='DarkMode'")
    admit("", "useVariableUpdater='none'")
    reject("<Image src='icon.png' visibility='not(#__darkmode)'/>")
    reject(trigger("<VariableCommand name='__darkmode' type='number' expression='0'/>"),
      "useVariableUpdater='DarkMode'")
    for (updater in listOf("DarkMode,DarkMode", "DateTime.Hour,DateTime.Second",
      "DarkMode,ContentProvider", "none,DarkMode", "DarkMode,DateTime.Second,DateTime.Minute")) {
      reject("", "useVariableUpdater='$updater'")
    }
  }

  @Test fun admitsOnlyKnownLifecycleAndSecondHandTriggerActions() {
    commands.forEach { action -> admit(trigger("<FrameRateCommand rate='30' delay='100'/>", action)) }
    for (action in listOf("arbitrary", "open_second,arbitrary", "onChange", "intent", "")) {
      reject(trigger("<FrameRateCommand rate='0'/>", action))
    }
  }

  @Test fun permitsOnlyTheExactDatetimeExternalCommandAndLiteralParameters() {
    for (time in listOf("Second", "Minute")) {
      admit(trigger("<ExternCommand command='__datatimeVarUpdate' strPara=\"'DateTime.$time'\"/>"))
    }
    for (xml in listOf(
      "<ExternCommand command='anything' strPara=\"'DateTime.Second'\"/>",
      "<ExternCommand command='__datatimeVarUpdate' strPara=\"'DateTime.Day'\"/>",
      "<ExternCommand command='__datatimeVarUpdate' strPara='@computed'/>",
      "<ExternCommand command='__datatimeVarUpdate' strPara=\"'DateTime.Second','other'\"/>",
      "<ExternCommand command='__datatimeVarUpdate' strPara=\"'DateTime.Second'\" numPara='0'/>",
      "<ExternCommand command='__datatimeVarUpdate'/>",
      "<IntentCommand action='android.intent.action.VIEW'/>",
      "<Binder host='example.com'/>",
    )) reject(trigger(xml))
  }

  @Test fun dateImagesRequireExactSelectorAndAllThirtyOneFrames() {
    val frames = (1..31).associate { base + "num_$it.png" to png }
    val date = "<Image src='num.png' srcid='#date' cornerRadius='0'/>"
    admit(date, extra = frames)
    for (selector in listOf("#month", "#date+1", "@date", "file:/date", "#date.text_width"))
      reject(date.replace("#date", selector), extra = frames)
    for (day in listOf(1, 16, 31)) reject(date, extra = frames - (base + "num_$day.png"))
    reject(date, extra = frames + (base + "num_16.png" to byteArrayOf(1, 2)))
    reject(date.replace("num.png", "../num.png"), extra = frames)
  }

  @Test fun weekdayTextIsAnExactLocalTimeVisualWithDeclaredTextMetrics() {
    val text = "<Text name='weekSize' pivotX='#weekSize.text_width/2' " +
      "pivotY='#weekSize.text_height/2' scale='min(1,190/#weekSize.text_width)' " +
      "size='44' fontFamily='mipro-semibold' color='#3482FF' textExp=\"formatDate('E',#time_sys)\"/>"
    admit(text, includeImage = false)
    reject(text.replace("#weekSize.text_width", "#missing.text_width"))
    reject(text.replace("formatDate('E',#time_sys)", "formatDate(@format,#time_sys)"))
    reject(text.replace("formatDate('E',#time_sys)", "eval(@content)"))
    reject(text.replace("mipro-semibold", "file:/font.ttf"))
  }

  @Test fun localFunctionsMayCallAcyclicFunctionsAndFolmeCallbacks() {
    val body = """
      <FolmeState name="shown" alpha="255"/>
      <FolmeConfig name="config" property="'alpha'" ease="6, 300" fromSpeed="0"
        onBegin="'start'" onComplete="'end'"/>
      <Function name="start"><FrameRateCommand rate="90"/></Function>
      <Function name="end">
        <IfCommand ifCondition="#second_visible == 1">
          <Consequent><ExternCommand command="__datatimeVarUpdate" strPara="'DateTime.Second'"/></Consequent>
          <Consequent><ExternCommand command="__datatimeVarUpdate" strPara="'DateTime.Minute'"/></Consequent>
        </IfCommand>
        <FrameRateCommand rate="0"/>
      </Function>
      <Function name="show"><FolmeCommand target="hand" states="'shown'" command="to" config="'config'"/></Function>
    """.trimIndent() + trigger("<VariableCommand name='second_visible' type='number' expression='1'/>" +
      "<FunctionCommand target='show'/>", "open_second")
    admit(body)
  }

  @Test fun rejectsUndeclaredFunctionCallsAndCallbacks() {
    reject(trigger("<FunctionCommand target='missing'/>"))
    reject("<FolmeConfig name='config' onComplete=\"'missing'\"/>")
    reject("<Function name='same'/><Function name='same'/>")
    reject("<Function name='bad.name'/>")
    reject(trigger("<FunctionCommand targetExp='@computed'/>"))
  }

  @Test fun rejectsDirectAndIndirectFunctionRecursionEvenWhenUnreachable() {
    reject("<Function name='a'><FunctionCommand target='a'/></Function>")
    reject("<Function name='a'><FunctionCommand target='b'/></Function>" +
      "<Function name='b'><FunctionCommand target='a'/></Function>")
  }

  @Test fun rejectsCyclesThroughFolmeBeginAndCompleteCallbacks() {
    for (callback in listOf("onBegin", "onComplete")) {
      reject("<FolmeState name='shown' alpha='255'/>" +
        "<FolmeConfig name='config' $callback=\"'cycle'\"/>" +
        "<Function name='cycle'><FolmeCommand target='hand' states=\"'shown'\" command='to' config=\"'config'\"/></Function>")
    }
    reject("<FolmeState name='shown' alpha='255'/>" +
      "<FolmeConfig name='one' onBegin=\"'a'\"/><FolmeConfig name='two' onComplete=\"'b'\"/>" +
      "<Function name='a'><FolmeCommand target='hand' states=\"'shown'\" command='to' config=\"'two'\"/></Function>" +
      "<Function name='b'><FolmeCommand target='hand' states=\"'shown'\" command='to' config=\"'one'\"/></Function>")
  }

  @Test fun acyclicFunctionExpansionAndDepthAreAlsoBounded() {
    fun graph(count: Int, copies: Int) = (0 until count).joinToString("") { index ->
      "<Function name='f$index'>" + if (index == 0) "<FrameRateCommand rate='0'/></Function>"
      else "<FunctionCommand target='f${index - 1}'/>".repeat(copies) + "</Function>"
    }
    admit(graph(4, 1) + trigger("<FunctionCommand target='f3'/>"))
    admit(graph(11, 2) + trigger("<FunctionCommand target='f10'/>"))
    val doublingFailure = assertThrows(Exception::class.java) {
      admit(graph(12, 2) + trigger("<FunctionCommand target='f11'/>"))
    }
    assertEquals("Local visual function expansion limit", doublingFailure.message)
    reject(graph(18, 1))
    reject(graph(14, 2))
    reject((0..32).joinToString("") { "<Function name='f$it'><FrameRateCommand rate='0'/></Function>" })
  }

  @Test fun conditionalsLocalNumberAssignmentsAndFrameDelaysStayBounded() {
    admit(trigger("<VariableCommand name='second_visible' type='number' expression='1'/>" +
      "<IfCommand ifCondition='#second_visible == 1'><Consequent>" +
      "<FrameRateCommand rate='60' delay='1000'/></Consequent></IfCommand>"))
    for (body in listOf(
      "<VariableCommand name='time_sys' type='number' expression='0'/>",
      "<VariableCommand name='hand.alpha' type='number' expression='0'/>",
      "<VariableCommand name='local' type='string' expression='0'/>",
      "<VariableCommand name='local' type='number' expression='eval(#second)'/>",
      "<IfCommand ifCondition='@external'><Consequent/></IfCommand>",
      "<IfCommand ifCondition='1'><Consequent/><Consequent/><Consequent/></IfCommand>",
      "<FrameRateCommand rate='121'/>", "<FrameRateCommand rate='0' delay='#second'/>",
      "<FrameRateCommand rate='0' delay='-1'/>", "<FrameRateCommand rate='0' delay='60001'/>",
    )) reject(trigger(body))
  }

  @Test fun realBrowserMamlUsesOnlyLocalValidatedLottieJsonAndDeclaredCommands() {
    val xml = requireNotNull(javaClass.getResourceAsStream("/perfect-icons/browser-lottie-manifest.xml"))
      .use { it.readBytes().toString(Charsets.UTF_8) }
    val json = """{"v":"5.7.4","fr":30,"ip":0,"op":60,"w":351,"h":351,"assets":[],"layers":[
      {"ty":4,"ind":1,"ks":{},"ip":0,"op":60,"shapes":[
        {"ty":"rc","p":{"a":0,"k":[0,0]},"s":{"a":0,"k":[1,1]},"r":{"a":0,"k":0}}
      ]}]}""".toByteArray()
    val source = resources("", "", emptyMap(), true) + mapOf(
      base + "manifest.xml" to xml.toByteArray(),
      base + "com.android.browser.json" to json,
    )
    LocalNativeIconValidator.validate(archive(source))
    val originalVector = requireNotNull(javaClass.getResourceAsStream(
      "/os/kei/hyper/home/xposed/lottie/HyperOSClassic-com.android.browser.json",
    )).use { it.readBytes() }
    val realBrowser = archive(source + (base + "com.android.browser.json" to originalVector))
    val realBrowserBefore = realBrowser.readBytes()
    LocalNativeIconValidator.validate(realBrowser)
    assertArrayEquals(realBrowserBefore, realBrowser.readBytes())
    fun completion(command: String, declarations: String = "") = xml
      .replace("<FrameRateCommand rate=\"0\" />", command)
      .replace("</Icon>", "$declarations</Icon>")
    val malformed = listOf(
      xml.replace("src=\"com.android.browser.json\"", "src=\"https://example.com/icon.json\""),
      xml.replace("src=\"com.android.browser.json\"", "src=\"../icon.json\""),
      xml.replace("target=\"icon\"", "target=\"missing\""),
      xml.replace("command=\"play\"", "command=\"arbitrary\""),
      xml.replace("params=\"999\"", "params=\"#date\""),
      xml.replace("params=\"999\"", "params=\"-1\""),
      xml.replace("params=\"999\"", "params=\"36001\""),
      xml.replace("params=\"999\"", ""),
      xml.replace("loop=\"0\"", "loop=\"17\""),
      xml.replace("autoplay=\"false\"", "autoplay=\"@external\""),
      xml.replace("action=\"complete\"", "action=\"anything\""),
      xml.replace("<FrameRateCommand rate=\"0\" />", "<LottieCommand target=\"icon\" command=\"play\"/>"),
      completion("<FrameRateCommand rate='90'/>"),
      completion("<FrameRateCommand rate='0' delay='1'/>"),
      completion("<FunctionCommand target='restart'/>",
        "<Function name='restart'><LottieCommand target='icon' command='play'/></Function>"),
      completion("<LottieCommand target='other' command='play'/>",
        "<Lottie name='other' src='com.android.browser.json' autoplay='false' loop='0'/>"),
      completion("<FolmeCommand target='otherImage' states=\"'still'\" command='setTo'/>",
        "<Image name='otherImage' src='icon.png'/><FolmeState name='still' alpha='0'/>"),
    )
    malformed.forEach { bad ->
      assertNotEquals(xml, bad)
      assertThrows(Exception::class.java) {
        LocalNativeIconValidator.validate(archive(source + (base + "manifest.xml" to bad.toByteArray())))
      }
    }
    assertThrows(Exception::class.java) {
      LocalNativeIconValidator.validate(archive(source - (base + "com.android.browser.json")))
    }
    assertThrows(Exception::class.java) {
      LocalNativeIconValidator.validate(archive(source +
        (base + "com.android.browser.json" to """{"assets":[{"u":"https://example.com/","p":"image.png"}]}""".toByteArray())))
    }
  }

  @Test fun suppliedRealBrowserJsonPassesWithoutPayloadChanges() {
    val path = System.getenv("KEIHOME_TEST_PERFECT_BROWSER")
    assumeTrue(path != null)
    val input = File(requireNotNull(path))
    if (input.isFile) {
      val before = input.readBytes()
      LocalNativeIconValidator.validate(input)
      assertArrayEquals(before, input.readBytes())
      return
    }
    val folder = input
    val root = "layer_animating_icons/com.android.browser/"
    val source = folder.walkTopDown().filter { it.isFile }.associate {
      root + it.relativeTo(folder).invariantSeparatorsPath to it.readBytes()
    }
    val file = archive(source)
    val before = file.readBytes()
    LocalNativeIconValidator.validate(file)
    assertArrayEquals(before, file.readBytes())
  }

  @Test fun realHyperOs4FixturePassesAndItsUnsafeMutationsFail() {
    val fixturePath = System.getenv("KEIHOME_TEST_PERFECT_HYPEROS4")
    assumeTrue(fixturePath != null)
    val file = File(requireNotNull(fixturePath))
    val before = file.readBytes()
    val resources = ZipFile(file).use { zip ->
      zip.entries().asSequence().filterNot { it.isDirectory }.associate {
        it.name to zip.getInputStream(it).use { input -> input.readBytes() }
      }
    }
    // Full admission also verifies the real PNG chunks/CRCs, including all date frames.
    LocalNativeIconValidator.validate(file)
    assertArrayEquals(before, file.readBytes())
    val clock = "layer_animating_icons/com.android.deskclock/1/fancy/manifest.xml"
    val calendar = "layer_animating_icons/com.android.calendar/1/fancy/manifest.xml"
    fun mutate(path: String, change: (String) -> String) =
      resources + (path to change(String(requireNotNull(resources[path]))).toByteArray())
    val unsafe = listOf(
      mutate(clock) { it.replace("__datatimeVarUpdate", "arbitraryExtern") },
      mutate(clock) { it.replace("'DateTime.Second'", "'content://external'") },
      mutate(clock) { it.replace("target=\"fra\"", "target=\"missing\"") },
      mutate(clock) { it.replace("<Function name=\"fra\">", "<Function name=\"fra\"><FunctionCommand target=\"fra\"/>") },
      mutate(clock) { it.replace("onBegin=\"'start'\"", "onBegin=\"'missing'\"") },
      mutate(clock) {
        it.replace("<Function name=\"start\">",
          "<Function name=\"start\"><FolmeCommand target=\"s\" command=\"to\" states=\"'show_second'\" config=\"'config'\"/>")
      },
      mutate(calendar) { it.replace("srcid=\"#date\"", "srcid=\"#month\"") },
      resources - "layer_animating_icons/com.android.calendar/1/fancy/num_31.png",
    )
    unsafe.forEach { assertThrows(Exception::class.java) { LocalNativeIconValidator.validate(archive(it)) } }
  }

  private fun trigger(body: String, action: String = "init") =
    "<ExternalCommands><Trigger action='$action'>$body</Trigger></ExternalCommands>"

  @Test fun appearanceSwitchForDateFramesRequiresBothCompleteLocalFamilies() {
    val frames = (1..31).flatMap { day ->
      listOf(base + "num_$day.png" to png, base + "keihome_night/num_$day.png" to png)
    }.toMap()
    val body = """<Image src="num.png" srcid="#date"
      srcExp="ifelse(#__darkmode,'keihome_night/num.png','num.png')"/>"""
    val attrs = "useVariableUpdater='DateTime.Day,DarkMode'"
    admit(body, attrs, frames)
    reject(body, attrs, frames - (base + "num_31.png"))
    reject(body, attrs, frames - (base + "keihome_night/num_31.png"))
    reject(body.replace("#date", "#month"), attrs, frames)
    reject(body.replace("keihome_night/num.png", "../num.png"), attrs, frames)
    reject(body, "useVariableUpdater='DateTime.Day'", frames)
  }

  private fun resources(
    body: String,
    attrs: String,
    extra: Map<String, ByteArray>,
    includeImage: Boolean,
  ) = mapOf(
    "layer_animating_icons/com.example/config.xml" to
      "<Config><Variables><Variable name='layer0' value='0'/></Variables></Config>".toByteArray(),
    "layer_animating_icons/com.example/0/quiet/quietImage.png" to png,
    base + "icon.png" to png,
    base + "manifest.xml" to
      "<Icon width='351' height='351' $attrs>${if (includeImage) image else ""}$body</Icon>".toByteArray(),
  ) + extra

  private fun admit(body: String, attrs: String = "", extra: Map<String, ByteArray> = emptyMap(),
    includeImage: Boolean = true) {
    LocalNativeIconValidator.validate(archive(resources(body, attrs, extra, includeImage)))
  }

  private fun reject(body: String, attrs: String = "", extra: Map<String, ByteArray> = emptyMap()) {
    assertThrows("Must reject $body", Exception::class.java) {
      admit(body, attrs, extra)
    }
  }

  private fun archive(resources: Map<String, ByteArray>): File = temporary.newFile().apply {
    ZipOutputStream(outputStream()).use { zip ->
      resources.forEach { (name, bytes) ->
        zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
      }
    }
  }


  @Test fun qualifiedNativeLayerRootsAndStaticPairsKeepStrictNameValidation() {
    val qualified = "com.example.debug#com.example.Main"
    val files = resources("", "", emptyMap(), true).mapKeys { (path, _) ->
      path.replace("layer_animating_icons/com.example/", "layer_animating_icons/$qualified/")
    }
    LocalNativeIconValidator.validate(archive(files))
    fun pair(name: String) = setOf("res/drawable-xxhdpi/$name/0.png", "res/drawable-xxhdpi/$name/1.png")
    val safe = pair(qualified)
    assertEquals(safe, LocalLayerIconValidator.validate(safe, { _, _ -> png }, {}))
    for (invalid in listOf("com.example#../Main", "com.example#com.other#Main", "#com.example.Main", "com.example#")) {
      assertThrows(IllegalArgumentException::class.java) {
        LocalLayerIconValidator.validate(pair(invalid), { _, _ -> png }, {})
      }
      val unsafe = files.mapKeys { (path, _) -> path.replace(qualified, invalid) }
      assertThrows(Exception::class.java) { LocalNativeIconValidator.validate(archive(unsafe)) }
    }
  }
}
