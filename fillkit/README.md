# FillKit

FillKit is debug-only developer tooling for instantly populating Jetpack Compose forms with coherent, synthetic data. It calls the same callbacks as real user input; **FillKit never owns application form state**.

## Modules and release guarantee

- `:fillkit:api` — the small Compose contract and no-op runtime. Safe in `main` and release variants.
- `:fillkit:engine` — deterministic generators and locale datasets. Debug runtime dependency only.
- `:fillkit:debug` — registry, initializer, generators, floating trigger, and Material 3 panel.

```kotlin
dependencies {
    implementation(project(":fillkit:api"))
    debugImplementation(project(":fillkit:debug"))
}
```

The debug artifact contributes a non-exported manifest provider. Android creates it before the first activity, and it installs the debug host strategy into the API. Registries and callbacks are not global: each `FillKitHost` owns a composition-scoped registry. Without the debug artifact, the API's default strategy renders content normally and modifier nodes find no registry.

This dependency arrangement is the production boundary. A runtime `BuildConfig.DEBUG` check is not used. For an even stricter zero-API integration, keep FillKit imports and wrappers in the consuming app's `src/debug` source set.

## Quick start

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

Run the debug app, tap the small ⚡ button, then use **Fill All**, **Randomize**, **Clear**, a scenario, or a field's individual controls.

The data path stays architecture-neutral:

```text
FillKit → onFill callback → ViewModel/action/reducer → application state → recomposition
```

`OutlinedTextField` is not required. The modifier works on `BasicTextField`, custom fields, dropdowns, checkboxes, switches, and other Compose UI because it only registers typed state and callbacks.

## Types and constraints

V1 includes:

- Person: `FirstName`, `LastName`, `FullName`, `Username`, `DateOfBirth`, `Age`
- Contact/internet: `Email`, `PhoneNumber`, `Url`, `Website`, `Password`
- Address: `StreetAddress`, `City`, `Region`, `Country`, `PostalCode`
- Business: `CompanyName`, `JobTitle`
- Generic: `Text`, `Integer`, `Decimal`, `BooleanValue`, `Date`, `Selection`
- Extension: typed `Custom<T>` with a form-scoped generator

Constraints validate at construction, so impossible ranges and password policies fail close to their declarations:

```kotlin
FillType.Integer(18..65)
FillType.Text(minLength = 10, maxLength = 50)
FillType.Password(minLength = 12, uppercase = true, lowercase = true, digits = true)
FillType.Date(min = FillDate(1980, 1, 1), max = FillDate(2005, 12, 31))
FillType.Selection(listOf("Plumber", "Electrician", "Carpenter"))
```

String fields clear to `""` by default. Other types should declare an unambiguous callback:

```kotlin
Modifier.fillKit(
    id = "acceptTerms",
    type = FillType.BooleanValue(),
    value = state.acceptTerms,
    onFill = onTermsChanged,
    onClear = { onTermsChanged(false) },
)
```

## Personas, seeds, and locales

`Fill All` creates related values from one `FakePersona`: names match the generated email and related address/company values stay together. `Randomize` creates a new persona before refilling.

```kotlin
FillKitConfig(seed = 845912L, locale = FillLocale.Code("en-KE"))
```

The same seed, resolved locale, field order, and configuration produce the same values. Included datasets are `en-KE`, `en-US`, and `en-GB`. Resolution is exact tag → English provider for the requested region (for example `sw-KE` → `en-KE`) → `en-US`.

All email data uses `example.com`, `example.org`, or `example.net`. Fabricated company websites remain under `example.com`. Addresses are synthetic combinations of public place and invented street names. FillKit does not generate payment data, government identifiers, secrets, or private residential records.

## Scenarios

Applications own workflow scenarios:

```kotlin
val happyPath = fillScenario("happy-path", "Happy path") {
    text("firstName", "Amina")
    text("lastName", "Wanjiku")
    text("email", "amina.wanjiku@example.com")
    integer("age", 29)
}

FillKitConfig(scenarios = listOf(happyPath))
```

Lenient validation logs and skips unknown or mismatched fields. `ScenarioValidationMode.Strict` throws an actionable debug exception.

## Custom generators

Generators are attached to one host configuration, so they cannot retain unrelated forms:

```kotlin
FillKitConfig(
    customGenerators = mapOf(
        "service-name" to FillGenerator<String> {
            listOf("House Cleaning", "Electrical Repair", "Furniture Assembly")
                .random(it.random)
        },
    ),
)

Modifier.fillKit(
    id = "serviceName",
    type = FillType.Custom("service-name", String::class),
    value = state.serviceName,
    onFill = onServiceNameChanged,
)
```

## Programmatic control

Disable the built-in trigger and use a controller for a custom debug UI:

```kotlin
val controller = rememberFillKitController()

FillKitHost(
    formId = "profile",
    controller = controller,
    config = FillKitConfig(showTrigger = false),
) { ProfileForm() }

Button(onClick = controller::fillAll) { Text("Fill") }
```

Controllers expose `fillAll`, `regenerateAll`, `clearAll`, `fill(fieldId)`, `clear(fieldId)`, and `applyScenario(id)`. They are no-ops without a bound debug host.

## Lifecycle and form isolation

`Modifier.fillKit` uses `ModifierNodeElement`, `Modifier.Node`, and `CompositionLocalConsumerModifierNode`. It registers on attach, updates its latest value/callback on modifier update, and unregisters on detach. This supports conditional fields, navigation, lazy-list recycling, and recomposition without retaining dead callbacks. The nearest host wins, and independent or nested hosts keep separate registries.

## Verification

```bash
./gradlew :fillkit:api:testDebugUnitTest
./gradlew :fillkit:engine:testDebugUnitTest
./gradlew :fillkit:debug:testDebugUnitTest
./gradlew :fillkit:debug:compileDebugAndroidTestKotlin
./gradlew :fillkit:debug:connectedDebugAndroidTest
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:dependencies --configuration debugRuntimeClasspath
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
./gradlew lint
```

The release graph must include `:fillkit:api` and exclude both `:fillkit:debug` and `:fillkit:engine`. The release manifest must not contain `FillKitInitializerProvider`.

## Current limitations and roadmap

- `FillDate` is a small civil-date model chosen to support minSdk 24 without adding desugaring or a date dependency.
- Locale selection is session-local but is not persisted across activity recreation.
- Compose lifecycle/panel tests require an emulator or device to execute; they can be compiled on any Android SDK host.
- Publishing, binary compatibility validation, saved personas, more locale packs, Compose semantics integration, and dedicated testing helpers are future work.
