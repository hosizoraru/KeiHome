package os.kei.hyper.home.xposed

import android.content.Context
import android.net.LocalSocket
import android.os.Process
import java.io.EOFException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
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
 * The native worker must independently require SYSTEM_UID and system_server SELinux context.
 * A generated PackageManager Stub hook authenticates the native Binder caller before giving
 * it one endpoint. The socketpair peer credentials describe system_server, which created BOTH
 * endpoints; the lease's registered launcher identity authenticates the greeting. A true result is
 * the only permission to persist an automatic selection; this file never writes
 * Settings. The caller must supply the same user-scoped Context used to read
 * settings/palette and scan masks. Reject a user switch rather than retargeting
 * that old user's policy to the new user's launcher. Readiness is uncached.
 */
internal class NativeMonetClient {
  private val worker = NativeMonetWorker()
  @Volatile private var failure: String? = null
  @Volatile var acknowledgedProcess: NativeMonetProcessIdentity? = null
    private set

  /** Reason for the last failed exchange; a completed successful exchange clears it. */
  val lastFailure: String? get() = failure

  private fun recordFailure(reason: String?) {
    failure = reason
    lastTransportFailure = reason
  }

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
    val request = NativeMonetProtocol.publishRequest(foreground, background, denied) ?: run {
      recordFailure("policy: invalid colors or package denial list")
      return false
    }
    return communicate(context, request)
  }

  fun disable(context: Context): Boolean = communicate(context, NativeMonetProtocol.disableRequest())

  /** Authenticate and read the greeting, then close without sending a request. */
  fun isAvailable(context: Context): Boolean = communicate(context, request = null)

  private fun communicate(context: Context, request: ByteArray?): Boolean {
    if (Process.myUid() != Process.SYSTEM_UID || Thread.currentThread().isInterrupted) {
      recordFailure("caller: requires active system_server worker")
      return false
    }
    val attempt = Attempt(context, request)
    val task = FutureTask { attempt.exchange() }
    var succeeded = false
    var outerFailure: String? = null
    return try {
      if (!worker.submit(task, attempt::cancel)) {
        outerFailure = "worker: stopped or queue full"
        return false
      }
      // Bound identity lookup and waiting for a native-registered endpoint independently of I/O.
      if (!attempt.connected.await(CONNECT_BUDGET_MS, TimeUnit.MILLISECONDS)) {
        outerFailure = "${attempt.stage}: endpoint deadline exceeded"
        return false
      }
      task.get(attempt.remainingNanos(), TimeUnit.NANOSECONDS).also {
        succeeded = it
        if (it) acknowledgedProcess = attempt.process
      }
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      outerFailure = nativeMonetFailure(attempt.stage, failure)
      false
    } catch (failure: TimeoutException) {
      outerFailure = nativeMonetFailure(attempt.stage, failure)
      false
    } catch (failure: ExecutionException) {
      outerFailure = nativeMonetFailure(attempt.stage, failure)
      false
    } catch (failure: CancellationException) {
      outerFailure = nativeMonetFailure(attempt.stage, failure)
      false
    } finally {
      // Interrupt alone does not cancel LocalSocket reads. Shutdown and close
      // wake blocked I/O; the worker checks cancellation again before writing.
      attempt.cancel()
      task.cancel(true)
      worker.release(task)
      recordFailure(if (succeeded) null else outerFailure ?: attempt.failure ?: "exchange rejected")
    }
  }

  private inner class Attempt(private val context: Context, private val request: ByteArray?) {
    var process: NativeMonetProcessIdentity? = null
      private set
    val connected = CountDownLatch(1)
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXCHANGE_BUDGET_MS)
    private val lock = Any()
    @Volatile private var cancelled = false
    private var lease: NativeMonetSocketBroker.Lease? = null
    @Volatile var failure: String? = null
      private set
    @Volatile var stage = "launcher identity"
      private set

    fun remainingNanos(): Long = (deadline - System.nanoTime()).coerceAtLeast(0)

    fun exchange(): Boolean = try {
      checkActive()
      val target = NativeMonetLauncherIdentity.resolve(context)
      stage = "native Binder endpoint"
      val acquired = NativeMonetSocketBroker.take(target, minOf(deadline,
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_BUDGET_MS)))
      acquired.use {
        synchronized(lock) {
          checkActive()
          lease = acquired
        }
        checkActive()
        val connection = acquired.socket
        try {
          connected.countDown()
          stage = "broker peer identity"
          checkActive()
          val peer = connection.peerCredentials
          if (peer.uid != Process.SYSTEM_UID || peer.pid != Process.myPid() || acquired.target != target) {
            failure = "broker peer rejected: socketpair creator or registered launcher mismatch"
            return@use false
          }
          stage = "native greeting/policy ACK"
          val accepted = NativeMonetProtocol.exchange(
            input = connection.inputStream,
            output = connection.outputStream,
            targetUid = target.uid,
            // This is the authenticated Binder registrant, NOT SO_PEERCRED's creator UID.
            peerUid = acquired.target.uid,
            request = request,
            beforeIo = { prepareIo(connection) },
            isCurrentTarget = { acquired.isCurrent(context) },
            onFailure = { failure = it },
          )
          if (!accepted) return@use false
          checkActive()
          process = acquired.process
          true
        } finally {
          NativeMonetSocketBroker.release(acquired)
        }
      }
    } catch (error: Exception) {
      failure = nativeMonetFailure(stage, error)
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
        lease.also { lease = null }
      } ?: return
      // Closing the borrowed LocalSocket alone does not close its supplied FD.
      NativeMonetSocketBroker.release(connection)
    }
  }

  companion object {
    /** Diagnostic only, for the owning runtime's state-transition log; never a readiness cache. */
    @Volatile var lastTransportFailure: String? = null
      private set
    private const val CONNECT_BUDGET_MS = 1_000L
    private const val IO_TIMEOUT_MS = 750
    private const val EXCHANGE_BUDGET_MS = 2_500L
  }
}

