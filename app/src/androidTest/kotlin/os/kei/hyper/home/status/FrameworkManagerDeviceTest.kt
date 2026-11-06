package os.kei.hyper.home.status

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the module's system-server scope; never executes a root/dialer command. */
@RunWith(AndroidJUnit4::class)
class FrameworkManagerDeviceTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test fun logIntentDeliversToAnAlreadyRunningHostActivity() {
    val intent = FrameworkManagerProtocol.parasiticIntent(context,
      FrameworkManagerProtocol.LOGS, FrameworkManagerProtocol.LSPOSED)
    assertEquals("logs", intent.dataString)
    assertEquals(FrameworkManagerProtocol.HOST, intent.component?.packageName)
    assertTrue(intent.hasCategory("org.lsposed.manager.LAUNCH_MANAGER"))
    assertNotEquals(0, intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
  }

  @Test fun opensParasiticManagerFromActualAppUid() = runBlocking {
    assertTrue(FrameworkManagerLauncher.requestParasitic(context,
      FrameworkManagerProtocol.MAIN, FrameworkManagerProtocol.LSPOSED))
  }

  @Test fun opensParasiticLogsFromActualAppUid() = runBlocking {
    assertTrue(FrameworkManagerLauncher.requestParasitic(context,
      FrameworkManagerProtocol.LOGS, FrameworkManagerProtocol.LSPOSED))
  }

  @Test fun rejectsMalformedDestinationFromEvenTheAuthorizedApp() = runBlocking {
    assertFalse(FrameworkManagerLauncher.requestParasitic(context, 99, FrameworkManagerProtocol.LSPOSED))
  }

  @Test fun transportDenialReturnsFallbackInsteadOfCrashing() = runBlocking {
    assertFalse(FrameworkManagerLauncher.requestParasitic(
      FailedTransport(context, true), FrameworkManagerProtocol.MAIN, FrameworkManagerProtocol.LSPOSED))
  }

  @Test fun stalledTransportDoesNotWaitIndefinitely() = runBlocking {
    val start = SystemClock.elapsedRealtime()
    assertFalse(FrameworkManagerLauncher.requestParasitic(
      FailedTransport(context, false), FrameworkManagerProtocol.MAIN, FrameworkManagerProtocol.LSPOSED))
    assertTrue(SystemClock.elapsedRealtime() - start < 7_000)
  }

  @Test fun rejectsExpiredRequest() {
    val done = CountDownLatch(1)
    var result = -1
    context.sendOrderedBroadcast(Intent(FrameworkManagerProtocol.ACTION).setPackage("android")
      .putExtra("target", FrameworkManagerProtocol.MAIN).putExtra("flavor", FrameworkManagerProtocol.LSPOSED)
      .putExtra("deadline", SystemClock.elapsedRealtime() - 100), null,
      BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { result = resultCode; done.countDown() }
      }, Handler(Looper.getMainLooper()), 0, null, null)
    assertTrue(done.await(3, TimeUnit.SECONDS))
    assertEquals(0, result)
  }

  private class FailedTransport(base: Context, private val denied: Boolean) : ContextWrapper(base) {
    override fun sendOrderedBroadcast(intent: Intent, permission: String?, options: Bundle?,
      receiver: BroadcastReceiver?, scheduler: Handler?, initialCode: Int, initialData: String?,
      initialExtras: Bundle?) {
      if (denied) throw SecurityException("Unavailable bridge")
    }
  }
}
