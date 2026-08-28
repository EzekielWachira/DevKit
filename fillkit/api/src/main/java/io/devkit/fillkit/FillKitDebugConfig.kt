package io.devkit.fillkit

/**
 * Application-owned navigation for QA and deep-link activations. FillKit never
 * knows how an application routes; it only asks for a form to be shown.
 */
fun interface FillKitLaunchNavigator {
    /** Return true when the application accepted responsibility for showing [request]'s form. */
    fun navigateTo(request: FillActivationRequest): Boolean
}

/**
 * Application-wide debug configuration. Everything here is only read by
 * `fillkit-debug`; in a release build the values are inert data.
 */
data class FillKitDebugConfig(
    /** Packs available before any screen composes, so the QA launcher has a catalog. */
    val packs: List<FillKitPack> = emptyList(),
    val navigator: FillKitLaunchNavigator? = null,
    /** Forms the navigator can reach. Used for QA availability reporting only. */
    val navigableForms: Set<String> = emptySet(),
    /** A pending activation older than this is discarded instead of surprising a later screen. */
    val pendingActivationTtlMillis: Long = DEFAULT_PENDING_TTL_MILLIS,
    /** Raw field injection over deep links stays off unless an application opts in. */
    val allowDeepLinkFieldOverrides: Boolean = false,
    val recentScenarioLimit: Int = 8,
) {
    init {
        require(pendingActivationTtlMillis > 0) { "pending activation TTL must be positive" }
        require(recentScenarioLimit >= 0) { "recent scenario limit cannot be negative" }
        requireUniqueIds("application FillKit pack", packs.map(FillKitPack::id))
    }

    companion object {
        const val DEFAULT_PENDING_TTL_MILLIS: Long = 5 * 60 * 1000
    }
}

class FillKitDebugConfigBuilder internal constructor(private var config: FillKitDebugConfig) {
    /** Keyed by id so re-running configuration (an Activity restart) is idempotent. */
    private val packs = config.packs.associateByTo(LinkedHashMap(), FillKitPack::id)

    fun packs(vararg values: FillKitPack) { values.forEach(::pack) }
    fun pack(value: FillKitPack) { packs[value.id] = value }

    /** The application decides how a form id maps to one of its destinations. */
    fun navigation(navigator: FillKitLaunchNavigator) { config = config.copy(navigator = navigator) }
    fun navigableForms(vararg formIds: String) { config = config.copy(navigableForms = formIds.toSet()) }
    fun pendingActivationTtlMillis(value: Long) { config = config.copy(pendingActivationTtlMillis = value) }
    fun allowDeepLinkFieldOverrides(value: Boolean) { config = config.copy(allowDeepLinkFieldOverrides = value) }
    fun recentScenarioLimit(value: Int) { config = config.copy(recentScenarioLimit = value) }

    internal fun build() = config.copy(packs = packs.values.toList())
}

/** Entry point for application-wide FillKit debug configuration. */
object FillKit {
    @Volatile
    var debugConfig: FillKitDebugConfig = FillKitDebugConfig()
        private set

    /**
     * Safe to call from an application's shared source set: without `fillkit-debug`
     * on the classpath nothing ever reads the result.
     */
    fun configureDebug(block: FillKitDebugConfigBuilder.() -> Unit) {
        debugConfig = FillKitDebugConfigBuilder(debugConfig).apply(block).build()
    }

    fun resetDebugConfig() {
        debugConfig = FillKitDebugConfig()
    }

    /**
     * Launches a scenario from application code — a QA screen, a debug drawer, an
     * internal shortcut. Safe in release: without `fillkit-debug` the request is
     * rejected instead of doing anything.
     */
    fun activate(request: FillActivationRequest): FillActivationResult =
        io.devkit.fillkit.runtime.FillKitRuntimeProvider.activate(request)
}

/**
 * Stable digest of everything that can change generated output for a given seed.
 *
 * Recorded in reproductions so a mismatch can be reported honestly instead of
 * pretending a seed reproduces data across arbitrary generator changes.
 */
fun FillKitConfig.configurationFingerprint(applicationPacks: List<FillKitPack> = emptyList()): String {
    val parts = buildList {
        add("engine=${FillSeedDerivation.ALGORITHM_VERSION}")
        add("spec=${FillReproductionSpec.VERSION}")
        (applicationPacks + packs).sortedBy(FillKitPack::id).forEach { add("kit=${it.coordinate()}") }
        allLocalePacks().sortedBy(FillLocalePack::code).forEach { add("locale=${it.coordinate()}") }
        allPersonaPacks().sortedBy(FillPersonaPack::id).forEach { add("persona=${it.coordinate()}") }
        allScenarioPacks().sortedBy(FillScenarioPack::id).forEach { add("scenario=${it.coordinate()}") }
        allGeneratorPacks().sortedBy(FillGeneratorPack::id).forEach { add("generator=${it.coordinate()}") }
        allGenerators().map { it.first }.sorted().forEach { add("gen=$it") }
        scenarios.map(FillScenario::id).sorted().forEach { add("localScenario=$it") }
    }
    @Suppress("SpreadOperator")
    return FillSeedDerivation.shortHash(*parts.toTypedArray())
}
