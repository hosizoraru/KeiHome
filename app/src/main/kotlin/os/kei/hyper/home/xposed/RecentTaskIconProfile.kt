package os.kei.hyper.home.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Parcel
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.io.File

internal data class RecentTaskIconMethod(
  val id: String,
  val calls: Set<String>,
  val reads: Set<String>,
  val writes: Set<String>,
)

internal object RecentTaskIconEligibility {
  const val MAIN = "android.intent.action.MAIN"
  const val LAUNCHER = "android.intent.category.LAUNCHER"
  private const val DOCUMENT_FLAGS =
    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
      Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
      Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS or
      Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET

  fun allows(
    userId: Int, contextUserId: Int, action: String?, categories: Set<String>?,
    flags: Int, hasData: Boolean, hasType: Boolean, hasSelector: Boolean,
    componentPackage: String?, basePackage: String?, rootPackages: Set<String>,
  ): Boolean = userId >= 0 && userId == contextUserId && ordinaryLaunch(action, categories) &&
    flags and DOCUMENT_FLAGS == 0 &&
    !hasData && !hasType && !hasSelector && !componentPackage.isNullOrEmpty() &&
    (basePackage == null || componentPackage == basePackage) &&
    rootPackages.isNotEmpty() && rootPackages.all { it == componentPackage }

  private fun ordinaryLaunch(action: String?, categories: Set<String>?): Boolean =
    (action == MAIN && categories == setOf(LAUNCHER)) ||
      ((action == null || action == MAIN) && categories.isNullOrEmpty())
}

/** Names are discovery seeds; the live call/field graph must also authenticate the boundary. */
internal object RecentTaskIconContract {
  const val RECENTS = "Lcom/android/server/wm/RecentTasks;"
  const val TASK = "Lcom/android/server/wm/Task;"
  const val CONTROLLER = "Lcom/android/server/wm/TaskChangeNotificationController;"
  const val INFO = "Landroid/app/TaskInfo;"
  const val DESCRIPTION = "Landroid/app/ActivityManager\$TaskDescription;"
  const val RECENT = "Landroid/app/ActivityManager\$RecentTaskInfo;"
  const val CREATE = "$RECENTS->createRecentTaskInfo(${TASK}ZZ)$RECENT"
  const val FILL = "$TASK->fillTaskInfo(${INFO}Z)V"
  const val FILL_BODY = "$TASK->fillTaskInfo(${INFO}ZLcom/android/server/wm/TaskDisplayArea;)V"
  const val GET_DESCRIPTION = "$TASK->getTaskDescription()$DESCRIPTION"
  const val NOTIFY = "$CONTROLLER->notifyTaskDescriptionChanged($INFO)V"
  const val REMOVE = "$CONTROLLER->notifyTaskRemoved(I)V"
  const val UPDATE = "$TASK->updateTaskDescription()V"
  const val CONSUMER = "Lcom/android/server/wm/TaskChangeNotificationController\$TaskStackConsumer;"
  const val DESCRIPTION_FIELD = "$INFO->taskDescription:$DESCRIPTION"
  const val ICON_FIELD = "$DESCRIPTION->mIcon:Landroid/graphics/drawable/Icon;"
  const val FILENAME_FIELD = "$DESCRIPTION->mIconFilename:Ljava/lang/String;"
  const val SET_ICON = "$DESCRIPTION->setIcon(Landroid/graphics/drawable/Icon;)V"
  const val SET_FILENAME = "$DESCRIPTION->setIconFilename(Ljava/lang/String;)V"
  const val HANDLER = "$CONTROLLER->mHandler:Landroid/os/Handler;"

