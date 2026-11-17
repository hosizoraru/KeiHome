package os.kei.hyper.home.xposed

import android.os.IBinder
import android.system.Os
import android.system.OsConstants

/** A sealed anonymous file supports ordinary dump writes, unlike an ashmem device descriptor. */
internal object BoundedBinderDiagnostics {
  const val MAX_BYTES = 512 * 1024
  // Stable Linux UAPI constants from linux/memfd.h and linux/fcntl.h.
  private const val CLOEXEC_ALLOW_SEALING = 0x0001 or 0x0002
  private const val ADD_SEALS = 1024 + 9
  private const val SEAL_SHRINK_GROW = 0x0002 or 0x0004

  fun read(binder: IBinder, arguments: Array<String>): String? = runCatching {
    val fd = Os.memfd_create("KeiHomeAppearance", CLOEXEC_ALLOW_SEALING)
    try {
      Os.ftruncate(fd, MAX_BYTES.toLong())
      Os.fcntlInt(fd, ADD_SEALS, SEAL_SHRINK_GROW)
      val writer = Os.dup(fd)
      try { binder.dump(writer, arguments) } finally {
        if (writer.valid()) Os.close(writer)
      }
      val size = Os.lseek(fd, 0, OsConstants.SEEK_CUR)
      if (size <= 0 || size >= MAX_BYTES) return null
      Os.lseek(fd, 0, OsConstants.SEEK_SET)
      val bytes = ByteArray(size.toInt())
      var offset = 0
      while (offset < bytes.size) {
        val count = Os.read(fd, bytes, offset, bytes.size - offset)
        if (count <= 0) return null
        offset += count
      }
      bytes.toString(Charsets.UTF_8)
    } finally { if (fd.valid()) Os.close(fd) }
  }.getOrNull()
}
