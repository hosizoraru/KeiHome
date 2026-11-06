package os.kei.hyper.home.status

/** One logical App-owned receipt. Never shared with an injected process through MMKV. */
internal data class IconImportLocalState(
  val associations: Map<String, String> = emptyMap(),
  val sources: Map<String, String> = emptyMap(),
  val pendingToken: String? = null,
  val pendingSource: String? = null,
  val result: NativeIconImportReport? = null,
  val navigated: Set<String> = emptySet(),
  val request: IconPackImportRequest? = null,
  val componentPalette: Map<String, Boolean> = emptyMap(),
) {
  val allSources: Map<String, String> get() = associations.entries.associate { it.value to it.key } + sources

  fun begin(request: IconPackImportRequest) = copy(pendingToken = request.token,
    pendingSource = request.sourceId, result = null, request = request)

  fun accept(report: NativeIconImportReport): IconImportMutation {
    if ((pendingToken == report.token && pendingSource != report.sourceId) ||
      (result?.token == report.token && result.sourceId != report.sourceId)) {
      return IconImportMutation(this, publish = false, complete = false, accepted = false)
    }
    val current = pendingToken == report.token && pendingSource == report.sourceId
    val repeated = pendingToken == null && result?.token == report.token && result.sourceId == report.sourceId
    val bootstrap = pendingToken == null && result == null && !report.pending
    val regressive = result?.token == report.token && !result.pending && report.pending
    val publish = (current || repeated || bootstrap) && !regressive
    val history = if (report.imported) {
      val retained = associations.entries.associate { it.value to it.key } + sources
      if (publish || report.localId !in retained) retained + (requireNotNull(report.localId) to report.sourceId) else retained
    } else sources
    val existingOwner = allSources[report.localId]
    val forward = if (report.imported && (publish || (report.sourceId !in associations &&
        (existingOwner == null || existingOwner == report.sourceId))))
      associations + (report.sourceId to requireNotNull(report.localId)) else associations
    val complete = current && publish && !report.pending
    return IconImportMutation(copy(associations = forward, sources = history,
      pendingToken = if (complete) null else pendingToken,
      pendingSource = if (complete) null else pendingSource,
      request = if (complete) null else request,
      componentPalette = if (current && publish && report.imported && request?.preparedPalette != null)
        componentPalette + (requireNotNull(report.localId) to request.preparedPalette) else componentPalette,
      result = if (publish) report else result), publish, complete)
  }

  /** A saved pending operation always offers reconciliation, including after cancellation or
   * when persisting an uncertainty message failed. This is presentation, never a success ACK. */
  fun presentationResult(userId: Int): NativeIconImportReport? =
    if (pendingToken != null && pendingSource != null && result == null)
      NativeIconImportReport(pendingToken, userId, pendingSource, "UNCERTAIN") else result

  fun consumeNavigation(report: NativeIconImportReport): IconImportLocalState? {
    val token = report.autoApplyToken ?: return null
    if (!report.imported || token != report.token || token in navigated ||
      result?.token != token || result.sourceId != report.sourceId || result.localId != report.localId ||
      result.autoApplyToken != token || pendingToken != null) return null
    return copy(navigated = navigated + token)
  }
}

internal data class IconImportMutation(val state: IconImportLocalState, val publish: Boolean, val complete: Boolean,
  val accepted: Boolean = true)
