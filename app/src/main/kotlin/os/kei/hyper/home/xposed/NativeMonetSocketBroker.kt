package os.kei.hyper.home.xposed

import android.content.Context
import android.net.LocalSocket
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * system_server owns both socketpair endpoints until an authenticated Binder reply transfers one.
 * No bind/connect, service registration, JNI library or SELinux mutation is involved. This object
 * belongs to one Java module generation; retirement closes pending AND borrowed descriptors.
 */
internal object NativeMonetSocketBroker {
  const val TRANSACTION = 0x4b484d
  const val DESCRIPTOR = "android.content.pm.IPackageManager"
  const val HOOK_ID = "keihome_monet_package_fd"
  private const val MAGIC = 0x334d484b
  private const val VERSION = 1
  private const val PENDING_TTL_MS = 5_000L
  private const val MAX_OWNED_CHANNELS = 4
  private const val STATUS_REJECTED = 1
  private const val STATUS_BUSY = 2
  private const val STATUS_INACTIVE = 3
  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
  private val lock = java.lang.Object()
  private var accepting = false
  private var context: Context? = null
  private var generation = 0L
  private var allocating: Any? = null
  private var current: Lease? = null
  private var worker: NativeMonetBrokerWorker? = null
  private val owned = mutableSetOf<Lease>()

  @Volatile var lastFailure: String? = null
    private set

  fun resolveTransaction(loader: ClassLoader): Method {
    val stub = Class.forName("android.content.pm.IPackageManager\$Stub", false, loader)
    check(stub.declaredFields.none {
      it.name.startsWith("TRANSACTION_") && it.type == Int::class.javaPrimitiveType &&
        Modifier.isStatic(it.modifiers) && it.apply { isAccessible = true }.getInt(null) == TRANSACTION
    }) { "Monet transaction collides with a generated PackageManager transaction" }
    return stub.getDeclaredMethod(
      "onTransact", Int::class.javaPrimitiveType, Parcel::class.java,
      Parcel::class.java, Int::class.javaPrimitiveType,
    ).apply {
      check(returnType == Boolean::class.javaPrimitiveType)
      isAccessible = true
    }
  }

  fun start(owner: Context): Boolean = synchronized(lock) {
    if (accepting) return true
    if (allocating != null || owned.isNotEmpty() || worker?.isStopped() == false) return false
    // Construction starts no thread and performs no socket/identity work under the lifecycle lock.
    worker = NativeMonetBrokerWorker()
    generation += 1
    context = owner
    accepting = true
    lock.notifyAll()
    true
  }

  /** Invoked only for TRANSACTION on the generated package Stub, never for normal PM calls. */
  fun onTransact(data: Parcel, reply: Parcel?, flags: Int): Boolean {
    if (!NativeMonetBrokerRequest.isSynchronous(reply != null, flags)) {
      lastFailure = "broker: a synchronous transaction is required"
      return true
    }
    requireNotNull(reply)
    try {
      val callerUid = Binder.getCallingUid()
      val callerPid = Binder.getCallingPid()
      data.enforceInterface(DESCRIPTOR)
      val hasDescriptors = data.hasFileDescriptors()
      require(data.dataAvail() == 4 * Int.SIZE_BYTES) {
        "invalid broker request length or descriptors"
      }
      require(NativeMonetBrokerRequest.matches(
        IntArray(4) { data.readInt() }, callerUid, callerPid, Process.myPid(), hasDescriptors,
      )) { "invalid broker request identity" }
      data.enforceNoDataAvail()
      val owner = synchronized(lock) {
        context?.takeIf { accepting }?.let { Owner(it, generation, checkNotNull(worker)) }
      }
      if (owner == null) return replyStatus(reply, STATUS_INACTIVE)
      // Capture the kernel caller BEFORE clearing identity for PackageManager/current-user reads.
      val token = Binder.clearCallingIdentity()
      try {
        val target = NativeMonetLauncherIdentity.resolve(owner.context, requireContextUser = false)
        require(callerUid == target.uid && callerPid > 0 && callerPid != Process.myPid()) {
          "caller is not the foreground launcher"
        }
        val process = NativeMonetProcessIdentity.read(callerPid, callerUid)
        require(NativeMonetLauncherIdentity.resolve(owner.context, false) == target) { "foreground user changed" }
        val previous = synchronized(lock) { current }
        // poll(2), like all descriptor/identity operations, is outside both broker/lifecycle locks.
        val previousDead = previous?.let { !it.borrowed && it.peerClosed() } ?: false
        val ticket = Any()
        val admission = synchronized(lock) {
          when {
            !matchesOwner(owner) -> STATUS_INACTIVE
            allocating != null || owned.size >= MAX_OWNED_CHANNELS || current !== previous -> STATUS_BUSY
            previous != null && previous.target == target && previous.process == process &&
              !previous.closed && (previous.borrowed || (!previousDead && System.nanoTime() < previous.expiresAt)) -> STATUS_BUSY
            else -> { allocating = ticket; current = null; 0 }
          }
        }
        if (admission != 0) return replyStatus(reply, admission)
        try {
          previous?.close()
          return allocate(owner, ticket, target, process, reply)
        } finally {
          val retiring = synchronized(lock) {
            if (allocating === ticket) allocating = null
            lock.notifyAll()
            shutdownWhenDrained()
          }
          retiring?.shutdown()
        }
      } finally {
        Binder.restoreCallingIdentity(token)
      }
    } catch (failure: Exception) {
      lastFailure = nativeMonetFailure("broker request", failure)
      return replyStatus(reply, STATUS_REJECTED)
    }
  }

