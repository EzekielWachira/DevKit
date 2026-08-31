# NetKit

NetKit is debug-only tooling for simulating difficult network conditions inside a running Android application — offline, latency, timeouts and forced HTTP responses — without touching the backend, turning off the device's connectivity, running a mock server or rebuilding the app for every scenario.

NetKit 0.1 is a **scenario toolkit, not a mock server**. Requests you have not configured keep going to your real backend:

```text
/api/v1/profile        → real staging API
/api/v1/bookings       → simulated HTTP 500
/api/v1/notifications  → real staging API
/api/v1/checkout       → simulated read timeout
```

## Contents

- [Why](#why) · [Install](#install-and-release-safety) · [Attach it](#attach-it-to-okhttp) · [Open the console](#open-the-console)
- Scenarios: [Global network](#global-network) · [Endpoint overrides](#endpoint-overrides) · [Precedence](#scenario-precedence)
- Runtime: [Controller](#the-controller) · [Architecture](#architecture) · [Threading](#threading-and-state)
- Inspection: [Request history](#request-history) · [Masking](#sensitive-data-masking)
- [Reset](#reset-and-clear) · [Sample app](#sample-app) · [Testing](#testing) · [Limitations](#current-limitations) · [Roadmap](#roadmap)

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
debugImplementation("io.devkit:netkit:0.1.0")
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
val netKit = NetKit.create()

val client = OkHttpClient.Builder()
    .addInterceptor(netKit.interceptor)
    .build()
```

`NetKit.create()` returns an independent runtime and registers nothing globally — the right choice when you have dependency injection. If you would rather not thread the instance through your graph, `NetKit.initialize()` remembers it:

```kotlin
NetKit.initialize(NetKitConfig(maxHistoryEntries = 200))

// elsewhere
val netKit = NetKit.requireInstance()
```

Add it as an **application** interceptor. NetKit then sees one entry per logical call — redirects and retries do not produce duplicate history rows — and a simulated response short-circuits before a connection is opened.

### Configuration

```kotlin
NetKitConfig(
    initialScenario = NetworkScenario.Default,  // scenario at launch
    maxHistoryEntries = 150,                    // oldest evicted past this
    historyEnabled = true,
    captureBodies = true,
    maxBodyPreviewBytes = 8 * 1024,
    masker = DefaultSensitiveDataMasker(),
    simulatedTimeoutDelayMillis = null,         // null = wait for the client's own timeout
)
```

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

The console is two surfaces — **Scenarios** and **History** — shown as tabs on a phone and side by side above 720dp.

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

## Endpoint overrides

A rule scopes a behaviour to one endpoint:

```kotlin
netKit.controller.addRule(
    EndpointRule.forPath(
        path = "/api/v1/bookings",
        method = HttpMethod.GET,
        action = NetworkAction.HttpError(
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

Actions are `PassThrough`, `Delay(ms)`, `HttpError(status, body, contentType, delay)`, `Offline` and `Timeout(type)`. Methods are `GET POST PUT PATCH DELETE HEAD OPTIONS ANY`.

Matching in 0.1 is `EndpointMatcher.ExactPath`, which compares the encoded path and ignores host, port and query string. Paths are normalised, so `api/v1/bookings`, `/api/v1/bookings` and `/api/v1/bookings/` all describe the same endpoint. `EndpointMatcher` is a plain interface, so you can supply your own today:

```kotlin
val prefix = object : EndpointMatcher {
    override val label = "/api/v1/*"
    override fun matches(target: RequestTarget) = target.path.startsWith("/api/v1/")
}
```

The console can create, edit, enable, disable and delete rules. A rule built in code with a custom matcher stays editable — the editor keeps the matcher rather than silently downgrading it to an exact path.

A simulated response is a complete `okhttp3.Response` with protocol, reason phrase, content type, `Content-Length` and request/response timestamps, so Retrofit converters and error handlers treat it exactly like a real one. It also carries `X-NetKit-Simulated: true` and `X-NetKit-Rule: <id>`.

## Scenario precedence

1. **NetKit disabled** → the request passes through.
2. **The first enabled rule that matches wins.** Its action fully *replaces* the global behaviour — global latency is **not** added on top.
3. Otherwise the **global mode and latency** apply.
4. Otherwise the request passes through.

Rules are evaluated in list order, so the order in the console is the precedence order.

Because a matching rule replaces global behaviour, a rule set to `PassThrough` works as an **allow-list**: that endpoint keeps reaching the backend even while the rest of the app is offline.

## The controller

`NetKitController` is the only supported way to change NetKit at runtime, and it is free of Compose, Android and OkHttp types:

```kotlin
interface NetKitController {
    val state: StateFlow<NetKitState>
    val history: StateFlow<List<NetworkRecord>>

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
    fun applyScenario(scenario: NetworkScenario)

    fun reset()
    fun clearHistory()
}
```

That neutrality is deliberate. The Compose UI is one client of the controller; a future Android Studio bridge or automation API is another, driving the same runtime.

## Architecture

```text
NetKitController  ──→  NetworkScenario  ──→  ScenarioEngine  ──→  ScenarioDecision  ──→  NetKitInterceptor
   (state)              (immutable)           (pure)              (what to do)           (how to do it)
```

| Layer | Type | Responsibility |
| --- | --- | --- |
| Entry point | `NetKit` | Wires config, controller, engine, history and interceptor |
| State | `NetKitController` / `NetKitState` | The only way to change the runtime |
| Scenario | `NetworkScenario`, `EndpointRule`, `NetworkAction`, `GlobalNetworkConfig` | Immutable description of what should happen |
| Engine | `NetworkScenarioEngine`, `ScenarioEvaluator` | Pure matching and precedence, no OkHttp |
| Decision | `ScenarioDecision` | `PassThrough` / `Delay` / `RespondWith` / `FailOffline` / `FailTimeout` |
| Transport | `NetKitInterceptor` | Executes one decision, records the result |
| History | `NetworkHistoryStore`, `NetworkRecord` | Bounded, thread-safe capture |
| Masking | `SensitiveDataMasker` | Redacts credentials before anything is stored |
| UI | `NetKitScreen` | Renders controller state; never touches the interceptor |

The engine works on `RequestTarget`, a plain Kotlin value with method, scheme, host, port, path and query. It has no OkHttp dependency, which is what makes a Ktor or Apollo binding a matter of writing a new adapter rather than reworking the matching layer.

## Threading and state

State lives in one `MutableStateFlow` of immutable snapshots, updated with a compare-and-set loop. Calls are safe from any thread, and each request evaluates against exactly one coherent snapshot even while the console is being used. History writes are serialised on a private lock and published as immutable lists.

When nothing is configured, `NetworkScenario.isIdle` short-circuits evaluation, so an enabled-but-empty NetKit costs essentially nothing per request.

Delays block the calling thread. OkHttp interceptors are synchronous by contract — a slow server blocks the same thread — so this keeps a simulated delay indistinguishable from a real one for `enqueue`, coroutine adapters and RxJava alike. OkHttp never runs an interceptor on the Android main thread. An interrupt during a delay is re-raised as `InterruptedIOException` so call cancellation still works.

## Request history

Every request through the interceptor produces a `NetworkRecord`: timestamp, method, host, path, masked URL, masked headers, body previews, status or exception, duration, whether the result was simulated, and the rule responsible.

The console shows them newest first, with the outcome spelled out (`200`, `500`, `TIMEOUT`, `OFFLINE`) and simulated results badged `SIMULATED` — never colour alone, so nobody files a bug against a response NetKit invented.

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

## Reset and clear

**Reset network** restores NetKit enabled, global mode normal, latency 0, and **removes every endpoint rule**. Rules are removed rather than disabled because 0.1 rules are temporary runtime state with no way to save them — leaving disabled rules behind would be clutter. History is untouched.

**Clear history** drops every record and leaves the scenario alone.

## Sample app

The `:app` module has a **Network scenarios** tab. It calls four endpoints against a local demo backend through the NetKit interceptor, with one-tap presets for offline, 2.5s latency, `GET /api/v1/bookings → 500` and `POST /api/v1/checkout → timeout`. Press "Call all endpoints" with a preset applied and watch which endpoints change and which keep working.

## Testing

```bash
./gradlew :netkit:testDebugUnitTest
```

```bash
./gradlew :netkit:connectedDebugAndroidTest
```

Unit tests cover the scenario engine (precedence, matching, methods, disabled rules, every action), the interceptor against `MockWebServer` (pass-through reaches the server, simulated responses do not, status overrides, offline and timeout exceptions, latency, history recording), the history store (ordering, eviction, concurrency), the masker and the controller. Compose tests cover rendering, the offline toggle, latency, rule creation and toggling, history, request details and reset.

## Current limitations

NetKit 0.1 deliberately does not include:

- Persistence — scenarios reset when the process dies
- Saved scenario packs, import/export, request replay, response sequences
- Chaos mode, probability rules, reproducible seeds
- Matchers other than exact path (though `EndpointMatcher` is extensible today)
- WebSocket, SSE, Ktor or Apollo interception
- An Android Studio plugin

Two behaviours worth knowing:

- **Timeouts are simulated at the interceptor, not the socket.** An interceptor cannot make a real socket stall, so NetKit waits for the client's own connect/read timeout (via `chain.connectTimeoutMillis()` / `chain.readTimeoutMillis()`, capped at 60s) and then throws `SocketTimeoutException`. The application sees a realistic timeout; a packet capture would not.
- **Response bodies are previews.** They are truncated to `maxBodyPreviewBytes` and skipped for binary or streamed payloads.

## Roadmap

| Release | Adds |
| --- | --- |
| 0.2 | Saved scenarios and packs, malformed responses, request replay, response sequences, import/export |
| 0.3 | Auth and pagination presets, chaos mode, probability and conditional rules, reproducible seeds |
| 0.4 | WebSockets, SSE, Ktor, Apollo GraphQL, event timeline |
| 0.5 | Android Studio plugin, live device control, endpoint browser, QA session export |

The 0.1 architecture is shaped for these: the engine is transport-independent and pure, decisions are data, scenarios are serialisable value types, and every runtime change already goes through one Compose-free controller.
