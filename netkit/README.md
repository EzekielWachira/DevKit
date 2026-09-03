# NetKit

NetKit is debug-only tooling for simulating difficult network conditions inside a running Android application — offline, latency, timeouts and forced HTTP responses — without touching the backend, turning off the device's connectivity, running a mock server or rebuilding the app for every scenario.

NetKit is a **scenario toolkit, not a mock server**. Requests you have not configured keep going to your real backend:

```text
/api/v1/profile        → real staging API
/api/v1/bookings       → simulated HTTP 500
/api/v1/notifications  → real staging API
/api/v1/checkout       → simulated read timeout
```

**NetKit 0.2** turned that from a set of switches into a reproduction workflow:

```text
Discover bug → Create scenario → Save → Reproduce → Export
     → attach to the ticket → developer imports → Activate → Replay
```

**NetKit 0.3** makes the *random* part of that workflow reproducible too. A
scenario can now fail 20% of the time, delay somewhere between 500ms and 3s, or
pick one of five outcomes per request — and still replay identically, because
every one of those decisions comes from a seed you can copy into a ticket:

```text
Scenario Definition  +  Seed  +  Request order  =  Reproducible network decisions
```

Which turns this:

> "The app sometimes breaks on bad networks."

into this:

```text
Scenario:  Poor Mobile Network
Seed:      842137293
Failure:   observed on evaluation #17

  GET /api/v1/bookings → 503

Reproduction: import the scenario, restart with seed 842137293
```

## Contents

