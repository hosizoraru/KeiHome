package os.kei.hyper.home.xposed

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.EOFException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One authenticated exchange with the launcher; the monitor owns retries.
 *
 * The server must independently require SO_PEERCRED.uid == SYSTEM_UID. This
 * client authenticates the server against PackageManager for the foreground
 * user, then checks its greeting before sending any request. A true result is
 * the only permission to persist an automatic selection; this file never writes
 * Settings. The caller must supply the same user-scoped Context used to read
 * settings/palette and scan masks. Reject a user switch rather than retargeting
 * that old user's policy to the new user's launcher. Readiness is uncached.
 */
internal class NativeMonetClient {
  private val worker = NativeMonetWorker()

  /** Initial construction is ready; reopening after stop requires actual worker retirement. */
  fun start(): Boolean = worker.start()

  /** Cancel queued/in-flight exchanges and close their sockets; interruption alone is insufficient. */
  fun stop() = worker.stop()

  /** Uses the monitor's absolute System.nanoTime deadline, without extending its budget. */
  fun awaitStopped(deadlineNanos: Long): Boolean = worker.awaitStopped(deadlineNanos)

  fun publish(
    context: Context,
    foreground: Int,
    background: Int,
    denied: Set<String>,
  ): Boolean {
    val request = NativeMonetProtocol.publishRequest(foreground, background, denied) ?: return false
    return communicate(context, request)
  }

  fun disable(context: Context): Boolean = communicate(context, NativeMonetProtocol.disableRequest())

  /** Authenticate and read the greeting, then close without sending a request. */
  fun isAvailable(context: Context): Boolean = communicate(context, request = null)

  private fun communicate(context: Context, request: ByteArray?): Boolean {
    if (Process.myUid() != Process.SYSTEM_UID || Thread.currentThread().isInterrupted) return false
    val attempt = Attempt(context, request)
    val task = FutureTask { attempt.exchange() }
    return try {
      if (!worker.submit(task, attempt::cancel)) return false
      // LocalSocket.connect(address, timeout) is unimplemented in AOSP. The
      // worker and this independent deadline also bound package/user resolution.
      if (!attempt.connected.await(CONNECT_BUDGET_MS, TimeUnit.MILLISECONDS)) return false
      task.get(attempt.remainingNanos(), TimeUnit.NANOSECONDS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    } catch (_: TimeoutException) {
      false
    } catch (_: ExecutionException) {
      false
    } catch (_: CancellationException) {
      false
    } finally {
      // Interrupt alone does not cancel LocalSocket reads. Shutdown and close
      // wake blocked I/O; the worker checks cancellation again before writing.
      attempt.cancel()
      task.cancel(true)
      worker.release(task)
    }
  }

  private data class LauncherIdentity(val userId: Int, val uid: Int)

  private fun resolveLauncher(context: Context): LauncherIdentity? {
    // A new foreground UID does not make a palette collected under the old
    // user's Context valid. Fail closed on this race, including before writing.
    val userId = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
    val contextUserId = Context::class.java.getMethod("getUserId").invoke(context) as Int
    if (userId < 0 || contextUserId != userId) return null
    val uid = PackageManager::class.java.getMethod(
      "getPackageUidAsUser", String::class.java, Int::class.javaPrimitiveType,
      Int::class.javaPrimitiveType,
    ).invoke(context.packageManager, LAUNCHER_PACKAGE, 0, userId) as Int
    val stillCurrent = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
    return if (uid >= 0 && stillCurrent == contextUserId) LauncherIdentity(userId, uid) else null
  }

  private inner class Attempt(private val context: Context, private val request: ByteArray?) {
    val connected = CountDownLatch(1)
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXCHANGE_BUDGET_MS)
    private val lock = Any()
    @Volatile private var cancelled = false
    private var socket: LocalSocket? = null

    fun remainingNanos(): Long = (deadline - System.nanoTime()).coerceAtLeast(0)

    fun exchange(): Boolean = try {
      checkActive()
      val target = resolveLauncher(context)
      if (target == null) {
        false
      } else {
        checkActive()
        LocalSocket(LocalSocket.SOCKET_STREAM).use { connection ->
          // Force FD creation before publishing the socket to cancel(). AOSP's
          // lazy creation takes the same monitor held by blocking connect().
          val input = connection.inputStream
          synchronized(lock) {
            checkActive()
            socket = connection
          }
          prepareIo(connection)
          connection.connect(LocalSocketAddress(
            "$ADDRESS_PREFIX${target.uid}", LocalSocketAddress.Namespace.ABSTRACT,
          ))
          connected.countDown()
          checkActive()
          val peerUid = connection.peerCredentials.uid
          if (peerUid != target.uid) return@use false
          val accepted = NativeMonetProtocol.exchange(
            input = input,
            output = connection.outputStream,
            targetUid = target.uid,
            peerUid = peerUid,
            request = request,
            beforeIo = { prepareIo(connection) },
            isCurrentTarget = { resolveLauncher(context) == target },
          )
          if (!accepted) return@use false
          val stillCurrent = resolveLauncher(context) == target
          checkActive()
          stillCurrent
        }
      }
    } catch (_: Exception) {
      false
    } finally {
      connected.countDown()
      cancel()
    }

    private fun checkActive() {
      if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException()
      if (remainingNanos() <= 0) throw SocketTimeoutException("Monet exchange deadline")
    }

    private fun prepareIo(connection: LocalSocket) {
      checkActive()
      val remainingMs = (remainingNanos() + 999_999) / 1_000_000
      // AOSP sets SO_RCVTIMEO and SO_SNDTIMEO. Recompute on each partial read
      // so trickled greeting/ACK bytes cannot extend the total deadline.
      connection.soTimeout = minOf(IO_TIMEOUT_MS.toLong(), remainingMs).coerceAtLeast(1).toInt()
    }

    fun cancel() {
      connected.countDown()
      val connection = synchronized(lock) {
        cancelled = true
        socket.also { socket = null }
      } ?: return
      try {
        connection.shutdownInput()
      } catch (_: Exception) {
        // A connection attempt may not have reached the connected state.
      }
      try {
        connection.shutdownOutput()
      } catch (_: Exception) {
        // Close is still necessary when shutdown is not supported by the peer.
      }
      try {
        connection.close()
      } catch (_: Exception) {
        // Cancellation is best effort and must preserve the Boolean API.
      }
    }
  }

