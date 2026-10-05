# UI pattern: state holder and stateless page

Each page of the Pebble window uses one pattern, the same pattern as Android UDF (unidirectional data flow).
The template is the Reminders page:
- `desktopApp/.../app/pages/RemindersStateHolder.kt`
- `desktopApp/.../app/pages/RemindersPage.kt`
- `desktopApp/src/test/.../RemindersStateHolderTest.kt`

Write new pages and changes to pages in this pattern. Write this file in STE (see [STYLE-STE.md](STYLE-STE.md)).

## The parts

| Part | What it is | Rule |
|---|---|---|
| `XUiState` | An immutable `data class` with the `@Immutable` annotation. It holds all the data that the page shows. | Put text that is ready to show in it (for example, "every 1 hour", "Due now"). The page must not calculate times or labels. |
| `XEvent` | A `sealed interface`. It has one subtype for each thing that you can do on the page. | Give each event only the data that it needs (for example, an id). |
| `XStateHolder` | A class with `val state: StateFlow<XUiState>` and `fun onEvent(e: XEvent)`. | It is the only part that calls repositories, engines and the clock. |
| `XPage(app)` | A composable function. It makes the state holder one time, and collects the state. | Use `remember { }` and `rememberCoroutineScope()`. Do no other work. |
| `XContent(state, onEvent)` | A stateless composable function. | Draw `state`. Send each action to `onEvent`. Do not read from `app`. |

## How to make a page

1. Make the `XUiState` data class. Put a field in it for each value that the page shows.
2. Make the `XEvent` sealed interface. Put a subtype in it for each button, toggle and field.
3. Make the state holder. Give it the repositories, the engines, `AppEnv` and a `CoroutineScope` as constructor parameters.
4. In the state holder, `combine` the flows that change the page. Then use `stateIn(scope, SharingStarted.Eagerly, build())`.
5. In `onEvent`, start a coroutine in `scope`. Write to the database in `withContext(env.dispatchers.io)`. Then call the engine, and update `refresh`.
6. Split the composable function into `XPage` and `XContent`. Move each repository call out of the composable function into the state holder.
7. Write the tests (see "Tests" below).

## Threads

**Caution:** `ReminderEngine` is not thread-safe. Its own loop runs on the UI thread.
- Call an engine (`tick`, `upcoming`, `act`) only on the thread of the state holder's scope. In the app, this is the UI thread.
- Write to the database in `withContext(env.dispatchers.io)`.
- Read time only from `env` (`env.millis()`, `env.zone()`). Do not use `System.currentTimeMillis()` or `ZoneId.systemDefault()`.

**Caution:** `combine` emits only after each flow emitted one time. A flow that has a fixed dispatcher can stop the state in tests.
- Give each flow from a repository the dispatcher from `env.dispatchers` (for example, `pendingOneOffsFlow(env.dispatchers.io)`).
- For a change that no flow reports, update a `MutableStateFlow` counter (`refresh`) and `combine` it too.

## Tests

Test the state holder, not the composable function. Use `kotlinx-coroutines-test`.

1. Make a `DispatcherProvider` that gives one `StandardTestDispatcher(testScheduler)` for `main`, `default` and `io`.
2. Make an `AppEnv` with this provider, a clock that the test can move, and a fixed zone.
3. Give the state holder a new `CoroutineScope(SupervisorJob() + dispatcher)`. Cancel the scope in `@AfterTest`.
4. Send an event with `onEvent`. Then call `advanceUntilIdle()`. Then examine `state.value`.

**Caution:** Do not give the state holder `runTest`'s `backgroundScope`. `advanceUntilIdle()` runs only foreground tasks. The tasks of the state holder then do not run, and the state does not change.

**Caution:** Time labels use the language of the system (for example, "PM" or "pm"). Compare them without case, or compare only the numbers.

Test these items for each page:
- the first state;
- the result of each event;
- the limits (for example, an interval of 5 min to 4 h);
- a change that comes from outside the page (for example, a reminder that Quick Add made).

## Pages

| Page | Status |
|---|---|
| Reminders | Done (WP B7, the template) |
| Calendar | Done (WP E2: `CalendarStateHolder`) |
| Today | "Agenda" card done (WP E2: `AgendaStateHolder`); the rest of the page to do |
| Notes | To do |
| Water | To do |
| Chat | To do |
| Memory | Search card done (WP C2: `MemorySearchStateHolder`); "Teach Pebble a command" card done (WP C3: `TeachStateHolder`); the rest of the page to do |
| Companion | To do |
| About | "Model packs" card done (WP D2: `ModelPacksStateHolder`); "Backup" card done (WP E4: `BackupStateHolder`); the rest is fixed text |

Change one page in each PR. Update this table in the same PR.
