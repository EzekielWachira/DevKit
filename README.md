# DevKit

An Android workspace for debug-only developer and QA tooling.

- **FillKit** — fills Jetpack Compose forms with coherent synthetic data, and turns any interesting form state into something you can reproduce, share, launch and convert into an automated regression test. Documented below.
- **NetKit** — simulates difficult network conditions (offline, latency, timeouts, forced HTTP responses) inside a running app, per endpoint, without touching the backend. Documented in **[netkit/README.md](netkit/README.md)**.

The rest of this file is the complete FillKit documentation.

FillKit covers three audiences from one set of definitions:

```text
Developer   → fill forms quickly while building a screen
QA          → launch a known scenario instantly
Automation  → activate the same scenario from a Compose UI test
```


## Contents

- [Modules](#modules) · [Requirements](#requirements) · [Run the sample](#run-the-sample) · [Install](#install-and-release-safety) · [Publishing](docs/PUBLISHING.md)
- Registering fields: [TextFieldState](#textfieldstate-and-one-fill-target) · [ContentType](#compose-contenttype-mapping) · [Suggestions](#field-suggestions) · [Semantics](#semantics-integration-and-discovery-boundary)
- Locales: [Internationalization](#internationalization-and-locales) · [Fallback](#fallback) · [Custom packs](#custom-packs-and-composition) · [Precedence](#locale-precedence) · [Adding a locale](#adding-a-locale)
- Test data: [Personas](#saved-personas) · [Locales](#locales) · [Scenarios](#scenario-packs) · [Generators](#custom-generator-dsl) · [Packs](#unified-packs) · [Precedence](#resolution-precedence)
- Reproducibility: [Deterministic generation](#deterministic-generation) · [Generation counter](#generation-counter) · [Specs](#reproduction-specs) · [Tokens](#reproduction-tokens) · [Fingerprints](#configuration-fingerprints)
- Field overlay: [Prefill overlay](#field-prefill-overlay) · [Placement](#placement-and-the-keyboard) · [Per-field regeneration](#per-field-regeneration)
- Test control: [Compose UI testing](#compose-ui-testing) · [Finders](#finding-fields-in-tests) · [Activating scenarios](#activating-scenarios-in-tests)
- QA: [Scenario launcher](#qa-scenario-launcher) · [Launching](#launching-scenarios) · [Cold start](#cold-start-activation) · [Navigation](#navigation-integration) · [Deep links](#deep-link-activation) · [ADB](#adb-reproduction)
- [The full workflow](#qa-to-developer-to-regression-test) · [Guarantees](#reproduction-guarantee) · [Verification](#verification)

## Modules

| Module | Maven artifact | Ships in | Contents |
| --- | --- | --- | --- |
| `:core` | `core` | release + debug | DevKit version metadata and the runtime/debug/test distribution classification |
| `:fillkit:api` | `fillkit-api` | release + debug | Pure models, DSLs, the Compose modifier, semantics keys, reproduction specs and token codec, activation requests, and a no-op release runtime |
| `:fillkit:engine` | `fillkit-engine` | debug | Locale registries, persona and value generation, scenario composition, deterministic random streams |
| `:fillkit:debug` | `fillkit-debug` | debug | Developer panel, QA scenario launcher, activation engine, pending activations, deep-link entry point, local persistence |
| `:fillkit:testing` | `fillkit-testing` | `androidTestImplementation` only | Compose test finders, assertions, `FillKitTestDriver`, reproduction helpers |
| `:netkit` | `netkit` | debug | Network scenario toolkit — OkHttp interceptor, scenario engine, request history, masking, Compose console. See [netkit/README.md](netkit/README.md) |
| `:devkit` | `devkit` | release + debug | Umbrella over every release-safe library. No source of its own |
| `:devkit-debug` | `devkit-debug` | debug | Umbrella over every developer and QA tool. No source of its own |
| `:devkit-bom` | `devkit-bom` | — | Version alignment |
| `:app` | — | — | Sample application exercising every capability |
| `:consumer-test` | — | — | Standalone build that resolves DevKit from Maven Local by coordinates, verifying the published graphs |

Only `:core` and `:fillkit:api` reach a release build. `:netkit` is `debugImplementation`-only and every reference to it lives in the sample app's `src/debug` source set, so the release APK contains none of it.

Every kit installs independently — see [Install](#install-and-release-safety) — and the split between `devkit` and `devkit-debug` is what keeps developer tooling out of production builds. [docs/PUBLISHING.md](docs/PUBLISHING.md) covers the artifact architecture, versioning and how to add a kit.

## Requirements

- Android Gradle Plugin 9.3.2, Gradle 9.5
- Kotlin 2.2.10, Compose BOM 2026.02.01
- OkHttp 5.5.0 and kotlinx-coroutines 1.11.0 (NetKit only)
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

DevKit publishes to Maven under the group **`io.github.ezekielwachira.devkit`**.
Every library installs independently — nothing forces you to take a kit you did
not ask for. Pick whichever of the four models fits.

> **Not yet on Maven Central.** The artifacts are ready and verified, but no
> release has been published yet. Until one is, build locally with
> `./gradlew publishToMavenLocal` and add `mavenLocal()` to your repositories.
> See [docs/PUBLISHING.md](docs/PUBLISHING.md).

### One library

```kotlin
dependencies {
    debugImplementation("io.github.ezekielwachira.devkit:netkit:0.1.0")
}
```

### Selected libraries

```kotlin
dependencies {
    // FillKit's release-safe half — production code annotates fields with it
    implementation("io.github.ezekielwachira.devkit:fillkit-api:0.1.0")

    // The panel and the network console, debug builds only
    debugImplementation("io.github.ezekielwachira.devkit:fillkit-debug:0.1.0")
    debugImplementation("io.github.ezekielwachira.devkit:netkit:0.1.0")

    androidTestImplementation("io.github.ezekielwachira.devkit:fillkit-testing:0.1.0")
}
```

### The BOM, so you name no versions

```kotlin
dependencies {
    implementation(platform("io.github.ezekielwachira.devkit:devkit-bom:0.1.0"))

    implementation("io.github.ezekielwachira.devkit:fillkit-api")
    debugImplementation("io.github.ezekielwachira.devkit:fillkit-debug")
    debugImplementation("io.github.ezekielwachira.devkit:netkit")
    androidTestImplementation("io.github.ezekielwachira.devkit:fillkit-testing")
}
```

One `implementation(platform(...))` line covers the debug and androidTest
configurations too, because they extend `implementation`. A second
`debugImplementation(platform(...))` is not needed — verified, not assumed.

### The bundles

```kotlin
dependencies {
    // Every release-safe DevKit library: core + fillkit-api.
    // Contains no developer tooling.
    implementation("io.github.ezekielwachira.devkit:devkit:0.1.0")

    // Every DevKit developer and QA tool: the FillKit panel (with its engine
    // and API) and NetKit. Not fillkit-testing — that stays androidTest-only.
    debugImplementation("io.github.ezekielwachira.devkit:devkit-debug:0.1.0")
}
```

`devkit` and `devkit-debug` are deliberately separate. A single "all of DevKit"
artifact would mean the most natural thing to type —
`implementation("…:devkit")` — silently shipped a network interceptor and a
debug panel to production.

### What each artifact is

| Artifact | Ships in | Version | Contents |
| --- | --- | --- | --- |
| `core` | release + debug | `0.1.0` | Ecosystem version metadata and the runtime/debug/test classification. Arrives transitively; you rarely add it yourself |
| `fillkit-api` | release + debug | `0.1.0` | Models, DSLs, the Compose modifier, semantics keys, reproduction specs, and a no-op release runtime |
| `fillkit-engine` | debug | `0.1.0` | Locale registries, persona and value generation, deterministic random streams |
| `fillkit-debug` | debug | `0.1.0` | Developer panel, QA scenario launcher, deep links, local persistence |
| `fillkit-testing` | `androidTestImplementation` only | `0.1.0` | Compose finders, assertions, `FillKitTestDriver` |
| `netkit` | debug | `0.1.0` | Network scenario toolkit — see [netkit/README.md](netkit/README.md) |
| `devkit` | release + debug | `0.1.0` | Umbrella: `core` + `fillkit-api` |
| `devkit-debug` | debug | `0.1.0` | Umbrella: `fillkit-debug` + `netkit` |
| `devkit-bom` | — | `0.1.0` | Version alignment for all of the above |

`fillkit-testing` exposes Compose test rules and JUnit types;
`implementation("…:fillkit-testing")` is not a supported configuration, and it is
in neither umbrella for the same reason.

You do not normally add `core` yourself. It arrives with whichever kits you
chose, and is only worth naming directly if you use its public API
(`DevKitTool`, `DevKitDistribution`).

### Version compatibility

DevKit BOM **`0.1.0`** pins this tested set:

```text
Artifact          Version
-------------------------
core              0.1.0
fillkit-api       0.1.0
fillkit-engine    0.1.0
fillkit-debug     0.1.0
fillkit-testing   0.1.0
netkit            0.1.0
devkit            0.1.0
devkit-debug      0.1.0
```

Every kit starts its published line at `0.1.0`, and from there versions move
independently — a future NetKit release does not oblige FillKit to follow. The
BOM and umbrella version names a compatible *combination* rather than the
maturity of any one kit, so those columns will diverge as soon as any kit moves.
See [docs/PUBLISHING.md](docs/PUBLISHING.md).

### Building from source

Within this repository the modules are consumed as projects:

```kotlin
dependencies {
    implementation(project(":fillkit:api"))
    debugImplementation(project(":fillkit:debug"))
    debugImplementation(project(":netkit"))
    androidTestImplementation(project(":fillkit:testing"))
}
```

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

Locale support is a first-class capability with 45 built-in packs, locale-aware names, phones and addresses, pack composition, and explicit fallback. It has its own chapter: **[Internationalization and locales](#internationalization-and-locales)**.

The flat DSL from earlier versions still works and populates the structured sections:

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

## Internationalization and locales

FillKit assumes nothing about English, Western names, US addresses, Latin script, or one-given-plus-one-family-name. A locale is a **pack** of optional capability sections, and adding a country means writing one — not touching the engine.

```text
FillLocalePack
├── person      names, order, surname count, separator, Latin forms
├── address     cities, administrative areas + their label, streets, postal codes
├── phone       calling code, national patterns, grouping
├── business    company suffixes, job titles
├── internet    email domains, username style
└── semantics   localized field-label aliases
```

Every section is optional, so a partial pack is valid and inherits the rest.

### Built-in locales

45 packs across seven regions:

| Region | Locales |
| --- | --- |
| Africa | `en-KE` `sw-KE` `en-NG` `en-UG` `en-TZ` `sw-TZ` `en-RW` `en-GH` `en-ZA` `af-ZA` `fr-SN` `fr-CI` `fr-CM` |
| Europe | `en-GB` `fr-FR` `de-DE` `es-ES` `it-IT` `pt-PT` `nl-NL` `pl-PL` `sv-SE` `nb-NO` `da-DK` `fi-FI` |
| North America | `en-US` `en-CA` `fr-CA` `es-MX` |
| South America | `pt-BR` `es-AR` `es-CO` `es-CL` `es-PE` |
| Asia | `en-IN` `hi-IN` `ja-JP` `ko-KR` `zh-CN` `zh-TW` `id-ID` `ms-MY` `th-TH` `vi-VN` `fil-PH` |
| Middle East | `ar-SA` `ar-AE` `ar-EG` `he-IL` `tr-TR` |
| Oceania | `en-AU` `en-NZ` |

Each is a representative synthetic dataset combined procedurally, not a bundled corpus. Names are common public forenames and surnames, places are public geography, and no address is real.

Packs are grouped by region in `:fillkit:engine`, so splitting them into per-region artifacts later needs no public API change — the registry only ever sees a list of packs.

### Names and Unicode

```text
en-KE    Amina Wanjiku
en-US    Emily Carter
es-ES    Lucía García López      two surnames
ja-JP    渡辺 さくら              family name first
ko-KR    김서준                   family first, no separator
```

Generated names keep their own script. Only the email username is normalised, using the pack's Latin forms:

```text
渡辺 さくら  →  sakura.watanabe4@example.org
أحمد العتيبي →  ahmed.alotaibi@example.com
```

When a pack cannot transliterate a name, FillKit falls back to a deterministic synthetic handle (`user84721@example.com`) rather than emitting a malformed address. Generated mail always lands on `example.com`, `example.org` or `example.net`.

### Phones

A pack declares its calling code, valid national prefixes, subscriber length and display grouping. One generated number renders three ways:

```kotlin
FillType.PhoneNumber(format = FillPhoneNumberFormat.International)  // +254 712 345 678
FillType.PhoneNumber(format = FillPhoneNumberFormat.National)       // 0712 345 678
FillType.PhoneNumber(format = FillPhoneNumberFormat.E164)           // +254712345678
```

These are synthetic testing numbers; FillKit does not claim they are unassigned.

### Addresses

Addresses are modelled as `FillAddress` — lines, locality, sub-locality, administrative area, postal code, country — never street/city/state/ZIP. The regional term travels with the pack:

```text
en-KE  County        en-US  State       en-CA  Province
en-GB  County        ja-JP  都道府県      de-DE  Bundesland
```

A locale that does not use postal codes declares that instead of inventing a format, and `FillType.PostalCode` reports it as unsupported rather than fabricating one.

### Selecting a locale

```kotlin
FillKitConfig(locale = FillLocale.System)            // device locale, with fallback
FillKitConfig(locale = FillLocale.Code("sw-KE"))     // exact tag
FillKitConfig(locale = FillLocale.Country("KE"))     // region; FillKit picks a language
```

Locale and country are modelled separately: `en-KE` and `sw-KE` share `country = KE`, its phone plan and its geography, but differ in names and labels.

### Fallback

`LocaleFallbackResolver` is the only place fallback is decided, and every resolution explains itself:

```text
exact tag  →  same region, other language  →  same language, other region  →  default
```

```text
sw-KE → sw-KE (exact locale pack)
de-AT → de-DE (no de-AT pack; same language)
zz-ZZ → en-US (no zz-ZZ locale pack registered)
```

The field inspector shows the reason, so unexpected data can be traced instead of debugged.

### Custom packs and composition

```kotlin
val companySwahili = fillLocalePack(
    code = "sw-KE",
    displayName = "Kenya — Kiswahili",
    id = "company-sw-ke",
    version = "1",
    region = FillLocaleRegion.Africa,
) {
    extends("en-KE")

    person {
        givenNames("Amani", "Baraka", "Neema", "Juma")
        familyNames("Mwangi", "Wanjiku", "Otieno", "Kamau")
    }
    business { suffixes("Ltd", "Kampuni") }
    semanticAliases {
        "jina" mapsTo FillContentHint.FirstName
        "barua pepe" mapsTo FillContentHint.Email
    }
}
```

`extends` inherits phone rules, cities, administrative areas and postal codes rather than duplicating them. Inheritance cycles and unknown parents are rejected at registry construction.

Override precedence is explicit, and a pack replaces the one below it section by section rather than being merged unpredictably:

```text
built-in en-KE  →  application en-KE  →  form-scoped en-KE
```

### Locale precedence

```text
field locale  →  scenario locale  →  persona locale  →  form locale  →  system default
```

A field can pin its own locale without affecting the rest of the form:

```kotlin
Modifier.fillKit(
    id = "internationalPhone",
    type = FillType.PhoneNumber(),
    value = state.internationalPhone,
    onFill = onInternationalPhoneChanged,
    locale = FillLocale.Code("en-GB"),
)
```

A scenario can pin one too, and activating it switches the form:

```kotlin
scenario(id = "japanese-checkout", name = "Japanese Customer", targetForm = "checkout-address") {
    locale("ja-JP")
}
```

A persona carries its locale, and a saved persona keeps its identity when the panel's locale changes — only a random persona is re-drawn.

### Localized field labels

The suggestion heuristics read alias tables from the active pack, so `Prénom`, `Vorname`, `Nombre` and `jina` are recognised the way `First name` is. `ContentType` remains stronger than any label, which is exactly why it matters: `ContentType.EmailAddress` maps to `FillType.Email` whether the label reads `Email`, `E-Mail`, `Correo electrónico` or `Adresse e-mail`.

### Right to left

`ar-SA`, `ar-AE`, `ar-EG` and `he-IL` are flagged right-to-left. The developer panel, QA launcher, field overlay and inspector use start/end rather than left/right throughout, and the overlay positioner anchors to the field's leading edge in either direction.

### Locale and reproduction

Locale is part of deterministic generation: the resolved pack coordinate seeds every field's stream, so the same seed under a different locale is a different — but equally stable — draw. Reproductions record both:

```text
locale=en-KE
localePack=builtin-en-ke@1
```

If a reproduction was recorded against a different pack version, activation reports it as a warning instead of quietly producing other data.

### Adding a locale

Create the pack, register it, add a test. Nothing in `FillValueResolver`, `FillKitHost`, the developer UI or the QA launcher changes.

```kotlin
val poland = fillLocalePack("pl-PL", "Poland — Polish", region = FillLocaleRegion.Europe) {
    person { givenNames("Zofia", "Jakub"); familyNames("Nowak", "Kowalski") }
    location {
        cities("Warszawa", "Kraków")
        administrativeAreas("Mazowieckie", "Małopolskie")
        administrativeAreaLabel("Województwo")
        postalCodes("00-001", "30-001")
        streetFormat("{street} {number}")
    }
    phone { countryCallingCode("+48"); pattern("5", "6", "7", subscriberDigits = 8); grouping(3, 3, 3) }
    business { suffixes("Sp. z o.o.", "S.A.") }
    currency("PLN")
}

FillKitConfig(localePacks = listOf(poland))
```

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

## Field prefill overlay

Focusing a registered field shows a contextual accessory. Tap the value to fill that field, `↻` for another, `⋯` for the inspector.

```text
┌──────────────────────────────────────┐
│ ⚡ +254 712 348 921         ↻    ⋯  │
└──────────────────────────────────────┘
```

It is FillKit tooling, not part of the form: `TextField` stays a `TextField`, and the existing registration is all it needs.

```kotlin
OutlinedTextField(
    value = state.phone,
    onValueChange = onPhoneChanged,
    modifier = Modifier.fillKit(
        id = "phone",
        type = FillType.PhoneNumber("KE"),
        value = state.phone,
        onFill = onPhoneChanged,
    ),
)
```

The suggestion comes from the same `FillValueResolver` as Fill All — same seed, persona, scenario, generators and locale — so with persona *Amina Wanjiku* active, first name offers `Amina`, last name `Wanjiku`, and email `amina.wanjiku@example.com`. A value pinned by the active scenario is offered as-is and tagged `SCENARIO`.

### Placement and the keyboard

One overlay host per `FillKitHost` renders the bar; there is no popup per field, and no `Dialog`. It draws inside the host's own window, which is why tapping a suggestion cannot move focus or dismiss the IME — the controls use raw pointer input rather than `clickable`, which would make them focusable.

Placement is computed from the field's bounds and the live `WindowInsets.ime`, never a hard-coded keyboard height:

```text
room below the field          → anchored popover under it
IME open, field close to it   → keyboard-accessory bar above the IME
no IME, field near the bottom → anchored popover above it
```

`FieldOverlayPositioner` is pure and unit-tested, including right-to-left anchoring, edge clamping and IME promotion. A field scrolled out of view gets no overlay — a `LazyColumn` keeps its focused item composed, so registration alone is not proof the field is on screen.

### Per-field regeneration

`↻` rerolls one field. Persona-derived fields draw from a per-field persona variant, so rerolling the phone leaves the name that is already in the form untouched, and the reroll itself is deterministic in `(seed, generation, field, counter)`.

Counters appear in the reproduction only when non-zero, so the common case stays compact:

```text
seed=845912
generation=0
fields=phone:2
```

That means a QA engineer who tapped `↻` twice before hitting the bug still hands over a token that reproduces it exactly.

### Behaviour by field type

| Field | Overlay |
| --- | --- |
| Text, email, phone, names, addresses, OTP | Value preview, tap to fill |
| Password | Masked preview, tap to fill |
| Long values | Truncated in the bar, full value in the inspector |
| Booleans | Actions only; set the value from the inspector |
| `FillType.Unsupported` | No fill affordance — FillKit does not pretend it can generate it |

An edited field is tagged `MODIFIED` and the bar offers to replace rather than silently overwriting manual input. Nothing is filled just because a field gained focus.

### Configuration

```kotlin
FillKitConfig(
    fieldOverlay = FieldOverlayConfig(
        enabled = true,
        mode = FieldOverlayMode.FocusedField,
        placement = FieldOverlayPlacement.Auto,
        showPreview = true,
    ),
)
```

Per field, when one is genuinely unhelpful:

```kotlin
Modifier.fillKit(id = "code", type = FillType.Text(), state = code, overlay = FieldOverlayBehavior.Disabled)
```

The overlay and the main ⚡ trigger solve different problems and coexist; `showTrigger = false` with the overlay enabled gives a panel-free workflow.

### Testing the overlay

Overlay semantics are separate from the application field's, so a test can tell the accessory apart from the form:

```kotlin
composeRule.onFillKitField("phone").performClick()
composeRule.onFillKitFieldOverlay("phone").assertExists().performFill()
composeRule.onFillKitOverlayAction("phone", FillKitOverlayAction.Regenerate).performClick()
```

These exist mainly for testing FillKit itself. Application tests should prefer the programmatic driver, which does not depend on focus or layout.

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

Finders use the semantics metadata, never visible label text, content descriptions or tree position. Merged/unmerged tree handling lives inside the finder; consumer tests never need `useUnmergedTree = true` to reach a FillKit field.

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
adb shell "am start -a android.intent.action.VIEW -d 'com.example.app.fillkit://reproduce/FK1-...'"
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

A release build contains none of: the developer panel, the field overlay and its positioner, the field inspector, the QA launcher, `FillKitDeepLinkActivity`, FillKit deep-link intent filters, FillKit control semantics, the pending activation store, the activation engine, the generation engine, or `fillkit-testing`. `FillKit.activate` degrades to `Rejected(NoRuntime)` and release builds do not resolve FillKit links.

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
