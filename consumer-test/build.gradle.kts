plugins {
    id("com.android.library") version "9.3.2" apply false
}

/**
 * Resolves each documented installation model and asserts what a consumer
 * actually gets.
 *
 * The value of these checks is entirely in the **negative** assertions. That
 * `netkit` resolves is unsurprising; that it drags in no FillKit is the claim
 * the whole artifact split rests on, and it is the one that would silently stop
 * being true the first time somebody added a convenience `api(...)` to a kit.
 */

// Named `devkitGroup`, not `group`: inside a `tasks.register { }` block a bare
// `group` resolves to the task's own group property, which is a `String?` and
// silently the wrong value.
val devkitGroup = providers.gradleProperty("devkitGroup").get()
val fillKit = providers.gradleProperty("devkitFillKitVersion").get()
val netKit = providers.gradleProperty("devkitNetKitVersion").get()
val ecosystem = providers.gradleProperty("devkitEcosystemVersion").get()

/**
 * Declares one resolvable configuration and a task that checks its graph.
 *
 * @param mustContain module coordinates (`group:artifact`) that must resolve.
 * @param mustNotContain module coordinates that must **not** appear anywhere in
 *   the graph, transitively included.
 */
fun verificationCase(
    name: String,
    description: String,
    dependencies: List<String>,
    mustContain: List<String>,
    mustNotContain: List<String> = emptyList(),
) {
    val configuration = configurations.create("case${name.replaceFirstChar(Char::uppercase)}") {
        isCanBeConsumed = false
        isCanBeResolved = true
        // Android artifacts are variant-aware. Without these attributes Gradle
        // cannot pick a variant of an AAR from a plain JVM build and fails with
        // an ambiguity error that looks like a publication bug but is not.
        //
        // The **runtime** usage, not the API one: leakage is a question about
        // what ends up in the consumer's application, and the API graph omits
        // everything a kit declared `implementation`. Asserting against the API
        // graph would have quietly passed a NetKit that shipped FillKit at
        // runtime.
        attributes {
            attribute(
                Usage.USAGE_ATTRIBUTE,
                objects.named(Usage::class.java, Usage.JAVA_RUNTIME),
            )
            attribute(
                Category.CATEGORY_ATTRIBUTE,
                objects.named(Category::class.java, Category.LIBRARY),
            )
            attribute(
                com.android.build.api.attributes.BuildTypeAttr.ATTRIBUTE,
                objects.named(com.android.build.api.attributes.BuildTypeAttr::class.java, "debug"),
            )
        }
    }

    project.dependencies {
        dependencies.forEach { notation ->
            if (notation.startsWith("platform:")) {
                add(configuration.name, platform(notation.removePrefix("platform:")))
            } else {
                add(configuration.name, notation)
            }
        }
    }

    tasks.register("verify${name.replaceFirstChar(Char::uppercase)}") {
        group = "verification"
        this.description = description

        val resolved = configuration.incoming.resolutionResult.rootComponent.map { root ->
            buildSet {
                fun walk(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
                    val id = component.moduleVersion ?: return
                    if (!add("${id.group}:${id.name}:${id.version}")) return
                    component.dependencies
                        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                        .forEach { walk(it.selected) }
                }
                walk(root)
            }
        }

        doLast {
            val graph = resolved.get()
            val modules = graph.map { it.substringBeforeLast(':') }.toSet()
            val problems = buildList {
                mustContain.forEach { required ->
                    if (required !in modules) add("missing: $required")
                }
                mustNotContain.forEach { forbidden ->
                    if (forbidden in modules) {
                        add("LEAKED: $forbidden should not be in this graph")
                    }
                }
            }
            if (problems.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("$name — $description")
                        problems.forEach { appendLine("  $it") }
                        appendLine("  resolved graph:")
                        graph.sorted().forEach { appendLine("    $it") }
                    },
                )
            }
            println("✓ $name — $description")
            graph.filter { it.startsWith(devkitGroup) }.sorted().forEach { println("    $it") }
        }
    }
}

