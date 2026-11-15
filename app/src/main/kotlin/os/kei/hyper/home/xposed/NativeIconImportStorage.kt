package os.kei.hyper.home.xposed

import java.io.IOException
import os.kei.hyper.home.iconpack.IconPackBudgets

internal object NativeIconImportStorage {
  private const val MIB = 1024L * 1024

  fun requiredFreeBytes(archiveBytes: Long): Long {
    require(archiveBytes in 1..IconPackBudgets.ARCHIVE_BYTES)
    // Our staging copy already exists; Themes still needs extraction and library copies.
    return archiveBytes * 3 + 64 * MIB
  }

  fun checkAvailable(archiveBytes: Long, availableBytes: Long) {
    val required = requiredFreeBytes(archiveBytes)
    if (availableBytes < required) throw IOException(
      "Insufficient storage for system theme import: requires ${(required + MIB - 1) / MIB} MiB free; " +
        "available ${availableBytes.coerceAtLeast(0) / MIB} MiB. Free storage and retry.",
    )
  }
}
