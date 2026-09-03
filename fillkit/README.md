# FillKit

FillKit is debug-only tooling for filling Jetpack Compose forms with coherent synthetic data, and for turning any interesting form state into something you can reproduce, share, launch and convert into an automated regression test.

The full documentation lives in the repository README: **[../README.md](../README.md)**.

| Module | Maven artifact | Ships in | Contents |
| --- | --- | --- | --- |
| `api` | `fillkit-api` | release + debug | Pure models, DSLs, the Compose modifier, semantics keys, reproduction specs and token codec, activation requests, and a no-op release runtime |
| `engine` | `fillkit-engine` | debug | Locale registries, persona and value generation, scenario composition, deterministic random streams |
| `debug` | `fillkit-debug` | debug | Developer panel, QA scenario launcher, activation engine, pending activations, deep-link entry point, local persistence |
| `testing` | `fillkit-testing` | `androidTestImplementation` only | Compose test finders, assertions, `FillKitTestDriver`, reproduction helpers |

## Install

```kotlin
dependencies {
    implementation("io.github.ezekielwachira.devkit:fillkit-api:0.1.0")
    debugImplementation("io.github.ezekielwachira.devkit:fillkit-debug:0.1.0")
    androidTestImplementation("io.github.ezekielwachira.devkit:fillkit-testing:0.1.0")
}
```

The published artifacts start at `0.1.0`. The milestone numbers in the
repository history (FillKit 0.2 through 0.4) predate publication and were never
release versions; `0.1.0` is the first with a Maven coordinate and contains all
of that work.

FillKit is four artifacts because its pieces have genuinely different
consumption scopes. `fillkit-api` is the only one that reaches a release build —
your production code annotates fields with it — so it is `implementation` while
the panel is `debugImplementation`. A single `fillkit` artifact could only be
declared in one configuration, and that choice would be wrong for the other two.

`fillkit-engine` arrives transitively with `fillkit-debug`; you do not add it
yourself.

To avoid naming versions, use the BOM:

```kotlin
dependencies {
    implementation(platform("io.github.ezekielwachira.devkit:devkit-bom:0.1.0"))

    implementation("io.github.ezekielwachira.devkit:fillkit-api")
    debugImplementation("io.github.ezekielwachira.devkit:fillkit-debug")
    androidTestImplementation("io.github.ezekielwachira.devkit:fillkit-testing")
}
```

`debugImplementation("io.github.ezekielwachira.devkit:devkit-debug:0.1.0")` takes
the FillKit panel and NetKit together. It does **not** include `fillkit-testing`,
which exposes JUnit and Compose test rules and stays `androidTestImplementation`
only, nor `fillkit-api` in `implementation` — add that separately if your
production code uses the modifier.

Inside this repository, as project dependencies:

```kotlin
dependencies {
    implementation(project(":fillkit:api"))
    debugImplementation(project(":fillkit:debug"))
    androidTestImplementation(project(":fillkit:testing"))
}
```