- [Why](#why) · [Install](#install-and-release-safety) · [Attach it](#attach-it-to-okhttp) · [Open the console](#open-the-console)
- Right now: [Global network](#global-network) · [Temporary overrides](#temporary-overrides) · [Precedence](#precedence)
- Saved work: [Scenarios](#saved-scenarios) · [Packs](#scenario-packs) · [Built-in packs](#built-in-scenario-packs)
- Behaviours: [Custom responses](#custom-responses) · [Malformed responses](#malformed-responses) · [Response sequences](#response-sequences)
- **0.3 — reproducible chaos**: [Scenario runs and seeds](#scenario-runs-and-seeds) · [Chaos mode](#chaos-mode) · [Probability](#probability-based-rules) · [Weighted outcomes](#weighted-outcomes) · [Latency ranges](#latency-ranges) · [Conditional rules](#conditional-rules)
- **0.3 — templates**: [Authentication presets](#authentication-presets) · [Pagination presets](#pagination-presets) · [Custom presets](#custom-presets)
- **0.3 — reproduction**: [Execution timeline](#execution-timeline) · [Rule statistics](#rule-statistics) · [Reproduction export](#reproduction-export) · [Determinism and its limits](#determinism-and-its-limits)
- Sharing: [Import and export](#import-and-export) · [File format](#scenario-file-format) · [Security](#security-considerations)
- Inspection: [Request history](#request-history) · [Request replay](#request-replay) · [Masking](#sensitive-data-masking)
- Runtime: [Controller](#the-controller) · [Architecture](#architecture) · [Threading](#threading-and-state)
- [Reset](#reset-semantics) · [Sample app](#sample-app) · [Testing](#testing) · [Migrating from 0.2](#migrating-from-02) · [Migrating from 0.1](#migrating-from-01) · [Limitations](#current-limitations) · [Roadmap](#roadmap)

## Why

Reproducing a network failure usually means one of: asking a backend engineer to break staging, flipping airplane mode and losing the whole session, standing up a mock server that then drifts from production, or adding a `if (BuildConfig.DEBUG && forceError)` branch that eventually ships.

NetKit replaces all of that with an interceptor and a console. A QA engineer taps "500" next to `GET /api/v1/bookings` and the app's error path runs — while login, profile and everything else keep hitting the real API.

## Install and release safety

NetKit is intended for development and QA builds and its safety model is Gradle dependency scoping:

```kotlin
dependencies {
    debugImplementation("io.github.ezekielwachira.devkit:netkit:0.3.0")
}
```

NetKit is one artifact and installs on its own — it pulls no FillKit and no
other kit, only `core` (version metadata, no dependencies of its own). If you
would rather not name versions, use the BOM:

```kotlin
dependencies {
    implementation(platform("io.github.ezekielwachira.devkit:devkit-bom:0.1.0"))
    debugImplementation("io.github.ezekielwachira.devkit:netkit")
}
```

Or take every DevKit developer tool at once — NetKit plus the FillKit panel:

```kotlin
debugImplementation("io.github.ezekielwachira.devkit:devkit-debug:0.1.0")
```

Inside this repository, as a project dependency:

```kotlin
debugImplementation(project(":netkit"))
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

## Scenario runs and seeds

0.3 splits a concept that 0.2 had conflated:

```text
NetworkScenario   the definition   — saved, exported, shared, immutable
ScenarioRun       the execution    — seeded, counted, restartable, disposable
```

A definition says *"20% of calls to `/bookings` fail"*. A run says *"with seed
843921773, since 09:44, evaluation #17 was the one that got the 503"*. A bug
report needs both, and before 0.3 only the first existed.

A new run starts when a scenario is activated, when it is restarted, when the
seed changes, and when the active scenario's rules are edited. Each one resets
**everything** deterministic: the random stream, rule hit counters, response
sequence cursors, previous-result state, statistics and the timeline. Saved
scenarios are never touched.

```kotlin
val run = netKit.controller.runs.current.value
run?.seed          // 843921773 — the number that goes in the ticket
run?.evaluationCount
run?.simulatedCount
```

### Restarting

```kotlin
netKit.controller.runs.restartWithSameSeed()   // reproduce what you just saw
netKit.controller.runs.restartWithNewSeed()    // try a different sequence
netKit.controller.runs.restartWithSeed(843921773)  // reproduce someone else's
```

The **Run** tab exposes all three, plus a field for pasting a seed out of a
ticket. `SeedGenerator.parse` is forgiving about how a seed arrives — `843921773`,
`Seed: 843921773` and `843 921 773` all work, because a seed is as likely to be
retyped from a screenshot as pasted.

Seeds are nine digits on purpose. A full 64-bit value would be transcribed wrong.

### Runs and process death

A run is in-memory. Killing the process ends it; the active scenario is restored
on the next launch and starts a **new** run with a new seed. That is deliberate —
continuing a half-finished run across a restart would mean persisting a random
generator's internal state and pretending the two halves were one execution. If a
run matters, export it before the process goes away.

## Chaos mode

Chaos applies instability across a *scope* rather than one endpoint at a time.

```kotlin
scenario("Poor mobile network") {
    chaos {
        failurePercent(8)
        latency(800, 3_000)
        exclude("/api/v1/auth/refresh")
        outcomes {
            timeout(weight = 3)
            http(503, weight = 3)
            disconnect(weight = 2)
        }
    }
}
```

Or from the console's **Chaos** tab, which is not saved and is the equivalent of
the global switch — an active scenario that declares its own chaos takes
precedence, and the tab says so rather than letting you edit a setting that has
no effect.

### What chaos is, precisely

NetKit runs inside an OkHttp interceptor. Chaos makes requests slow, fail with a
status, time out, or throw the exception a dropped connection produces. It does
**not** drop packets, degrade the radio, congest TCP or interfere with DNS, and
nothing in NetKit's vocabulary claims otherwise — the terms used throughout are
*request failure rate*, *simulated timeout*, *simulated disconnect* and
*latency*. A bug that reproduces under chaos has reproduced under a realistic
application-layer approximation, which is usually what a client bug needs.

### Scope and exclusions

```kotlin
chaos {
    failurePercent(15)
    hosts("api.staging.example.com")
    paths("/api/v1")
    methods(HttpMethod.GET, HttpMethod.POST)
    exclude("/api/v1/auth/refresh", "/analytics")
}
```

Prefixes match on **path segment boundaries**, so `/api/v1` claims
`/api/v1/bookings` but not `/api/v10/bookings`.

Reach for `exclude` before turning the failure rate up. Chaos across a whole app
also breaks its token refresh, its crash reporter and its analytics, and the
logout loop that follows looks exactly like the bug you were hunting.
`excludeRecommended()` covers the usual suspects.

### Presets

`ChaosPresets.mildInstability`, `.poorMobileNetwork` and `.veryUnstable` are
starting points, editable after selection. NetKit ships **no globally enabled
chaos**: a library that made an app flaky by existing would deserve everything
that followed.

## Probability-based rules

A rule can fire only some of the time:

```kotlin
get("/api/v1/bookings") {
    probability(0.3)
    respond(500)
}
```

or programmatically:

```kotlin
EndpointRule.forPath(
    path = "/api/v1/bookings",
    method = HttpMethod.GET,
    probability = Probability(0.3),
    action = NetworkAction.ReturnResponse(500),
)
```

### A declined rule falls through

A rule whose probability (or condition) fails does **not** end evaluation — the
next rule is tried. That is what makes layering work:

```text
GET /bookings   30%  → HTTP 500
GET /bookings        → Delay 2000ms
```

30% of calls fail and the other 70% are slow, which is what someone writing those
two rules in that order plainly meant. For a rule with no probability and no
conditions the 0.2 behaviour is unchanged: it never declines, so the first
matching rule still wins.

## Weighted outcomes

One rule, several possible results:

```kotlin
post("/api/v1/checkout") {
    outcomes {
        passThrough(weight = 60)
        http(500, weight = 15)
        http(503, weight = 10)
        timeout(weight = 10)
        disconnect(weight = 5)
    }
}
```

Weights are relative and normalised, so they need not sum to 100 — adding a
sixth outcome does not mean rebalancing the other five. `passThrough` is a
legitimate outcome and is how "and the rest of the time it works" is written
down.

Weighted outcomes cannot nest, and cannot be used as a response-sequence step: a
sequence is the *deterministic* way to script retry behaviour, and randomness
inside one would make "request 2 of this sequence" mean something different every
run.

## Latency ranges

```kotlin
get("/api/v1/feed") { delayBetween(500, 3_000) }
```

Each request waits a different amount inside the range, drawn from the run's
seed. A range whose ends agree draws nothing and behaves exactly like `delay`.

`NetworkAction.Disconnect` is new too: it fails with an `IOException` the way a
connection dropped mid-flight does, as distinct from `Offline`, which imitates a
device that cannot resolve a host at all. Clients routinely treat the two
differently.

## Conditional rules

A 0.2 rule was `matcher + action`. A 0.3 rule is:

```text
matcher  +  conditions  +  probability  →  action
```

```kotlin
get("/api/v1/bookings") {
    whereQuery("page", "2")
    firstRequestOnly()
    respond(500)
}
```

That reads: *`GET /api/v1/bookings?page=2`, first attempt only, → HTTP 500.*

### The conditions

| Condition | DSL | Notes |
| --- | --- | --- |
| Request count | `firstRequestOnly()`, `onRequest(3)`, `onRequests(3, 5)`, `everyNthRequest(4)` | Per rule, per run |
| Query parameter | `whereQuery("page", "2")`, `whereQueryExists("cursor")` | Values are percent-decoded first |
| Header | `whereHeaderExists("Authorization")`, `whereHeader("X-App-Version", "4.2")` | Names matched case-insensitively |
| Body | `whereBodyContains("coupon")` | Only for bodies NetKit can read safely |
| Previous result | `where(PreviousResultCondition(AFTER_SIMULATED_FAILURE))` | Deliberately minimal |

Conditions on one rule are combined with **AND**. `AnyOf` exists for the "page 2
or page 3" case. There is no boolean expression language, and there is not going
to be one — a debug console that needs a grammar reference has lost the plot.

### Request counting

A rule counts **the requests that reached it**, not the requests the endpoint
received. A rule below one that already handled request 1 never saw request 1, so
its own first request is the endpoint's second.

The practical consequence: pairing `Exactly(1)` with `AtLeast(2)` does *not*
partition the traffic — it leaves request 2 handled by neither. To say "the first
one fails and the rest work", leave the second rule unconditional:

```text
GET /bookings   first request only  → HTTP 401
GET /bookings                       → HTTP 200
```

### Body conditions and their limits

Reading a request body is the one inspection that can break the request being
inspected. NetKit never consumes the real body: the interceptor offers a bounded,
re-buffered peek, and only for bodies that are textual, non-duplex, non-one-shot
and under 64 KB.

When a body cannot be read safely the condition evaluates to **false** — the rule
does not fire — and the timeline records it as `body too large to inspect` (or
`body not repeatable`, or `body is not text`) rather than as a plain non-match.
A scenario that silently stopped working is distinguishable from one that
correctly did not apply.

## Authentication presets

`ScenarioPresetRegistry` ships five:

| Preset | What it reproduces |
| --- | --- |
| Access token expires | The next N protected calls return 401 |
| Refresh succeeds | 401, a working refresh, then normal service |
| Refresh fails | 401 and a rejected refresh — the session-expiry path |
| Refresh is slow | A refresh that takes seconds, or never answers |
| Concurrent 401 storm | Every protected call fails at once |

The last is the most productive. It catches an app that performs **one refresh
per failed call**: open a screen that fans out to four endpoints and count the
refreshes in history. Four means the refresh is not being coalesced, which on a
real backend is a rate-limit or revoked-token incident waiting to happen.

### What NetKit can and cannot do here

NetKit simulates *network behaviour*. It does not know where your app keeps its
access token, cannot expire one, and will not touch a token store. What it can do
is make the network behave the way an expired token makes it behave — which is
exactly the input your auth logic is supposed to handle.

That is also why refresh responses are **configurable**. NetKit has no idea what
shape your refresh payload takes, and inventing one would exercise your error
path rather than your success path. The wizard asks; the default is a plausible
OAuth-shaped body you will want to replace.

## Pagination presets

| Preset | What it reproduces |
| --- | --- |
| A page fails | One page errors, the rest load normally |
| Slow next page | One page takes seconds and then returns real data |
| Empty next page | The end-of-list case |
| Empty first page | The empty state, as distinct from the loading state |
| Retry eventually succeeds | A page that fails twice and then loads |
| Page data fixture | Duplicate ids, or metadata that contradicts itself |

Every one of these is a **query-parameter condition** underneath —
`page = 2`, or `cursor = eyJpZCI6MTB9`. There is no pagination logic anywhere in
the interceptor, which is why cursor pagination needs no special support: it is
the same mechanism with a different parameter name.

The presets that return a body ask you for it. NetKit cannot invent a response
your parser will accept, and a fixture that fails to parse tests your error path
rather than the pagination bug you were chasing. The defaults are plausible shapes
to edit, not shapes to trust.

## Custom presets

A preset is a **generator**. It builds ordinary endpoint rules and gets out of the
way; nothing in the engine knows it exists, and a scenario keeps working if the
preset that made it is renamed or deleted.

```kotlin
object BookingPaymentFailure : ScenarioPreset {
    override val id = "handypro.booking-payment"
    override val name = "Booking payment declined"
    override val description = "The gateway declines the card."
    override val category = PresetCategory.OTHER
    override val fields = listOf(
        PresetField("path", "Checkout endpoint", default = "/api/v1/bookings/checkout"),
    )

    override fun build(configuration: PresetConfiguration) = NetworkScenario(
        name = name,
        rules = listOf(
            EndpointRule.forPath(
                path = configuration.text("path"),
                method = HttpMethod.POST,
                action = NetworkAction.ReturnResponse(402),
            ),
        ),
    )
}

NetKitScreen(
    controller = netKit.controller,
    presets = ScenarioPresetRegistry().plus(listOf(BookingPaymentFailure)),
)
```

No dependency-injection framework is involved, or required.

## Execution timeline

History answers *"what did the network do"*. The timeline answers *"what did
NetKit decide, and why"* — a different question, and the one that matters when a
scenario is not behaving as expected.

```text
09:42:11  Run started        Seed 842133
09:42:14  #1  GET /profile        Chaos · Pass through
09:42:15  #2  GET /bookings       Chaos · Delay 2140ms · 800–3000ms
09:42:19  #3  GET /notifications  Chaos · HTTP 503 · 15% failure rate
09:42:25  #4  POST /checkout      Checkout rule · 20% · did not come up
```

Bounded to 500 events, in memory, never written to disk, and switchable off from
the Run tab. Events record the evaluation index — the number that ties a timeline
row to a history row and to a line in an exported trace.

## Rule statistics

The Run tab shows a funnel per rule:

```text
Bookings 500 rule
  matched 20 · conditions 12 · probability 4 · executed 4
```

Which answers the question a QA engineer asks most often about a scenario that
"isn't working": did the rule not match, did a condition rule it out, or did the
dice simply not come up? Three very different fixes, and without these numbers
all three look identical from the outside. NetKit says which:

- *"Matched no request — check the method and path."*
- *"Matched 20, but a condition ruled every one out."*
- *"Conditions passed 12 times; the probability never came up."*

A rule that is **absent** from the list was disabled.

## Reproduction export

The Run tab's **Copy reproduction** puts this on the clipboard:

```text
NetKit Reproduction

Scenario:     Poor Mobile Network
Scenario ID:  checkout-chaos
Seed:         843921773
Run ID:       run-abc12345
Requests:     37 evaluated · 8 simulated
Schema:       2
NetKit:       0.3.0
```

**Export run** writes a portable `.netkit-run.json` carrying the scenario, the
seed and optionally the trace. A developer picks it with the ordinary import
button and NetKit activates the scenario **on that seed** — the whole point of
the format being distinct from a plain scenario export.

### What a reproduction never contains

```text
Authorization or any other credential-bearing header
cookies
API keys, tokens or signatures in a query string   (masked)
request bodies
response bodies
replay snapshots
```

There is no setting that relaxes any of these. A file whose value depends on being
attachable to a public bug tracker without review has to be safe by construction,
not safe by default — an "include full details" checkbox would be ticked exactly
once, by someone in a hurry, on the day it mattered.

## Determinism and its limits

Every stochastic decision NetKit makes derives from `(seed, evaluation index,
purpose)`. Each request is stamped with a monotonic evaluation index and derives
its own random stream from it, rather than advancing a shared generator. So
evaluation #17 draws the same values whatever else is in flight — which is what
makes a trace saying *"failed on evaluation #17"* replayable.

**What is guaranteed.** The same scenario, the same seed and the same sequence of
requests produce the same sequence of decisions. Adding an unrelated rule does not
change whether an existing one fires; each rule's probability gate is keyed on its
own id.

**What is not.** Which request *becomes* evaluation #17. That is a property of
your application's own request ordering, not of NetKit. An app that fires four
requests concurrently may issue them in a different order on a different run, and
those four will then swap decisions among themselves. In practice this matters
little — a screen that fans out to four endpoints reproduces its failure whichever
of the four gets it — but it is the honest boundary, and NetKit does not
globally serialise your networking to pretend otherwise.

Practically: for sequential flows (a checkout, a login, a paginated scroll) a seed
reproduces exactly. For heavily concurrent screens it reproduces the *set* of
failures reliably and their exact assignment usually.

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

`type` is `scenario`, `scenario-pack` or — new in 0.3 — `reproduction`. The
`format` field exists so that a file which merely *is* valid JSON is still
rejected: an exported Postman collection must not be read as a scenario because
its shape happens to fit. NetKit never decides what a file is from its name, so a
`.netkit-run.json` renamed on its way through a chat client is still identified
correctly.

Import is deliberately paranoid, in this order:

```text
size → JSON → format identifier → schema version → migration
     → typed model → domain model → validation
```

Each stage produces a distinct `ScenarioImportResult` so the console can say exactly what is wrong. **Nothing is imported partially**: a pack whose third scenario is invalid is rejected whole, because half a reproduction is worse than none.

A file from a **newer** schema is refused outright rather than partly read — an unknown action type silently dropped is a scenario that reproduces the wrong bug:

```text
Unsupported NetKit schema version: 4
This version supports schema version 2.
```

Older versions are migrated. 0.3 defines schema 2 and ships
`ScenarioMigration1To2`, which rewrites nothing — every field 0.3 added is
optional with a neutral default, so a schema-1 rule already means exactly what a
schema-2 reader takes it to mean. The step exists as a real, tested migration
rather than a version bump in the reader so that 0.4, which may well have data to
rewrite, has an exercised pipeline to add to rather than one that has never run.

An unknown *condition* type is a hard error rather than a dropped field, for the
same reason an unknown action is: a condition silently discarded would widen a
rule from "page 2 only" to "every page", and a scenario reproducing the wrong bug
is worse than one that refuses to load.

NetKit is pre-1.0, so the schema is **not** promised to be stable. NetKit will provide migrations where practical.

## Security considerations

An exported scenario gets attached to tickets, pasted into chat and occasionally committed to a repository. So an export contains scenario definitions and nothing else:

| Never exported | Why |
| --- | --- |
| `Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, … | Stripped from custom response headers, case-insensitively, with a warning naming each one removed |
| Request history | Not part of a scenario definition |
| Replay snapshots | In-memory only; the serializer cannot reach that package |
| Device identifiers | NetKit never collects any |

A **reproduction** export (`.netkit-run.json`) carries more — the seed, the run
metadata and optionally a decision trace — and is held to the same standard. It
never contains credential-bearing headers, cookies, unmasked query secrets,
request bodies, response bodies or replay snapshots, and there is no setting that
relaxes any of that. A file whose value depends on being attachable to a public
bug tracker without review has to be safe by construction, not safe by default;
an "include full details" checkbox would be ticked exactly once, by someone in a
hurry, on the day it mattered.

Traced paths go through the same `SensitiveDataMasker` history uses, so an
organisation that added its own sensitive parameter names has them respected in a
trace too.

Stripping is not advisory. A custom response header is free text, and "paste the real `Set-Cookie` so the app behaves" is exactly the shortcut someone takes at six in the evening; the export sanitiser is the backstop for that moment. Harmless headers (`Retry-After`, `X-RateLimit-Remaining`, `Content-Language`) are kept, and stripping never mutates the scenario in memory.

## Precedence

Three layers compose, and the order is fixed so that "which rule won" is never a question:

```text
1. NetKit disabled                → the request passes through
2. temporary endpoint override    → wins
3. active scenario endpoint rule  → next
4. chaos, the scenario's or the console's
5. active scenario global config  → next, when the scenario sets one
6. temporary global configuration → next
7. otherwise                      → pass through
```

Within each layer, rules are evaluated in list order, so the order in the console is the precedence order. A rule that *declines* — because a condition or its probability said no — does not end evaluation; the next rule is tried, which is what makes layered rules compose.

**Chaos sits below explicit rules on purpose.** A rule is a deliberate statement about one endpoint; chaos is background weather. If chaos could override a rule, a scenario that says "`/checkout` returns 402" would sometimes return 503 instead, and the endpoint you were trying to pin down would be the one you could not.

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
    val runs: RunController                // seed, counters, timeline, reproduction
    val replayer: RequestReplayer          // replay from history

    fun setEnabled(enabled: Boolean)
    fun enable(); fun disable()

    fun setGlobalMode(mode: GlobalNetworkMode)
    fun setOffline(offline: Boolean)
    fun setTimeout(type: TimeoutType?)
    fun setGlobalLatency(milliseconds: Long)

    fun setChaos(config: ChaosConfig)
    fun setChaosEnabled(enabled: Boolean)

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

The methods on `NetKitController` itself are the **temporary** layer. Saved scenarios live behind `scenarios`, the current execution behind `runs`, and replay behind `replayer` — four interfaces sized for their callers rather than one that knows everything.

The split follows a real seam:

```text
NetKitController    the network right now      (temporary overrides, chaos)
ScenarioController  definitions on disk        (saved scenarios, packs)
RunController       this execution             (seed, counters, timeline)
RequestReplayer     one recorded request       (replay)
```

`RunController` is the reproduction workflow:

```kotlin
controller.runs.current            // StateFlow<ScenarioRun?>
controller.runs.timeline           // StateFlow<List<ExecutionEvent>>
controller.runs.ruleStatistics     // the per-rule funnel
controller.runs.restartWithSameSeed()
controller.runs.restartWithSeed(843921773)
controller.runs.reproductionSummary()      // the text a ticket wants
controller.runs.exportReproduction()       // the .netkit-run.json
```

That neutrality is deliberate. The Compose UI is one client of the controller; a future Android Studio bridge or automation API is another, driving the same runtime.

## Architecture

```text
ScenarioStorage ─→ ScenarioRepository ─→ ScenarioManager ─┐
  (DataStore)         (definitions)        (activation)    │
                                                           │
                                    ScenarioRunManager ────┤
                                  (seed, counters, timeline)│
                                                           ↓
                        NetKitController ─→ ActiveNetworkConfiguration
                          (temporary)            (immutable snapshot)
                                                           ↓
                                              NetworkScenarioEngine
                                                           ↓
                    ┌──────────────────────────────────────┴───────┐
                    │  RuleGate       matcher → conditions → probability
                    │  ChaosEvaluator scope → exclusions → failure → latency
                    │  ActionResolver action → decision, drawing from the seed
                    └──────────────────────────────────────┬───────┘
                                                           ↓
                                                 ScenarioDecision
                                                  (what to do)
                                                           ↓
                                                 NetKitInterceptor
                                                   (how to do it)
```

The evaluation pipeline is the part 0.3 changed most. What was one function is
now named stages, because a single `when` over matching, conditions, probability,
chaos, weighted outcomes and ten action types would have been unreadable and —
more to the point — untestable stage by stage:

```text
request
   ↓  rule candidate matching        EndpointRule.matches
   ↓  condition evaluation           RuleGate  → RuleCondition
   ↓  probability evaluation         RuleGate  → Probability
   ↓  chaos                          ChaosEvaluator
   ↓  action resolution              ActionResolver
   ↓  deterministic random           NetKitRandom, seeded per evaluation
ScenarioDecision
```

Each stage is an object with one entry point and no state of its own. All mutable
runtime state lives in `ScenarioRunManager` and is reached through
`ScenarioExecutionContext`.

| Layer | Type | Responsibility |
| --- | --- | --- |
| Entry point | `NetKit` | Wires config, repository, manager, controller, engine, history, replay and interceptor |
| Definition | `NetworkScenario`, `ScenarioPack`, `EndpointRule`, `NetworkAction` | Immutable description of what should happen |
| Persistence | `ScenarioRepository`, `ScenarioStorage` | Where definitions live; the only Android-aware part is `DataStoreScenarioStorage` |
| Serialization | `ScenarioSerializer`, `ScenarioStorageMapper`, `ScenarioMigrator` | Domain ↔ storage ↔ JSON, with versioning |
| Validation | `ScenarioValidator` | One set of rules for the editor, imports and code registration |
| Activation | `ScenarioManager` | What is active, and the flattened snapshot the engine reads |
| Sequences | `ScenarioExecutionState` | Per-rule cursors, atomic, outside the definition |
| Runs | `ScenarioRunManager`, `ScenarioRun` | Seed, evaluation index, rule counters, statistics, timeline — every piece of state a restart clears |
| Randomness | `NetKitRandom`, `RunRandomSource`, `SeedGenerator` | The **only** source of randomness in NetKit; a feature that reached for `Random.Default` would silently opt out of reproducibility |
| Conditions | `RuleCondition`, `ConditionContext` | Deterministic predicates; never touch the random stream |
| Chaos | `ChaosConfig`, `ChaosScope`, `ChaosExclusions` | Scoped instability, evaluated below explicit rules |
| Presets | `ScenarioPreset`, `ScenarioPresetRegistry` | Generators that build ordinary rules and then get out of the way |
| Reproduction | `ReproductionExporter`, `ExecutionTimeline` | The copyable summary, the trace, and the `.netkit-run.json` |
| State | `NetKitController` / `NetKitState` | The temporary layer; orchestrates the rest |
| Runtime | `ActiveNetworkConfiguration`, `ActiveScenarioSnapshot` | The single immutable value a request evaluates against |
| Engine | `NetworkScenarioEngine`, `ScenarioEvaluator` | Matching and precedence, no OkHttp |
| Decision | `ScenarioDecision` | `PassThrough` / `Delay` / `RespondWith` / `FailOffline` / `FailTimeout` / `FailDisconnect` |
| Transport | `NetKitInterceptor` | Executes one decision, records the result |
| History | `NetworkHistoryStore`, `NetworkRecord` | Bounded, thread-safe capture |
| Replay | `RequestReplayer`, `ReplaySnapshotStore` | Re-sends a recorded request; snapshots never leave memory |
| Masking | `SensitiveDataMasker` | Redacts credentials before anything is stored |
| UI | `NetKitScreen` | Renders controller state; never touches the interceptor |

The engine works on `RequestTarget`, a plain Kotlin value with method, scheme, host, port, path, query, and — new in 0.3 — headers and a lazy body source. It has no OkHttp dependency, which is what makes a Ktor or Apollo binding a matter of writing a new adapter rather than reworking the matching layer.

The two new fields are opt-in. `RequestInspection`, precomputed once per
configuration change, tells the transport how much of a request to capture:
headers are only built when some condition actually reads one, and the body source
never reads anything until a body condition asks. A scenario shaped like an 0.1
one costs what it did in 0.1.

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

0.3 adds three more packs against the same demo backend:

```text
Network Reliability          Authentication              Pagination
├── Mild latency             ├── Token expires once      ├── Page 2 fails
├── Poor mobile network      ├── Refresh succeeds        ├── Page 2 is slow
├── Unstable API             ├── Refresh fails           ├── Empty next page
├── Heavy failure mode       ├── Refresh timeout         ├── Page 2 retries and succeeds
├── Checkout is a coin flip  └── Concurrent 401 storm    ├── Duplicate page data
└── Weighted checkout                                    └── Malformed pagination metadata
```

The demo backend serves three real pages of `/api/v1/services`, so the pagination
scenarios have a page 1 and a page 3 to *not* break — which is the whole
demonstration. It also serves `/api/v1/auth/refresh`, so the auth scenarios can
show a refresh that is excluded from the 401 rule.

A **Try this** list walks through each capability end to end: activate *Retry
eventually succeeds* and call `GET /bookings` three times to watch
`500 → 500 → 200`; activate *Poor mobile network*, tap **Call all endpoints** a
few times, then open the Run tab, copy the seed, restart with it and watch the
same failures come back; activate *Concurrent 401 storm* and count the refresh
requests in History. The one-tap chips demonstrate the *temporary* layer
alongside it.

Saved and imported scenarios persist to DataStore, so the QA → developer handoff is demonstrable rather than described.

## Testing

```bash
./gradlew :netkit:testDebugUnitTest
```

```bash
./gradlew :netkit:connectedDebugAndroidTest
```

465 unit tests and 59 Compose tests.

Every stochastic test pins a **fixed seed** and asserts exact outcomes. There is
not one statistical assertion in the suite: a test that checked "roughly 30% of
draws passed" would fail on some runs, which is precisely the disease this release
is the cure for.

| Suite | Covers |
| --- | --- |
| `DeterministicRandomTest` | Stream identity, per-index derivation under reordering, purpose and key independence, probability edges, latency ranges, seed parsing and normalisation |
| `ProbabilityRuleTest` | 0% and 100% short-circuits, same-seed reproduction, fall-through to the next rule, weighted selection, every outcome kind reachable, invalid weights |
| `RuleConditionTest` | Every count shape, query/header/body/previous-result conditions, `AllOf`/`AnyOf`, per-rule counting semantics, and that an unreadable body never matches |
| `ChaosTest` | Same-seed reproduction, scope by host/path/method, segment-boundary prefixes, exclusions beating scope, latency, precedence against rules and global, preset sanity |
| `ScenarioRunTest` | Same-seed restart reproducing a sequence, resets of counters/sequences/previous-result/timeline, run counters, the rule funnel and its diagnoses, timeline bounding |
| `ScenarioPresetTest` | Every shipped preset validates and generates working rules; each auth and pagination preset driven through the real engine; a generated scenario runs identically with its preset metadata stripped |
| `SchemaMigrationTest` | Literal 0.2-era JSON importing unchanged, schema-2 round trips for every new construct, and rejection of every malformed 0.3 field |
| `ReproductionTest` | Summary and trace contents, export/import round trip, an imported reproduction actually reproducing, and that no credential reaches a reproduction |
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
| `NetKit03ScreenTest` | Chaos screen and its validation, seed display and the three restart actions, applying a pasted seed, copy reproduction, timeline toggle, progressive disclosure of the advanced rule editor, condition and outcome editors, preset picker and generation |

No test sleeps. Sequence and count behaviour is asserted on counters, chaos and
probability on decisions, and latency on the *decision's* delay rather than by
timing the interceptor — so the whole unit suite runs in seconds.

## Migrating from 0.2

**0.3 is source-compatible with 0.2.** Nothing was renamed and nothing was
removed; every 0.1 and 0.2 capability works unchanged. Four things are worth
knowing.

**Saved scenarios and exports still work.** The file format moved to schema
version 2, and `ScenarioMigration1To2` runs on import. It rewrites nothing —
every field 0.3 added is optional with a neutral default, because a rule with no
`probability` field means "always fires", which is exactly what a 0.2 rule did.
A `.netkit.json` exported months ago imports unchanged.

A 0.3 scenario that uses no 0.3 features also *writes* a file indistinguishable
from a 0.2 one apart from the version stamp, so upgrading does not produce a diff
across a repository of saved scenarios.

**`EndpointRule` gained two parameters.** `conditions` and `probability` sit
between `matcher` and `action`, so a positional construction breaks:

```kotlin
// Breaks — action is now in the conditions slot
EndpointRule("id", "name", true, HttpMethod.GET, matcher, action)

// Use named arguments, or the factory
EndpointRule(id = "id", matcher = matcher, action = action)
EndpointRule.forPath("/api/v1/bookings", HttpMethod.GET, action)
```

**`NetworkAction` gained three cases.** An exhaustive `when` over it now needs
`Disconnect`, `RandomDelay` and `Weighted`, and the compiler will say so.
`ScenarioDecision` likewise gained `FailDisconnect`, and `DecisionOrigin` gained
`CHAOS`.

**`DefaultNetworkScenarioEngine` takes a run manager.** It is defaulted, so
existing construction still compiles; in an application `NetKit.create` supplies
the shared instance. An engine left with a run manager of its own would keep a
seed nobody could read.

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

NetKit 0.3 deliberately does not include:

- WebSocket, SSE, Ktor or Apollo interception
- An Android Studio plugin, ADB control or remote device scenario editing
- A network proxy, or any packet-level shaping
- Full backend mocking, or distributed multi-device scenarios
- Matchers beyond exact path and path prefix (though `EndpointMatcher` is extensible today)
- A boolean expression language for conditions — AND across a rule's conditions, plus `AnyOf`, and no more

Behaviours worth knowing:

- **Determinism is per evaluation index, not per request.** The same seed and the same request *ordering* reproduce the same decisions; which request becomes evaluation #17 is your application's business. See [Determinism and its limits](#determinism-and-its-limits).
- **Chaos is application-layer.** It is a failure rate, a simulated timeout, a simulated disconnect and latency — not packet loss, radio degradation, TCP congestion or DNS behaviour. NetKit does not use those words about itself and neither should a bug report based on it.
- **A run does not survive process death.** The active scenario is restored; the run is not. Export a reproduction before the process goes away.
- **The execution timeline is in-memory and bounded** to 500 events, and is never written to disk. A debug tool that quietly accumulated a log of every request an app made would be a liability.
- **Body conditions are best-effort.** Duplex, one-shot, binary and over-64 KB bodies are never read, and a condition that cannot read a body does not match. The timeline says which case applied.
- **Request bodies are still not masked in history.** Headers and URLs are. Reproduction exports carry no bodies at all, so this affects only the on-device history detail.

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
| 0.3 ✓ | Deterministic seeds, chaos mode, probability and weighted rules, conditional rules, auth and pagination presets, scenario runs, execution timeline, reproduction export |
| 0.4 | WebSockets, SSE, Ktor, Apollo GraphQL, network event streams |
| 0.5 | Android Studio plugin, live device control, endpoint browser, QA session export |

The 0.3 architecture is shaped for these:

- **Other transports** need a `RequestTarget` producer and a decision executor, and nothing above the interceptor knows OkHttp exists. `RequestInspection` already tells a binding how much of a request to capture, so a Ktor plugin pays the same "only what the conditions need" cost the OkHttp interceptor does.
- **WebSockets and SSE** are long-lived rather than request/response, which is the one shape the current `ScenarioDecision` does not express. The seam is `ScenarioDecision` itself: the evaluator, the conditions, the seeded random layer and the run manager are all transport-independent already, and a `StreamDecision` sibling would reuse every one of them.
- **A network event stream** is the execution timeline with a different sink. `ExecutionTimeline` is already a bounded, thread-safe recorder behind an interface-shaped API; pointing it at a socket instead of a `StateFlow` is the whole change.
- **An IDE plugin or ADB control** drives `NetKitController`, `ScenarioController` and `RunController`, none of which mention Compose, Android or OkHttp. Restarting a run on a given seed from a laptop is already one method call.
- **New conditions and actions** are new subtypes plus a `StoredCondition` / `StoredAction` case. `ScenarioMigration` now has a real, exercised step in it rather than an empty pipeline.
- **Custom presets** are already supported — `ScenarioPresetRegistry().plus(...)`, no DI framework involved.
