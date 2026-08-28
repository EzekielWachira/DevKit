package io.devkit.fillkit

/** Pure, serializable-friendly synthetic identity with arbitrary application values. */
data class FillPersona(
    val id: String,
    val name: String,
    val locale: FillLocale? = null,
    val values: Map<String, FillValue>,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "persona id cannot be blank" }
        require(name.isNotBlank()) { "persona name cannot be blank" }
        require(values.keys.none(String::isBlank)) { "persona value keys cannot be blank" }
    }

    fun value(key: String): FillValue? = values[key]
}

fun fillPersona(id: String, name: String, block: FillPersonaBuilder.() -> Unit): FillPersona =
    FillPersonaBuilder().apply(block).build(id, name)

class FillPersonaBuilder internal constructor() {
    private var locale: FillLocale? = null
    private val values = linkedMapOf<String, FillValue>()
    private val metadata = linkedMapOf<String, String>()

    fun locale(value: FillLocale) { locale = value }
    fun value(key: String, value: Any) = put(key, FillValue.of(value))
    fun firstName(value: String) = text("firstName", value)
    fun lastName(value: String) = text("lastName", value)
    fun fullName(value: String) = text("fullName", value)
    fun email(value: String) = text("email", value)
    fun phone(value: String) = text("phone", value)
    fun country(value: String) = text("country", value)
    fun city(value: String) = text("city", value)
    fun metadata(key: String, value: String) {
        require(key.isNotBlank()) { "persona metadata key cannot be blank" }
        require(metadata.put(key, value) == null) { "duplicate persona metadata key: $key" }
    }

    private fun text(key: String, value: String) = put(key, FillValue.Text(value))
    private fun put(key: String, value: FillValue) {
        require(key.isNotBlank()) { "persona value key cannot be blank" }
        require(values.put(key, value) == null) { "duplicate persona value key: $key" }
    }

    internal fun build(id: String, name: String) = FillPersona(id, name, locale, values.toMap(), metadata.toMap())
}

/** Optional version metadata used by configuration fingerprints and QA warnings. */
interface FillVersionedPack {
    val id: String
    val name: String
    val version: String?

    /** `pack@version` when a version exists, otherwise just the id. */
    fun coordinate(): String = version?.let { "$id@$it" } ?: id
}

data class FillPersonaPack(
    override val id: String,
    override val name: String,
    val personas: List<FillPersona>,
    override val version: String? = null,
) : FillVersionedPack {
    init {
        require(id.isNotBlank()) { "persona pack id cannot be blank" }
        require(name.isNotBlank()) { "persona pack name cannot be blank" }
        requireUniqueIds("persona", personas.map(FillPersona::id))
    }
}

fun personaPack(
    id: String,
    name: String,
    version: String? = null,
    block: FillPersonaPackBuilder.() -> Unit,
): FillPersonaPack = FillPersonaPackBuilder().apply(block).build(id, name, version)

class FillPersonaPackBuilder internal constructor() {
    private val personas = mutableListOf<FillPersona>()
    fun persona(value: FillPersona) { personas += value }
    internal fun build(id: String, name: String, version: String? = null) =
        FillPersonaPack(id, name, personas.toList(), version)
}

internal fun requireUniqueIds(kind: String, ids: List<String>) {
    val duplicate = ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
    require(duplicate == null) { "duplicate $kind id: $duplicate" }
}
