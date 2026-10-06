package dev.pebble.desktop

import dev.pebble.core.backup.Backup
import dev.pebble.core.brain.CommandFeedbackRepository
import dev.pebble.core.brain.CommandRouter
import dev.pebble.core.brain.Understood
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

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

    /** Calendar events (WP E2); [agenda] expands them into days and keeps their linked reminders. */
    val calendar = dev.pebble.core.calendar.CalendarRepository(db)
    val agenda = dev.pebble.core.calendar.CalendarAgenda(calendar, reminders, env.zone)

    /** This device's id (WP E1), made at the first start; rows from before it existed are claimed for it. */
    val deviceId: String = dev.pebble.core.settings.DeviceIdentity.ensure(settings).also { id ->
        notes.claim(id)
        reminders.claim(id)
        calendar.claim(id)
    }

    /**
     * The change journal (WP E3a, ADR 0018). At each start it records rows from before E3, and changes that an older
     * Pebble made to the same file without the journal (they keep their HLC, so the guard triggers let them pass).
     */
    val journal = dev.pebble.core.sync.ChangeJournal(db).also { j ->
        runCatching { j.reconcile(now()) }
            .onSuccess { r ->
                if (r.backfilled > 0) log.info(TAG, "change journal: recorded ${r.backfilled} rows from before the journal")
                if (r.repaired + r.graves > 0) {
                    log.warn(
                        TAG,
                        "change journal: recorded ${r.repaired} changes and ${r.graves} deletes made without it (an older Pebble?)",
                    )
                }
            }
            .onFailure { log.warn(TAG, "change journal: reconcile failed", it) }
    }

    /** Old versions and lost edits of events, notes and reminders, kept 90 days on this device (WP E3c, ADR 0019). */
    val history = dev.pebble.core.sync.ChangeHistory(db, journal)

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
    val models = dev.pebble.desktop.brain.ModelRuntime(appScope + env.dispatchers.default, DatabaseFactory.defaultDataDir().toPath())
    val model = models.intent

    /** "Model packs" on the About page (WP D2): signed packs in `%APPDATA%\Pebble\models`, used from the next start. */
    val modelPacks = object : dev.pebble.desktop.app.pages.ModelPacksPort {
        private val labels = mapOf("intent" to "Command model", "asr" to "Speech models", "chat" to "Chat model (Smart replies)")
        private val bundled = System.getProperty("compose.application.resources.dir")?.let { java.nio.file.Path.of(it).resolve("models") }

        override suspend fun models() = withContext(env.dispatchers.io) {
            val staged = dev.pebble.desktop.brain.ModelPack.staged(models.packsDir)
            labels.map { (name, label) ->
                val dir = models.packsDir.resolve(name)
                val pack = dev.pebble.desktop.brain.ModelPack.installedManifest(dir)
                val status = when {
                    staged[name] == "remove" -> "Removed when Pebble starts again"

                    staged[name] != null -> "Installs when Pebble starts again: ${staged.getValue(name).removePrefix("install ")}"

                    pack != null -> when (val r = dev.pebble.desktop.brain.ModelPack.verifyInstalled(dir, name, models.cache)) {
                        is dev.pebble.desktop.brain.ModelPack.Result.Ok -> "Pack ${pack.version} (signed)"
                        is dev.pebble.desktop.brain.ModelPack.Result.Invalid -> "Pack not used: ${r.reason}"
                    }

                    bundled != null && java.nio.file.Files.isDirectory(bundled.resolve(name)) -> "Built in"

                    bundled == null -> "Development build"

                    else -> "Not installed"
                }
                dev.pebble.desktop.app.pages.ModelPacksUiState.Row(
                    name,
                    label,
                    status,
                    canRemove = pack != null && staged[name] != "remove",
                )
            }
        }

        override suspend fun install(zip: java.nio.file.Path) = withContext(env.dispatchers.io) {
            val r = runCatching {
                dev.pebble.desktop.brain.ModelPack.stageInstall(zip, models.packsDir, System.getProperty("jpackage.app-version"))
            }.getOrElse { dev.pebble.desktop.brain.ModelPack.Result.Invalid(it.message ?: "cannot read the file") }
            val what = when (r) {
                is dev.pebble.desktop.brain.ModelPack.Result.Ok -> r.manifest.let { "staged ${it.name} ${it.version} (${it.keyId})" }
                is dev.pebble.desktop.brain.ModelPack.Result.Invalid -> "refused, ${r.reason}"
            }
            log.info(TAG, "model pack ${zip.fileName}: $what")
            (r as? dev.pebble.desktop.brain.ModelPack.Result.Invalid)?.reason
        }

        override suspend fun remove(name: String) = withContext(env.dispatchers.io) {
            dev.pebble.desktop.brain.ModelPack.stageRemove(models.packsDir, name)
            log.info(TAG, "model pack $name: removal staged")
        }
    }
    val commandFeedback = dev.pebble.core.brain.CommandFeedbackRepository(db)

    /**
     * What you taught, picked and marked wrong, blended into the model's reading at once (WP C3, ADR 0010).
     * Rows before [Keys.PERSONAL_SINCE] are ignored ("Forget what you taught me"); they stay as training labels.
     */
    val personal = dev.pebble.core.brain.PersonalLayer(
        source = { commandFeedback.personalSince(settings.get(Keys.PERSONAL_SINCE)?.toLongOrNull() ?: 0L) },
        embed = model::embedIfLoaded,
        modelVersion = { model.version },
        hourOf = { env.minuteOfDay(it) / 60 },
        clock = this::now,
    )
    val router = dev.pebble.core.brain.CommandRouter({ model }, today = { env.today().dayOfWeek.value }, personal = personal)

    /**
     * "Backup" on the About page (WP E4, ADR 0015): an encrypted copy of the whole database, and a restore that is
     * applied at the next start ([create]). Tests point [dataDir] at a temp folder.
     */
    var dataDir: java.io.File = DatabaseFactory.defaultDataDir()
    val backup = object : dev.pebble.desktop.app.pages.BackupPort {
        override suspend fun export(file: java.nio.file.Path, passphrase: CharArray): String {
            eventLog.flush() // queued events go into the copy too
            return withContext(env.dispatchers.io) {
                dev.pebble.core.backup.Backup.export(java.io.File(dataDir, dev.pebble.core.backup.Backup.DB), file, passphrase)
                    .also { log.info(TAG, "backup exported ($it)") }.toString()
            }
        }

        override suspend fun stageRestore(file: java.nio.file.Path, passphrase: CharArray): String = withContext(env.dispatchers.io) {
            dev.pebble.core.backup.Backup.stageRestore(file, dataDir, passphrase).also {
                log.info(TAG, "backup staged for restore ($it)")
            }.toString()
        }

        override suspend fun cancelRestore() = withContext(env.dispatchers.io) {
            dev.pebble.core.backup.Backup.cancelRestore(dataDir)
            log.info(TAG, "staged restore cancelled")
        }

        override suspend fun restorePending(): Boolean = withContext(env.dispatchers.io) {
            dev.pebble.core.backup.Backup.restorePending(dataDir)
        }
    }

    /** "Teach Pebble a command" on the Memory page. Each change refreshes the layer, so the next sentence uses it. */
    val teaching = object : dev.pebble.desktop.app.pages.TeachingPort {
        override suspend fun taught() = withContext(env.dispatchers.io) { commandFeedback.taught() }

        override suspend fun teach(phrase: String, action: String) = withContext(env.dispatchers.io) {
            commandFeedback.teach(phrase, action, now())
            personal.refresh()
            Unit
        }

        override suspend fun unteach(id: Long) = withContext(env.dispatchers.io) {
            commandFeedback.delete(id)
            personal.refresh()
            Unit
        }

        override suspend fun forgetAll() = withContext(env.dispatchers.io) {
            commandFeedback.forgetTaught()
            settings.set(Keys.PERSONAL_SINCE, now().toString())
            personal.refresh()
            Unit
        }
    }

    /** Offline speech (VAD + Whisper), loaded only when you talk and freed when idle. */
    val speech = models.speech
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

    /** Rolls old log days into `daily_stat` (WP B6); declared before `init`, which starts the loop that uses it. */
    private val compactor = dev.pebble.core.history.HistoryCompactor(db, env::dayOf)

    /** Saves bus events to `event_log`; one writer thread, so the UI thread never waits for SQLite. */
    val eventLog = EventLogger(db) { log.warn(TAG, "event log write failed", it) }

    /**
     * Search over notes, facts and what you said (WP C2). It indexes only while the command model is loaded
     * anyway, so indexing never loads a model; a search you ask for on the Memory page loads it.
     */
    val memorySearch = dev.pebble.core.search.MemorySearch(db, embed = model::embedIfLoaded, modelVersion = {
        model.version
    }, clock = this::now)

    /** Coalesced: many requests while one runs mean one more pass, not many. */
    private val indexRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Asks for an index pass soon (in the background, after a short pause so several changes go together). */
    fun requestIndexing() {
        indexRequests.tryEmit(Unit)
    }

    override fun searchMemory(topic: String): List<String> = memorySearch.search(topic, limit = 3).map { it.text }

    /**
     * A search you asked for (the Memory page): loads the command model if needed, catches up the index,
     * then searches, all off the UI thread. A question that names a topic ("about the project") searches the
     * topic. Null when there is no command model at all.
     */
    suspend fun searchMemoryLoading(query: String, limit: Int = 5): List<dev.pebble.core.search.MemorySearch.Result>? =
        withContext(env.dispatchers.io) {
            if (model.modelDir == null || model.embedLoading(query) == null) return@withContext null
            runCatching { memorySearch.reconcile() }.onFailure { log.warn(TAG, "memory search indexing failed", it) }
            memorySearch.search(dev.pebble.core.search.MemorySearch.topicOf(query) ?: query, limit)
        }

    init {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        appScope.launch(env.dispatchers.io.limitedParallelism(1)) {
            indexRequests.collect {
                delay(1_500)
                runCatching { memorySearch.reconcile() }
                    .onSuccess { n -> if (n > 0) log.info(TAG, "memory search: indexed $n items") }
                    .onFailure { log.warn(TAG, "memory search indexing failed", it) }
                runCatching { personal.refresh() }
                    .onSuccess { n -> if (n > 0) log.info(TAG, "personal layer: embedded $n examples") }
                    .onFailure { log.warn(TAG, "personal layer refresh failed", it) }
            }
        }
        appScope.launch {
            bus.events.collect { e ->
                if (e is PebbleEvent.NoteCreated || e is PebbleEvent.FactRemembered || e is PebbleEvent.QuickAddUsed) requestIndexing()
            }
        }
        // The model just loaded (first Quick Add, a voice command): catch up on anything not indexed yet.
        appScope.launch { model.statusFlow.collect { if (it is dev.pebble.desktop.brain.ModelStatus.Ready) requestIndexing() } }
    }

    init {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        eventLog.attach(bus, appScope, writerDispatcher = env.dispatchers.io.limitedParallelism(1))
        bus.publish(PebbleEvent.AppStarted(now()))
        models.packChangesAtStart.forEach { log.info(TAG, "model pack: $it") }
        engine.start(appScope)
        // Learning is cheap (a few small queries); every 10 minutes keeps memories fresh.
        appScope.launch {
            while (isActive) {
                runCatching {
                    withContext(env.dispatchers.io) { rollUpHistory() }
                }.onFailure { log.warn(TAG, "history roll-up failed", it) }
                runCatching {
                    val n = withContext(env.dispatchers.io) { agenda.scheduleReminders(now()) }
                    if (n > 0) engine.tick()
                }.onFailure { log.warn(TAG, "calendar reminders failed", it) }
                runCatching { brain.learn() }.onFailure { log.warn(TAG, "learning failed", it) }
                requestIndexing() // edited notes and cleared chats have no event of their own
                delay(10 * 60_000L)
            }
        }
    }

    /** Counts whole days older than a week into `daily_stat`; does work once a day (the first time: all history). */
    private fun rollUpHistory() {
        val until = env.today().minusDays(dev.pebble.core.history.HistoryCompactor.KEEP_RAW_DAYS.toLong())
            .atStartOfDay(env.zone()).toInstant().toEpochMilli()
        val n = compactor.rollUpBefore(until)
        if (n > 0) log.info(TAG, "rolled up $n log entries before $until")
        // Tombstones (WP E1): kept 90 days, so a device that syncs later still learns about the delete.
        val purgeBefore = env.millis() - TOMBSTONE_DAYS * 24 * 60 * 60_000L
        val purged = notes.purgeTombstones(purgeBefore) + reminders.purgeTombstones(purgeBefore) + calendar.purgeTombstones(purgeBefore)
        if (purged > 0) log.info(TAG, "purged $purged deleted notes, reminders and events older than $TOMBSTONE_DAYS days")
        // History (WP E3c): the same 90 days, and all history of the rows purged above.
        val dropped = history.purge(purgeBefore)
        if (dropped > 0) log.info(TAG, "purged $dropped history entries")
    }

    /** Changes the text of note [id] (a journaled edit, so its History keeps the old text); the search index catches up. */
    fun editNote(id: Long, text: String) {
        notes.update(id, text, now())
        requestIndexing()
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

    /** Saves [e] (new or changed) and makes its reminders again. */
    override fun saveEvent(e: dev.pebble.core.calendar.CalendarEvent) {
        calendar.save(e, now())
        agenda.eventChanged(e.uid, now())
        engine.tick()
    }

    /** Deletes the event [uid] (a tombstone) and its reminders that did not fire yet. */
    override fun deleteEvent(uid: String) {
        calendar.delete(uid, now())
        agenda.eventChanged(uid, now())
        engine.tick()
    }

    override fun addNote(text: String): Long = notes.add(text, now()).also { bus.publish(PebbleEvent.NoteCreated(it, now())) }

    /** Your conversation with Pebble (Quick Add and voice), shown in Quick Add and on the Chat page. */
    val conversation = dev.pebble.core.brain.ConversationRepository(db)

    /**
     * Everything you say to Pebble goes through here: runs it (with "Not what I meant" when the model chose),
     * remembers the exchange, and returns Pebble's reply. [via] is "typed" or "voice".
     */
    fun converse(
        text: String,
        via: String,
        routed: CommandRouter.Routed.Run,
        /** A reply from the chat model ([smartReply]) in place of the canned line. */
        reply: String? = null,
        retry: (text: String, wrongAction: String) -> Unit,
    ): PetLine {
        val done = if (routed.source == CommandRouter.Source.MODEL) executeFromModel(text, routed, retry) else execute(routed.command)
        val line = if (reply != null) done.copy(text = reply) else done
        remember(text, via, routed.command, line)
        return line
    }

    /**
     * Like [converse] for small talk, but owned by the app, not by Quick Add: the optional chat-model reply
     * ([reply], up to 6 s) and the turn are finished even when Quick Add closes meanwhile. The pet always says the
     * answer ([petLines]); [onLine] lets a window that is still open show it too (it runs on the main thread).
     */
    fun converseAsync(
        text: String,
        via: String,
        routed: CommandRouter.Routed.Run,
        retry: (text: String, wrongAction: String) -> Unit,
        reply: suspend (QuickCommand) -> String? = ::smartReply,
        onLine: (PetLine?) -> Unit = {},
    ): Job = appScope.launch {
        guarded(onLine) {
            converse(text, via, routed, safeReply(reply, routed.command), retry)
        }
    }

    /** [converseChoice] with the same app-owned reply as [converseAsync]. */
    fun converseChoiceAsync(
        text: String,
        via: String,
        option: CommandRouter.Option,
        understood: Understood?,
        reply: suspend (QuickCommand) -> String? = ::smartReply,
        onLine: (PetLine?) -> Unit = {},
    ): Job = appScope.launch {
        guarded(onLine) {
            converseChoice(text, via, option, understood, safeReply(reply, option.command))
        }
    }

    /**
     * Runs [turn], lets the pet say its line and passes it to [onLine]. A failure is logged and [onLine] gets null,
     * so a window that waits ("thinking") always resets; cancellation is rethrown.
     */
    internal suspend fun guarded(onLine: (PetLine?) -> Unit, turn: suspend () -> PetLine) {
        val line = try {
            turn().also { say(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "smart reply turn failed", e)
            null
        }
        onLine(line)
    }

    private suspend fun safeReply(reply: suspend (QuickCommand) -> String?, cmd: QuickCommand): String? = try {
        reply(cmd)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null // the canned line answers
    }

    /**
     * The local chat model (WP C5, ADR 0012): `llama-server` from a signed "chat" pack, or from `PEBBLE_CHAT_DIR`
     * (developers). It runs only while "Smart replies" is on and you chat; it listens only on 127.0.0.1.
     */
    val chat = dev.pebble.desktop.brain.LocalChat(
        locate = {
            dev.pebble.desktop.brain.LocalChat.filesIn(
                System.getenv("PEBBLE_CHAT_DIR")?.let { java.nio.file.Path.of(it) }
                    ?: dev.pebble.desktop.brain.ModelChecksums.trustedUserDir(
                        DatabaseFactory.defaultDataDir().toPath(),
                        "chat",
                        models.cache,
                    ),
            )
        },
        scope = appScope + env.dispatchers.io,
        dispatcher = env.dispatchers.io,
        log = log,
        logFile = DatabaseFactory.defaultDataDir().toPath().resolve("chat-server.log"),
    )

    /**
     * Whether a chat model is installed. The check can hash a big pack, so it runs on the io thread, never on the UI
     * thread, and only when needed: at start with Smart replies on, and when the Memory page opens ([refreshChatInstalled]).
     */
    internal val chatInstalled = dev.pebble.desktop.brain.BackgroundFlag { chat.available }.also {
        if (settings.bool(Keys.SMART_REPLIES, false)) appScope.launch(env.dispatchers.io) { it.refresh() }
    }

    /** Checks again, off the UI thread, whether a chat model is installed. */
    fun refreshChatInstalled() {
        appScope.launch(env.dispatchers.io) { chatInstalled.refresh() }
    }

    /** True when the chat model may answer small talk: the setting is on and a chat model is installed. */
    fun smartRepliesOn(): Boolean = settings.bool(Keys.SMART_REPLIES, false) && chatInstalled.value

    /**
     * A chat-model reply to small talk [cmd], or null for the canned line (off, not installed, a low mood, a
     * health or money question, a script the model writes badly, too slow, or not safe to show).
     */
    suspend fun smartReply(cmd: QuickCommand): String? {
        val c = cmd as? QuickCommand.Chitchat ?: return null
        if (!smartRepliesOn() || !dev.pebble.core.brain.ChatSafety.mayUseModel(c.text, c.intent, c.mood, CHAT_SCRIPTS)) return null
        val ctx = withContext(env.dispatchers.io) {
            dev.pebble.core.brain.ReplyContext(
                userText = c.text,
                mood = c.mood,
                recentTurns = runCatching { conversation.recent(3).map { it.said to it.reply } }.getOrDefault(emptyList()),
                memories = runCatching {
                    memorySearch.search(c.text, limit = 3).filter { it.kind != dev.pebble.core.search.MemorySearch.SAID }.map { it.text }
                }.getOrDefault(emptyList()),
            )
        }
        return chat.reply(ctx)
    }

    /** You picked [option] from "Did you mean…": a strong label for the next model, and a turn in the chat. */
    fun converseChoice(
        text: String,
        via: String,
        option: CommandRouter.Option,
        understood: Understood?,
        /** A reply from the chat model ([smartReply]) in place of the canned line. */
        reply: String? = null,
    ): PetLine {
        commandFeedback.record(text.trim(), option.action, understood, now())
        requestIndexing() // the personal layer learns the pick
        val line = execute(option.command).let { if (reply != null) it.copy(text = reply) else it }
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
        // The pet bubble and Quick Add both show this button: only the first click acts.
        val clicked = AtomicBoolean(false)
        val notWhatIMeant = dev.pebble.desktop.pet.BubbleAction("Not what I meant") {
            if (!clicked.compareAndSet(false, true)) return@BubbleAction
            executed.undo?.invoke()
            commandFeedback.markWrong(id)
            requestIndexing()
            retry(text, action)
        }
        return line.copy(durationMillis = maxOf(line.durationMillis, 8_000), actions = listOf(notWhatIMeant))
    }

    /** Runs a quick-add command and returns what the pet should say about it. */
    fun execute(cmd: QuickCommand): PetLine = executor.execute(cmd).line

    /** One-line preview shown under the quick-add field before you press Enter. */
    fun describe(cmd: QuickCommand): String = executor.describe(cmd)

    /** On exit: saves the events still queued (at most 2 s), then stops every coroutine. */
    fun shutdown() {
        runCatching { chat.close() }
        bus.publish(PebbleEvent.AppStopping(now()))
        val saved = runBlocking { eventLog.close(2.seconds) }
        if (!saved) log.warn(TAG, "event log not flushed within 2 s on exit")
        appScope.cancel()
    }

    companion object {
        private const val TAG = "app"

        /** Deleted notes, reminders and events stay as tombstones this long (until sync can confirm that peers saw them). */
        const val TOMBSTONE_DAYS = 90L

        /** Scripts the shipped chat model writes well enough (brain/eval/chat_v1.jsonl, ADR 0012); others get canned lines. */
        val CHAT_SCRIPTS = setOf(dev.pebble.core.brain.Script.EN)

        fun create(): PebbleApp {
            val env = AppEnv.system()
            val dir = DatabaseFactory.defaultDataDir()
            val log = FileLogger(dir.toPath().resolve("pebble.log"), env::millis, env.zone)
            // A restore staged on the About page replaces the database before it is opened (WP E4).
            runCatching { Backup.applyStaged(dir) }
                .onSuccess { it?.let { line -> log.info(TAG, line) } }
                .onFailure {
                    log.warn(TAG, "restoring the backup failed", it)
                    // No new empty database next to the old files: the next start puts them back (Backup.recoverInterrupted).
                    if (!File(dir, Backup.DB).exists() && Backup.interruptedSwapPending(dir)) throw RestoreNotFinishedException(dir, it)
                }
            return PebbleApp(DatabaseFactory.create(), env, log)
        }
    }
}

@Deprecated("Untestable wall-clock time. Use PebbleApp.now() (the app's clock).", ReplaceWith("app.now()"))
fun now(): Long = System.currentTimeMillis()

@Deprecated("Uses the system zone directly. Use PebbleApp.env.minuteOfDay().", ReplaceWith("app.env.minuteOfDay(millis)"))
fun minuteOfDay(millis: Long): Int =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().let { it.hour * 60 + it.minute }

/** The restore of a backup could not finish and `pebble.db` is missing; the old files are in [dataDir] as `pebble.db.restoring-*`. */
class RestoreNotFinishedException(val dataDir: File, cause: Throwable) :
    IllegalStateException("Pebble could not finish restoring a backup, and the database is missing from $dataDir.", cause)
