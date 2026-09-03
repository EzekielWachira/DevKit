plugins {
    // AGP 9 applies the Kotlin Android plugin itself, so declaring it here
    // would be a duplicate registration. Matches every module in the parent
    // build, none of which declares it either.
    id("com.android.library")
}

android {
    namespace = "io.example.consumer"
    // Must be at least the kits' own compileSdk: the published AARs declare a
    // minimum through their AAR metadata, and AGP enforces it. Matching it here
    // is part of what this build verifies — a consumer on an older SDK gets a
    // clear message rather than a link error.
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

/**
 * A consumer that compiles against DevKit **as published**.
 *
 * Resolution alone proves the metadata is well formed; compiling proves the
 * artifacts actually contain the classes their POMs promise, which is a
 * different failure and the one a broken `singleVariant` configuration
 * produces.
 *
 * The split below is the model the README documents, exercised for real:
 * `fillkit-api` in `implementation` because production code touches it, and the
 * debug umbrella in `debugImplementation` because none of it may reach a release
 * binary. `src/main` referencing a NetKit type would fail to compile, which is
 * precisely the guardrail being demonstrated.
 */
val group = providers.gradleProperty("devkitGroup").get()
val ecosystem = providers.gradleProperty("devkitEcosystemVersion").get()

dependencies {
    // Version-free: the BOM supplies them.
    implementation(platform("$group:devkit-bom:$ecosystem"))

    implementation("$group:fillkit-api")

    // The BOM's constraints reach debug configurations too, because
    // `debugImplementation` extends `implementation`. That is what makes one
    // `implementation(platform(...))` line enough, and this build is where that
    // claim is verified rather than assumed.
    debugImplementation("$group:devkit-debug")
}

/**
 * Asserts that no DevKit debug tooling reaches the **release** variant.
 *
 * The end-to-end form of the release-safety claim. Everything else — the
 * umbrella split, the `debugImplementation` guidance, the POM graphs — exists
 * to make this true, and this is the only check that reads the classpath AGP
 * will actually package.
 *
 * Resolving `releaseRuntimeClasspath` rather than reasoning about
 * configurations is deliberate: it is the same set AGP packages, so a mistake
 * in how the consumer wired its dependencies is caught here too, not only a
 * mistake in how DevKit published them.
 */
val debugOnlyArtifacts = listOf(
    "netkit",
    "fillkit-debug",
    "fillkit-engine",
    "fillkit-testing",
    "devkit-debug",
)

tasks.register("verifyReleaseIsClean") {
    group = "verification"
    description = "Fails if any DevKit debug tool reaches the release runtime classpath"

    val releaseGraph = configurations.named("releaseRuntimeClasspath")
        .flatMap { it.incoming.resolutionResult.rootComponent }
        .map { root ->
            buildSet {
                fun walk(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
                    val id = component.moduleVersion ?: return
                    if (!add("${id.group}:${id.name}")) return
                    component.dependencies
                        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                        .forEach { walk(it.selected) }
                }
                walk(root)
            }
        }

    val expectedGroup = providers.gradleProperty("devkitGroup").get()

    doLast {
        val graph = releaseGraph.get()
        val leaked = debugOnlyArtifacts.filter { "$expectedGroup:$it" in graph }
        if (leaked.isNotEmpty()) {
            throw GradleException(
                "Debug tooling reached the release classpath: ${leaked.joinToString()}",
            )
        }
        val devkit = graph.filter { it.startsWith(expectedGroup) }.sorted()
        check(devkit.isNotEmpty()) { "Expected the release variant to still use fillkit-api" }
        println("✓ release variant is clean; DevKit artifacts present:")
        devkit.forEach { println("    $it") }
    }
}
