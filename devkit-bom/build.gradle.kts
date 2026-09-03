import io.devkit.gradle.devKitVersion

plugins {
    `java-platform`
    id("devkit.publish.platform")
}

/**
 * The DevKit BOM: one tested, compatible set of kit versions.
 *
 * A `java-platform` and not an Android library. A BOM carries constraints and
 * no code, and publishing one as an AAR would give consumers an artifact whose
 * `platform(...)` semantics Gradle does not apply.
 *
 * ### Constraints, not dependencies
 *
 * Adding the BOM pulls nothing. It states *which version* of a kit to use if
 * the consumer asks for that kit, which is what lets them omit versions:
 *
 * ```kotlin
 * implementation(platform("io.github.ezekielwachira.devkit:devkit-bom:0.1.0"))
 * implementation("io.github.ezekielwachira.devkit:fillkit-api")
 * debugImplementation("io.github.ezekielwachira.devkit:netkit")
 * ```
 *
 * ### Direction
 *
 * The BOM depends on nothing and nothing depends on the BOM. Kits must never
 * declare it — `netkit → devkit-bom → netkit` is a cycle, and the BOM is a
 * consumer-facing alignment tool rather than part of any library's own graph.
 *
 * Every artifact the ecosystem publishes is constrained, including the
 * umbrellas, so a consumer can take the BOM plus `devkit-debug` and still name
 * no versions at all.
 */
javaPlatform {
    // Constraints only; this platform never brings a dependency of its own.
    allowDependencies()
}

val group = providers.gradleProperty("devkit.group").get()
val coreVersion = devKitVersion("core")
val fillKitVersion = devKitVersion("fillkit")
val netKitVersion = devKitVersion("netkit")
val ecosystemVersion = devKitVersion("ecosystem")

dependencies {
    constraints {
        // Shared foundation
        api("$group:core:$coreVersion")

        // FillKit — four artifacts, one version. They are released together
        // because they are one library split by consumption scope, and a
        // mismatched pair would not compile against each other.
        api("$group:fillkit-api:$fillKitVersion")
        api("$group:fillkit-engine:$fillKitVersion")
        api("$group:fillkit-debug:$fillKitVersion")
        api("$group:fillkit-testing:$fillKitVersion")

        // NetKit — one artifact, its own version.
        api("$group:netkit:$netKitVersion")

        // Ecosystem aggregators, versioned as the set rather than as kits.
        api("$group:devkit:$ecosystemVersion")
        api("$group:devkit-debug:$ecosystemVersion")
    }
}

devKitPublishing {
    artifactId.set("devkit-bom")
    displayName.set("DevKit BOM")
    description.set(
        "Bill of materials for the DevKit libraries. Import it as a platform to use DevKit " +
            "artifacts without naming individual versions.",
    )
    versionKey.set("ecosystem")
}
