package os.kei.hyper.home.xposed

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLayerKeyframeValidatorTest {
  @get:Rule val temporary = TemporaryFolder()
  private val root = "layer_animating_icons/io.github.perfecticons/"
  private val base = root + "0/fancy/"
  private val png = tinyPng()
  private val positionItems = "<Item x='0' y='20' time='0'/><Item x='0' y='0' time='250'/>"
  private val alphaItems = "<Item value='0' time='0'/><Item value='255' time='250'/>"
  private val position = position(positionItems)
  private val alpha = alpha(alphaItems)

  @Test fun admitsTheSourceThreeImageStaggerWithoutModifyingArchiveBytes() {
    // Source data equivalent to io.github.perfecticons/1/fancy/manifest.xml in generated.zip.
    val images = (1..3).joinToString("") { index ->
      val delay = (index - 1) * 150
      val firstPosition = "<Item x='0' y='20' time='0'/>"
      val firstAlpha = "<Item value='0' time='0'/>"
      val waitPosition = if (delay == 0) "" else "<Item x='0' y='20' time='$delay'/>"
      val waitAlpha = if (delay == 0) "" else "<Item value='0' time='$delay'/>"
      image("p$index",
        position(firstPosition + waitPosition + "<Item x='0' y='0' time='${250 + delay}'/>") +
          alpha(firstAlpha + waitAlpha + "<Item value='255' time='${250 + delay}'/>"),
        "p$index.png")
    }
    fun commands(play: String) = (1..3).joinToString("") { animation("p$it", play) }
    val body = trigger(commands("play(1600,1600)"), "init,present,back_home_cancel") +
      trigger(commands("play(0,0)"), "back_home_start") +
      trigger(commands("play(0,1600)"), "back_home_finish") +
      "<Group name='fg'>$images</Group>"
    val file = archive(body)
    val before = file.readBytes()
    LocalNativeIconValidator.validate(file)
    assertArrayEquals(before, file.readBytes())
  }

  @Test fun admitsBothTrackKindsIndividuallyAndInEitherOrder() {
    for (tracks in listOf(position, alpha, position + alpha, alpha + position)) {
      admit(image("p1", tracks) + trigger(animation()))
    }
    admit(image("p1", position) + image("p2", alpha) +
      trigger(animation("p1") + animation("p2")))
  }

  @Test fun admitsLiteralEndpointsSingleFramesAndEqualAdjacentTimes() {
    val positions = "<Item x='-4096' y='4096' time='0'/>" +
      "<Item x='0' y='-4096' time='0'/><Item x='4096' y='0' time='60000'/>"
    val alphas = "<Item value='0' time='0'/><Item value='255' time='0'/>" +
      "<Item value='0' time='60000'/>"
    admit(image("p1", position(positions) + alpha(alphas)) +
      trigger(animation(command = "play(0,60000)")))
    admit(image("p1", position("<Item x='0' y='0' time='60000'/>") +
      alpha("<Item value='255' time='60000'/>")) +
      trigger(animation(command = "play(60000,60000)")))
  }

  @Test fun animationCommandsResolveForwardFromLifecycleTriggersAndFunctions() {
    val start = "<Function name='start'>" + animation() + "</Function>"
    admit(trigger("<FunctionCommand target='start'/>") + start + image("p1", position + alpha))
    for (action in listOf("init", "present", "resume", "back_home_start", "back_home_finish", "back_home_cancel")) {
      admit(trigger(animation(), action) + image("p1", position))
    }
    for (command in listOf("play(0,0)", "play(0,1600)", "play(1600,1600)", "play(0,60000)")) {
      admit(image("p1", alpha) + trigger(animation(command = command)))
    }
  }

  @Test fun rejectsDescendingNegativeUnboundedAndComputedFrameTimes() {
    for (track in listOf(position, alpha)) {
      val valid = image("p1", track)
      for (time in listOf("-1", "60001", "999999999999999999999999999999", "#second",
        "@time", "1+1", "NaN", "Infinity", "1e999", "")) {
        rejectMutation(valid, valid.replace("time='250'", "time='$time'"))
      }
      rejectMutation(valid, valid.replace("time='0'", "time='251'"))
      rejectMutation(valid, valid.replace(" time='250'", ""))
    }
  }

  @Test fun rejectsOutOfRangeNonliteralAndMissingPositionCoordinates() {
    val valid = image("p1", position)
    for (attribute in listOf("x", "y")) {
      val original = if (attribute == "x") "x='0'" else "y='20'"
      for (value in listOf("-4097", "4097", "999999999999999999999999", "#second",
        "@position", "1+1", "NaN", "Infinity", "-Infinity", "1e999", "")) {
        rejectMutation(valid, valid.replace(original, "$attribute='$value'"))
      }
      rejectMutation(valid, valid.replace(" $original", ""))
    }
  }

  @Test fun rejectsOutOfRangeComputedAndMissingAlphaValues() {
    val valid = image("p1", alpha)
    for (value in listOf("-1", "256", "999999999999999999999999", "#second", "@alpha",
      "255-1", "NaN", "Infinity", "1e999", "")) {
      rejectMutation(valid, valid.replace("value='255'", "value='$value'"))
    }
    rejectMutation(valid, valid.replace(" value='255'", ""))
  }

  @Test fun rejectsDuplicateTracksAndEmptyTracksOfEitherKind() {
    for (track in listOf(position, alpha)) {
      reject(image("p1", track + track))
      reject(image("p1", track + (if (track == position) alpha else position) + track))
    }
    reject(image("p1", "<PositionAnimation/>"))
    reject(image("p1", "<AlphaAnimation/>"))
    reject(image("p1", "<PositionAnimation><!-- no frames --></PositionAnimation>"))
    reject(image("p1", "<AlphaAnimation> \n\t </AlphaAnimation>"))
  }

  @Test fun enforcesExactTrackAndFrameAttributesAndLeafItems() {
    val valid = image("p1", position + alpha)
    for (changed in listOf(
      valid.replace("<PositionAnimation>", "<PositionAnimation loop='true'>"),
      valid.replace("<AlphaAnimation>", "<AlphaAnimation delay='1'>"),
      valid.replace("<PositionAnimation>", "<PositionAnimation name='track'>"),
      valid.replace("<Item x=", "<Item ease='1' x="),
      valid.replace("<Item x=", "<Item value='0' x="),
      valid.replace("<Item value=", "<Item x='0' value="),
      valid.replace("<Item value=", "<Item expression='#second' value="),
      valid.replace("x='0' y='20'", "x='0' y='20' z='0'"),
      valid.replace("x='0' y='20'", "x='0' y='20' timeExp='#second'"),
      valid.replace("<Item x='0' y='20' time='0'/>",
        "<Item x='0' y='20' time='0'><Item x='0' y='0' time='0'/></Item>"),
      valid.replace("</PositionAnimation>", "<Image src='p1.png'/></PositionAnimation>"),
      valid.replace("</AlphaAnimation>", "<VariableCommand name='a' type='number' expression='0'/></AlphaAnimation>"),
      valid.replace("</AlphaAnimation>", "<AnimationCommand target='p1' command='play(0,0)'/></AlphaAnimation>"),
      valid.replace("</PositionAnimation>", "<Unknown/></PositionAnimation>"),
      valid.replace("</PositionAnimation>", "unexpected</PositionAnimation>"),
      valid.replace("<Item value='255' time='250'/>", "<Item value='255' time='250'>extra</Item>"),
    )) rejectMutation(valid, changed)
  }

  @Test fun keyframesMustBeImmediateChildrenOfImages() {
    for (track in listOf(position, alpha)) {
      reject(track)
      reject("<Group name='p1'>$track</Group>")
      reject("<Function name='start'>$track</Function>")
      reject(trigger(track))
      reject(image("p1", "<Group>$track</Group>"))
      reject(image("p1", position(track)))
    }
    reject(image("p1", "<Item x='0' y='0' time='0'/>"))
  }

  @Test fun animationCommandsRejectExpressionsOtherOperationsAndLoopArguments() {
    for (command in listOf(
      "play", "play()", "play(0)", "play(0,1,0)", "play(0,1,true)", "play(0,1,false)",
      "play(1600,0)", "play(-1,1)", "play(0,-1)", "play(0,60001)", "play(60001,60001)",
      "play(999999999999999999999999,999999999999999999999999)",
      "play(#second,1600)", "play(0,@end)", "play(0,100+100)", "play(0.0,1)",
      "play(0,1.5)", "play(0,NaN)", "play(0,Infinity)", "play(0,1e3)",
      "play(0, 1)", "play( 0,1)", "play(0,1 )", " play(0,1)", "play(0,1) ",
      "play(0,1);play(0,1)", "prefixplay(0,1)", "play(0,1)suffix",
      "pause", "resume", "stop", "setTo(0)", "eval(@command)", "content://weather/data",
    )) reject(image("p1", position) + trigger(animation(command = command)))
  }

  @Test fun animationCommandsRequireExactAttributesLeafShapeAndLifecycleContext() {
    val valid = animation()
    for (command in listOf(
      "<AnimationCommand target='p1'/>",
      "<AnimationCommand command='play(0,1600)'/>",
      valid.replace("target='p1'", "targetExp=\"'p1'\""),
      valid.replace("command='play(0,1600)'", "commandExp=\"'play(0,1600)'\""),
      valid.replace("/>", " loop='0'/>"),
      valid.replace("/>", " params='0,1600'/>"),
      valid.replace("/>", " delay='1'/>"),
      valid.replace("/>", " uri='content://weather/data'/>"),
      valid.replace("/>", "><FrameRateCommand rate='0'/></AnimationCommand>"),
      valid.replace("/>", ">extra</AnimationCommand>"),
    )) reject(image("p1", position) + trigger(command))
    reject(image("p1", position) + animation())
    reject(image("p1", position + animation()))
    reject(image("p1", position) + "<Group>${animation()}</Group>")
    reject(image("p1", position) + trigger(animation(), "arbitrary"))
    reject(image("p1", position) + trigger("<IntentCommand action='android.intent.action.VIEW'/>"))
    reject(image("p1", position) + trigger("<ContentProviderBinder uri='content://weather/data'/>"))
  }

  @Test fun animationTargetsMustResolveToNamedImagesWithTheirOwnKeyframes() {
    reject(image("p1", position) + trigger(animation("missing")))
    reject(image("p1", position) + trigger(animation("@target")))
    reject(image("p1", position) + trigger(animation("p1.alpha")))
    reject(image("p1", "") + trigger(animation()))
    reject(image("p1", position).replace(" name='p1'", "") + trigger(animation()))
    reject("<Group name='group'>${image("p1", position)}</Group>" + trigger(animation("group")))
    reject("<Group name='group'/>" + image("p1", position) + trigger(animation("group")))
    reject("<Function name='start'/>" + image("p1", position) + trigger(animation("start")))
    reject(image("p1", "") + image("p2", position) + trigger(animation("p1")))
    reject(image("p1", position) + image("p1", alpha) + trigger(animation()))
  }

  @Test fun eachTrackPermitsExactlyOneThroughSixtyFourItems() {
    for (kind in listOf("position", "alpha")) {
      for (count in listOf(1, 64)) admit(image("p1", track(kind, count)))
      reject(image("p1", track(kind, 65)))
    }
  }

  @Test fun trackBudgetIsPerProgramAcrossImagesAndGroups() {
    val thirtyTwo = (0 until 16).joinToString("") {
      "<Group>${image("i$it", track("position", 1) + track("alpha", 1))}</Group>"
    }
    admit(thirtyTwo)
    reject(thirtyTwo + image("extra", track("position", 1)))
  }

  @Test fun itemBudgetIsPerProgramAcrossImagesAndTrackKinds() {
    val twoHundredFiftySix = (0 until 2).joinToString("") {
      "<Group>${image("i$it", track("position", 64) + track("alpha", 64))}</Group>"
    }
    admit(twoHundredFiftySix)
    reject(twoHundredFiftySix + image("extra", track("position", 1)))
  }

  private fun position(items: String) = "<PositionAnimation>$items</PositionAnimation>"
  private fun alpha(items: String) = "<AlphaAnimation>$items</AlphaAnimation>"

  private fun image(name: String, tracks: String, source: String = "p1.png") =
    "<Image name='$name' src='$source' x='0' y='0'>$tracks</Image>"

  private fun animation(target: String = "p1", command: String = "play(0,1600)") =
    "<AnimationCommand target='$target' command='$command'/>"

  private fun trigger(body: String, action: String = "init") =
    "<ExternalCommands><Trigger action='$action'>$body</Trigger></ExternalCommands>"

  private fun track(kind: String, count: Int): String {
    val items = (0 until count).joinToString("") {
      if (kind == "position") "<Item x='0' y='0' time='$it'/>"
      else "<Item value='255' time='$it'/>"
    }
    return if (kind == "position") position(items) else alpha(items)
  }

  private fun admit(body: String) {
    LocalNativeIconValidator.validate(archive(body))
  }

  private fun reject(body: String) {
    assertThrows("Must reject $body", Exception::class.java) { admit(body) }
  }

  private fun rejectMutation(original: String, changed: String) {
    assertNotEquals("Mutation must change the fixture", original, changed)
    reject(changed)
  }

  private fun archive(body: String): File = temporary.newFile().apply {
    val resources = mapOf(
      root + "config.xml" to
        "<Config><Variables><Variable name='layer0' value='0'/></Variables></Config>".toByteArray(),
      root + "0/quiet/quietImage.png" to png,
      base + "manifest.xml" to
        "<Icon version='1' width='512' height='512' frameRate='60'>$body</Icon>".toByteArray(),
      base + "p1.png" to png,
      base + "p2.png" to png,
      base + "p3.png" to png,
    )
    ZipOutputStream(outputStream()).use { zip ->
      resources.forEach { (name, bytes) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
  }

  private fun tinyPng(): ByteArray {
    val output = ByteArrayOutputStream()
    val data = DataOutputStream(output)
    data.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
    fun chunk(name: String, payload: ByteArray) {
      val type = name.toByteArray(Charsets.US_ASCII)
      data.writeInt(payload.size)
      data.write(type)
      data.write(payload)
      val crc = CRC32().apply { update(type); update(payload) }
      data.writeInt(crc.value.toInt())
    }
    val header = ByteArrayOutputStream()
    DataOutputStream(header).use {
      it.writeInt(2)
      it.writeInt(2)
      it.writeByte(8)
      it.writeByte(6)
      it.writeByte(0)
      it.writeByte(0)
      it.writeByte(0)
    }
    chunk("IHDR", header.toByteArray())
    val compressed = ByteArrayOutputStream()
    DeflaterOutputStream(compressed).use { pixels ->
      repeat(2) {
        pixels.write(0)
        repeat(2) { pixels.write(byteArrayOf(40, 80, 120, 0xff.toByte())) }
      }
    }
    chunk("IDAT", compressed.toByteArray())
    chunk("IEND", byteArrayOf())
    return output.toByteArray()
  }
}
