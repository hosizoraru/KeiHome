package os.kei.hyper.home.xposed

/** One report tick may authenticate more than one launcher while a process restarts. */
internal class NativeRuntimeIdentitySelection {
  var greeting: NativeMonetGreeting? = null
    private set
  var processChanged: Boolean = false
    private set
  val process: NativeMonetProcessIdentity? get() = greeting?.process
  val acknowledgementProcess: NativeMonetProcessIdentity? get() = retainEarlierAcknowledgement(process)

  fun <T> retainEarlierAcknowledgement(value: T?): T? = value.takeUnless { processChanged }

  fun observe(authenticated: NativeMonetGreeting?) {
    if (authenticated == null) return
    if (greeting != null && process != authenticated.process) processChanged = true
    // Keep the latest connection, but never resurrect earlier ACKs during this tick.
    greeting = authenticated
  }
}
