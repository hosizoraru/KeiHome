package os.kei.hyper.home.xposed

import android.os.Binder
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileDescriptor
import java.io.PrintWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoundedBinderDiagnosticsDeviceTest {
  private fun source(text: String, close: Boolean = false) = object : Binder() {
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
      writer.print(text)
      if (close) writer.close()
    }
  }

  @Test fun completeOutputLargerThanAPipeBuffer() {
    val text = "wallpaper-region=dark\n".repeat(6000)
    assertEquals(text, BoundedBinderDiagnostics.read(source(text), emptyArray()))
  }

  @Test fun implementationMayCloseItsWriterWithoutClosingOurReader() {
    assertEquals("complete", BoundedBinderDiagnostics.read(source("complete", close = true), emptyArray()))
  }

  @Test fun oversizedOrEmptyOutputNeverLooksComplete() {
    assertNull(BoundedBinderDiagnostics.read(source(""), emptyArray()))
    assertNull(BoundedBinderDiagnostics.read(source("x".repeat(BoundedBinderDiagnostics.MAX_BYTES + 100)), emptyArray()))
  }
}