  fun verify(methods: Map<String, RecentTaskIconMethod>) {
    fun method(id: String) = checkNotNull(methods[id]) { "Missing recent-task method: $id" }
    check(method(CREATE).calls.any { it == FILL || it == FILL_BODY })
    check(FILL_BODY in method(FILL).calls)
    check("$DESCRIPTION-><init>($DESCRIPTION)V" in method(FILL_BODY).calls)
    check(DESCRIPTION_FIELD in method(FILL_BODY).writes)
    check("$INFO->baseIntent:Landroid/content/Intent;" in method(FILL_BODY).writes)
    val descriptionField = "$TASK->mTaskDescription:$DESCRIPTION"
    val fill = method(FILL_BODY)
    check(descriptionField in fill.reads ||
      (GET_DESCRIPTION in fill.calls && descriptionField in method(GET_DESCRIPTION).reads))
    val notify = method(NOTIFY)
    check(HANDLER in notify.reads)
    check("Landroid/os/Handler;->obtainMessage(ILjava/lang/Object;)Landroid/os/Message;" in notify.calls)
    check("Landroid/os/Message;->sendToTarget()V" in notify.calls)
    check("$CONTROLLER->forAllLocalListeners(${CONSUMER}Landroid/os/Message;)V" in notify.calls)
    check(NOTIFY in method(UPDATE).calls)
    check(HANDLER in method(REMOVE).reads)
    check("Landroid/os/Message;->sendToTarget()V" in method(REMOVE).calls)
    check(method(SET_ICON).writes == setOf(ICON_FIELD))
    check(method(SET_FILENAME).writes.containsAll(setOf(ICON_FIELD, FILENAME_FIELD)))
    val copy = method("$INFO-><init>($INFO)V")
    check(copy.writes.containsAll(setOf(
      "$INFO->taskId:I", "$INFO->userId:I", DESCRIPTION_FIELD,
      "$INFO->topActivity:Landroid/content/ComponentName;",
      "$INFO->origActivity:Landroid/content/ComponentName;",
      "$INFO->baseIntent:Landroid/content/Intent;",
    )))
    check("$INFO-><init>($INFO)V" in method("$RECENT-><init>($RECENT)V").calls)
  }
}

