package dev.pebble.desktop

import dev.pebble.core.brain.CommandFeedbackRepository
import dev.pebble.core.brain.CommandRouter
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventBus
import dev.pebble.core.event.EventLogger
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.layout.WidgetLayoutRepository
import dev.pebble.core.memory.MemoryEngine
import dev.pebble.core.memory.MemoryRepository
import dev.pebble.core.quickadd.QuickCommand
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderKind
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.settings.SettingsRepository
import dev.pebble.core.settings.SettingsRepository.Keys
import dev.pebble.core.wellness.GLASS_ML
import dev.pebble.core.wellness.NoteRepository
import dev.pebble.core.wellness.WaterRepository
import dev.pebble.db.PebbleDatabase
import dev.pebble.desktop.command.CommandActions
import dev.pebble.desktop.command.CommandExecutor
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.FileLogger
import dev.pebble.desktop.core.Logger
import dev.pebble.desktop.core.UiPort
import dev.pebble.desktop.pet.Mood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.time.ZoneId

/** Something the pet should say out loud (in its speech bubble), with the face to make. */
data class PetLine(
    val text: String,
    val mood: Mood = Mood.HAPPY,
    val durationMillis: Long = 4_000,
    val actions: List<dev.pebble.desktop.pet.BubbleAction> = emptyList(),
)

/**
 * App-wide object graph. Created once in `main`, shared by every window. Lives on the Swing thread.
 * Time and dispatchers come from [env], so tests can fix "now".
 */
