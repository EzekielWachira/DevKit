# NetKit

NetKit is debug-only tooling for simulating difficult network conditions inside a running Android application — offline, latency, timeouts and forced HTTP responses — without touching the backend, turning off the device's connectivity, running a mock server or rebuilding the app for every scenario.

NetKit is a **scenario toolkit, not a mock server**. Requests you have not configured keep going to your real backend:

```text
/api/v1/profile        → real staging API
/api/v1/bookings       → simulated HTTP 500
/api/v1/notifications  → real staging API
/api/v1/checkout       → simulated read timeout
```

**NetKit 0.2** turns that from a set of switches into a reproduction workflow:

```text
Discover bug → Create scenario → Save → Reproduce → Export
     → attach to the ticket → developer imports → Activate → Replay
```

## Contents

- [Why](#why) · [Install](#install-and-release-safety) · [Attach it](#attach-it-to-okhttp) · [Open the console](#open-the-console)
- Right now: [Global network](#global-network) · [Temporary overrides](#temporary-overrides) · [Precedence](#precedence)
- Saved work: [Scenarios](#saved-scenarios) · [Packs](#scenario-packs) · [Built-in packs](#built-in-scenario-packs)
- Behaviours: [Custom responses](#custom-responses) · [Malformed responses](#malformed-responses) · [Response sequences](#response-sequences)
- Sharing: [Import and export](#import-and-export) · [File format](#scenario-file-format) · [Security](#security-considerations)
- Inspection: [Request history](#request-history) · [Request replay](#request-replay) · [Masking](#sensitive-data-masking)
- Runtime: [Controller](#the-controller) · [Architecture](#architecture) · [Threading](#threading-and-state)
- [Reset](#reset-semantics) · [Sample app](#sample-app) · [Testing](#testing) · [Migrating from 0.1](#migrating-from-01) · [Limitations](#current-limitations) · [Roadmap](#roadmap)

## Why

Reproducing a network failure usually means one of: asking a backend engineer to break staging, flipping airplane mode and losing the whole session, standing up a mock server that then drifts from production, or adding a `if (BuildConfig.DEBUG && forceError)` branch that eventually ships.

NetKit replaces all of that with an interceptor and a console. A QA engineer taps "500" next to `GET /api/v1/bookings` and the app's error path runs — while login, profile and everything else keep hitting the real API.

## Install and release safety

NetKit is intended for development and QA builds and its safety model is Gradle dependency scoping:

```kotlin
dependencies {
    debugImplementation(project(":netkit"))
}
```

or, once published:

```kotlin
debugImplementation("io.devkit:netkit:0.2.0")
```

Because the whole module is debug-scoped, `src/main` cannot reference it — including the code that builds your `OkHttpClient`. Use a per-build-type source set for the wiring. The sample app does exactly this:

```text
app/src/main/…/DebugNetworking.kt          // hooks, no NetKit types
app/src/debug/…/DebugNetworkingInit.kt     // installs NetKit
app/src/release/…/DebugNetworkingInit.kt   // does nothing
```

`src/main` calls `installDebugNetworking()` and reads `DebugNetworking.interceptors`; only the debug variant ever names a NetKit class. The release APK of the sample app contains **zero** references to `io.devkit.netkit`.

NetKit never crashes an application because it is present, and it does not fail a release build. Keeping it out of production is the dependency declaration's job.

## Attach it to OkHttp

```kotlin
import io.devkit.netkit.android.create

val netKit = NetKit.create(context)

val client = OkHttpClient.Builder()
    .addInterceptor(netKit.interceptor)
    .build()
```

`NetKit.create(context)` wires saved scenarios to DataStore so they survive a restart. The `Context` is only used for storage; NetKit's core APIs never take one. `NetKit.create()` without a context works exactly the same but keeps scenarios in memory for the life of the process.

Either form returns an independent runtime and registers nothing globally — the right choice when you have dependency injection. If you would rather not thread the instance through your graph, `NetKit.initialize()` remembers it:

```kotlin
NetKit.initialize(context, NetKitConfig(maxHistoryEntries = 200))

// elsewhere
val netKit = NetKit.requireInstance()
```

Add it as an **application** interceptor. NetKit then sees one entry per logical call — redirects and retries do not produce duplicate history rows — and a simulated response short-circuits before a connection is opened.

### Configuration

```kotlin
NetKitConfig(
    initialConfiguration = ActiveNetworkConfiguration.Default,  // temporary layer at launch
    builtInPacks = listOf(checkoutPack),        // scenario packs declared in code
    scenarioStorage = null,                     // null = in memory; see NetKit.create(context)
    maxHistoryEntries = 150,                    // oldest evicted past this
    historyEnabled = true,
    replayEnabled = true,                       // keeps in-memory replay snapshots
    maxReplaySnapshots = 50,                    // how many requests stay replayable
    replayClientFactory = null,                 // null = NetKit builds its own client
    captureBodies = true,
    maxBodyPreviewBytes = 8 * 1024,
    masker = DefaultSensitiveDataMasker(),
    simulatedTimeoutDelayMillis = null,         // null = wait for the client's own timeout
)
```

Limits that protect the host application live in `NetKitLimits`: a 1 MB cap on a custom response body, 4 MB on an imported file, 100 steps per sequence, 200 rules per scenario. They are generous enough that no real debugging scenario meets them and strict enough that a malformed `.netkit.json` cannot allocate a gigabyte.

## Open the console

NetKit does not choose an activation mechanism. Show `NetKitScreen` from a developer menu, a debug drawer, a hidden screen, a shake handler — whatever your app already has:

```kotlin
if (showNetKit) {
    NetKitScreen(
        controller = netKit.controller,
        onClose = { showNetKit = false },
    )
}
```

A ready-made floating launcher is available if you want one, but nothing depends on it:

```kotlin
Box(Modifier.fillMaxSize()) {
    AppContent()
    NetKitDebugButton(netKit.controller)
}
```

The console is three surfaces, in the order a session uses them:

| Tab | Answers |
| --- | --- |
| **Console** | What is happening to my network *right now*? |
| **Scenarios** | What have I saved, and what can I activate? |
| **History** | What actually happened, and can I send it again? |

On a phone they are tabs; above 720dp history gets its own column beside the other two.

## Global network

The global state has an independent connectivity **mode** and **latency**, so "online but slow" does not require leaving offline mode first.

| Mode | What the app sees |
| --- | --- |
| Normal | Requests reach the server |
| Offline | `java.net.UnknownHostException` — what a device with no connectivity throws, not a fake HTTP status |
| Connect timeout | `SocketTimeoutException` after the client's connect timeout |
| Read timeout | `SocketTimeoutException` after the client's read timeout |

Latency presets are 0 / 500ms / 1s / 2.5s / 5s plus a validated custom value. From code:

```kotlin
netKit.controller.setOffline(true)
netKit.controller.setGlobalLatency(2_500)
netKit.controller.setTimeout(TimeoutType.READ)
```

## Temporary overrides

A rule scopes a behaviour to one endpoint. Rules added through the controller are the **temporary** layer: ad-hoc, unsaved, the thing you reach for mid-investigation before you know whether it is worth keeping.

```kotlin
netKit.controller.addRule(
    EndpointRule.forPath(
        path = "/api/v1/bookings",
        method = HttpMethod.GET,
        action = NetworkAction.ReturnResponse(
            statusCode = 500,
            body = """{"message":"Bookings service temporarily unavailable"}""",
        ),
        name = "Bookings failure",
    ),
)

netKit.controller.addRule(
    EndpointRule.forPath(
        path = "/api/v1/checkout",
        method = HttpMethod.POST,
        action = NetworkAction.Timeout(TimeoutType.READ),
        name = "Checkout timeout",
    ),
)
```

Actions are `PassThrough`, `Delay(ms)`, `ReturnResponse(...)`, `Malformed(...)`, `Sequence(...)`, `Offline` and `Timeout(type)`. Methods are `GET POST PUT PATCH DELETE HEAD OPTIONS ANY`.

Once a setup reproduces the bug, keep it — **Save current setup** on the Scenarios tab turns the global setting and every temporary override into a saved scenario, then clears the temporary layer so nothing is applied twice:

```kotlin
netKit.controller.scenarios.saveCurrentSetupAsScenario(
    name = "Checkout retry bug",
    description = "QA-421: two failures then a success",
)
```

Matching is `EndpointMatcher.ExactPath`, which compares the encoded path and ignores host, port and query string. Paths are normalised, so `api/v1/bookings`, `/api/v1/bookings` and `/api/v1/bookings/` all describe the same endpoint. `EndpointMatcher` is a plain interface, so you can supply your own today:

```kotlin
val prefix = object : EndpointMatcher {
    override val label = "/api/v1/*"
    override fun matches(target: RequestTarget) = target.path.startsWith("/api/v1/")
}
```

The console can create, edit, enable, disable and delete rules. A rule built in code with a custom matcher stays editable — the editor keeps the matcher rather than silently downgrading it to an exact path.

A simulated response is a complete `okhttp3.Response` with protocol, reason phrase, content type, `Content-Length` and request/response timestamps, so Retrofit converters and error handlers treat it exactly like a real one. It also carries `X-NetKit-Simulated: true` and `X-NetKit-Rule: <id>`.

## Saved scenarios

A `NetworkScenario` is a **named, reusable collection of network behaviour** — the unit a developer saves, a QA engineer activates, and either of them exports and attaches to a bug report.

```kotlin
val scenario = NetworkScenario(
    name = "Checkout retry",
    description = "QA-421: the gateway fails twice, then succeeds",
    globalConfig = null,                 // leave the console's own global alone
    rules = listOf(
        EndpointRule.forPath(
            path = "/api/v1/checkout",
            method = HttpMethod.POST,
            action = NetworkAction.Sequence(
                steps = listOf(
                    SequenceStep(NetworkAction.ReturnResponse(500)),
                    SequenceStep(NetworkAction.Timeout(TimeoutType.READ)),
                    SequenceStep(
                        NetworkAction.ReturnResponse(
                            statusCode = 200,
                            body = """{"success":true}""",
                        ),
                    ),
                ),
                completion = SequenceCompletionBehavior.REPEAT_LAST,
            ),
        ),
    ),
)

netKit.controller.scenarios.save(scenario)
netKit.controller.scenarios.activate(scenario.id)
```

A scenario is a **definition**: nothing inside it changes as requests run. Sequence cursors, activation and history live elsewhere, which is what makes one safe to serialise, copy and compare.

`ScenarioController` is the whole API:

```kotlin
val scenarios = netKit.controller.scenarios

scenarios.scenarios            // StateFlow<List<NetworkScenario>>
scenarios.packContents         // StateFlow<List<ScenarioPackContents>>
scenarios.activeScenario       // StateFlow<NetworkScenario?>
scenarios.sequenceProgress     // StateFlow<Map<String, SequenceProgress>>

scenarios.activate(id)         // exactly one scenario is active at a time
scenarios.deactivate()
scenarios.setScenarioEnabled(id, false)   // pause without losing the activation
scenarios.save(scenario)
scenarios.duplicate(id)
scenarios.delete(id)
scenarios.export(id)           // ScenarioExport?
scenarios.preview(content)     // parse and validate, save nothing
scenarios.commitImport(result)
scenarios.resetSequence(ruleId)
scenarios.resetAllSequences()
```

**Exactly one scenario is active at a time.** That is a deliberate constraint: two overlapping scenarios would make "which rule won" a question nobody can answer at a glance, and the entire point is reproducibility.

Saved scenarios survive a restart when NetKit is created with a `Context`, and so does the activation — the app comes back up running the scenario it was running before.

**Editing an active scenario applies immediately.** Sequence progress resets when, and only when, the active *rule set* changes; renaming a scenario mid-reproduction leaves a running sequence where it was.

## Scenario packs

A pack groups related scenarios. It is organisational, not behavioural: you activate a scenario *from* a pack, never a pack itself.

```kotlin
val pack = ScenarioPack(name = "Checkout", description = "Payment failures")
netKit.controller.scenarios.savePack(pack)
netKit.controller.scenarios.setPack(scenario.id, pack.id)
```

Membership lives on the scenario (`ScenarioMetadata.packId`), so a scenario is never in two places at once. Deleting a pack keeps its scenarios and turns them into loose scenarios — deleting a folder should not delete the work inside it.

## Built-in scenario packs

Register scenarios from application code and every QA engineer has them on every build, with nothing to configure:

```kotlin
val checkoutPack = scenarioPack("Checkout") {
    description("Everything that can go wrong at payment time")

    scenario("Gateway unavailable") {
        post("/api/v1/checkout") {
            respond(503, body = """{"message":"Payment gateway unavailable"}""")
        }
    }

    scenario("Retry eventually succeeds") {
        post("/api/v1/checkout") {
            sequence {
                respond(500)
                respond(500)
                respond(200, body = """{"orderId":"o-1"}""")
            }
        }
    }

    scenario("Slow dashboard") {
        latency(2_500)
        get("/api/v1/notifications") { passThrough() }
    }
}

NetKit.create(context, NetKitConfig(builtInPacks = listOf(checkoutPack)))
```

The builder also offers `get/put/patch/delete/any`, `delay(ms)`, `malformed(type)`, `offline()`, `timeout(type)`, `name("…")` and `disabled()`.

Built-in scenarios are **code, not data**. They are re-declared on every launch and never written to the scenario store, so changing one here changes what QA sees on the next build instead of leaving a stale saved copy behind. Their ids are derived from the pack and scenario names, so they stay stable across builds and an activated built-in survives a restart.

In the console a built-in can be activated, duplicated and exported, but not edited or deleted — the honest way to make one editable is to duplicate it.

## Custom responses

`NetworkAction.ReturnResponse` models **any** HTTP response, not only an error. Any status in `100..599`, any body, any content type, and custom headers:

```kotlin
NetworkAction.ReturnResponse(
    statusCode = 200,
    body = """{"data":[]}""",
    contentType = "application/json",
)

NetworkAction.ReturnResponse(
    statusCode = 429,
    body = """{"message":"Too many requests"}""",
    headers = listOf(
        ResponseHeader("Retry-After", "60"),
        ResponseHeader("X-RateLimit-Remaining", "0"),
    ),
)
```

Headers are an ordered list rather than a map, because HTTP allows repeated names and a scenario that returns two `Set-Cookie` headers is a legitimate thing to test. A scenario header *replaces* the one NetKit derived, so `Content-Type: text/plain` on a JSON body is testable; the `X-NetKit-Simulated` marker is added afterwards and cannot be overwritten.

With no body, NetKit returns an empty body for `204`/`304`, a marker payload for other `2xx`, and its error envelope otherwise.

## Malformed responses

The failure a well-formed error status never reproduces: a response the client cannot parse.

```kotlin
NetworkAction.Malformed(MalformedResponseType.TruncatedJson)
NetworkAction.Malformed(MalformedResponseType.HtmlInsteadOfJson, statusCode = 502)
```

| Type | What comes back |
| --- | --- |
| `InvalidJson` | `{\n  "user":` with a JSON content type |
| `EmptyBody` | `200`, JSON content type, zero bytes |
| `TruncatedJson` | Valid JSON cut short mid-array, as a dropped connection would |
| `HtmlInsteadOfJson` | An HTML error page — the classic proxy or WAF failure |
| `WrongContentType` | Valid JSON sent as `text/plain` |
| `UnexpectedPrimitive` | `"ok"` where an object was expected |
| `Custom(label, body, contentType)` | Anything else you want to model |

Each type carries the exact bytes NetKit returns, so the failure is reproducible rather than "some invalid JSON". History badges these `MALFORMED` so a `200` that broke the app does not read as a success.

## Response sequences

The same endpoint behaving differently on each request — how retry logic gets tested without a backend that can be asked to fail exactly twice.

```kotlin
NetworkAction.Sequence(
    steps = listOf(
        SequenceStep(NetworkAction.ReturnResponse(500)),
        SequenceStep(NetworkAction.ReturnResponse(500)),
        SequenceStep(NetworkAction.ReturnResponse(200)),
    ),
    completion = SequenceCompletionBehavior.REPEAT_LAST,
)
```

| Completion | After the last step |
| --- | --- |
| `REPEAT_LAST` | Every later request repeats the final step |
| `PASS_THROUGH` | Every later request goes to the real server |
| `LOOP` | The sequence starts again from step 1 |

Progress is **runtime state**, held by `ScenarioExecutionState` and keyed on rule id, so the definition can be saved, exported and re-imported without carrying a cursor. Each rule gets one atomic counter and each request is a single compare-and-swap, so two concurrent requests to the same sequenced endpoint always receive two *different* steps — never the same one twice and never a skipped one.

Progress resets when a scenario is activated or reactivated, when its rule set is edited, on `resetSequence(ruleId)` / `resetAllSequences()`, and on `controller.reset()`. The console shows live position (`2 / 3`) on the rule row, on the active-scenario card and in the scenario detail, each with its own **Reset**.

## Request replay

Open a captured request in History and send it again.

```kotlin
when (val result = netKit.controller.replayer.replay(recordId)) {
    is ReplayResult.Success -> println("→ ${result.statusCode}, simulated=${result.simulated}")
    is ReplayResult.Failed -> println(result.error.message)
    is ReplayResult.Unavailable -> println(result.reason.message)
}
```

> ⚠️ **Replay can send a real request to your configured backend.** Replaying `POST`, `PUT`, `PATCH` or `DELETE` may create or modify real data. NetKit surfaces this through `ReplaySnapshot.isSideEffectful` and the console requires an explicit confirmation, with the method named on the button — never a bare "Replay" that reads the same for `GET` and `DELETE`.

Replay is a **debugging action, not an application action**: the response is not delivered back into the ViewModel that made the original call. It runs on its own client, its result is recorded in history, and that is all.

A replay goes through NetKit's interceptor by default, so the active scenario applies to it exactly as it applies to the app's own traffic. `bypassNetKit = true` sends it straight to the server instead, which is how you compare "what the scenario does" with "what the backend actually does".

Overrides let you retype what you want changed. Note what the sheet never does: it shows the **masked** record and reports only *whether* a replaceable body exists, never the body itself. NetKit does not mask request bodies, and a body is exactly where `{"password":…}` lives, so leaving the field empty re-sends the captured body without ever putting it on screen.

```kotlin
netKit.controller.replayer.replay(
    recordId,
    ReplayOverride(
        url = "https://staging.example.com/api/v1/checkout",
        body = """{"cartId":"c-2"}""",
        headers = mapOf("Authorization" to "Bearer a-token-you-typed"),
    ),
)
```

**Replay does not survive process death, on purpose.** A `NetworkRecord` is masked on the way in, which makes it safe to display, copy, persist and export — and useless for replay, because a masked `Authorization` header is not a credential. So NetKit keeps the real request in a separate `ReplaySnapshot` that is **in memory only, never persisted, never exported, never displayed** — its public surface reports the method, whether the request is side-effectful and whether a body can be replaced, and nothing else — bounded to the 50 most recent requests. Weakening any of that to make replay survive a restart would put real tokens on disk, which is a far worse trade for a debug tool. An older record simply reports why replay is unavailable.

Replays are recorded with `kind = REPLAY` and `replayOfRecordId`. `REAL`/`SIMULATED` and `LIVE`/`REPLAY` are independent facts, so all four combinations are distinguishable:

```text
POST /checkout   503   REPLAY · SIMULATED
```

## Import and export

The workflow this release exists for:

```text
QA creates "Checkout Retry Bug"
  → Export → checkout-retry-bug.netkit.json
  → attach to the ticket
  → developer imports, previews, activates
  → reproduces the exact same network behaviour
```

```kotlin
val export = netKit.controller.scenarios.export(scenario.id) ?: return
ScenarioFiles.share(context, export)   // writes to the cache, opens the share sheet
```

Importing is a two-step flow on purpose — a picked file **never** changes anything by itself:

```kotlin
val content = ScenarioFiles.read(context, uri) ?: return
when (val result = netKit.controller.scenarios.preview(content)) {
    is ScenarioImportResult.Scenario -> // show result.summary, then:
        netKit.controller.scenarios.commitImport(result)
    is ScenarioImportResult.Pack -> netKit.controller.scenarios.commitImport(result)
    else -> showError(result.failureReason)
}
```

The preview reports rule count, global behaviour, schema version and a checklist of what the file contains — HTTP overrides, malformed responses, sequences, timeouts, offline rules — so nobody activates something they have not seen.

**An id collision never overwrites silently.** The default `IMPORT_AS_COPY` gives the incoming scenario a fresh id and an `(Imported)` name suffix, keeping both, and the outcome names everything renamed. `REPLACE_EXISTING` is available when overwriting is what you meant.

Serialization is separate from the file system: `ScenarioSerializer` turns strings into scenarios and back with no `Context`, `Uri` or `Intent` anywhere near it, and `ScenarioFiles` is the thin Android wrapper that picks and shares. NetKit declares its own `FileProvider`, so a consuming app needs no manifest changes.

## Scenario file format

Versioned, portable, human-readable JSON. The extension is `.netkit.json`.

```json
{
  "format": "netkit",
  "schemaVersion": 1,
  "type": "scenario",
  "exportedAt": "2026-09-02T08:30:00Z",
  "generator": "netkit/0.2",
  "scenario": {
    "id": "scn-…",
    "name": "Checkout retry",
    "rules": [
      {
        "id": "…",
        "method": "POST",
        "matcher": { "type": "exactPath", "path": "/api/v1/checkout" },
        "action": {
          "type": "sequence",
          "completion": "repeatLast",
          "steps": [
            { "type": "respond", "statusCode": 500 },
            { "type": "respond", "statusCode": 200, "body": "{\"success\":true}" }
          ]
        }
      }
    ]
  }
}
```

`type` is `scenario` or `scenario-pack`. The `format` field exists so that a file which merely *is* valid JSON is still rejected — an exported Postman collection must not be read as a scenario because its shape happens to fit.

Import is deliberately paranoid, in this order:

```text
size → JSON → format identifier → schema version → migration
     → typed model → domain model → validation
```

Each stage produces a distinct `ScenarioImportResult` so the console can say exactly what is wrong. **Nothing is imported partially**: a pack whose third scenario is invalid is rejected whole, because half a reproduction is worse than none.

A file from a **newer** schema is refused outright rather than partly read — an unknown action type silently dropped is a scenario that reproduces the wrong bug:

```text
Unsupported NetKit schema version: 4
This version supports schema version 1.
```

Older versions are migrated where a path exists. NetKit 0.2 ships no migrations because version 1 is the first schema, but `ScenarioMigration` and `ScenarioMigrator` exist and are wired into both import and storage, so 0.3 can add rule types without stranding a QA team's saved work.

NetKit is pre-1.0, so the schema is **not** promised to be stable. NetKit will provide migrations where practical.

## Security considerations

An exported scenario gets attached to tickets, pasted into chat and occasionally committed to a repository. So an export contains scenario definitions and nothing else:

| Never exported | Why |
| --- | --- |
| `Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, … | Stripped from custom response headers, case-insensitively, with a warning naming each one removed |
| Request history | Not part of a scenario definition |
| Replay snapshots | In-memory only; the serializer cannot reach that package |
| Device identifiers | NetKit never collects any |

Stripping is not advisory. A custom response header is free text, and "paste the real `Set-Cookie` so the app behaves" is exactly the shortcut someone takes at six in the evening; the export sanitiser is the backstop for that moment. Harmless headers (`Retry-After`, `X-RateLimit-Remaining`, `Content-Language`) are kept, and stripping never mutates the scenario in memory.

## Precedence

Two layers compose, and the order is fixed so that "which rule won" is never a question:

```text
1. NetKit disabled                → the request passes through
2. temporary endpoint override    → wins
3. active scenario endpoint rule  → next
4. active scenario global config  → next, when the scenario sets one
5. temporary global configuration → next
6. otherwise                      → pass through
```

Within each layer, rules are evaluated in list order, so the order in the console is the precedence order.

A matching rule fully **replaces** global behaviour — global latency is *not* added on top. A rule set to `PassThrough` therefore works as an **allow-list**: that endpoint keeps reaching the backend even while the rest of the app is offline.

A scenario that leaves `globalConfig` as `null` does not touch the global layer at all, so the console's own offline switch still works alongside a rules-only scenario. A scenario that sets it — even to normal — deliberately *pins* the network and outranks the console. The console says which layer is in force rather than leaving you to work it out:

```text
In force: Offline — set by the scenario "Checkout Failure"
```

## The controller

`NetKitController` is the only supported way to change NetKit at runtime, and it is free of Compose, Android and OkHttp types:

```kotlin
interface NetKitController {
    val state: StateFlow<NetKitState>
    val history: StateFlow<List<NetworkRecord>>
    val scenarios: ScenarioController      // saved scenarios, packs, import/export
    val replayer: RequestReplayer          // replay from history

    fun setEnabled(enabled: Boolean)
    fun enable(); fun disable()

    fun setGlobalMode(mode: GlobalNetworkMode)
    fun setOffline(offline: Boolean)
    fun setTimeout(type: TimeoutType?)
    fun setGlobalLatency(milliseconds: Long)

    fun addRule(rule: EndpointRule): String
    fun updateRule(rule: EndpointRule)
    fun removeRule(id: String)
    fun setRuleEnabled(id: String, enabled: Boolean)
    fun enableRule(id: String); fun disableRule(id: String)
    fun applyConfiguration(configuration: ActiveNetworkConfiguration)

    fun reset()
    fun resetEverything()
    fun resetAllSequences()
    fun clearHistory()
}
```

The methods on `NetKitController` itself are the **temporary** layer. Saved scenarios live behind `scenarios`, and replay behind `replayer` — three interfaces sized for their callers rather than one that knows everything.

That neutrality is deliberate. The Compose UI is one client of the controller; a future Android Studio bridge or automation API is another, driving the same runtime.

## Architecture

```text
ScenarioStorage ─→ ScenarioRepository ─→ ScenarioManager ─┐
  (DataStore)         (definitions)        (activation)    │
                                                           ↓
                        NetKitController ─→ ActiveNetworkConfiguration
                          (temporary)            (immutable snapshot)
                                                           ↓
                                              NetworkScenarioEngine
                                                    (pure)
                                                           ↓
                                                 ScenarioDecision
                                                  (what to do)
                                                           ↓
                                                 NetKitInterceptor
                                                   (how to do it)
```

| Layer | Type | Responsibility |
| --- | --- | --- |
| Entry point | `NetKit` | Wires config, repository, manager, controller, engine, history, replay and interceptor |
| Definition | `NetworkScenario`, `ScenarioPack`, `EndpointRule`, `NetworkAction` | Immutable description of what should happen |
| Persistence | `ScenarioRepository`, `ScenarioStorage` | Where definitions live; the only Android-aware part is `DataStoreScenarioStorage` |
| Serialization | `ScenarioSerializer`, `ScenarioStorageMapper`, `ScenarioMigrator` | Domain ↔ storage ↔ JSON, with versioning |
| Validation | `ScenarioValidator` | One set of rules for the editor, imports and code registration |
| Activation | `ScenarioManager` | What is active, and the flattened snapshot the engine reads |
| Sequences | `ScenarioExecutionState` | Per-rule cursors, atomic, outside the definition |
| State | `NetKitController` / `NetKitState` | The temporary layer; orchestrates the rest |
| Runtime | `ActiveNetworkConfiguration`, `ActiveScenarioSnapshot` | The single immutable value a request evaluates against |
| Engine | `NetworkScenarioEngine`, `ScenarioEvaluator` | Matching and precedence, no OkHttp |
| Decision | `ScenarioDecision` | `PassThrough` / `Delay` / `RespondWith` / `FailOffline` / `FailTimeout` |
| Transport | `NetKitInterceptor` | Executes one decision, records the result |
| History | `NetworkHistoryStore`, `NetworkRecord` | Bounded, thread-safe capture |
| Replay | `RequestReplayer`, `ReplaySnapshotStore` | Re-sends a recorded request; snapshots never leave memory |
| Masking | `SensitiveDataMasker` | Redacts credentials before anything is stored |
| UI | `NetKitScreen` | Renders controller state; never touches the interceptor |

The engine works on `RequestTarget`, a plain Kotlin value with method, scheme, host, port, path and query. It has no OkHttp dependency, which is what makes a Ktor or Apollo binding a matter of writing a new adapter rather than reworking the matching layer.

Everything above the interceptor is free of Android types except `DataStoreScenarioStorage` and `ScenarioFiles`, which is what lets the scenario system — persistence, serialization, validation, activation, sequences — be unit-tested on the JVM with no emulator.

## Threading and state

State lives in one `MutableStateFlow` of immutable snapshots, updated with a compare-and-set loop. Calls are safe from any thread, and each request evaluates against exactly one coherent snapshot even while the console is being used. History writes are serialised on a private lock and published as immutable lists.

**The interceptor never touches storage.** Activating a scenario flattens it into an `ActiveScenarioSnapshot` once; the engine then reads a plain volatile field per request. There is no DataStore read, no `Flow` collection and no JSON parsing on the request path, so an active scenario costs the same per request as a temporary rule did in 0.1. Scenario persistence happens on NetKit's own coroutine scope, never on an OkHttp thread.

When nothing is configured, `ActiveNetworkConfiguration.isIdle` short-circuits evaluation, so an enabled-but-empty NetKit costs one boolean per request.

Sequence cursors are `AtomicInteger`s in a `ConcurrentHashMap`, advanced with a single compare-and-swap. Concurrent requests to one sequenced endpoint always receive distinct steps, and the counter is clamped inside the CAS so a long session cannot drift towards `Int.MAX_VALUE`.

Repository writes are serialised by a `Mutex` and are `suspend`; reads go through `StateFlow`s that are updated synchronously by a write, so activating a scenario is correct the instant the call returns rather than one dispatch later.

Delays block the calling thread. OkHttp interceptors are synchronous by contract — a slow server blocks the same thread — so this keeps a simulated delay indistinguishable from a real one for `enqueue`, coroutine adapters and RxJava alike. OkHttp never runs an interceptor on the Android main thread. An interrupt during a delay is re-raised as `InterruptedIOException` so call cancellation still works.

## Request history

Every request through the interceptor produces a `NetworkRecord`: timestamp, method, host, path, masked URL, masked headers, body previews, status or exception, duration, and full attribution — whether the result was real or simulated, whether the app or a replay made it, which scenario and rule were responsible, and which sequence step ran.

Attribution is a **snapshot taken at request time**, never a lookup at display time. A scenario can be renamed, edited or deleted after a request ran, and an old row has to keep saying what was actually responsible for it.

The console shows them newest first, with the outcome spelled out (`200`, `500`, `TIMEOUT`, `OFFLINE`) and badges as words — `SIMULATED`, `REPLAY`, `MALFORMED` — never colour alone, so nobody files a bug against a response NetKit invented.

```text
09:36:26  GET  REPLAY  SIMULATED   200
/api/v1/bookings
api.example.com · 4ms · Checkout Retry · Bookings retry · step 3 / 3
```

Filters narrow the list to **All**, **Real**, **Simulated**, **Replay** or **Errors**, each showing its own count.

Bodies are captured conservatively and NetKit never consumes an application's stream: request bodies are re-serialised into a scratch buffer, response bodies are read with `Response.peekBody`. Duplex, one-shot, oversized and non-textual payloads are skipped rather than risking a broken upload or an OOM.

History is bounded by `maxHistoryEntries` (default 150), in memory only, and survives `reset()`.

## Sensitive data masking

Masking happens on the way *in*, so NetKit never holds a raw token. Copying a request from the detail screen therefore cannot leak one.

Masked by default: `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `Api-Key`, `X-Auth-Token`, `X-Csrf-Token`, matched case-insensitively. A leading auth scheme survives because it is a debugging signal: `Bearer eyJhbGci…` becomes `Bearer ••••••••`. Credential query parameters (`access_token`, `api_key`, `token`, `password`, …) are masked in URLs.

Extend it without replacing it:

```kotlin
NetKitConfig(
    masker = DefaultSensitiveDataMasker(
        additionalHeaderNames = setOf("X-Tenant-Secret"),
    ),
)
```

Or implement `SensitiveDataMasker` for full control.

## Reset semantics

Four separate actions, because "reset" means four different things and conflating them is how a QA engineer loses an afternoon's work:

| Action | Does | Keeps |
| --- | --- | --- |
| **Deactivate scenario** | Clears the active scenario | Everything else |
| **Reset overrides** (`reset()`) | Normal global, no temporary rules, sequence progress restarted | The active scenario, saved scenarios, history |
| **Reset everything** (`resetEverything()`) | The above, plus deactivates the scenario and drops replay snapshots | **Saved scenarios and history** |
| **Reset sequences** (`resetAllSequences()`) | Restarts every response sequence | Everything else |
| **Clear history** | Drops every record and every replay snapshot | Scenarios and configuration |

**No reset ever deletes a saved scenario.** A reset button that silently destroyed saved reproductions would be unforgivable; deleting one is an explicit, confirmed action in the scenario detail.

Clearing history also drops replay snapshots — a snapshot outliving its history row would keep a credential alive in memory for a request nobody can see any more.

## Sample app

The `:app` module has a **Network scenarios** demo: four endpoints against a local demo backend, called through the NetKit interceptor.

It registers two built-in packs from application code, which is the pattern a real app would use:

```text
Demo API                          Checkout
├── Server error                  ├── Checkout timeout
├── Slow response                 ├── Gateway unavailable
├── Malformed JSON                ├── Payment declined
├── Retry eventually succeeds     └── Flaky checkout
├── Rate limited
├── Empty state
└── Offline except profile
```

A **Try this** list on the demo screen walks through each 0.2 capability end to end: activate *Retry eventually succeeds* and call `GET /bookings` three times to watch `500 → 500 → 200`; open a request in History and press Replay; export a scenario and import it back. The one-tap chips demonstrate the *temporary* layer alongside it.

Saved and imported scenarios persist to DataStore, so the QA → developer handoff is demonstrable rather than described.

## Testing

```bash
./gradlew :netkit:testDebugUnitTest
```

```bash
./gradlew :netkit:connectedDebugAndroidTest
```

288 unit tests and 35 Compose tests.

| Suite | Covers |
| --- | --- |
| `ScenarioEvaluatorTest` | Precedence, matching, methods, disabled rules, every action |
| `ScenarioActivationTest` | Activation, one-at-a-time, two-layer precedence, live editing, reset semantics |
| `ResponseSequenceTest` | Every completion behaviour, resets, bounded cursors, **concurrent step claiming** |
| `CustomResponseTest` | Every status, custom headers, all six malformed types — each asserting the server was never contacted |
| `RequestReplayTest` | Eligibility, side-effect flagging, one-shot bodies, overrides, bypass, snapshot eviction, and that a replay sends a real credential the record still masks |
| `ScenarioRepositoryTest` | Save, update, delete, duplicate, packs, persistence across recreation, corrupt and future-version documents |
| `ScenarioSerializerTest` | Round trips for every action and global mode; rejection of bad format, version, status, delay, sequence, size |
| `ScenarioImportTest` | Collision policies, pack import, validation |
| `ExportSecurityTest` | That no credential, history or replay data reaches an exported file |
| `RobustnessTest` | Persisted-id uniqueness, concurrent history writes, an application body that throws, all-or-nothing pack import, built-in collision handling, surfaced storage failures |
| `NetKitInterceptorTest` | The OkHttp binding against `MockWebServer` |
| `NetKitControllerTest` | The 0.1 temporary layer, unchanged — the regression net for backward compatibility |
| `ScenarioScreenTest` | Scenario list, create, activate, duplicate, delete-with-confirmation, sequence editor, replay confirmation, history filters, save-current-setup |

Sequence tests use counters rather than sleeps, so the whole unit suite runs in seconds.

## Migrating from 0.1

0.2 is source-compatible with 0.1 apart from three renames, each of which fails loudly at compile time rather than changing behaviour silently.

**`NetworkScenario` now means a saved scenario.** In 0.1 the name described the runtime snapshot; in 0.2 that type is `ActiveNetworkConfiguration`, and `NetworkScenario` is the named, saved, exportable thing every part of the UI and docs calls a scenario. Keeping the old meaning would have left two different "scenarios" side by side.

```kotlin
// 0.1
import io.devkit.netkit.scenario.NetworkScenario
NetKitConfig(initialScenario = NetworkScenario(global = GlobalNetworkConfig(latencyMillis = 250)))
controller.applyScenario(scenario)

// 0.2
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
NetKitConfig(
    initialConfiguration = ActiveNetworkConfiguration(
        global = GlobalNetworkConfig(latencyMillis = 250),
    ),
)
controller.applyConfiguration(configuration)
```

**`NetworkAction.HttpError` is now `ReturnResponse`.** The action always modelled any HTTP response, and the old name made "200 with a custom body" read like a contradiction. `NetworkAction.HttpError(500)` still compiles — it is a deprecated factory returning a `ReturnResponse` — but `is NetworkAction.HttpError` checks and `NetworkAction.HttpError.MIN_STATUS` do not; use `ReturnResponse` for both.

**`DefaultNetKitController` takes collaborators.** Constructing one by hand now needs a `ScenarioManager`, a `RequestReplayer` and a `CoroutineScope`. Almost nobody does this — `NetKit.create()` wires it — but tests sometimes did.

Everything else is additive. Every 0.1 runtime capability works unchanged: the master switch, global mode and latency, temporary endpoint rules, history, masking and `reset()`.

## Current limitations

NetKit 0.2 deliberately does not include:

- Chaos mode, probability rules, random latency ranges, reproducible seeds
- Conditional or stateful multi-endpoint workflows
- Built-in auth or pagination scenario intelligence
- Matchers other than exact path (though `EndpointMatcher` is extensible today)
- WebSocket, SSE, Ktor or Apollo interception
- An Android Studio plugin or remote device control

Behaviours worth knowing:

- **Replay does not survive process death.** By design — see [Request replay](#request-replay). The alternative is persisting real credentials.
- **A replay uses NetKit's own OkHttp client** unless you supply `replayClientFactory`. It reproduces the request as the app's interceptor chain produced it, but not your custom TLS, DNS or connection pool.
- **Sequence progress is keyed on rule id.** Rule ids are UUIDs and are persisted with the scenario, so a rule created after a restart can never inherit a saved rule's cursor. Duplicating a scenario regenerates them for the same reason.
- **Request bodies are not masked.** Headers and URLs are; bodies are shown as captured in the history detail. Body-field masking is 0.3 work — until then, treat the history detail as showing what the app actually sent.
- **The scenario schema is not stable before 1.0.** Migrations will be provided where practical.
- **Timeouts are simulated at the interceptor, not the socket.** An interceptor cannot make a real socket stall, so NetKit waits for the client's own connect/read timeout (via `chain.connectTimeoutMillis()` / `chain.readTimeoutMillis()`, capped at 60s) and then throws `SocketTimeoutException`. The application sees a realistic timeout; a packet capture would not.
- **Response bodies in history are previews.** They are truncated to `maxBodyPreviewBytes` and skipped for binary or streamed payloads. A scenario's own response body is returned in full.

## Roadmap

| Release | Adds |
| --- | --- |
| 0.2 ✓ | Saved scenarios and packs, custom and malformed responses, response sequences, request replay, import/export |
| 0.3 | Auth and pagination presets, chaos mode, probability and conditional rules, reproducible seeds |
| 0.4 | WebSockets, SSE, Ktor, Apollo GraphQL, event timeline |
| 0.5 | Android Studio plugin, live device control, endpoint browser, QA session export |

The 0.2 architecture is shaped for these:

- **Chaos and probability rules** are a new `NetworkAction` kind plus a `StoredAction` subtype. The evaluator already resolves one action to one decision, and `ScenarioExecutionState` already owns the per-rule mutable state a seeded generator needs.
- **Reproducible seeds** fit the same seam: a seed is scenario data, and the cursor layer is where a deterministic generator would live.
- **Auth and pagination scenarios** are built-in packs — the DSL and read-only registration already exist, so they are content rather than architecture.
- **Conditional behaviour** extends `EndpointMatcher`, an open interface that already participates in evaluation and already round-trips through a polymorphic `StoredMatcher`.
- **New rule types across versions** are exactly what `ScenarioMigration` and the domain ↔ storage mapper exist for.
- **Other transports** need a `RequestTarget` producer and a decision executor; nothing above the interceptor knows OkHttp exists.
