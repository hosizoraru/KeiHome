package os.kei.hyper.home.status

import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class StaticScopePlan(
  val declared: List<String>,
  val eligible: List<String>,
  val granted: Set<String>,
  val absent: List<String>,
  val unconfirmed: List<String>,
  val unsupported: List<String>,
  val optional: Set<String> = emptySet(),
) {
  val requiredEligible: List<String> get() = eligible.filterNot { it in optional }
  val requiredGranted: Int get() = requiredEligible.count { it in granted }
  val missing: List<String> get() = requiredEligible.filterNot { it in granted }
  val requiredUnconfirmed: List<String> get() = unconfirmed.filterNot { it in optional }
  val complete: Boolean get() = missing.isEmpty() && requiredUnconfirmed.isEmpty() && unsupported.isEmpty()
}

internal fun planStaticScope(
  declared: List<String>, granted: Set<String>, systemSupported: Boolean,
  optional: Set<String> = emptySet(),
  installed: (String) -> Boolean?,
): StaticScopePlan {
  require(declared.isNotEmpty())
  val eligible = mutableListOf<String>()
  val absent = mutableListOf<String>()
  val unconfirmed = mutableListOf<String>()
  val unsupported = mutableListOf<String>()
  for (name in declared.distinct()) {
    if (name == "system") {
      if (systemSupported) eligible += name else unsupported += name
    } else when (installed(name)) {
      true -> eligible += name
      false -> absent += name
      null -> unconfirmed += name
    }
  }
  return StaticScopePlan(declared.distinct(), eligible, granted.intersect(eligible.toSet()),
    absent, unconfirmed, unsupported, optional)
}

internal enum class ScopeRequestFeedback { NONE, APPROVED, FAILED, RETURNED, TIMEOUT }

internal data class StaticScopeUiState(
  val connected: Boolean = false,
  val plan: StaticScopePlan? = null,
  val requesting: Boolean = false,
  val readFailed: Boolean = false,
  val feedback: ScopeRequestFeedback = ScopeRequestFeedback.NONE,
  val error: String? = null,
  val checking: Boolean = false,
) {
  val canRequest: Boolean get() = connected && !requesting && !readFailed && plan?.missing?.isNotEmpty() == true
  val needsAttention: Boolean get() = readFailed || (!checking && (!connected || plan?.complete != true || requesting))
}

internal interface StaticScopeClient {
  val identity: Any
  fun readPlan(): StaticScopePlan
  fun request(packages: List<String>, callback: ScopeDecision)
}

internal fun interface ScopeDecision {
  fun complete(approved: Boolean, error: String?)
}