  private data class Owner(val context: Context, val generation: Long, val worker: NativeMonetBrokerWorker)

  // These two predicates only inspect state and are called with lock held.
  private fun matchesOwner(owner: Owner) = accepting && generation == owner.generation &&
    context === owner.context && worker === owner.worker

  private fun shutdownWhenDrained(): NativeMonetBrokerWorker? =
    worker.takeIf { !accepting && allocating == null && owned.isEmpty() }

  private fun allocate(
    owner: Owner, ticket: Any, target: NativeMonetLauncherIdentity,
    process: NativeMonetProcessIdentity, reply: Parcel,
  ): Boolean {
    // Authentication and a bounded reservation precede allocation. A stop can invalidate that
    // reservation while this slow work runs; publication checks the generation again below.
    val pair = ParcelFileDescriptor.createSocketPair()
    var lease: Lease? = null
    var published = false
    try {
      val candidate = Lease(target, process, pair[0], owner.worker)
      lease = candidate
      synchronized(lock) { owned.add(candidate) }
      if (NativeMonetProcessIdentity.read(process.pid, target.uid) != process ||
        NativeMonetLauncherIdentity.resolve(owner.context, false) != target
      ) throw IOException("launcher changed during broker allocation")
      candidate.installExpiry(owner.worker.schedule(PENDING_TTL_MS) {
        val expired = synchronized(lock) {
          if (current === candidate && !candidate.borrowed) {
            current = null
            lock.notifyAll()
            true
          } else false
        }
        if (expired) candidate.close()
      })
      replyStatus(reply, 0)
      // NDK reads optional<ParcelFileDescriptor>, including both presence and PFD comm markers.
      reply.writeTypedObject(pair[1], 0)
      published = synchronized(lock) {
        if (matchesOwner(owner) && allocating === ticket && !candidate.closed &&
          System.nanoTime() < candidate.expiresAt
        ) {
          current = candidate
          lock.notifyAll()
          true
        } else false
      }
      if (!published) return replyStatus(reply, STATUS_INACTIVE)
      lastFailure = null
      return true
    } finally {
      try {
        // Parcel owns its duplicate. No close or Parcel serialization runs under the broker lock.
        pair[1].close()
      } finally {
        if (lease == null) pair[0].close() else if (!published) lease.close()
      }
    }
  }

  private fun replyStatus(reply: Parcel, status: Int): Boolean {
    reply.setDataSize(0)
    reply.setDataPosition(0)
    reply.writeInt(0) // Exact exception/status header; no StrictMode/AppOps reply header.
    reply.writeInt(MAGIC)
    reply.writeInt(VERSION)
    reply.writeInt(status)
    return true
  }

