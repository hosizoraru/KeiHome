package os.kei.hyper.home.xposed

import org.junit.Assert.*
import org.junit.Test

class LocalCalendarLayerProgramTest {
  private val base = "layer_animating_icons/com.example/1/fancy/"
  private val frames = ((0..9).map { "${base}digit_$it.png" } +
    (1..7).map { "${base}weekday_$it.png" }).toSet()
  private val program = """
    <Icon version="1" width="300" height="300" frameRate="0" useVariableUpdater="DateTime.Day">
      <VariableBinders><BroadcastBinder action="android.intent.action.DATE_CHANGED"/></VariableBinders>
      <ExternalCommands><Trigger action="init,present"><AnimationCommand target="refresh" command="play"/></Trigger></ExternalCommands>
      <FramerateController name="refresh" initPause="true" loop="false">
        <ControlPoint frameRate="0" time="0"/><ControlPoint frameRate="20" time="1"/><ControlPoint frameRate="0" time="100"/>
      </FramerateController>
      <Group><Image src="weekday.png" srcid="#day_of_week"/><Time src="digit.png" format="dd"/></Group>
    </Icon>
  """.trimIndent()
  private fun read(xml: String = program, paths: Set<String> = frames) =
    LocalCalendarLayerProgram.resources(xml.toByteArray(), base, paths)

  @Test fun calendarUsesOnlyItsCompleteLocalDigitAndWeekdayFrames() {
    assertEquals(frames, read())
    assertNull(read("<Icon><Image src='icon.png'/></Icon>"))
    assertThrows(IllegalArgumentException::class.java) { read(paths = frames - "${base}digit_0.png") }
    assertThrows(IllegalArgumentException::class.java) { read(paths = frames - "${base}weekday_7.png") }
  }

  @Test fun refreshControllersMustBeFiniteOrderedPausedAndDeclared() {
    for (bad in listOf(
      program.replace("loop=\"false\"", "loop=\"true\""),
      program.replace("initPause=\"true\"", "initPause=\"false\""),
      program.replace("frameRate=\"0\" time=\"100\"", "frameRate=\"20\" time=\"100\""),
      program.replace("time=\"100\"", "time=\"1\""),
      program.replace("time=\"100\"", "time=\"60001\""),
      program.replace("frameRate=\"20\"", "frameRate=\"121\""),
      program.replace("target=\"refresh\"", "target=\"missing\""),
      program.replace("refresh", "__root"),
      program.replace("refresh", "root"),
      program.replace("command=\"play\"", "command=\"play(0,100)\""),
    )) assertThrows(IllegalArgumentException::class.java) { read(bad) }
  }

  @Test fun providersOtherBroadcastsExpressionsAndNonlocalFramesRemainRejected() {
    for (bad in listOf(
      program.replace("android.intent.action.DATE_CHANGED", "android.intent.action.BATTERY_CHANGED"),
      program.replace("<BroadcastBinder action=\"android.intent.action.DATE_CHANGED\"/>", "<ContentProviderBinder uri=\"content://weather\"/>"),
      program.replace("<BroadcastBinder action=\"android.intent.action.DATE_CHANGED\"/>", "<SettingsBinder/>"),
      program.replace("digit.png", "../digit.png"),
      program.replace("format=\"dd\"", "format=\"MM\""),
      program.replace("#day_of_week", "#month"),
      program.replace("width=\"300\"", "width=\"99999\""),
    )) assertThrows(IllegalArgumentException::class.java) { read(bad) }
  }
}
