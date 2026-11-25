package os.kei.hyper.home.status

import android.content.Context
import com.tencent.mmkv.MMKV
import org.json.JSONArray
import org.json.JSONObject
import os.kei.hyper.home.data.AppLocalStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All four legacy groups migrate as ONE MMKV value. No MMKV Editor batching/getAll assumptions.
 * encode/readback are checked; sync requests a flush, it does not return a filesystem ACK. */
internal class IconImportLocalStore(
  private val context: Context,
  private val id: String = "native_icon_state_v1",
  private val legacyPrefix: String = "",
  private val encode: (MMKV, String) -> Boolean = { store, value -> store.encode(STATE, value) },
) {
  private var committed: IconImportLocalState? = null
  private val storage by lazy { AppLocalStorage.open(context, id) }
  private val mutableStates = MutableStateFlow<IconImportLocalState?>(null)
  val states = mutableStates.asStateFlow()

  private fun publish(state: IconImportLocalState) {
    committed = state
    mutableStates.value = state
  }

  @Synchronized
  fun snapshot(): IconImportLocalState {
    committed?.let { return it }
    if (storage.contains(STATE)) return decode(requireNotNull(storage.getString(STATE, null))).also(::publish)
    val associations = strings(legacy("native_icon_associations").all)
    val history = associations.entries.associate { it.value to it.key } + strings(legacy("native_icon_sources").all)
    val operation = legacy("native_icon_operation")
    val pendingToken = operation.getString("request_token", null)
    val pendingSource = operation.getString("request_source", null)
    check((pendingToken == null) == (pendingSource == null)) { "Incomplete legacy pending import" }
    val raw = operation.getString("result", null)
    val report = raw?.let { requireNotNull(NativeIconImportReport.decode(it)) { "Malformed legacy import receipt" } }
    val navigation = legacy("native_icon_auto_navigation").getString("token", null)
    val state = IconImportLocalState(associations, history, pendingToken, pendingSource, report,
      navigation?.let(::setOf).orEmpty())
    persist(state)
    return state
  }

  @Synchronized
  fun begin(request: IconPackImportRequest) {
    val state = snapshot()
    check(state.pendingToken == null || (state.pendingToken == request.token && state.request == request)) {
      "Another native import is still pending"
    }
    if (state.pendingToken == request.token || (state.result?.token == request.token && state.result.sourceId == request.sourceId)) return
    persist(state.begin(request))
  }

  /** An unconfirmed Binder result may already have reached the system journal. Retain the
   * request/handoff for reconciliation rather than reporting a definite rejection. */
  @Synchronized
  fun uncertain(request: IconPackImportRequest): NativeIconImportReport? {
    val state = snapshot()
    if (state.pendingToken != request.token || state.pendingSource != request.sourceId) return state.result?.takeIf {
      it.token == request.token && it.sourceId == request.sourceId }
    val report = NativeIconImportReport(request.token, request.userId, request.sourceId, "UNCERTAIN",
      message = "Native submission is unconfirmed; recheck its existing operation")
    persist(state.copy(result = report))
    return report
  }

  @Synchronized
  fun accept(report: NativeIconImportReport): IconImportMutation {
    val mutation = snapshot().accept(report)
    persist(mutation.state)
    return mutation
  }

  @Synchronized
  fun takeNavigation(report: NativeIconImportReport): Boolean {
    val next = snapshot().consumeNavigation(report) ?: return false
    persist(next)
    return true
  }

  private fun legacy(name: String) = context.applicationContext.getSharedPreferences(legacyPrefix + name, Context.MODE_PRIVATE)

  private fun persist(state: IconImportLocalState) {
    if (state == committed) return
    val bytes = encodeState(state)
    check(encode(storage, bytes)) { "Cannot persist native import state" }
    storage.sync()
    check(storage.getString(STATE, null) == bytes) { "Native import state readback failed" }
    publish(state)
  }

  private fun strings(values: Map<String, *>): Map<String, String> = values.mapValues { (key, value) ->
    requireNotNull(value as? String) { "Invalid legacy import mapping: $key" }
  }

  private fun encodeState(state: IconImportLocalState): String = JSONObject().apply {
    put("schema", 1)
    put("associations", JSONObject(state.associations))
    put("sources", JSONObject(state.sources))
    put("token", state.pendingToken ?: JSONObject.NULL)
    put("source", state.pendingSource ?: JSONObject.NULL)
    put("result", state.result?.encode() ?: JSONObject.NULL)
    put("navigated", JSONArray(state.navigated.sorted()))
    put("request", state.request?.let { request -> JSONObject().put("token", request.token)
      .put("user", request.userId).put("start", request.startedAt).put("source", request.sourceId)
      .put("origin", request.convertedOrigin ?: JSONObject.NULL).put("auto", request.autoApply)
      .put("palette", request.preparedPalette ?: JSONObject.NULL)
      .put("staticAppearance", request.staticAppearance) } ?: JSONObject.NULL)
    put("componentPalette", JSONObject(state.componentPalette))
  }.toString().also { check(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Import receipt storage limit exceeded" } }

  private fun decode(raw: String): IconImportLocalState {
    check(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
    val root = JSONObject(raw)
    check(root.getInt("schema") == 1) { "Unsupported native import state" }
    fun mapping(name: String): Map<String, String> {
      val values = root.getJSONObject(name)
      return values.keys().asSequence().associateWith { key -> values.get(key) as? String ?: error("Invalid import mapping") }
    }
    fun optional(name: String) = if (root.isNull(name)) null else root.get(name) as? String ?: error("Invalid import state field")
    val token = optional("token")
    val source = optional("source")
    check((token == null) == (source == null))
    val result = optional("result")?.let { requireNotNull(NativeIconImportReport.decode(it)) }
    val navigation = root.getJSONArray("navigated")
    val request = if (root.isNull("request")) null else root.getJSONObject("request").let { value ->
      IconPackImportRequest(value.getString("token"), value.getInt("user"), value.getLong("start"), value.getString("source"),
        convertedOrigin = if (value.isNull("origin")) null else value.getString("origin"), autoApply = value.getBoolean("auto"),
        preparedPalette = if (value.isNull("palette")) null else value.getBoolean("palette"),
        staticAppearance = value.optBoolean("staticAppearance", false))
    }
    check(request == null || (request.token == token && request.sourceId == source))
    return IconImportLocalState(mapping("associations"), mapping("sources"), token, source, result,
      (0 until navigation.length()).mapTo(linkedSetOf()) { navigation.get(it) as? String ?: error("Invalid navigation receipt") }, request,
      root.getJSONObject("componentPalette").let { values -> values.keys().asSequence().associateWith { key ->
        values.get(key) as? Boolean ?: error("Invalid component palette receipt") } })
  }

  companion object {
    private const val STATE = "state"
    private const val MAX_BYTES = 8 * 1024 * 1024
  }
}
