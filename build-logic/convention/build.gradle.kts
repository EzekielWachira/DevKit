plugins {
    `kotlin-dsl`
}

// Gradle 9 runs on JDK 17+, and precompiled script plugins are compiled against
// the Gradle runtime rather than against the Android modules' Java 11 target.
// Setting it explicitly keeps the Kotlin and Java halves of the kotlin-dsl
// compilation agreeing, which they otherwise do not always do.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // `compileOnly`: the convention plugins configure AGP's extensions, but the
    // consuming module is what actually applies AGP. Depending on it at runtime
    // here would put a second copy on the classpath.
    compileOnly(libs.android.gradlePlugin)
}
