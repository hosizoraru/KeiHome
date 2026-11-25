package os.kei.hyper.home.data

import android.os.Build

internal enum class SystemDarkStyle { ENHANCED, MEDIUM, GENTLE }

internal fun supportsSystemDarkStyle(): Boolean =
  Build.VERSION.SDK_INT >= 37 && Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
