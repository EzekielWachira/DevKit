# DevKit

An Android workspace for **FillKit** — debug-only tooling that fills Jetpack Compose forms with coherent synthetic data, and turns any interesting form state into something you can reproduce, share, launch and convert into an automated regression test.

The repository contains the FillKit library modules and a sample application that exercises them.

## Modules

| Module | Ships in | Contents |
| --- | --- | --- |
| `:fillkit:api` | release + debug | Pure models, DSLs, the Compose modifier, semantics keys, reproduction specs and token codec, activation requests, and a no-op release runtime |
| `:fillkit:engine` | debug | Locale registries, persona and value generation, scenario composition, deterministic random streams |
| `:fillkit:debug` | debug | Developer panel, QA scenario launcher, activation engine, pending activations, deep-link entry point, local persistence |
| `:fillkit:testing` | `androidTestImplementation` only | Compose test finders, assertions, `FillKitTestDriver`, reproduction helpers |
| `:app` | — | Sample application: five screens covering explicit fields, `ContentType` inference, suggestions, and the QA/reproduction workflow |

Only `:fillkit:api` reaches a release build. The panel, the generator engine, the QA launcher and every deep-link entry point live behind `debugImplementation`.

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

## Use FillKit in an application

```kotlin
dependencies {
    implementation(project(":fillkit:api"))
    debugImplementation(project(":fillkit:debug"))
    androidTestImplementation(project(":fillkit:testing"))
}
```

Wrap a form in a host and mark its fields:

```kotlin
FillKitHost(
    formId = "registration",
    config = FillKitConfig(locale = FillLocale.Code("en-KE"), seed = 845912L),
) {
    OutlinedTextField(
        value = state.email,
        onValueChange = onEmailChanged,
        modifier = Modifier.fillKit(
            id = "email",
            type = FillType.Email,
            value = state.email,
            onFill = onEmailChanged,
        ),
    )
}
```

FillKit never owns application state: the data path stays `FillKit → application callback → application state → recomposition`.

## The 0.4 workflow

A scenario is defined once and reached from four places, all of which build the same `FillActivationRequest` and run through the same activation engine:

```text
Developer panel · QA launcher · Compose test · Deep link / ADB
        ↓
FillActivationRequest → FillKitActivationEngine → FillValueResolver → FormRegistry
```

State is described once, by a `FillReproductionSpec`:

```text
FillKit Reproduction
form=registration
scenario=validation/maximum-values
persona=random
locale=en-KE
seed=845912
generation=0
config=fdfdd2a8a717
token=FK1-...
```

That single token carries a bug through its whole life:

```bash
# QA, from a bug report
adb shell am start -a android.intent.action.VIEW \
  -d "io.devkit.fillkit://reproduce/FK1-..."
```

```kotlin
// The developer's regression test, from the same token
@Test
fun reproduceReportedIssue() {
    composeRule.applyFillKitReproduction("FK1-...")

    composeRule.onFillKitField("email").assertRegistered()
}
```

Generated values are derived per field from the master seed, the generation counter and stable identifiers — never from a single shared random stream — so adding an unrelated field does not change any other field's data.

Full documentation, including determinism guarantees, tokens, configuration fingerprints, the QA launcher, deep-link validation and navigation integration, lives in [fillkit/README.md](fillkit/README.md).

## Verification

```bash
./gradlew :fillkit:api:testDebugUnitTest
./gradlew :fillkit:engine:testDebugUnitTest
./gradlew :fillkit:debug:testDebugUnitTest
./gradlew :fillkit:debug:connectedDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :fillkit:debug:lintDebug :app:assembleDebug :app:assembleRelease
```

Release safety is part of the contract and is checked directly against the artifact: the release manifest must contain no FillKit activity, provider or `*.fillkit` scheme, and the release dex must contain no `DebugFillKitRuntime`, `FillKitActivationEngine`, `FillKitDeepLinkActivity` or `FillValueResolver`.

## Data safety

FillKit generates synthetic data only. Email domains are limited to `example.com`, `example.org` and `example.net`. It does not generate payment details, government identifiers, production credentials or real private records, and reproduction tokens, deep links and bug-report blocks carry identifiers and a seed — never generated field values.
