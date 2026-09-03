package io.devkit.netkit.scenario.preset

import io.devkit.netkit.scenario.model.NetworkScenario

/**
 * One field a preset needs filled in before it can build a scenario.
 *
 * Declared as data rather than drawn by the preset itself, so the same preset
 * definition serves the Compose wizard, a future IDE bridge and a plain
 * programmatic call. A preset that rendered its own form would be a preset that
 * only worked inside the debug console.
 *
 * @param key how the value is looked up in a [PresetConfiguration].
 * @param label the field's name in the wizard.
 * @param hint placeholder or explanation text.
 * @param default the value the wizard starts with.
 * @param kind how the wizard should render and validate it.
 * @param options the allowed values, for [PresetFieldKind.CHOICE].
 */
data class PresetField(
    val key: String,
    val label: String,
    val hint: String? = null,
    val default: String = "",
    val kind: PresetFieldKind = PresetFieldKind.TEXT,
    val options: List<String> = emptyList(),
) {
    init {
        require(key.isNotBlank()) { "NetKit preset field needs a key" }
        require(kind != PresetFieldKind.CHOICE || options.isNotEmpty()) {
            "NetKit preset field '$key' is a choice but offers no options"
        }
    }
}

/** How a [PresetField] is entered. */
enum class PresetFieldKind {
    /** Free text, e.g. a path. */
    TEXT,

    /** A whole number, e.g. a page number or a count. */
    NUMBER,

    /** One of [PresetField.options]. */
    CHOICE,

    /** A multi-line payload, e.g. a response fixture. */
    BODY,
}

/**
 * The values a preset was filled in with.
 *
 * A `Map<String, String>` behind a typed accessor rather than a bespoke class per
 * preset: presets are configuration-driven by design, the values come from text
 * fields, and the map round-trips into
 * [io.devkit.netkit.scenario.model.ScenarioPresetOrigin] so the wizard can be
 * reopened pre-populated.
 */
@JvmInline
value class PresetConfiguration(val values: Map<String, String> = emptyMap()) {

    /** The value for [key], or [fallback] when absent or blank. */
    fun text(key: String, fallback: String = ""): String =
        values[key]?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    /** The value for [key] as a number, or [fallback] when absent or unparseable. */
    fun number(key: String, fallback: Long): Long =
        values[key]?.trim()?.toLongOrNull() ?: fallback

    /** The value for [key] as an `Int`. */
    fun int(key: String, fallback: Int): Int =
        values[key]?.trim()?.toIntOrNull() ?: fallback

    /** The raw value, untrimmed — used for response bodies, where blanks matter. */
    fun raw(key: String, fallback: String = ""): String = values[key] ?: fallback

    companion object {
        /** The defaults declared by [fields]. */
        fun defaults(fields: List<PresetField>): PresetConfiguration =
            PresetConfiguration(fields.associate { it.key to it.default })
    }
}

/** Where a preset appears in the "new scenario" picker. */
enum class PresetCategory(val label: String) {
    AUTHENTICATION("Authentication"),
    PAGINATION("Pagination"),
    RELIABILITY("Reliability"),
    OTHER("Other"),
}

/**
 * A generator that turns a handful of answers into an ordinary scenario.
 *
 * ### The one rule presets must obey
 *
 * A preset **builds generic rules and then gets out of the way**. It is not
 * consulted at request time, it has no runtime behaviour, and nothing in the
 * engine knows it exists. The scenario it produces is exactly the scenario a
 * person could have built by hand in the rule editor — and, crucially, remains
 * editable afterwards.
 *
 * That constraint is what stops NetKit from growing a second execution engine.
 * The tempting alternative — an "auth scenario" type the interceptor recognises
 * and interprets — would mean two code paths that must agree, two sets of bugs,
 * and scenarios that stop working the day a preset is renamed. Instead, a
 * scenario generated from "Refresh token fails" keeps working forever, even if
 * that preset is deleted from the library, because the rules are complete.
 *
 * ### Implementing one
 *
 * ```kotlin
 * object BookingPaymentFailure : ScenarioPreset {
 *     override val id = "handypro.booking-payment"
 *     override val name = "Booking payment declined"
 *     override val category = PresetCategory.OTHER
 *     override val fields = listOf(
 *         PresetField("path", "Checkout endpoint", default = "/api/v1/bookings/checkout"),
 *     )
 *
 *     override fun build(configuration: PresetConfiguration) = scenarioFrom(
 *         name = name,
 *         rules = listOf(
 *             EndpointRule.forPath(
 *                 path = configuration.text("path"),
 *                 method = HttpMethod.POST,
 *                 action = NetworkAction.ReturnResponse(402),
 *             ),
 *         ),
 *     )
 * }
 * ```
 *
 * Register it with [ScenarioPresetRegistry] and it appears in the wizard
 * alongside the built-in ones. Implementations must be stateless and free of
 * Compose or Android types.
 */
interface ScenarioPreset {

    /** Stable identity, e.g. `auth.refresh-fails`. Stored as scenario provenance. */
    val id: String

    /** What the picker shows. */
    val name: String

    /** One sentence explaining what the generated scenario reproduces. */
    val description: String

    /** Where the picker groups this preset. */
    val category: PresetCategory

    /** What the wizard asks for. May be empty. */
    val fields: List<PresetField>

    /** Builds the scenario. Must produce rules that stand entirely on their own. */
    fun build(configuration: PresetConfiguration): NetworkScenario

    /** The configuration this preset starts with. */
    fun defaults(): PresetConfiguration = PresetConfiguration.defaults(fields)

    /**
     * Validation messages for [configuration], empty when it is usable.
     *
     * Checked before [build] is called, so [build] can assume sane input.
     */
    fun validate(configuration: PresetConfiguration): List<String> = emptyList()
}

/**
 * The presets available to the "new scenario" picker.
 *
 * Deliberately an instance rather than a global object, and deliberately free of
 * any dependency-injection framework: an application registers its own presets by
 * constructing a registry and handing it to the console, and NetKit itself
 * neither knows nor cares whether that happened through Hilt, Koin or a `val`.
 *
 * Thread-safe for reads; register everything at startup.
 */
class ScenarioPresetRegistry(
    presets: List<ScenarioPreset> = builtIn,
) {
    private val byId: Map<String, ScenarioPreset> = presets.associateBy { preset ->
        require(preset.id.isNotBlank()) { "NetKit preset '${preset.name}' needs an id" }
        preset.id
    }

    /** Every preset, in declaration order. */
    val all: List<ScenarioPreset> = presets

    /** The preset with [id], or `null`. */
    fun byId(id: String): ScenarioPreset? = byId[id]

    /** Presets grouped by category, in category declaration order. */
    fun byCategory(): List<Pair<PresetCategory, List<ScenarioPreset>>> =
        PresetCategory.entries
            .map { category -> category to all.filter { it.category == category } }
            .filter { it.second.isNotEmpty() }

    /** A registry with NetKit's own presets plus [additional]. */
    fun plus(additional: List<ScenarioPreset>): ScenarioPresetRegistry =
        ScenarioPresetRegistry(all + additional)

    companion object {
        /** Every preset NetKit ships. */
        val builtIn: List<ScenarioPreset> = AuthScenarioPresets.all + PaginationScenarioPresets.all
    }
}