/** State changes run on the owner's main scope; Binder and package queries run on IO. */
internal class StaticScopeRequests(
  private val owner: CoroutineScope,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val timeoutMillis: Long = 120_000,
  private val resumeGraceMillis: Long = 1_500,
  private val onReconciled: suspend () -> Unit = {},
) {
  private val mutable = MutableStateFlow(StaticScopeUiState(checking = true))
  val state = mutable.asStateFlow()
  private var client: StaticScopeClient? = null
  private var requestId = 0L
  private var readId = 0L
  private var appliedRead = 0L
  private var timeout: Job? = null

  fun connect(value: StaticScopeClient?, checking: Boolean = value != null) {
    requestId++
    timeout?.cancel()
    client = value
    mutable.value = StaticScopeUiState(connected = value != null, checking = checking)
  }

  fun beginRead(identity: Any): Long? = if (client?.identity === identity) ++readId else null

  fun observePlan(identity: Any, sequence: Long, plan: StaticScopePlan): Boolean {
    if (client?.identity !== identity || sequence < appliedRead) return false
    appliedRead = sequence
    applyPlan(plan)
    return true
  }

  fun readFailed(identity: Any, sequence: Long): Boolean {
    if (client?.identity !== identity || sequence < appliedRead) return false
    appliedRead = sequence
    mutable.value = mutable.value.copy(readFailed = true, checking = false)
    return true
  }

  private fun applyPlan(plan: StaticScopePlan) {
    val previous = mutable.value
    mutable.value = previous.copy(
      plan = plan, readFailed = false, checking = false,
      feedback = if (plan.complete && previous.feedback != ScopeRequestFeedback.APPROVED)
        ScopeRequestFeedback.NONE else previous.feedback,
      error = if (plan.complete) null else previous.error,
    )
    if (mutable.value.requesting && plan.missing.isEmpty()) {
      finish(if (plan.complete) ScopeRequestFeedback.APPROVED else ScopeRequestFeedback.RETURNED)
    }
  }

  suspend fun refresh() {
    val current = client ?: return
    val sequence = ++readId
    try {
      val plan = withContext(io) { current.readPlan() }
      if (client !== current || sequence < appliedRead) return
      appliedRead = sequence
      applyPlan(plan)
      onReconciled()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      if (client === current && sequence >= appliedRead) {
        appliedRead = sequence
        mutable.value = mutable.value.copy(readFailed = true, checking = false)
      }
    }
  }

  fun check() { owner.launch { refresh() } }

  fun request() {
    owner.launch {
      if (!mutable.value.canRequest) return@launch
      val current = client ?: return@launch
      val token = ++requestId
      mutable.value = mutable.value.copy(requesting = true, feedback = ScopeRequestFeedback.NONE, error = null)
      timeout = owner.launch {
        delay(timeoutMillis)
        if (active(token, current)) {
          timeout = null
          finish(ScopeRequestFeedback.TIMEOUT)
          refresh()
        }
      }
      try {
        // Reconcile again at submission time; a manager may have granted scopes since the last poll.
        val sequence = ++readId
        val plan = withContext(io) { current.readPlan() }
        if (!active(token, current)) return@launch
        observePlan(current.identity, sequence, plan)
        onReconciled()
        if (!active(token, current)) return@launch
        if (mutable.value.readFailed) {
          finish(ScopeRequestFeedback.RETURNED)
          return@launch
        }
        val packages = mutable.value.plan?.missing.orEmpty()
        if (packages.isEmpty()) {
          finish(ScopeRequestFeedback.RETURNED)
          return@launch
        }
        val callback = WeakDecision(this@StaticScopeRequests, token)
        withContext(io) { current.request(packages, callback) }
      } catch (cancelled: CancellationException) {
        if (active(token, current)) finish(ScopeRequestFeedback.NONE)
        throw cancelled
      } catch (error: Exception) {
        if (active(token, current)) {
          finish(ScopeRequestFeedback.FAILED, error.message)
          refresh()
        }
      }
    }
  }

  fun onResumed() {
    val token = requestId
    val pending = mutable.value.requesting
    owner.launch {
      if (pending) delay(resumeGraceMillis)
      refresh()
      if (pending && token == requestId && mutable.value.requesting)
        finish(ScopeRequestFeedback.RETURNED)
    }
  }

  private fun active(token: Long, value: StaticScopeClient): Boolean =
    token == requestId && client === value && mutable.value.requesting

  private fun finish(feedback: ScopeRequestFeedback, error: String? = null) {
    requestId++
    timeout?.cancel()
    timeout = null
    mutable.value = mutable.value.copy(requesting = false, feedback = feedback,
      error = error?.trim()?.take(240)?.takeIf { it.isNotEmpty() })
  }

  private fun onDecision(token: Long, approved: Boolean, error: String?) {
    owner.launch {
      if (token != requestId || !mutable.value.requesting) return@launch
      finish(if (approved) ScopeRequestFeedback.APPROVED else ScopeRequestFeedback.FAILED, error)
      // An approval callback can describe a subset. The actual framework scope is authoritative.
      refresh()
    }
  }

  private class WeakDecision(owner: StaticScopeRequests, private val token: Long) : ScopeDecision {
    private val owner = WeakReference(owner)
    override fun complete(approved: Boolean, error: String?) { owner.get()?.onDecision(token, approved, error) }
  }
}
