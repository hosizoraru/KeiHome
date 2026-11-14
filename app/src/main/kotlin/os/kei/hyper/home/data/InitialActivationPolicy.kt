package os.kei.hyper.home.data

internal enum class InitialActivationStatus { COMPLETE, REBOOT_REQUIRED, UNKNOWN }

internal data class InitialActivationRecord(
  val completed: Boolean = false,
  val scopeGranted: Boolean = false,
  val bootCount: Int? = null,
) {
  fun encode(): String = "1|${if (completed) 1 else 0}|${if (scopeGranted) 1 else 0}|${bootCount ?: -1}"

  companion object {
    fun decode(raw: String?): InitialActivationRecord {
      val fields = raw?.split('|') ?: return InitialActivationRecord()
      if (fields.size != 4 || fields[0] != "1" ||
        fields[1] !in setOf("0", "1") || fields[2] !in setOf("0", "1")) return InitialActivationRecord()
      val boot = fields[3].toIntOrNull()?.takeIf { it >= -1 } ?: return InitialActivationRecord()
      return InitialActivationRecord(fields[1] == "1", fields[2] == "1", boot.takeIf { it >= 0 })
    }
  }
}

internal object InitialActivationPolicy {
  fun observe(
    previous: InitialActivationRecord,
    bootCount: Int?,
    scopeGranted: Boolean?,
    systemLoaded: Boolean,
  ): InitialActivationRecord {
    if (previous.completed) return previous
    val boot = bootCount?.takeIf { it >= 0 }
    if (scopeGranted == true && systemLoaded ||
      previous.scopeGranted && previous.bootCount != null && boot != null && boot != previous.bootCount)
      return previous.copy(completed = true)
    return when (scopeGranted) {
      true -> InitialActivationRecord(scopeGranted = true,
        bootCount = if (previous.scopeGranted) previous.bootCount ?: boot else boot)
      false -> InitialActivationRecord()
      null -> if (previous.scopeGranted && previous.bootCount == null)
        previous.copy(bootCount = boot) else previous
    }
  }

  fun status(record: InitialActivationRecord, bootCount: Int?): InitialActivationStatus = when {
    record.completed -> InitialActivationStatus.COMPLETE
    !record.scopeGranted || record.bootCount == null || bootCount == null || bootCount < 0 ->
      InitialActivationStatus.UNKNOWN
    else -> InitialActivationStatus.REBOOT_REQUIRED
  }
}