  private companion object {
    const val LAUNCHER_PACKAGE = "com.miui.home"
    const val ADDRESS_PREFIX = "os.kei.hyper.home.monet."
    const val CONNECT_BUDGET_MS = 250L
    const val IO_TIMEOUT_MS = 250
    const val EXCHANGE_BUDGET_MS = 900L
  }
}

/** Per-client lifecycle, kept free of Android calls so retirement can be tested on the host. */
internal class NativeMonetWorker {
  private val lock = Any()
  private var stopped = false
  private var executor: ThreadPoolExecutor? = null
  private val ownedThreads = ConcurrentHashMap.newKeySet<Thread>()
  private val cancellations = mutableMapOf<FutureTask<Boolean>, () -> Unit>()

  fun start(): Boolean = synchronized(lock) {
    if (!stopped) return true
    if (executor?.isTerminated == false || ownedThreads.any { it.isAlive }) return false
    executor = null
    ownedThreads.clear()
    stopped = false
    true
  }

  fun submit(task: FutureTask<Boolean>, cancel: () -> Unit): Boolean = synchronized(lock) {
    if (stopped) return false
    ownedThreads.removeAll { it.state == Thread.State.TERMINATED }
    val current = executor ?: ThreadPoolExecutor(
      // One pending slot handles publish immediately after a readiness probe;
      // cancellation removes pending work rather than replaying it later.
      1, 1, 100, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
      { runnable ->
        Thread(runnable, "KeiHomeMonetSocket").apply {
          isDaemon = true
          ownedThreads.add(this)
        }
      },
      ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }.also { executor = it }
    cancellations[task] = cancel
    try {
      current.execute(task)
      true
    } catch (_: RejectedExecutionException) {
      cancellations.remove(task)
      false
    }
  }

  fun release(task: FutureTask<Boolean>) = synchronized(lock) {
    cancellations.remove(task)
    executor?.remove(task)
    Unit
  }

  fun stop() = synchronized(lock) {
    stopped = true
    // Drain pending tasks before closing the running socket can release its
    // worker to execute them. Interrupt is followed by explicit socket closure.
    executor?.shutdownNow()
    // Keep reopening excluded until every close callback has finished. These
    // callbacks only shut down/close sockets; they never acquire this lock.
    for ((task, cancel) in cancellations) {
      cancel()
      task.cancel(true)
    }
    cancellations.clear()
    Unit
  }

