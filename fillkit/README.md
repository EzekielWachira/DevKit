# FillKit 0.2

FillKit is debug-only tooling for filling Jetpack Compose forms with coherent synthetic data. It calls the same callbacks as user input and never owns application form state.

## Install and release safety

```kotlin
dependencies {
    implementation(project(":fillkit:api"))
    debugImplementation(project(":fillkit:debug"))
}
```

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
