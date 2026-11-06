package os.kei.hyper.home.ui.main

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

internal object UpdateChannel {
  const val USERNAME = "VoyagerMIUIUpdate"
  const val WEB_URL = "https://t.me/$USERNAME"

  fun open(context: Context): Boolean {
    for (url in listOf("tg://resolve?domain=$USERNAME", WEB_URL)) {
      try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
          .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
      } catch (_: ActivityNotFoundException) {
        // Telegram is optional; the HTTPS route also supports a browser.
      } catch (_: SecurityException) {
        // A restricted client must not prevent the browser fallback.
      }
    }
    return false
  }
}
