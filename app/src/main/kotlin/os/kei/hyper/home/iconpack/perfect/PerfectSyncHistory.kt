package os.kei.hyper.home.iconpack.perfect

import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

internal enum class PerfectSyncFailure { FAILED, INTERRUPTED }

internal data class PerfectSyncHistoryState(
  val startedAtMillis: Long = 0,
  val owner: String = "",
  val issue: PerfectSyncFailure? = null,
  val issueAtMillis: Long = 0,
  val lastSuccessfulSyncAtMillis: Long = 0,
)

/** One durable record; a new controller in the same process does not interrupt live work. */
internal class PerfectSyncHistory(
  private val read: (String) -> String?,
  private val write: (String, String) -> Unit,
  private val now: () -> Long,
  private val processToken: String = PROCESS_TOKEN,
) {
  fun current(): PerfectSyncHistoryState {
    val saved = read(KEY)?.let { text ->
      val json = try { JSONObject(text) } catch (_: JSONException) { return@let null }
      val issue = PerfectSyncFailure.entries.firstOrNull { it.name == json.optString("issue") }
      PerfectSyncHistoryState(
        json.optLong("startedAt", 0).coerceAtLeast(0), json.optString("owner", ""),
        issue, if (issue == null) 0 else json.optLong("issueAt", 0).coerceAtLeast(0),
        json.optLong("lastSuccessAt", 0).coerceAtLeast(0),
      )
    } ?: PerfectSyncHistoryState()
    return if (saved.startedAtMillis > 0 && saved.owner != processToken) {
      save(saved.copy(startedAtMillis = 0, owner = "", issue = PerfectSyncFailure.INTERRUPTED,
        issueAtMillis = saved.startedAtMillis))
    } else saved
  }

  fun started() = save(current().copy(startedAtMillis = now().coerceAtLeast(1), owner = processToken))

  fun succeeded() = save(PerfectSyncHistoryState(lastSuccessfulSyncAtMillis = now().coerceAtLeast(1)))

  fun failed() = finish(PerfectSyncFailure.FAILED)

  fun interrupted() = finish(PerfectSyncFailure.INTERRUPTED)

  fun seedLastSuccess(timestamp: Long): PerfectSyncHistoryState {
    val current = current()
    return if (current.lastSuccessfulSyncAtMillis == 0L && timestamp > 0)
      save(current.copy(lastSuccessfulSyncAtMillis = timestamp)) else current
  }

  private fun finish(issue: PerfectSyncFailure) = save(current().copy(
    startedAtMillis = 0, owner = "", issue = issue, issueAtMillis = now().coerceAtLeast(1)))

  private fun save(state: PerfectSyncHistoryState): PerfectSyncHistoryState {
    write(KEY, JSONObject().put("startedAt", state.startedAtMillis).put("owner", state.owner)
      .put("issue", state.issue?.name.orEmpty()).put("issueAt", state.issueAtMillis)
      .put("lastSuccessAt", state.lastSuccessfulSyncAtMillis).toString())
    return state
  }

  companion object {
    private const val KEY = "syncHistoryV1"
    private val PROCESS_TOKEN = UUID.randomUUID().toString()
  }
}

internal enum class PerfectLocalHealth { MISSING, READY, CORRUPT }

internal data class PerfectLocalStatus(
  val health: PerfectLocalHealth,
  val catalog: PerfectIconCatalog? = null,
  val corruptAtMillis: Long = 0,
)
