package os.kei.hyper.home.iconpack

import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The JVM android.jar stubs cannot parse XML; exercise the real platform parser here. */
@RunWith(AndroidJUnit4::class)
class ApkIconPackAuthorMetadataXmlTest {
  @Test fun arcticonsDeclaredCreatorResolvesWithoutPickingIconOrDashboardContributors() {
    // Arcticons Day & Night 15.3.2: string/about_title + xml/contributors.
    val xml = """<resources>
      <contributor name="An icon contributor" contribution="Icon Creator"/>
      <contributor name="Framework author" contribution="CandyBar"/>
      <contributor name="Onno van den Dungen" contribution="Creator of Arcticons"/>
      <contributor name="Kaiserdragon2" contribution="Creator of Arcticons You, Icons, Automation scripts"/>
    </resources>"""
    assertEquals("Onno van den Dungen", read(mapOf("about_title" to "Arcticons"), xml))
    assertNull(read(mapOf("about_title" to "Other pack"), xml))
    assertNull(read(emptyMap(), xml))
  }

  @Test fun malformedDeepOrLongContributorsRemainUnknown() {
    for (xml in listOf(
      "<wrong><contributor name=\"Wrong\" contribution=\"Creator of Arcticons\"/></wrong>",
      "<resources><group><contributor name=\"Nested\" contribution=\"Creator of Arcticons\"/></group></resources>",
      "<resources><group><group><group/></group></group></resources>",
      "<resources>" + "<contributor name=\"Other\"/>".repeat(260) +
        "<contributor name=\"Too late\" contribution=\"Creator of Arcticons\"/></resources>",
      "<resources><contributor",
    )) assertNull(read(mapOf("about_title" to "Arcticons"), xml))
  }

  private fun read(strings: Map<String, String>, xml: String): String? =
    ApkIconPackAuthorMetadata.read(strings::get, { title ->
      val parser = Xml.newPullParser()
      parser.setInput(StringReader(xml))
      ApkIconPackAuthorMetadata.creator(parser, title)
    })
}
