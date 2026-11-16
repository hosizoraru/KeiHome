package os.kei.hyper.home.ui.main

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import os.kei.hyper.home.data.PendingPackageRefresh
import os.kei.hyper.home.data.PackageRefreshTarget
import os.kei.hyper.home.data.PreservedIcons
import os.kei.hyper.home.iconpack.*
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.data.IconPackAppearance

internal data class CustomIconsState(
  val selections: List<CustomIconSelection> = emptyList(),
  val apps: List<PreservedIconApp> = emptyList(),
  val origins: Map<String, Boolean> = emptyMap(),
  val loading: Boolean = false,
  val busy: Boolean = false,
  val failed: Boolean = false,
  val saved: Long = 0,
  val ready: Boolean = false,
  val showHidden: Boolean = false,
)

internal class CustomIconsController(
  context: Context, private val scope: CoroutineScope,
  private val repository: IconPackRepository, private val previews: IconPackPreviewRepository,
  private val onCustomSaved: (String) -> Unit,
  private val onOriginalPreserved: (String) -> Unit,
) {
  private val app = context.applicationContext
  val inventory = PreservedIconInventory(app)
  private val mutableState = MutableStateFlow(CustomIconsState())
  val state = mutableState.asStateFlow()
  private val previewWorker = Dispatchers.IO.limitedParallelism(2)
  private val pending = PendingPackageRefresh()
  private val wakeUp = Channel<Unit>(Channel.CONFLATED)
  private val metadataRetry = MutableStateFlow(0L)

  init {
    scope.launch {
      CustomIconStore.revision.combine(metadataRetry) { _, _ -> Unit }.collectLatest {
        try {
          val rows = withContext(Dispatchers.IO) { CustomIconStore.selections(app) }
          currentCoroutineContext().ensureActive()
          val old = state.value.selections.mapTo(hashSetOf()) { it.packageName }
          val next = rows.mapTo(hashSetOf()) { it.packageName }
          mutableState.update { it.copy(selections = rows, ready = true) }
          ((old - next) + (next - old)).forEach(::refreshApps)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
          android.util.Log.w("KeiHomeCustomIcons", "Cannot read custom choices", failure)
          mutableState.update { it.copy(failed = true, ready = false) }
        }
      }
    }
    scope.launch {
    for (signal in wakeUp) {
    if (state.value.busy) continue
    val request = pending.drain()
    if (!request.global && request.targets.isEmpty()) continue
    mutableState.update { it.copy(loading = true, failed = false) }
      try {
        val result = withContext(Dispatchers.IO) {
          val apps = inventory.read(Locale.getDefault(),
            if (request.global) null else request.targets.mapTo(linkedSetOf()) { it.packageName },
            IconInventoryVisibility(state.value.showHidden,
              PreservedIcons.snapshot(app) + state.value.selections.map { it.packageName }))
            .filter { os.kei.hyper.home.data.PreservedIcons.isValidPackageName(it.packageName) }.distinctBy { it.packageName }
          // Flags also classify hidden components without loading their labels or bitmaps.
          val pm = app.packageManager
          val origins = if (request.global) pm.getInstalledApplications(0).associate {
            it.packageName to (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
          } else state.value.origins.toMutableMap().apply {
            request.targets.forEach { target ->
              try {
                val info = pm.getApplicationInfo(target.packageName, 0)
                put(target.packageName, info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
              } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                remove(target.packageName)
              }
            }
          }
          apps to origins
        }
        mutableState.update { it.copy(apps = result.first, origins = result.second, loading = false) }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) {
        android.util.Log.w("KeiHomeCustomIcons", "Cannot read custom icons", failure)
        mutableState.update { it.copy(loading = false, failed = true) }
      }
    }
  } }

  fun load() {
    if (!state.value.ready && state.value.failed) metadataRetry.value++
    pending.add()
    wakeUp.trySend(Unit)
  }

  fun setShowHidden(value: Boolean) {
    if (state.value.showHidden == value) return
    mutableState.update { it.copy(showHidden = value) }
    load()
  }

  fun refreshApps(packageName: String) {
    pending.add(PackageRefreshTarget(packageName))
    wakeUp.trySend(Unit)
  }

  fun save(packageName: String, label: String, pack: IconPackDescriptor, entry: IconPackEntry,
    sourceTitle: String = pack.displayName) {
    if (!state.value.ready || state.value.busy || state.value.loading) return
    mutableState.update { it.copy(busy = true, failed = false) }
    scope.launch {
      try {
        val caller = currentCoroutineContext()
        val resources = withContext(Dispatchers.IO) {
          val sourceTarget = entry.component ?: entry.nativeName?.let {
            val parts = it.split('#', limit = 2)
            IconPackComponent.parse(if (parts.size == 2) "${parts[0]}/${parts[1]}" else it)
          } ?: IconPackComponent(packageName)
          val selected = if (entry.component == null && entry.nativeName == null) entry.copy(component = sourceTarget) else entry
          val handoff = IconPackHandoffs.prepare(app, repository, previews, pack.copy(entries = listOf(selected)),
            listOf(sourceTarget), IconPackAppearance.SYSTEM, includePalette = false, installedOnly = true,
            completeSystemIconConfig = false, applyCustomIcons = false)
          val module = File(app.cacheDir, "custom-capture-${UUID.randomUUID()}.zip")
          try {
            ZipFile(handoff.file).use { theme ->
              val icons = requireNotNull(theme.getEntry("icons"))
              theme.getInputStream(icons).use { input -> module.outputStream().use { input.copyTo(it) } }
            }
            val resources = ZipFile(module).use { zip ->
              val candidates = listOfNotNull(selected.nativeName) + nativeLauncherIconNames(sourceTarget)
              val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
              val owner = candidates.firstOrNull { name -> entries.any { CustomIconResources.owner(it.name) == name } }
                ?: throw IconPackException("The selected icon cannot be exported")
              var bytes = 0L
              val selectedResources = entries.filter { CustomIconResources.owner(it.name) == owner }.associate { resource ->
                caller.ensureActive()
                val data = zip.getInputStream(resource).use { it.readNBytes(CustomIconResources.MAX_BYTES + 1) }
                bytes += data.size
                require(bytes <= CustomIconResources.MAX_BYTES)
                resource.name to data
              }
              CustomIconResources.select(selectedResources, listOf(owner))
            }
            caller.ensureActive()
            resources
          } finally { handoff.file.delete(); module.delete() }
        }
        // Once storage commits, finish the mutually exclusive preference even if the page closes.
        withContext(kotlinx.coroutines.NonCancellable) {
          withContext(Dispatchers.IO) {
            CustomIconStore.save(app, packageName, label, pack.copy(displayName = sourceTitle), entry, resources) {
              caller.ensureActive()
            }
          }
          onCustomSaved(packageName)
          mutableState.update { it.copy(busy = false, saved = it.saved + 1) }
        }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) {
        android.util.Log.w("KeiHomeCustomIcons", "Cannot save custom icon", failure)
        mutableState.update { it.copy(busy = false, failed = true) }
      } finally { wakeUp.trySend(Unit) }
    }
  }

  fun remove(packageName: String, preserveOriginal: Boolean = false) {
    if (!state.value.ready || state.value.busy || state.value.loading) return
    mutableState.update { it.copy(busy = true, failed = false) }
    scope.launch {
      try {
        currentCoroutineContext().ensureActive()
        withContext(kotlinx.coroutines.NonCancellable) {
          withContext(Dispatchers.IO) { CustomIconStore.remove(app, packageName) }
          if (preserveOriginal) onOriginalPreserved(packageName)
          mutableState.update { it.copy(busy = false) }
        }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) {
        android.util.Log.w("KeiHomeCustomIcons", "Cannot remove custom icon", failure)
        mutableState.update { it.copy(busy = false, failed = true) }
      } finally { wakeUp.trySend(Unit) }
    }
  }

  suspend fun preview(pack: IconPackDescriptor, entry: IconPackEntry, dark: Boolean) =
    previews.load(pack, entry, 96, maskPolicy = IconMaskPolicy(false), preservedPackages = emptySet())
      .let { if (dark) it.dark ?: it.light else it.light }

  suspend fun preview(item: CustomIconSelection, dark: Boolean) = withContext(previewWorker) {
    CustomIconStore.preview(app, item, dark)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
  }
}