  fun take(target: NativeMonetLauncherIdentity, deadlineNanos: Long): Lease {
    while (true) {
      var expired: Lease? = null
      val taken = synchronized(lock) {
        check(accepting) { "native broker inactive" }
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) throw SocketTimeoutException("waiting for native Binder endpoint")
        val lease = current
        if (lease != null && !lease.borrowed) {
          // An old user cannot evict a successor endpoint, even when its old request arrives late.
          if (lease.target != target) throw IOException("broker endpoint belongs to another launcher user")
          if (lease.closed || System.nanoTime() >= lease.expiresAt) {
            current = null
            expired = lease
            null
          } else {
            lease.borrowed = true
            lease
          }
        } else {
          TimeUnit.NANOSECONDS.timedWait(lock, remaining)
          null
        }
      }
      expired?.close()
      if (taken != null) {
        taken.cancelExpiry()
        // A closed peer is handled by the ordinary bounded greeting read. Do not poll under lock.
        return taken
      }
    }
  }

  fun release(lease: Lease) {
    synchronized(lock) {
      if (current === lease) current = null
      lock.notifyAll()
    }
    lease.close()
  }

  fun stop() {
    val closing = synchronized(lock) {
      accepting = false
      context = null
      current = null
      lock.notifyAll()
      owned.toList()
    }
    // stop is called under the runtime lifecycle lock. Only queue closes here, never do FD I/O.
    closing.forEach { it.close() }
    synchronized(lock) { shutdownWhenDrained() }?.shutdown()
  }

  fun awaitStopped(deadlineNanos: Long): Boolean {
    val retiring = synchronized(lock) { if (accepting) return false; worker }
    try {
      synchronized(lock) {
        while (!accepting && worker === retiring && (allocating != null || owned.isNotEmpty())) {
          val remaining = deadlineNanos - System.nanoTime()
          if (remaining <= 0) return false
          TimeUnit.NANOSECONDS.timedWait(lock, remaining)
        }
        if (accepting || worker !== retiring) return false
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      return false
    }
    if (retiring?.awaitStopped(deadlineNanos) == false) return false
    return synchronized(lock) {
      !accepting && allocating == null && current == null && owned.isEmpty() && worker === retiring
    }
  }

  private fun cleaned(lease: Lease) {
    val retiring = synchronized(lock) {
      owned.remove(lease)
      lock.notifyAll()
      shutdownWhenDrained()
    }
    retiring?.shutdown()
  }

  class Lease internal constructor(
    val target: NativeMonetLauncherIdentity,
    val process: NativeMonetProcessIdentity,
    private val descriptor: ParcelFileDescriptor,
    private val cleanup: NativeMonetBrokerWorker,
  ) : Closeable {
    private val closing = AtomicBoolean()
    val closed: Boolean get() = closing.get()
    @Volatile internal var borrowed = false // Written under broker lock; peer probe reads outside it.
    @Volatile private var expiry: ScheduledFuture<*>? = null
    internal val expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PENDING_TTL_MS)
    // The hidden platform constructor borrows this FD; LocalSocket.close alone does not own it.
    val socket: LocalSocket = NativeMonetPlatformMembers.socketConstructor
      .newInstance(descriptor.fileDescriptor)

    internal fun installExpiry(value: ScheduledFuture<*>) {
      expiry = value
      if (closed) value.cancel(false)
    }

    internal fun cancelExpiry() { expiry?.cancel(false) }

    fun isCurrent(context: Context): Boolean = !closed &&
      NativeMonetLauncherIdentity.resolve(context) == target &&
      NativeMonetProcessIdentity.read(process.pid, target.uid) == process

    internal fun peerClosed(): Boolean = try {
      val poll = StructPollfd().apply {
        fd = descriptor.fileDescriptor
        events = OsConstants.POLLIN.toShort()
      }
      Os.poll(arrayOf(poll), 0)
      poll.revents.toInt() and (OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL) != 0
    } catch (_: Exception) { true }

    override fun close() {
      if (!closing.compareAndSet(false, true)) return
      cancelExpiry()
      cleanup.execute {
        try {
          try { Os.shutdown(descriptor.fileDescriptor, OsConstants.SHUT_RDWR) } catch (_: Exception) { }
          // LocalSocket(FileDescriptor) borrows the FD. Its synchronized close is a no-op for
          // ownership and can contend with I/O; close only the actual PFD owner after shutdown.
          descriptor.close()
        } catch (failure: Exception) {
          lastFailure = nativeMonetFailure("broker FD cleanup", failure)
        } finally {
          cleaned(this)
        }
      }
    }
  }
}

/** Android-free owner of expiry and FD cleanup work, including actual thread retirement. */
internal class NativeMonetBrokerWorker {
  private val threads = ConcurrentHashMap.newKeySet<Thread>()
  private val executor = ScheduledThreadPoolExecutor(1) {
    Thread(it, "KeiHomeMonetBroker").apply { isDaemon = true; threads.add(this) }
  }.apply {
    removeOnCancelPolicy = true
    setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
  }