// ---- Case 1: NetKit alone --------------------------------------------------
verificationCase(
    name = "netKitOnly",
    description = "debugImplementation(netkit) pulls no FillKit",
    dependencies = listOf("$devkitGroup:netkit:$netKit"),
    mustContain = listOf("$devkitGroup:netkit", "$devkitGroup:core"),
    mustNotContain = listOf(
        "$devkitGroup:fillkit-api",
        "$devkitGroup:fillkit-engine",
        "$devkitGroup:fillkit-debug",
        "$devkitGroup:fillkit-testing",
        "$devkitGroup:devkit",
        "$devkitGroup:devkit-debug",
    ),
)

// ---- Case 2: FillKit alone -------------------------------------------------
verificationCase(
    name = "fillKitOnly",
    description = "debugImplementation(fillkit-debug) pulls no NetKit",
    dependencies = listOf("$devkitGroup:fillkit-debug:$fillKit"),
    mustContain = listOf(
        "$devkitGroup:fillkit-debug",
        "$devkitGroup:fillkit-api",
        "$devkitGroup:fillkit-engine",
        "$devkitGroup:core",
    ),
    mustNotContain = listOf("$devkitGroup:netkit", "$devkitGroup:devkit", "$devkitGroup:devkit-debug"),
)

// ---- Case 3: BOM + selected kits, no versions named ------------------------
verificationCase(
    name = "bom",
    description = "the BOM supplies versions for unversioned kit dependencies",
    dependencies = listOf(
        "platform:$devkitGroup:devkit-bom:$ecosystem",
        "$devkitGroup:netkit",
        "$devkitGroup:fillkit-api",
    ),
    mustContain = listOf("$devkitGroup:netkit", "$devkitGroup:fillkit-api", "$devkitGroup:devkit-bom"),
    mustNotContain = listOf("$devkitGroup:fillkit-debug", "$devkitGroup:devkit-debug"),
)

// ---- Case 4: the debug umbrella --------------------------------------------
verificationCase(
    name = "debugUmbrella",
    description = "devkit-debug brings every QA tool",
    dependencies = listOf("$devkitGroup:devkit-debug:$ecosystem"),
    mustContain = listOf(
        "$devkitGroup:devkit-debug",
        "$devkitGroup:netkit",
        "$devkitGroup:fillkit-debug",
        "$devkitGroup:fillkit-engine",
        "$devkitGroup:fillkit-api",
        "$devkitGroup:core",
    ),
    // Test helpers pull JUnit and Compose test rules; they stay out.
    mustNotContain = listOf("$devkitGroup:fillkit-testing", "$devkitGroup:devkit"),
)

// ---- Case 5: the production umbrella ---------------------------------------
verificationCase(
    name = "productionUmbrella",
    description = "devkit contains only release-safe libraries",
    dependencies = listOf("$devkitGroup:devkit:$ecosystem"),
    mustContain = listOf("$devkitGroup:devkit", "$devkitGroup:core", "$devkitGroup:fillkit-api"),
    // The assertion the release-safety model depends on.
    mustNotContain = listOf(
        "$devkitGroup:netkit",
        "$devkitGroup:fillkit-debug",
        "$devkitGroup:fillkit-engine",
        "$devkitGroup:fillkit-testing",
        "$devkitGroup:devkit-debug",
    ),
)

tasks.register("verifyAll") {
    group = "verification"
    description = "Resolves every documented DevKit installation model from mavenLocal()"
    dependsOn(
        "verifyNetKitOnly",
        "verifyFillKitOnly",
        "verifyBom",
        "verifyDebugUmbrella",
        "verifyProductionUmbrella",
        // Resolution proves the metadata; compiling proves the AARs contain the
        // classes their POMs promise, and the release check proves none of the
        // debug ones do.
        ":app:assembleDebug",
        ":app:assembleRelease",
        ":app:verifyReleaseIsClean",
    )
}
