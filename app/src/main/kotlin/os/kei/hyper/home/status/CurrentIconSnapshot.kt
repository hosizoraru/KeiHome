package os.kei.hyper.home.status

import android.content.Context
import android.net.Uri
import java.io.File

/** Private resource snapshots supplied only by the already authenticated system worker. */
internal object CurrentIconSnapshot {
  fun file(context: Context, key: String): File {
    require(key.matches(Regex("[a-f0-9]{64}")))
    return File(context.cacheDir, "current-icons").apply { mkdirs() }.resolve("$key.zip")
  }
  fun uri(key: String): Uri = Uri.parse("content://${RuntimeStatusInbox.AUTHORITY}/current-icons/$key")
}
