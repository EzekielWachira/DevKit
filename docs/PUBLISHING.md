# Publishing DevKit

How DevKit is packaged, versioned, published and verified — and how to add a new
kit to the ecosystem.

- [Artifact architecture](#artifact-architecture) · [Coordinates](#maven-coordinates) · [Classification](#runtime-debug-and-test)
- [Versioning](#versioning) · [The BOM](#the-bom) · [Umbrellas](#the-umbrella-artifacts)
- [Publishing locally](#publishing-locally) · [Verification](#verifying-a-publication) · [Publishing publicly](#publishing-publicly)
- [CI and secrets](#ci-and-secrets) · [Adding a kit](#adding-a-new-kit) · [Release checklist](#release-checklist)

## Artifact architecture

```text
                          devkit-bom
                     (version constraints only)
                               │
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
   fillkit-api             fillkit-debug            netkit
   fillkit-engine          fillkit-testing
        │                      │                      │
        └──────────── core ────┴──────────────────────┘


devkit                          devkit-debug
├── core                        ├── fillkit-debug ──► fillkit-engine ──► fillkit-api
└── fillkit-api                 └── netkit
   (release-safe only)             (developer and QA tooling)
```

Three rules hold the shape together:

1. **Umbrellas depend on leaves, never the reverse.** `fillkit-debug` must never
   depend on `devkit-debug`. A leaf that reached back to its umbrella would make
   installing one kit install all of them.
2. **Nothing depends on the BOM.** It constrains versions for consumers;
   `netkit → devkit-bom → netkit` would be a cycle.
3. **`core` depends on nothing.** It is the foundation, so anything added to it
   is imposed on every kit that touches it.

## Maven coordinates

Group: **`io.github.ezekielwachira.devkit`**

| Gradle module | Artifact | Purpose | Version | Class |
| --- | --- | --- | --- | --- |
| `:core` | `core` | Ecosystem version metadata and the distribution classification | `0.1.0` | runtime |
| `:chartkit` | `chartkit` | Compose-native charting engine and chart composables | `0.1.0` | runtime |
| `:fillkit:api` | `fillkit-api` | FillKit's Compose modifier, models, DSLs and no-op release runtime | `0.1.0` | runtime |
| `:fillkit:engine` | `fillkit-engine` | FillKit's data generation engine | `0.1.0` | debug |
| `:fillkit:debug` | `fillkit-debug` | FillKit's developer panel and QA launcher | `0.1.0` | debug |
| `:fillkit:testing` | `fillkit-testing` | FillKit's Compose test support | `0.1.0` | test |
| `:netkit` | `netkit` | Network scenario and failure simulation toolkit | `0.1.0` | debug |
| `:devkit` | `devkit` | Umbrella: every release-safe library | `0.1.0` | runtime |
| `:devkit-debug` | `devkit-debug` | Umbrella: every developer and QA tool | `0.1.0` | debug |
| `:devkit-bom` | `devkit-bom` | Version alignment | `0.1.0` | — |

### Why ChartKit is one artifact and FillKit is four

Because its pieces have genuinely different consumption scopes, and collapsing
them would force the release-safe half into a debug-only dependency:

```kotlin
implementation("…:fillkit-api")              // production code annotates fields
debugImplementation("…:fillkit-debug")       // the panel
androidTestImplementation("…:fillkit-testing") // test helpers
```

A single `fillkit` artifact could only be declared in one of those three
configurations, and every choice would be wrong for the other two. NetKit needs
no such split — it is debug-only in its entirety, so it is one artifact.

## Runtime, debug and test

The classification is what the umbrellas are built from, and it is also
available at runtime as `DevKitDistribution` in `core`:

| Class | Configuration | Umbrella | Artifacts |
| --- | --- | --- | --- |
| **runtime** | `implementation` | `devkit` | `core`, `chartkit`, `fillkit-api` |
| **debug** | `debugImplementation` | `devkit-debug` | `netkit`, `fillkit-debug`, `fillkit-engine` |
| **test** | `androidTestImplementation` | none | `fillkit-testing` |

`fillkit-testing` is in no umbrella on purpose: it exposes JUnit and Compose test
rules, which have no business on an application's classpath even in debug.

Not every kit is debug-only. ChartKit is **runtime**: it draws an application's
own data on a screen its users see, so it belongs in `implementation` and in the
`devkit` umbrella, alongside `fillkit-api`. The classification is per kit,
decided when it is added, and written down here.

ChartKit is one artifact rather than several. Unlike FillKit it has no piece with
a different consumption scope — the engine, the layers and the composables are
all needed at runtime by the same code — so splitting it would produce artifacts
nobody would ever declare separately.

### Enforcement model

Dependency scoping, not runtime checks. A kit is kept out of production by being
declared `debugImplementation`; DevKit does not crash an app that packaged a
debug tool by mistake, because turning a packaging error into an outage is worse
than the error. The safety net is that `devkit` cannot pull debug tooling even if
someone asks it to — which the consumer verification build asserts on every run.

## Versioning

Two independent tracks.

**Kits version independently.** Every kit starts its published line at `0.1.0`,
and from there a NetKit release does not oblige FillKit to move. FillKit's four artifacts share one version because they are one library
split by scope, and a mismatched pair would not compile against each other.

**The ecosystem versions as a set.** `devkit`, `devkit-debug` and `devkit-bom`
share `devkit.version.ecosystem`. That number names a *tested compatible
combination* of kit versions, not the maturity of any one kit — which is why
`devkit` and `fillkit-api` can diverge as soon as either moves.

Everything lives in `gradle.properties`:

```properties
devkit.group=io.github.ezekielwachira.devkit

devkit.version.core=0.1.0
devkit.version.chartkit=0.1.0
devkit.version.fillkit=0.1.0
devkit.version.netkit=0.1.0

devkit.version.ecosystem=0.1.0
```

That file, and only that file, is the source of truth. `gradle/libs.versions.toml`
manages the dependencies DevKit *builds against*, which is a different lifecycle:
conflating them makes it impossible to bump Compose without appearing to cut a
release.

A module names a version *stream* rather than a literal:

```kotlin
devKitPublishing {
    artifactId.set("fillkit-debug")
    versionKey.set("fillkit")   // → devkit.version.fillkit
}
```

## The BOM

`devkit-bom` is a Gradle `java-platform`. It carries constraints and no
dependencies, so importing it pulls nothing:

```kotlin
dependencies {
    implementation(platform("io.github.ezekielwachira.devkit:devkit-bom:0.1.0"))

    implementation("io.github.ezekielwachira.devkit:chartkit")
    implementation("io.github.ezekielwachira.devkit:fillkit-api")
    debugImplementation("io.github.ezekielwachira.devkit:netkit")
    debugImplementation("io.github.ezekielwachira.devkit:fillkit-debug")
}
```

**One `implementation(platform(...))` line is enough.** `debugImplementation`
extends `implementation`, so the constraints reach debug configurations without a
second `debugImplementation(platform(...))`. This is verified rather than assumed
— `consumer-test` resolves exactly this shape.

## The umbrella artifacts

Aggregators. They contain dependency declarations and **no source**; kit code is
never copied into them.

`api` rather than `implementation` is correct here and only here: an umbrella
exists to expose its children transitively, and `implementation` would hide the
very types the consumer added it for. Inside real libraries, `implementation`
stays the default.

## Publishing locally

```bash
# Everything
./gradlew publishToMavenLocal

# One kit
./gradlew :netkit:publishToMavenLocal
./gradlew :fillkit:api:publishToMavenLocal

# The BOM and the umbrellas
./gradlew :devkit-bom:publishToMavenLocal
./gradlew :devkit:publishToMavenLocal
./gradlew :devkit-debug:publishToMavenLocal
```

Local publishing needs no licence, no signing key and no credentials — by design,
so the pipeline stays testable.

## Verifying a publication

```bash
./scripts/verify-publication.sh
```

Publishes everything to Maven Local, then resolves and compiles it from
`consumer-test/`, a standalone build that knows DevKit only by Maven
coordinates. That distinction matters: a `project(":netkit")` dependency inside
the main build bypasses the POM, the module metadata, the artifact ids and the
versions — every layer a publishing change can break.

It checks six installation models plus release safety:

| Case | Asserts |
| --- | --- |
| NetKit only | resolves; pulls **no** FillKit, **no** ChartKit |
| ChartKit only | resolves; pulls **no** FillKit, **no** NetKit |
| FillKit only | resolves with engine and api; pulls **no** NetKit, **no** ChartKit |
| BOM + kits | versions resolve with none named |
| `devkit-debug` | brings NetKit and the FillKit panel; **not** `fillkit-testing`, **not** ChartKit |
| `devkit` | brings only release-safe libraries — including ChartKit; **no** NetKit, **no** debug |
| release variant | no debug tooling on `releaseRuntimeClasspath`; ChartKit present |

The negative assertions are the point. That `netkit` resolves is unsurprising;
that it drags in no FillKit is the claim the artifact split rests on, and it is
what would silently stop being true the first time somebody added a convenience
`api(...)` to a kit.

To inspect a graph by hand:

```bash
./gradlew --project-dir consumer-test dependencies --configuration caseNetKitOnly
```

## Publishing publicly

DevKit is licensed **Apache-2.0** ([LICENSE.md](../LICENSE.md)), which every POM
declares. Copyright is asserted in [NOTICE](../NOTICE) — Apache-2.0 §4(d) makes
that the canonical place for it, and requires redistributors to carry it along.

Keep `devkit.pom.license.*` in `gradle.properties` matching the licence file, and
bump the year in `NOTICE` when it changes. The build refuses a remote publish if
the licence is missing, because Maven Central rejects such a POM late and
unhelpfully.

```bash
# One artifact
./gradlew :netkit:publishToMavenCentral --no-configuration-cache

# Everything, as a single deployment
./gradlew publishToMavenCentral --no-configuration-cache
```

`--no-configuration-cache` is required. Publishing a release to Central cannot
be config-cached yet — the repository spec is not serialisable, pending
[gradle#22779](https://github.com/gradle/gradle/issues/22779) — and without the
flag the run dies with a serialisation error that looks nothing like a
publishing problem. Every other task keeps the cache.

Both upload and stop. The deployment appears at
[central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments)
as `VALIDATED` and waits for someone to press **Publish**. Release to Central is
irreversible — a version, once live, can never be replaced — so the last step is
deliberate rather than a side effect of merging. `publishAndReleaseToMavenCentral`
skips the review; the workflow does not use it.

Run from the root, the nine modules go up as **one** deployment, so they
validate and release together instead of as nine that can each fail separately.

### Why the upload is a plugin's job

The Central Portal takes a deployment as a single POST of a bundled zip, not as
a file-by-file PUT into a repository, so `maven-publish` alone cannot address
it. The path that looks like it works — Sonatype's OSSRH compatibility bridge at
`ossrh-staging-api.central.sonatype.com` — accepts the PUTs, returns success,
and leaves the result in a *legacy staging repository keyed to (user, source
IP)*. It never appears in the Portal until promoted, and the promotion has to
come from the same IP that uploaded. On a CI runner that IP dies with the job,
so the deployment is stranded and the build reports success. DevKit used that
bridge once and lost a release to it.

Before publishing, the build refuses anything that would produce an unusable
artifact: a missing artifact id, display name, description or version key; a
missing group; a missing licence; a missing signing key. `unspecified` versions
in particular publish successfully and produce an artifact nobody can depend on,
which is why the version key is validated rather than defaulted.

## CI and secrets

`.github/workflows/publish.yml` publishes on a **published GitHub Release** or a
**manual dispatch**, never on a branch push. Manual dispatch takes a module path,
so one kit can be released without the others.

Required repository secrets — names only, never values:

| Secret | Purpose |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central portal token password |
| `SIGNING_KEY` | ASCII-armoured PGP private key |
| `SIGNING_PASSWORD` | Passphrase for that key |

The workflow passes these to Gradle as `ORG_GRADLE_PROJECT_mavenCentralUsername`,
`…Password`, `…signingInMemoryKey` and `…signingInMemoryKeyPassword`. The prefix
is load-bearing: Gradle turns such a variable into the Gradle property of the
same name, which is where the publishing plugin looks. The secret names in
GitHub are unchanged.

Signing uses an in-memory key: CI has secrets, not files, and a keyring path in
a build script ends up pointing at somebody's home directory. Never commit keys,
passwords or tokens; the build reads them from the environment or Gradle
properties and never logs them.

## Adding a new kit

Worked through with a hypothetical runtime-safe `NavKit`. ChartKit is the most
recent kit added this way, so its module, BOM entry, umbrella membership and
consumer-test cases are the reference to copy from.

**1. Create the module and register it** in `settings.gradle.kts` under the kits
section.

**2. Apply the convention plugin:**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    id("devkit.publish")
}

android {
    namespace = "io.devkit.navkit"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 24 }
}

dependencies {
    // `core` only if it genuinely needs it. Several kits will not.
}

devKitPublishing {
    artifactId.set("navkit")
    displayName.set("NavKit")
    description.set("Type-safe Compose navigation for Android.")  // specific, not generic
    versionKey.set("navkit")
}
```

**3. Add the version** to `gradle.properties`:

```properties
devkit.version.navkit=0.1.0
```

**4. Classify it** as runtime, debug or test, and record it in the table above
and in the root README.

**5. Add it to the matching umbrella** — `:devkit` for runtime, `:devkit-debug`
for debug, neither for test:

```kotlin
dependencies {
    api(project(":navkit"))
}
```

**6. Constrain it in the BOM:**

```kotlin
api("$group:navkit:$navKitVersion")
```

**7. Add a verification case** to `consumer-test/build.gradle.kts`, including the
negative assertions — that it pulls no unrelated kit, and that a debug kit stays
out of `devkit`.

**8. Document installation** in the root README and the kit's own README.

**9. Verify:** `./scripts/verify-publication.sh`

No root publishing logic changes.

## Release checklist

```text
[ ] tests pass                     ./gradlew build
[ ] kit version bumped             gradle.properties
[ ] ecosystem version bumped       if the compatible set changed
[ ] BOM constraints updated        devkit-bom/build.gradle.kts
[ ] umbrella membership correct    devkit / devkit-debug
[ ] Maven Local smoke test passes  ./scripts/verify-publication.sh
[ ] POM inspected                  name, description, URL, SCM, developer, licence
[ ] sources jar present            *-sources.jar in ~/.m2
[ ] README compatibility table updated
[ ] kit README install snippet updated
[ ] licence configured             required for Maven Central
[ ] NOTICE year current           LICENSE.md and NOTICE agree
[ ] CI secrets available
[ ] release published
```