  fun awaitStopped(deadlineNanos: Long): Boolean {
    val retiring = synchronized(lock) {
      if (!stopped) return false
      executor ?: return true
    }
    try {
      if (Thread.currentThread() in ownedThreads) return false
      if (!retiring.isTerminated) {
        retiring.awaitTermination(
          (deadlineNanos - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS,
        )
      }
      // Executor termination is signalled just before the last Thread returns.
      // Hot reload also requires the old generation's actual threads to exit.
      for (thread in ownedThreads) {
        val remaining = (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
        if (thread.isAlive && remaining > 0) {
          val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
          val nanos = (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
          thread.join(millis, nanos)
        }
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      return false
    }
    return synchronized(lock) {
      stopped && executor === retiring && retiring.isTerminated && ownedThreads.none { it.isAlive }
    }
  }
}

/** Pure stream framing shared by the client and host unit tests. All words are uint32 LE. */
internal object NativeMonetProtocol {
  private const val MAGIC = 0x334d484b
  private const val VERSION = 1
  private const val PROFILE_VERSION = 1
  private const val ENABLED = 1
  private const val MAX_ENTRIES = 512
  private const val MAX_ENTRY_BYTES = 255

  fun publishRequest(foreground: Int, background: Int, denied: Set<String>): ByteArray? {
    if (!MonetColorPolicy.isLegiblePair(foreground, background) || denied.size > MAX_ENTRIES) {
      return null
    }
    // Validate before US_ASCII conversion; the encoder otherwise substitutes '?'
    // and could silently deny a different package. Never truncate the deny set.
    if (denied.any { !isPackageName(it) }) return null
    val entries = denied.sorted().map { it.toByteArray(Charsets.US_ASCII) }
    val buffer = header(
      flags = ENABLED, foreground = foreground, background = background, count = entries.size,
      entryBytes = entries.sumOf { Int.SIZE_BYTES + it.size },
    )
    for (entry in entries) {
      buffer.putInt(entry.size)
      buffer.put(entry)
    }
    return buffer.array()
  }

  fun disableRequest(): ByteArray = header(flags = 0, foreground = 0, background = 0, count = 0).array()

  private fun isPackageName(name: String): Boolean {
    if (name.length !in 1..MAX_ENTRY_BYTES) return false
    var segmentStart = true
    for (character in name) {
      when {
        character in 'a'..'z' || character in 'A'..'Z' -> segmentStart = false
        !segmentStart && (character == '_' || character in '0'..'9') -> Unit
        !segmentStart && character == '.' -> segmentStart = true
        else -> return false
      }
    }
    return !segmentStart
  }

  fun exchange(
    input: InputStream,
    output: OutputStream,
    targetUid: Int,
    peerUid: Int,
    request: ByteArray?,
    beforeIo: () -> Unit = {},
    isCurrentTarget: () -> Boolean = { true },
  ): Boolean {
    if (targetUid < 0 || peerUid != targetUid) return false
    val greeting = readWords(input, 4, beforeIo)
    if (greeting[0] != MAGIC || greeting[1] != VERSION || greeting[2] != targetUid ||
      greeting[3] != PROFILE_VERSION || !isCurrentTarget()
    ) return false
    if (request == null) return true
    beforeIo()
    output.write(request)
    beforeIo()
    output.flush()
    val ack = readWords(input, 3, beforeIo)
    return ack[0] == MAGIC && ack[1] == VERSION && ack[2] == 0
  }

  private fun header(
    flags: Int,
    foreground: Int,
    background: Int,
    count: Int,
    entryBytes: Int = 0,
  ): ByteBuffer = ByteBuffer.allocate(6 * Int.SIZE_BYTES + entryBytes)
    .order(ByteOrder.LITTLE_ENDIAN)
    .putInt(MAGIC).putInt(VERSION).putInt(flags)
    .putInt(foreground).putInt(background).putInt(count)

  private fun readWords(input: InputStream, count: Int, beforeIo: () -> Unit): IntArray {
    val bytes = ByteArray(count * Int.SIZE_BYTES)
    var offset = 0
    while (offset < bytes.size) {
      beforeIo()
      val read = input.read(bytes, offset, bytes.size - offset)
      if (read <= 0) throw EOFException("Incomplete Monet response")
      offset += read
    }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return IntArray(count) { buffer.int }
  }
}
