# DevKit

An Android workspace for **FillKit** — debug-only tooling that fills Jetpack Compose forms with coherent synthetic data, and turns any interesting form state into something you can reproduce, share, launch and convert into an automated regression test.

FillKit 0.4 covers three audiences from one set of definitions:

```text
Developer   → fill forms quickly while building a screen
QA          → launch a known scenario instantly
Automation  → activate the same scenario from a Compose UI test
```

This is the complete documentation for the workspace and the library.

## Contents

- [Modules](#modules) · [Requirements](#requirements) · [Run the sample](#run-the-sample) · [Install](#install-and-release-safety)
- Registering fields: [TextFieldState](#textfieldstate-and-one-fill-target) · [ContentType](#compose-contenttype-mapping) · [Suggestions](#field-suggestions) · [Semantics](#semantics-integration-and-discovery-boundary)
- Test data: [Personas](#saved-personas) · [Locales](#locales) · [Scenarios](#scenario-packs) · [Generators](#custom-generator-dsl) · [Packs](#unified-packs) · [Precedence](#resolution-precedence)
- Reproducibility: [Deterministic generation](#deterministic-generation) · [Generation counter](#generation-counter) · [Specs](#reproduction-specs) · [Tokens](#reproduction-tokens) · [Fingerprints](#configuration-fingerprints)
- Test control: [Compose UI testing](#compose-ui-testing) · [Finders](#finding-fields-in-tests) · [Activating scenarios](#activating-scenarios-in-tests)
- QA: [Scenario launcher](#qa-scenario-launcher) · [Launching](#launching-scenarios) · [Cold start](#cold-start-activation) · [Navigation](#navigation-integration) · [Deep links](#deep-link-activation) · [ADB](#adb-reproduction)
- [The full workflow](#qa-to-developer-to-regression-test) · [Guarantees](#reproduction-guarantee) · [Verification](#verification)

## Modules

| Module | Ships in | Contents |
| --- | --- | --- |
| `:fillkit:api` | release + debug | Pure models, DSLs, the Compose modifier, semantics keys, reproduction specs and token codec, activation requests, and a no-op release runtime |
| `:fillkit:engine` | debug | Locale registries, persona and value generation, scenario composition, deterministic random streams |
| `:fillkit:debug` | debug | Developer panel, QA scenario launcher, activation engine, pending activations, deep-link entry point, local persistence |
| `:fillkit:testing` | `androidTestImplementation` only | Compose test finders, assertions, `FillKitTestDriver`, reproduction helpers |
| `:app` | — | Sample application exercising every capability |

Only `:fillkit:api` reaches a release build.

## Requirements

- Android Gradle Plugin 9.3.2, Gradle 9.5
- Kotlin 2.2.10, Compose BOM 2026.02.01
- JDK 17 or newer to run Gradle; Java 11 source/target compatibility
- `compileSdk` 37, `minSdk` 24

## Run the sample

```bash
./gradlew :app:installDebug
```

The sample application id is `io.devkit`. Tap the ⚡ trigger on any screen to open the FillKit panel.

| Screen | Demonstrates |
| --- | --- |
| Registration | Callback fields, custom generators, scenarios, seeded reproduction |
| Business onboarding | Selections, numeric ranges, a second locale |
| Checkout address | Address generation, persona-driven values |
| Smart Fields | `TextFieldState`, `ContentType` mapping, field suggestions |
| QA and reproduction | Scenario catalog, launching by seed, tokens, deep links, ADB commands |

## Install and release safety

```kotlin
dependencies {
    implementation(project(":fillkit:api"))
    debugImplementation(project(":fillkit:debug"))
    androidTestImplementation(project(":fillkit:testing"))
}
```

`:fillkit:testing` is an **`androidTestImplementation`-only** artifact. It exposes Compose test rules and JUnit types; `implementation(":fillkit:testing")` is not a supported configuration.

Minimum tested Compose versions are UI/Foundation `1.10.4` and Material 3 `1.4.0` (Compose BOM `2026.02.01`).

Because only the debug variant includes `:fillkit:engine` and `:fillkit:debug`, the panel, the QA launcher, generated data, deep-link entry points and runtime-saved personas cannot appear in release builds.

```kotlin
FillKitHost(
    formId = "registration",
    config = FillKitConfig(locale = FillLocale.Code("en-KE")),
) {
    OutlinedTextField(
        value = state.email,
        onValueChange = onEmailChanged,
        modifier = Modifier.fillKit(
            id = "email",
            label = "Business email",
            group = "Contact",
            type = FillType.Email,
            value = state.email,
            onFill = onEmailChanged,
        ),
    )
}
```

The data path remains `FillKit → application callback → application state → recomposition`. FillKit never owns application form state. The modifier works with custom fields, dropdowns, checkboxes, switches, and basic text fields.

## TextFieldState and one fill target

State-based Material fields and `BasicTextField` use the official Foundation editing APIs. Fill replaces the text and places the cursor at the end; clear calls `clearText`. An optional normalizer is applied once before the mutation.

```kotlin
val email = rememberTextFieldState()

OutlinedTextField(
    state = email,
    modifier = Modifier.fillKit(
        id = "email",
        type = FillType.Email,
        state = email,
        contentType = ContentType.EmailAddress,
        normalize = String::trim,
    ),
)
```

Callback fields, `TextFieldState`, and public `OnFillData` semantics actions all adapt to one `FillTarget`. A FillKit command invokes exactly one target, avoiding duplicate callback/state/semantics fills. Programmatic `TextFieldState` edits follow Compose behavior and bypass `InputTransformation`; use `normalize` when application-specific normalization is required.

## Compose ContentType mapping

`BuiltInContentTypeMapper` maps the public Compose content vocabulary to `FillType`, including names, email, usernames/passwords, phone components, addresses, birth-date components, gender, and `SmsOtpCode`. Unknown/custom `ContentType` values are left unmapped. Payment content types are detected as `FillType.Unsupported` and are never generated.

Applications can extend or override mappings without reflection:

```kotlin
val appContentTypes = contentTypeMappings {
    map(ContentType("com.example.referral"), FillType.Custom("referral", String::class))
}

FillKitConfig(contentTypeMappers = listOf(appContentTypes))
```

Local mappers take priority over packed mappers, then the built-in mapper is used as a fallback. Mappers can also be distributed from `FillKitPack` with `contentTypes(appContentTypes)`.

## Field suggestions

Use `fillKitSuggestion` on fields where choosing an explicit type would be repetitive:

```kotlin
val city = rememberTextFieldState()

OutlinedTextField(
    state = city,
    label = { Text("City or town") },
    modifier = Modifier.fillKitSuggestion(
        state = city,
        id = "deliveryCity",
        label = "City or town",
        contentType = ContentType.AddressLocality,
    ),
)
```

Every candidate includes a proposed type, `Exact`/`High`/`Medium`/`Low` confidence, its source, human-readable reasons, and whether it is fillable, detection-only, or unsupported. The panel exposes Add and Ignore actions. Explicit `fillKit(type = …)` registrations always win.

Suggestion modes are:

- `Disabled`: no suggestions are registered or shown.
- `Suggest` (default): the panel asks the developer to Add or Ignore.
- `AutoRegisterExact`: only exact, supported candidates with a fill target register automatically. High/medium/low guesses never auto-register.

Built-in rules recognize common labels such as email, first/last name, phone, city, postal code, password, and date of birth. An ambiguous label such as “Code” intentionally produces multiple low-confidence choices instead of guessing. Current-value shape is only medium confidence.

Custom pure rules are reusable in configuration or a unified pack:

```kotlin
val commerceRules = suggestionRules("commerce", "Commerce") {
    labelContains("referral", FillType.Custom("referral", String::class))
}

FillKitConfig(suggestionRulePacks = listOf(commerceRules))
```

The suggestion engine consumes `FieldMetadata` and has no Android or semantics-tree dependency, so its behavior is deterministic and unit-testable.

## Semantics integration and discovery boundary

In debug builds, registered/suggested nodes publish non-sensitive FillKit semantics keys for field ID/type/label/group, form ID, source, confidence, and target kind. They do not publish generated values, personas, scenarios, roles, click actions, or content descriptions; existing application accessibility semantics remain intact. Without `fillkit-debug`, the registry is absent and FillKit metadata is not emitted.

Compose 1.10.4 exposes individual `contentType`, `fillableData`, and `onFillData` semantics publicly, but it does **not** expose a supported way for a composable host to acquire and traverse its `SemanticsOwner`. FillKit therefore does not scrape a passive semantics tree, use reflection, or depend on internal APIs. `Modifier.fillKitSuggestion` is the strongest supported integration: it is a lightweight opt-in bridge that supplies public semantic hints and, when given a state/target, supports accepting and filling the field. `semanticDiscovery = false` disables that bridge.

## OTP and sensitive data

`FillType.OtpCode(length = 6, numericOnly = true)` supports synthetic 4–8 character one-time codes and maps from `ContentType.SmsOtpCode`. FillKit intentionally does not generate payment card numbers, security codes, expiration data, government identifiers, production credentials, or real private records.

## Saved personas

Personas are pure Kotlin data with arbitrary typed values. They are not limited to names or contact fields.

```kotlin
val experiencedProvider = fillPersona(
    id = "experienced-provider",
    name = "Experienced Provider",
) {
    locale(FillLocale.Code("en-KE"))
    firstName("Brian")
    lastName("Mwangi")
    email("brian.mwangi@example.com")
    value("profession", "Electrician")
    value("yearsExperience", 12)
    value("businessType", "Company")
    metadata("fixture", "provider")
}
```

Selecting a saved persona fills its values and generates any missing values coherently. Changing the panel locale never mutates a saved persona or its declared locale.

## Persona packs

```kotlin
val providerPersonas = personaPack("provider-personas", "Provider Personas") {
    persona(newProvider)
    persona(experiencedProvider)
    persona(businessProvider)
}

FillKitConfig(personaPacks = listOf(providerPersonas))
```

IDs are stable persistence and composition keys. Duplicate persona IDs are rejected when configuration is constructed.

## Runtime-saved personas

The panel can save the current generated or selected persona under a new name, select it again, delete it, or delete all locally saved personas. These values use a versioned private DataStore representation in `:fillkit:debug`:

- local device only;
- no sync or network behavior;
- absent from release builds;
- no DataStore types in the public API;
- intended only for synthetic values—never save credentials, tokens, production data, or secrets.

Core persona models remain independent of Android, Compose, activities, ViewModels, and persistence technology, making future file import/export straightforward.

## Locales

Built-in packs include:

- Africa: `en-KE`, `en-NG`, `en-UG`, `en-TZ`, `en-RW`, `en-GH`, `en-ZA`
- International: `en-US`, `en-GB`, `en-CA`, `en-AU`

Each pack is modular. Optional person, address, phone, business, and internet datasets fall back independently.

Resolution is predictable:

```text
exact custom locale → exact built-in locale → English pack for the region → en-US
```

Thus `sw-KE` may extend `en-KE`, `fr-CA` uses `en-CA`, and an unknown region uses `en-US`. The resolved pack retains the requested custom pack's code and display name while filling only missing capabilities from its fallback.

## Custom locale packs

```kotlin
val swahiliKenya = fillLocalePack("sw-KE", "Kenya — Swahili") {
    firstNames("Amina", "Baraka", "Neema", "Juma")
    lastNames("Mwangi", "Wanjiku", "Otieno", "Kamau")
    cities("Nairobi", "Mombasa", "Kisumu")
    phone {
        countryCode = "+254"
        formats("7########", "1########")
    }
}

FillKitConfig(localePacks = listOf(swahiliKenya))
```

Configuration-scoped custom packs intentionally override built-ins with the same locale code. Same-scope duplicate IDs fail early.

## Scenario packs

```kotlin
val validationScenarios = scenarioPack("validation", "Validation") {
    scenario("minimum-values", "Minimum Values") {
        text("name", "A")
    }
    scenario("invalid-email", "Invalid Email") {
        text("email", "not-an-email")
    }
}

FillKitConfig(scenarioPacks = listOf(validationScenarios))
```

The panel preserves pack grouping. `ScenarioValidationMode.Lenient` logs unknown fields; `Strict` throws an actionable configuration error.

## Scenario composition

```kotlin
val longProvider = fillScenario("long-provider", "Provider — Long Business Name") {
    include(experiencedProviderScenario)
    persona("experienced-provider")
    text("businessName", "Extremely Long Synthetic Business Name Limited")
    generated("phone", FillType.PhoneNumber("GB"))
    generated("service", "service-name")
}
```

Included scenarios are resolved first in declaration order, then child values and generators override matching fields. Recursive include chains and unknown includes are rejected with their dependency path.

## Custom generator DSL

```kotlin
val profession = fillGenerator<String>("profession") {
    oneOf("Plumber", "Electrician", "Cleaner", "Carpenter")
}

val businessName = fillGenerator<String>("business-name") {
    val prefix = weighted("Savanna" to 5, "Urban" to 2, "Prime" to 1)
    val service = oneOf("Cleaning", "Electrical", "Plumbing", "Repairs")
    "$prefix $service Ltd"
}

val yearsExperience = fillGenerator<Int>("years-experience") {
    integer(min = 1, max = 25)
}
```

Available focused utilities are `oneOf`, `weighted`, `integer`, `decimal`, `boolean`, `chance`, `text`, `optional`, and `repeat`. The scope also exposes `random`, the resolved `locale`, and the active `persona`.

```kotlin
val customerEmail = fillGenerator<String>("customer-email") {
    val firstName = (persona?.values?.get("firstName") as FillValue.Text).value
    val lastName = (persona.values["lastName"] as FillValue.Text).value
    "${firstName}.${lastName}@example.com".lowercase()
}
```

Generators are typed (`FillGenerator<String>`, `FillGenerator<Int>`, and so on). A field-local generator has highest generator priority:

```kotlin
Modifier.fillKit(
    id = "profession",
    type = FillType.Custom("profession", String::class),
    value = state.profession,
    onFill = onProfessionChanged,
    generator = profession,
)
```

## Generator dependencies

```kotlin
val email = fillGenerator<String>("email") {
    val firstName = generate<String>("first-name")
    val lastName = generate<String>("last-name")
    "${firstName}.${lastName}@example.com".lowercase()
}
```

Dependency execution uses one controlled context and seed. Cycles fail with a path such as `FillKit generator dependency cycle detected: a -> b -> a`.

## Generator packs

```kotlin
val homeServicesGenerators = generatorPack("home-services", "Home Services") {
    generator(profession)
    generator(businessName)
    generator(yearsExperience)
}

FillKitConfig(generatorPacks = listOf(homeServicesGenerators))
```

Local `generators` and modifier-level generators can override reusable pack behavior intentionally. Same-scope duplicates are rejected rather than depending on list order.

## Unified packs

```kotlin
val providerTesting = fillKitPack("provider-testing", "Provider Testing") {
    locale(providerLocale)
    generators(homeServicesGenerators)
    personas(providerPersonas)
    scenarios(providerScenarios)
}

FillKitHost(
    formId = "provider-onboarding",
    config = FillKitConfig(packs = listOf(providerTesting)),
) {
    ProviderOnboardingScreen()
}
```

Individual pack types remain independent, while `FillKitPack` makes it easy to define once and attach the complete dataset to multiple forms or applications.

## Resolution precedence

One `FillValueResolver` owns all resolution rules; the panel and form registry contain no precedence logic:

```text
1. scenario explicit value
2. scenario custom/registered/type generator
3. modifier field-specific generator
4. active saved persona value matching the field ID
5. persona-aware generation (including partial-persona derivation)
6. field-ID registered generator
7. registered FillType/custom-key generator
8. built-in FillType generator
9. actionable error when no safe generator exists
```

Scenario selection may activate a persona. Scenario values still override that persona. This order is stable and independent of registration order.

## Deterministic generation

```kotlin
FillKitConfig(seed = 845912L, locale = FillLocale.Code("en-KE"))
```

A single globally consumed `Random(seed)` is not reproducible enough: inserting a field shifts every later field's values. FillKit instead derives one independent stream per stable namespace.

```text
master seed + generation + form id + field id + generator/type + purpose
        ↓
derived stream for exactly that field
```

Given the same master seed, field ID, `FillType`, generator, locale and pack configuration, a field produces the same value **regardless of registration order**. Adding an unrelated field leaves every other value untouched. Persona attributes work the same way, and dependent values stay coherent — `Amina Wanjiku` still yields `amina.wanjiku@example.com`.

Identity values (name, email, username, address) follow the persona, so the same seed describes the same person on every screen. Values with field-level randomness (OTP codes, passwords, free text, numbers, selections) are scoped to their form and field.

## Generation counter

`Randomize` keeps the master seed and advances a generation counter, so a reroll is still fully described by two numbers.

```text
seed 845912, generation 0  →  persona A
seed 845912, generation 1  →  persona B   (deterministically)
```

`New Seed` picks a new master seed and resets the generation to zero. Applying a seed by hand reproduces generation zero unless a generation is given.

Rerolling one field from the panel uses a per-field counter that is deliberately **not** part of a reproduction: any seed change resets it, so a reproduction stays exact while a single-field reroll remains a local exploration.

## Reproduction specs

```kotlin
data class FillReproductionSpec(
    val formId: String,
    val seed: Long,
    val generation: Int = 0,
    val locale: String? = null,
    val scenarioPackId: String? = null,
    val scenarioId: String? = null,
    val personaPackId: String? = null,
    val personaId: String? = null,
    val configurationFingerprint: String? = null,
    val version: Int = FillReproductionSpec.VERSION,
)
```

Pure data, free of Android types, and the single representation every entry point converges on. `spec.describe()` renders a bug-report block:

```text
FillKit Reproduction
form=provider-onboarding
scenario=validation/maximum-values
persona=random
locale=en-KE
seed=845912
generation=0
config=8f2a1c4d0b73
token=FK1-...
```

Generated field values never appear in a reproduction, a token, or a link.

## Reproduction tokens

```kotlin
val token = FillReproductionTokenCodec.encode(spec)   // "FK1-…-a1b2c3d4"
val spec = FillReproductionTokenCodec.decode(token)
```

Tokens are versioned, URL-safe, checksummed and length-limited. They are compact debug configuration, not encryption. Decoding failures raise `FillReproductionTokenException` carrying a `FillTokenError` (`MissingPrefix`, `UnsupportedVersion`, `ChecksumMismatch`, `InvalidField`, …) rather than a generic parse error.

## Configuration fingerprints

The same seed does not reproduce the same data forever: locale datasets, generators, scenario definitions and pack versions all change output. Packs therefore carry optional versions:

```kotlin
fillKitPack("provider-testing", "Provider Testing", version = 3) { … }
scenarioPack("validation", "Validation", version = "3") { … }
```

`FillKitConfig.configurationFingerprint()` digests the engine algorithm version plus every pack coordinate. It is recorded in reproductions, and a mismatch is reported instead of silently producing different data:

```text
Reproduction was recorded with configuration 8f2a1c4d0b73 but this build is
41d0c88ae215; the same seed may produce different values.
```

The activation still runs — the result is `FillActivationResult.PartiallyApplied` with that warning — unless a component genuinely no longer exists.

## Compose UI testing

Tests never open the developer panel. The debug runtime publishes a control node as Compose semantics, and `fillkit-testing` drives it:

```text
Compose test → fillkit-testing → FillKit semantics → FillKitHost → activation engine
```

```kotlin
@get:Rule
val composeRule = createAndroidComposeRule<MainActivity>()

@Test
fun providerMaximumValuesScenario() {
    composeRule.fillKit(formId = "registration") {
        scenario(pack = "validation", id = "maximum-values")
        seed(845912)
    }

    composeRule.onNodeWithTag("continue").performClick()
}
```

A chained form reads the same way:

```kotlin
composeRule.fillKit("checkout")
    .scenario("returning-customer")
    .seed(845912)
    .activate()
```

Every helper waits for Compose to settle through the supported test synchronization APIs — there is no `Thread.sleep` or fixed delay anywhere in the testing artifact, so screenshot tools and CI see a stable tree.

## Finding fields in tests

```kotlin
composeRule.onFillKitField("email").assertExists()
composeRule.onFillKitField(fieldId = "email", formId = "registration").assertRegistered()
composeRule.onFillKitField("email").assertFillKitType(FillType.Email)
composeRule.onFillKitForm("registration").assertRegisteredFieldCount(8)
```

Finders use the 0.3 semantics metadata, never visible label text, content descriptions or tree position. Merged/unmerged tree handling lives inside the finder; consumer tests never need `useUnmergedTree = true` to reach a FillKit field.

## Activating scenarios in tests

```kotlin
composeRule.fillKit("registration") {
    useLocale("en-KE")
    seed(845912)
    fillAll()
}

composeRule.fillKit("registration") {
    scenario(pack = "validation", id = "maximum-values")
    activate()
}

fillKitScenario {
    form("provider-onboarding")
    scenario("validation", "maximum-values")
    seed(94821)
}
```

`FillKitTestDriver` also exposes `persona`, `selectRandomPersona`, `setLocale`, `setSeed`, `fillAll`, `clearAll`, `regenerateAll`, `fill(fieldId)`, `clear(fieldId)`, `snapshot()`, `currentReproduction()` and `currentReproductionToken()`.

## QA scenario launcher

Open the FillKit panel and tap the **QA** chip. The launcher lists the same scenarios the panel and tests use, with search over name, ID, form, tags, category and description, filters by form/pack/tag, plus Recent and Favorites.

Scenarios describe themselves so a QA engineer who did not author them can choose sensibly:

```kotlin
scenario(
    id = "maximum-values",
    name = "Maximum Values",
    targetForm = "provider-onboarding",
    description = "Populates every constrained field with its largest allowed value.",
    category = "Validation",
    tags = setOf("validation", "edge-case"),
) { … }
```

Before launching, the launcher shows the target form, persona, locale and seed, and lets QA pin or reroll the seed. When something is missing it says so instead of failing silently — `Missing persona: experienced-provider`, `Missing generator pack entry: business-name`, `The application navigator does not declare form "provider-onboarding"`.

## Launching scenarios

Every entry point builds the same request and hands it to the same engine:

```text
Developer panel · QA launcher · Compose test · Deep link · ADB
        ↓
FillActivationRequest  →  FillKitActivationEngine  →  FillValueResolver  →  FormRegistry
```

```kotlin
FillKit.activate(
    FillActivationRequest(
        formId = "registration",
        scenarioPackId = "validation",
        scenarioId = "maximum-values",
        seed = 845912L,
    ),
)
```

`FillKit.activate` is safe to call from shared code: without `fillkit-debug` it returns `Rejected(NoRuntime)`. Results are explicit — `Applied`, `PartiallyApplied(warnings)`, `Pending(formId, expiresAt)` or `Rejected(reason, message)` — so callers never have to read logs.

## Cold start activation

A scenario is often requested before its host exists:

```text
QA launcher / deep link → request queued → application navigates → FillKitHost composes → request consumed
```

Pending requests are **consume-once**, are applied one frame after the host composes (so the form's fields have registered), expire after a configurable window (5 minutes by default), and survive process death through the same debug persistence used for saved personas. An expired request is discarded with a log line rather than surprising a screen opened hours later.

Explicit activation is idempotent — applying the same reproduction twice reapplies the same state. Pending activation is not: it is consumed exactly once.

## Navigation integration

FillKit does not know how an application navigates and is not wired to `NavController` or any route type. The application supplies an adapter:

```kotlin
FillKit.configureDebug {
    pack(sampleTestingPack)
    navigableForms("registration", "checkout-address")
    navigation { request ->
        val destination = formRoutes[request.formId] ?: return@navigation false
        currentScreen.value = destination
        true
    }
}
```

`configureDebug` is idempotent (packs are keyed by ID), safe to call from a shared source set, and read only by `fillkit-debug`. Without a navigator FillKit still queues the request and applies it when you open the screen yourself.

Application-wide packs make the QA launcher useful before any screen composes. Resolution priority stays explicit: **field → form → application pack → built-in**.

## Deep-link activation

The deep-link entry point ships **only** in `fillkit-debug`, and its scheme is derived from the application ID so several debug builds can coexist:

```text
com.example.app.fillkit://reproduce/FK1-…
com.example.app.fillkit://scenario?form=provider-onboarding&scenario=maximum-values&seed=845912&locale=en-KE
```

Links are treated as untrusted input: scheme, path, token format and version, length, seed and generation bounds, and identifier shape are all validated, and a malformed link logs a useful message instead of throwing a parse exception. Raw field injection (`?email=…&password=…`) is **rejected by default**; only `allowDeepLinkFieldOverrides = true` accepts extra parameters. Links carry IDs, a seed, a generation and a locale — never generated values, which would end up in ADB output and system logs.

`FillKitDeepLinkActivity` validates the link, queues the activation, hands control to whatever the application's launcher activity is (resolved through the package manager, never a hard-coded `MainActivity`), and finishes without rendering anything.

## ADB reproduction

The panel's **Seed** chip opens the reproduction sheet with copyable report, token, deep link and a ready-to-run command:

```bash
adb shell am start -a android.intent.action.VIEW -d "com.example.app.fillkit://reproduce/FK1-..."
```

## QA to developer to regression test

```text
QA finds a bug          → launches the scenario, copies the token
Bug report              → FillKit Reproduction / token=FK1-…
Developer               → adb … or pastes the seed into the panel
Fix                     → same synthetic state, reproduced
Regression test         → composeRule.applyFillKitReproduction("FK1-…")
CI                      → the case is verified forever
```

```kotlin
@Test
fun reproduceReportedIssue() {
    composeRule.applyFillKitReproduction("FK1-...")

    composeRule.onFillKitField("email").assertRegistered()
}
```

`FillKitReproductionRule` is an optional `TestWatcher` that prints the reproduction of a failing test, so a red CI run already carries the token needed to reproduce it locally.

## Reproduction guarantee

> A FillKit seed is designed to reproduce generated data for the same compatible FillKit configuration.

Changes to locale datasets, generators, scenario definitions, pack versions or the derivation algorithm itself can alter generated output. That is why reproduction metadata carries a configuration fingerprint and packs carry versions. FillKit does not promise deterministic output across arbitrary library versions.

## Debug-only guarantees

A release build contains none of: the developer panel, the QA launcher, `FillKitDeepLinkActivity`, FillKit deep-link intent filters, FillKit control semantics, the pending activation store, the activation engine, the generation engine, or `fillkit-testing`. `FillKit.activate` degrades to `Rejected(NoRuntime)` and release builds do not resolve FillKit links.

## Programmatic control

```kotlin
val controller = rememberFillKitController()

FillKitHost(
    formId = "profile",
    controller = controller,
    config = FillKitConfig(showTrigger = false),
) { ProfileForm() }
```

Controllers support filling, regenerating, clearing, applying scenarios, selecting random/saved personas, and changing locale. They are harmless no-ops when the debug runtime is absent.

## Verification

```bash
./gradlew :fillkit:api:testDebugUnitTest
./gradlew :fillkit:engine:testDebugUnitTest
./gradlew :fillkit:debug:testDebugUnitTest
./gradlew :fillkit:debug:connectedDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :fillkit:debug:lintDebug :app:assembleDebug :app:assembleRelease
```

The release runtime graph must include only `:fillkit:api`. The release manifest must not contain `FillKitInitializerProvider`, `FillKitDeepLinkActivity`, or any `*.fillkit` scheme, and the release dex must not contain `DebugFillKitRuntime`, `FillKitActivationEngine` or `FillValueResolver`.

## Data safety

FillKit generates synthetic data only. Email domains are limited to `example.com`, `example.org` and `example.net`. It does not generate payment details, government identifiers, production credentials or real private records. Reproduction tokens, deep links and bug-report blocks carry identifiers, a seed and a generation — never generated field values, which would otherwise end up in ADB output, system logs and issue trackers.
