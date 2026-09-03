# DevKit Core

The shared foundation for the DevKit libraries. Deliberately tiny.

**You do not normally add this yourself.** It arrives transitively with whichever
kits you chose. Name it directly only if you use its public API.

```kotlin
implementation("io.github.ezekielwachira.devkit:core:0.1.0")
```

## What is in it

Two things, both genuinely shared:

**`DevKitTool`** — a kit's identity at runtime: artifact id, display name,
version, and how it is meant to be consumed. Each kit already knew its own name
and version; what it had no way to state was which ecosystem it belongs to and
whether it is safe in production.

```kotlin
NetKitVersion.tool.qualifiedLabel      // "NetKit 0.1.0 (debug-only)"
FillKitVersion.api.qualifiedLabel      // "FillKit API 0.1.0"
NetKitVersion.tool.coordinates()       // "io.github.ezekielwachira.devkit:netkit:0.1.0"
```

**`DevKitDistribution`** — `RUNTIME`, `DEBUG` or `TEST`. The machine-readable
form of the classification that decides which umbrella artifact a kit belongs to,
so the build files, the documentation and any future tooling cannot disagree
about it.

It does **not** enforce anything at runtime. A library that crashed because it
noticed it was in a release build would turn a packaging mistake into an outage,
which is worse than the mistake. Keeping debug tooling out of production is the
dependency declaration's job — see the root README.

## What is deliberately not in it

No networking, no charting, no fake-data generation, no navigation, no large UI
surface, and no feature-specific models. `core` is the foundation, so anything
added here is imposed on every kit that touches it — which is why it currently
has **no dependencies at all** beyond the Kotlin standard library.

FillKit's and NetKit's debug design systems are *not* shared here despite looking
similar. They differ in shapes, spacing and components, and merging them would be
a visual refactor of two shipped UIs rather than a genuine extraction. Shared
Compose primitives are the obvious next thing to land here — when two kits
actually want the same one.

Some kits may end up depending on nothing from `core`. That is fine and expected.
