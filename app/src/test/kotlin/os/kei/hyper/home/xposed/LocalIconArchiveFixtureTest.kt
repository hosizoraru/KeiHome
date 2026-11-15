package os.kei.hyper.home.xposed

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalIconArchiveFixtureTest {
  @Test fun validatesTheExplicitLocalArchive() {
    val path = System.getenv("KEIHOME_LOCAL_ICON_ARCHIVE")
    assumeTrue("Explicit local archive fixture required", !path.isNullOrBlank())
    LocalNativeIconValidator.validate(File(requireNotNull(path)))
  }
}