  fun schedule(delayMs: Long, action: () -> Unit): ScheduledFuture<*> =
    executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)

  fun execute(action: () -> Unit) = executor.execute(action)
  fun shutdown() = executor.shutdown() // Never shutdownNow: queued FD cleanup must run.
  fun isStopped(): Boolean = executor.isTerminated && threads.none { it.isAlive }

  fun awaitStopped(deadlineNanos: Long): Boolean {
    if (Thread.currentThread() in threads) return false
    try {
      executor.awaitTermination((deadlineNanos - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
      for (thread in threads) {
        val remaining = deadlineNanos - System.nanoTime()
        if (thread.isAlive && remaining > 0) TimeUnit.NANOSECONDS.timedJoin(thread, remaining)
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      return false
    }
    return isStopped()
  }
}

/** Exact v1 request admission; called before resolving a launcher or allocating any endpoint. */
internal object NativeMonetBrokerRequest {
  fun isSynchronous(hasReply: Boolean, flags: Int): Boolean = hasReply && flags and IBinder.FLAG_ONEWAY == 0

  fun matches(
    words: IntArray, callerUid: Int, callerPid: Int, serverPid: Int,
    hasFileDescriptors: Boolean = false,
  ): Boolean = !hasFileDescriptors &&
    words.size == 4 && words[0] == 0x334d484b && words[1] == 1 && words[2] == callerUid &&
      words[3] == 1 && callerUid >= 0 && callerPid > 0 && callerPid != serverPid
}

internal data class NativeMonetLauncherIdentity(val userId: Int, val uid: Int) {
  companion object {
    private const val PACKAGE = "com.miui.home"

    fun resolve(context: Context, requireContextUser: Boolean = true): NativeMonetLauncherIdentity {
      val user = NativeMonetPlatformMembers.currentUser.invoke(null) as? Int
        ?: throw IOException("currentUser=NULL")
      require(user >= 0) { "currentUser invalid" }
      if (requireContextUser) {
        val contextUser = NativeMonetPlatformMembers.contextUser.invoke(context) as? Int
          ?: throw IOException("contextUser=NULL")
        require(contextUser == user) { "context user is not foreground user" }
      }
      val uid = NativeMonetPlatformMembers.packageUid
        .invoke(context.packageManager, PACKAGE, 0, user) as? Int
        ?: throw IOException("launcherUid=NULL")
      require(uid >= 0 && context.packageManager.getPackagesForUid(uid)?.contains(PACKAGE) == true) {
        "launcher UID package ownership rejected"
      }
      require(NativeMonetPlatformMembers.currentUser.invoke(null) == user) {
        "foreground user changed"
      }
      return NativeMonetLauncherIdentity(user, uid)
    }
  }
}

internal data class NativeMonetProcessIdentity(val pid: Int, val startTicks: Long) {
  companion object {
    fun read(pid: Int, uid: Int): NativeMonetProcessIdentity {
      require(pid > 0) { "invalid launcher PID" }
      val root = File("/proc/$pid")
      val before = startTicks(File(root, "stat").readText())
      val name = File(root, "cmdline").inputStream().use { input ->
        val expected = "com.miui.home\u0000".toByteArray(Charsets.US_ASCII)
        input.readNBytes(expected.size).contentEquals(expected)
      }
      require(name) { "caller process is not com.miui.home" }
      val ids = File(root, "status").useLines { lines ->
        lines.firstOrNull { it.startsWith("Uid:") }?.substringAfter(':')
          ?.trim()?.split(Regex("\\s+"))?.mapNotNull(String::toIntOrNull)
      }
      require(ids?.size == 4 && ids.all { it == uid }) { "caller PID UID ownership rejected" }
      require(startTicks(File(root, "stat").readText()) == before) { "caller PID was reused" }
      return NativeMonetProcessIdentity(pid, before)
    }

    private fun startTicks(stat: String): Long {
      val end = stat.lastIndexOf(')')
      require(end > 0) { "invalid process stat" }
      return stat.substring(end + 1).trim().split(Regex("\\s+")).getOrNull(19)
        ?.toLongOrNull()?.takeIf { it > 0 } ?: throw IOException("process start identity missing")
    }
  }
}
