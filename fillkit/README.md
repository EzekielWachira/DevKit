# FillKit

FillKit is debug-only tooling for filling Jetpack Compose forms with coherent synthetic data, and for turning any interesting form state into something you can reproduce, share, launch and convert into an automated regression test.

The full documentation lives in the repository README: **[../README.md](../README.md)**.

| Module | Ships in | Contents |
| --- | --- | --- |
| `api` | release + debug | Pure models, DSLs, the Compose modifier, semantics keys, reproduction specs and token codec, activation requests, and a no-op release runtime |
| `engine` | debug | Locale registries, persona and value generation, scenario composition, deterministic random streams |
| `debug` | debug | Developer panel, QA scenario launcher, activation engine, pending activations, deep-link entry point, local persistence |
| `testing` | `androidTestImplementation` only | Compose test finders, assertions, `FillKitTestDriver`, reproduction helpers |

```kotlin
dependencies {
    implementation(project(":fillkit:api"))
    debugImplementation(project(":fillkit:debug"))
    androidTestImplementation(project(":fillkit:testing"))
}
```
