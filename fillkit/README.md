# FillKit 0.3

FillKit is debug-only tooling for filling Jetpack Compose forms with coherent synthetic data. Version 0.3 adds public Compose semantics metadata, `ContentType` mapping, explainable field suggestions, `TextFieldState` support, and one centralized fill target. It never owns application form state.

## Install and release safety

```kotlin
dependencies {
    implementation(project(":fillkit:api"))
    debugImplementation(project(":fillkit:debug"))
}
```

Minimum tested Compose versions are UI/Foundation `1.10.4` and Material 3 `1.4.0` (Compose BOM `2026.02.01`).

`:fillkit:api` contains pure models, DSLs, the Compose modifier, and a no-op release runtime. `:fillkit:engine` contains locale registries, generators, scenario composition, and value resolution. `:fillkit:debug` contains the panel and local persona persistence. Because only the debug variant includes the latter two, the panel, generated data, and runtime-saved personas cannot appear in release builds.

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

The data path remains `FillKit → application callback → application state → recomposition`. The modifier works with custom fields, dropdowns, checkboxes, switches, and basic text fields.

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

## Seeded reproduction

```kotlin
FillKitConfig(seed = 845912L, locale = FillLocale.Code("en-KE"))
```

The same seed, resolved locale, scenario/persona, generator registrations, and invocation order produce the same values. Generator dependencies share that deterministic random stream. `Randomize` advances it to another coherent persona.

All generated email domains use `example.com`, `example.org`, or `example.net`. FillKit does not generate payment details, government identifiers, production credentials, or real private records.

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
./gradlew :fillkit:debug:lintDebug :app:assembleDebug :app:assembleRelease
```

The release runtime graph must include only `:fillkit:api`; the release manifest must not contain `FillKitInitializerProvider`.