class PebbleApp(
    db: PebbleDatabase,
    val env: AppEnv = AppEnv.system(),
    /** Quiet failures go here (`pebble.log` in the app; dropped in tests unless a test passes one). */
    val log: Logger = Logger.None,
) : CommandActions {
    /** Now, from the app's clock ([env]). Use this instead of the top-level [dev.pebble.desktop.now]. */
    fun now(): Long = env.millis()

    val bus = EventBus()
    val layouts = WidgetLayoutRepository(db)
    val settings = SettingsRepository(db)
    val reminders = ReminderRepository(db).apply { seedDefaults() }
    val water = WaterRepository(db)
    val notes = NoteRepository(db)
    val memory = MemoryRepository(db)

    /** The pet's growth: levels earned by what you do (reminders done, water goals, active days, chats). */
    val growth = dev.pebble.core.growth.GrowthEngine(
        db,
        dayOf = env::dayOf,
        waterGoalMl = { waterGoalGlasses * GLASS_ML },
    )

    val brain = MemoryEngine(
        db,
        memory,
        clock = this::now,
        hourOf = { env.minuteOfDay(it) / 60 },
        dayOf = env::dayOf,
        waterGoalMl = { waterGoalGlasses * GLASS_ML },
    )

    /** Learns when repeating reminders land best (contextual bandit); beliefs persist in the database. */
    val nudge = dev.pebble.core.brain.NudgePolicy(dev.pebble.core.brain.SqlNudgeStore(db), quietHours = brain::quietHours)
        .also { brain.nudge = it }
    val engine = ReminderEngine(
        reminders,
        bus,
        clock = this::now,
        minuteOfDay = env::minuteOfDay,
        quietHours = brain::quietHours,
        nudge = nudge,
        busy = dev.pebble.desktop.platform.UserActivity::isFullscreenBusy,
    )

    /** "Forget this" on the Memory page; forgetting a learned nudge timing also resets what was learned there. */
    fun forget(m: dev.pebble.core.memory.Memory) {
        memory.forget(m)
        if (m.key.startsWith(dev.pebble.core.memory.MemoryEngine.NUDGE_PREFIX)) {
            nudge.resetContext(m.key.removePrefix(dev.pebble.core.memory.MemoryEngine.NUDGE_PREFIX))
        }
    }

    /** Every coroutine of the app runs in this scope (or a child of it), so [shutdown] stops them all. */
    private val appScope = CoroutineScope(SupervisorJob() + env.dispatchers.main)

    private val _petLines = MutableSharedFlow<PetLine>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val petLines: SharedFlow<PetLine> = _petLines

    @Volatile private var boundUi: UiPort? = null

    /** The UI (toasts, opening pages). Does nothing until [bindUi]; read at call time, so it can be bound late. */
    val ui: UiPort = object : UiPort {
        override fun notify(title: String, message: String) = (boundUi ?: UiPort.None).notify(title, message)

        override fun openPage(page: String) = (boundUi ?: UiPort.None).openPage(page)
    }

    /** `Main` binds the real UI once the tray exists. Exactly once: a second bind is logged and ignored. */
    @Synchronized
    fun bindUi(port: UiPort) {
        if (boundUi != null) {
            log.warn(TAG, "UI bound a second time; keeping the first")
            return
        }
        boundUi = port
    }

    /** The local command model, loaded on demand; the router falls back to rules without it. */
    val model = dev.pebble.desktop.brain.ModelManager(appScope + env.dispatchers.default)
    val router = dev.pebble.core.brain.CommandRouter({ model }, today = { env.today().dayOfWeek.value })
    val commandFeedback = dev.pebble.core.brain.CommandFeedbackRepository(db)

    /** Offline speech (VAD + Whisper), loaded only when you talk and freed when idle. */
    val speech = dev.pebble.desktop.voice.SpeechRecognizer(appScope + env.dispatchers.default)
    val voice = dev.pebble.desktop.voice.VoiceInput(speech, appScope + env.dispatchers.default) {
        settings.bool(Keys.MICROPHONE_ENABLED, true)
    }

    /** Voice clips you corrected (opt-in), kept to tune speech recognition to your voice. */
    val voiceCorrections = dev.pebble.desktop.voice.VoiceCorrectionService(
        voice,
        speech,
        settings,
        dev.pebble.core.brain.VoiceSampleRepository(db),
        clock = this::now,
        dir = DatabaseFactory.defaultDataDir().toPath().resolve("voice"),
        log = log,
    )
    val voiceSamples: dev.pebble.core.brain.VoiceSampleRepository get() = voiceCorrections.samples

    /** Where kept voice clips go (tests point it elsewhere). */
    var voiceDir: java.nio.file.Path by voiceCorrections::dir

    fun noteVoiceCorrection(finalText: String) = voiceCorrections.noteCorrection(finalText)

    /** Deletes every kept voice clip, files and rows. */
    fun clearVoiceSamples() = voiceCorrections.clear()

    /** Runs every quick-add command. */
    val executor = CommandExecutor(env, bus, notes, reminders, engine, actions = this, ui = ui)

    init {
        // Unconfined: events are written on the publisher's thread, so nothing is lost on exit.
        EventLogger(db).attach(bus, CoroutineScope(appScope.coroutineContext + Dispatchers.Unconfined))
        bus.publish(PebbleEvent.AppStarted(now()))
        engine.start(appScope)
        // Learning is cheap (a few small queries); every 10 minutes keeps memories fresh.
        appScope.launch {
            while (isActive) {
                runCatching { brain.learn() }.onFailure { log.warn(TAG, "learning failed", it) }
                delay(10 * 60_000L)
            }
        }
    }

    fun completeNote(id: Long) {
        notes.archive(id, now())
        bus.publish(PebbleEvent.NoteCompleted(id, now()))
    }

    fun logMood(score: Int) {
        memory.logMood(score, now())
        bus.publish(PebbleEvent.MoodLogged(score, now()))
        brain.learn()
    }

    override fun remember(text: String) {
        val key = memory.remember(text, now())
        bus.publish(PebbleEvent.FactRemembered(key, now()))
    }

    /** Whether the pet already asked "how was today?" today. */
    fun moodLoggedToday(): Boolean = memory.moodSince(startOfToday()).isNotEmpty()

    fun say(line: PetLine) {
        _petLines.tryEmit(line)
    }

    override val waterGoalGlasses: Int get() = settings.int(Keys.WATER_GOAL_GLASSES, 8)

    fun startOfToday(): Long = env.startOfToday()

    override fun logWater(glasses: Int): Int {
        val goalMl = waterGoalGlasses * GLASS_ML
        val before = water.totalSince(startOfToday())
        repeat(glasses) { water.log(GLASS_ML, now()) }
        val total = water.totalSince(startOfToday())
        bus.publish(PebbleEvent.WaterLogged(glasses * GLASS_ML, total, now()))
        // Drinking counts as answering an open water reminder.
        engine.active.value.firstOrNull { it.kind == ReminderKind.WATER }
            ?.let { engine.act(it.key, dev.pebble.core.reminders.ReminderAction.DONE) }
        if (before < goalMl && total >= goalMl) {
            runCatching { brain.learn() }.onFailure { log.warn(TAG, "learning after the water goal failed", it) }
            val streak = memory.byKey(MemoryEngine.KEY_WATER_STREAK)?.data
            say(
                PetLine(
                    if (streak !=
                        null
                    ) {
                        "Water goal done — $streak days in a row."
                    } else {
                        "Water goal done for today."
                    },
                    Mood.CELEBRATE,
                    3_500,
                ),
            )
        }
        return total / GLASS_ML
    }

    fun undoWater() {
        water.undoLast(startOfToday())
    }

    override fun addNote(text: String): Long = notes.add(text, now()).also { bus.publish(PebbleEvent.NoteCreated(it, now())) }

    /** Your conversation with Pebble (Quick Add and voice), shown in Quick Add and on the Chat page. */
    val conversation = dev.pebble.core.brain.ConversationRepository(db)

    /**
     * Everything you say to Pebble goes through here: runs it (with "Not what I meant" when the model chose),
     * remembers the exchange, and returns Pebble's reply. [via] is "typed" or "voice".
     */
    fun converse(text: String, via: String, routed: CommandRouter.Routed.Run, retry: (text: String, wrongAction: String) -> Unit): PetLine {
        val line = if (routed.source == CommandRouter.Source.MODEL) executeFromModel(text, routed, retry) else execute(routed.command)
        remember(text, via, routed.command, line)
        return line
    }

    /** You picked [option] from "Did you mean…": a strong label for the next model, and a turn in the chat. */
    fun converseChoice(text: String, via: String, option: CommandRouter.Option, understood: dev.pebble.core.brain.Understood?): PetLine {
        commandFeedback.record(text.trim(), option.action, understood, now())
        val line = execute(option.command)
        remember(text, via, option.command, line)
        return line
    }

    private fun remember(text: String, via: String, cmd: QuickCommand, line: PetLine) {
        val did = describe(cmd)
        conversation.add(dev.pebble.core.brain.Turn(now(), text.trim(), via, did, line.text.ifBlank { did }))
    }

    /**
     * Runs a command the model chose, with a way out: Pebble's reply gets a "Not what I meant" button
     * that undoes it, labels it wrong for the next model, and calls [retry] with the text and the wrong
     * action so Quick Add can ask what you did mean. Unchallenged, it stays a "confirmed" label.
     */
    fun executeFromModel(text: String, run: CommandRouter.Routed.Run, retry: (text: String, wrongAction: String) -> Unit): PetLine {
        val executed = executor.execute(run.command)
        val line = executed.line
        val action = run.action ?: return line
        val id = commandFeedback.record(text.trim(), action, run.understood, now(), CommandFeedbackRepository.CONFIRMED)
        val notWhatIMeant = dev.pebble.desktop.pet.BubbleAction("Not what I meant") {
            executed.undo?.invoke()
            commandFeedback.markWrong(id)
            retry(text, action)
        }
        return line.copy(durationMillis = maxOf(line.durationMillis, 8_000), actions = listOf(notWhatIMeant))
    }

    /** Runs a quick-add command and returns what the pet should say about it. */
    fun execute(cmd: QuickCommand): PetLine = executor.execute(cmd).line

    /** One-line preview shown under the quick-add field before you press Enter. */
    fun describe(cmd: QuickCommand): String = executor.describe(cmd)

    fun shutdown() {
        bus.publish(PebbleEvent.AppStopping(now()))
        appScope.cancel()
    }

    companion object {
        private const val TAG = "app"

        fun create(): PebbleApp {
            val env = AppEnv.system()
            val log = FileLogger(DatabaseFactory.defaultDataDir().toPath().resolve("pebble.log"), env::millis, env.zone)
            return PebbleApp(DatabaseFactory.create(), env, log)
        }
    }
}

@Deprecated("Untestable wall-clock time. Use PebbleApp.now() (the app's clock).", ReplaceWith("app.now()"))
fun now(): Long = System.currentTimeMillis()

@Deprecated("Uses the system zone directly. Use PebbleApp.env.minuteOfDay().", ReplaceWith("app.env.minuteOfDay(millis)"))
fun minuteOfDay(millis: Long): Int =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().let { it.hour * 60 + it.minute }
