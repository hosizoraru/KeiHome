package os.kei.hyper.home.data

/** Decimal byte thresholds for reminders only; they do not limit downloads or delete resources. */
internal object CacheReminderBudget {
  const val DEFAULT_BYTES: Long = 500_000_000L
  const val PERFECT_BYTES: Long = 1_000_000_000L
  const val MIN_BYTES: Long = 50_000_000L
  const val MAX_BYTES: Long = 20_000_000_000L

  fun isValidCustom(bytes: Long): Boolean = bytes in MIN_BYTES..MAX_BYTES

  /** Pass current downloaded Perfect resource bytes so automatic mode follows resource changes. */
  fun resolve(customBytes: Long, perfectBytes: Long): Long = when {
    isValidCustom(customBytes) -> customBytes
    perfectBytes > 0L -> PERFECT_BYTES
    else -> DEFAULT_BYTES
  }
}