/** Keep only a bounded one-line reason, never Throwable/Context/credential objects across attempts. */
internal fun nativeMonetFailure(stage: String, failure: Throwable): String {
  var cause = failure
  repeat(8) {
    if (cause is InvocationTargetException || cause is ExecutionException) {
      cause.cause?.takeIf { it !== cause }?.let { cause = it }
    }
  }
  val message = cause.message.orEmpty()
    .replace(Regex("(?i)(authorization\\s*[:=]\\s*)(?:bearer|basic)\\s+[^\\s,;]+"), "$1[redacted]")
    .replace(Regex("(?i)(authorization|password|passwd|token|secret|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+"), "$1=[redacted]")
    .replace(Regex("[\\r\\n\\t]"), " ")
    .take(300)
  return "$stage: ${cause.javaClass.simpleName}${if (message.isEmpty()) "" else ": $message"}"
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

  /** peerUid is independently authenticated by the transport: for Binder it is the registrant. */
  fun exchange(
    input: InputStream,
    output: OutputStream,
    targetUid: Int,
    peerUid: Int,
    request: ByteArray?,
    beforeIo: () -> Unit = {},
    isCurrentTarget: () -> Boolean = { true },
    onFailure: (String) -> Unit = {},
  ): Boolean {
    fun reject(reason: String): Boolean { onFailure(reason); return false }
    if (targetUid < 0 || peerUid != targetUid) return reject("registered launcher UID rejected")
    val greeting = readWords(input, 4, beforeIo)
    if (greeting[0] != MAGIC) return reject("native greeting magic mismatch")
    if (greeting[1] != VERSION) return reject("native greeting protocol mismatch")
    if (greeting[2] != targetUid) return reject("native greeting launcher UID mismatch")
    if (greeting[3] != PROFILE_VERSION) return reject("native greeting profile mismatch")
    if (!isCurrentTarget()) return reject("launcher changed before policy publication")
    if (request == null) return true
    beforeIo()
    output.write(request)
    beforeIo()
    output.flush()
    val ack = readWords(input, 3, beforeIo)
    if (ack[0] != MAGIC || ack[1] != VERSION) return reject("native policy ACK identity mismatch")
    if (ack[2] != 0) return reject("native policy rejected: status=${ack[2]}")
    // The lease can be retired or its foreground user replaced while the native side ACKs.
    // Keep this check in the protocol so callers cannot treat that ACK as a current publication.
    return if (isCurrentTarget()) true else reject("launcher changed after policy ACK")
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
