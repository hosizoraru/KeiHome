package os.kei.hyper.home.ui.main

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class UpdateChannelTest {
  @Test fun usesTelegramClientWhenAvailable() {
    val context = RecordingContext(true, true)
    assertTrue(UpdateChannel.open(context))
    assertEquals(1, context.attempts.size)
    assertEquals("VoyagerMIUIUpdate", context.attempts.single().data?.getQueryParameter("domain"))
    assertEquals("tg", context.attempts.single().data?.scheme)
  }

  @Test fun absentTelegramFallsBackToExactChannelUrl() {
    val context = RecordingContext(false, true)
    assertTrue(UpdateChannel.open(context))
    assertEquals(listOf("tg://resolve?domain=VoyagerMIUIUpdate", "https://t.me/VoyagerMIUIUpdate"),
      context.attempts.map { it.dataString })
    assertTrue(context.attempts.all { it.action == Intent.ACTION_VIEW })
  }

  @Test fun absentHandlersReturnFailureWithoutCrashing() {
    assertFalse(UpdateChannel.open(RecordingContext(false, false)))
  }

  private class RecordingContext(private val telegram: Boolean, private val browser: Boolean) :
    ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
    val attempts = mutableListOf<Intent>()
    override fun startActivity(intent: Intent) {
      attempts += intent
      if (!(if (intent.data?.scheme == "tg") telegram else browser)) throw ActivityNotFoundException()
    }
  }
}