internal class RecentTaskIconProfile private constructor(
  val create: Method,
  val notify: Method,
  val remove: Method,
  private val controllerClass: Class<*>,
  private val recentClass: Class<*>,
  private val recentCopy: Constructor<*>,
  private val infoCopy: Constructor<*>,
  private val descriptionCopy: Constructor<*>,
  private val description: Field,
  private val taskId: Field,
  private val userId: Field,
  private val displayId: Field,
  private val token: Field,
  private val baseIntent: Field,
  private val components: List<Field>,
  private val handler: Field,
  private val recentsController: Field,
  private val setIcon: Method,
  private val setFilename: Method,
) {
  fun controller(recents: Any): Any = checkNotNull(recentsController.get(recents)).also {
    check(controllerClass.isInstance(it))
  }

  fun handler(controller: Any): Handler = handler.get(controller) as Handler
  fun taskId(info: Any): Int = taskId.getInt(info)
  fun userId(info: Any): Int = userId.getInt(info)
  fun component(info: Any, contextUserId: Int): ComponentName? {
    val intent = baseIntent.get(info) as? Intent ?: return null
    val component = intent.component ?: return null
    if (intent.identifier != null || intent.`package`?.let { it != component.packageName } == true)
      return null
    val roots = components.mapNotNull { (it.get(info) as? ComponentName)?.packageName }.toSet()
    val basePackage = (components.last().get(info) as? ComponentName)?.packageName
    return component.takeIf { RecentTaskIconEligibility.allows(
      userId(info), contextUserId, intent.action, intent.categories, intent.flags,
      intent.data != null || intent.clipData != null, intent.type != null, intent.selector != null,
      component.packageName, basePackage, roots,
    ) }
  }

  fun copy(info: Any): Any =
    (when {
      recentClass.isInstance(info) -> recentCopy.newInstance(info)
      info.javaClass == ActivityManager.RunningTaskInfo::class.java ->
        copyRunningTask(info as ActivityManager.RunningTaskInfo)
      else -> infoCopy.newInstance(info)
    }).also {
      val original = description.get(info)
      if (original != null) description.set(it, descriptionCopy.newInstance(original))
    }

  /** Match the native lite description event, whose listeners require RunningTaskInfo. */
  fun notification(info: Any): Any = ActivityManager.RunningTaskInfo().also {
    taskId.setInt(it, taskId.getInt(info))
    userId.setInt(it, userId.getInt(info))
    displayId.setInt(it, displayId.getInt(info))
    token.set(it, token.get(info))
    description.set(it, description.get(info)?.let { original -> descriptionCopy.newInstance(original) })
  }

  fun withDescription(info: Any, update: Any): Any = copy(info).also {
    val updated = description.get(update)
    if (updated != null) description.set(it, descriptionCopy.newInstance(updated))
    displayId.setInt(it, displayId.getInt(update))
  }

  fun withIcon(info: Any, icon: Icon): Any? {
    val original = description.get(info) ?: return null
    val replacement = descriptionCopy.newInstance(original)
    // Rust tries the in-memory bitmap first and then the filename. Do not retain the old file.
    setFilename.invoke(replacement, null)
    setIcon.invoke(replacement, icon)
    return copy(info).also { description.set(it, replacement) }
  }

  companion object {
    fun resolve(loader: ClassLoader, context: Context): RecentTaskIconProfile {
      val c = RecentTaskIconContract
      val graph = linkedMapOf<String, RecentTaskIconMethod>()
      fun snapshot(data: MethodData) {
        val uses = data.usingFields
        graph[data.descriptor] = RecentTaskIconMethod(data.descriptor,
          data.invokes.map { it.descriptor }.toSet(),
          uses.filter { it.usingType == FieldUsingType.Read }.map { it.field.descriptor }.toSet(),
          uses.filter { it.usingType == FieldUsingType.Write }.map { it.field.descriptor }.toSet())
      }
      fun DexKitBridge.required(id: String): MethodData = checkNotNull(getMethodData(id)).also {
        check(!Modifier.isStatic(it.modifiers) && !Modifier.isNative(it.modifiers) &&
          !Modifier.isAbstract(it.modifiers))
        snapshot(it)
      }
      // The framework's "android" context has no ordinary application data directory.
      val directory = if (context.packageName == "android") {
        File("/data/system/keihome-icon-imports/code_cache")
      } else context.codeCacheDir
      val methods = SystemUiMaterialDexKit.open("/system/framework/services.jar", directory).use { dex ->
        dex.setThreadNum(1)
        val create = dex.required(c.CREATE).getMethodInstance(loader)
        val notify = dex.required(c.NOTIFY).getMethodInstance(loader)
        val remove = dex.required(c.REMOVE).getMethodInstance(loader)
        dex.required(c.FILL)
        dex.required(c.FILL_BODY)
        dex.required(c.GET_DESCRIPTION)
        dex.required(c.UPDATE)
        // A typed field alone is insufficient: constructors must bind the native controller.
        val controllerField = dex.findMethod { matcher { declaredClass = create.declaringClass.name; name = "<init>" } }
          .filter { ctor -> ctor.invokes.any {
            it.returnTypeName == notify.declaringClass.name && it.paramCount == 0
          } }
          .flatMap { it.usingFields }
          .filter { it.usingType == FieldUsingType.Write &&
            it.field.className == create.declaringClass.name &&
            it.field.typeName == notify.declaringClass.name }
          .map { it.field.getFieldInstance(loader) }.distinct().single()
        listOf(create, notify, remove) to controllerField
      }
      SystemUiMaterialDexKit.open("/system/framework/framework.jar", directory).use { dex ->
        dex.setThreadNum(1)
        dex.required(c.SET_ICON)
        dex.required(c.SET_FILENAME)
        dex.required("${c.INFO}-><init>(${c.INFO})V")
        dex.required("${c.RECENT}-><init>(${c.RECENT})V")
      }
      c.verify(graph)
      fun type(name: String): Class<*> = Class.forName(name, false, loader)
      fun Class<*>.field(name: String): Field = getDeclaredField(name).apply { isAccessible = true }
      fun Class<*>.copy(): Constructor<*> = getDeclaredConstructor(this).apply { isAccessible = true }
      val info = type("android.app.TaskInfo")
      val recent = type("android.app.ActivityManager\$RecentTaskInfo")
      val desc = type("android.app.ActivityManager\$TaskDescription")
      val controller = methods.first[1].declaringClass
      return RecentTaskIconProfile(
        methods.first[0].apply { isAccessible = true },
        methods.first[1].apply { isAccessible = true },
        methods.first[2].apply { isAccessible = true },
        controller, recent, recent.copy(), info.copy(), desc.copy(),
        info.field("taskDescription"), info.field("taskId"), info.field("userId"), info.field("displayId"),
        info.field("token"),
        info.field("baseIntent"),
        listOf("origActivity", "realActivity", "baseActivity").map { info.field(it) },
        controller.field("mHandler").also { check(it.type == Handler::class.java) },
        methods.second.apply { isAccessible = true },
        desc.getDeclaredMethod("setIcon", Icon::class.java).apply { isAccessible = true },
        desc.getDeclaredMethod("setIconFilename", String::class.java).apply { isAccessible = true },
      )
    }
  }
}

internal fun copyRunningTask(info: ActivityManager.RunningTaskInfo): ActivityManager.RunningTaskInfo {
  val parcel = Parcel.obtain()
  return try {
    info.writeToParcel(parcel, 0)
    parcel.setDataPosition(0)
    ActivityManager.RunningTaskInfo.CREATOR.createFromParcel(parcel)
  } finally { parcel.recycle() }
}
