package io.devkit.fillkit

/** A named, application-owned set of deterministic form values. */
data class FillScenario(
    val id: String,
    val name: String,
    val values: Map<String, FillValue>,
)

/** Builds a [FillScenario] with type-explicit values. */
fun fillScenario(
    id: String,
    name: String,
    block: FillScenarioBuilder.() -> Unit,
): FillScenario {
    require(id.isNotBlank()) { "scenario id cannot be blank" }
    require(name.isNotBlank()) { "scenario name cannot be blank" }
    return FillScenarioBuilder().apply(block).build(id, name)
}

class FillScenarioBuilder internal constructor() {
    private val values = linkedMapOf<String, FillValue>()

    fun text(fieldId: String, value: String) = put(fieldId, FillValue.Text(value))
    fun integer(fieldId: String, value: Int) = put(fieldId, FillValue.Integer(value))
    fun decimal(fieldId: String, value: Double) = put(fieldId, FillValue.Decimal(value))
    fun boolean(fieldId: String, value: Boolean) = put(fieldId, FillValue.BooleanValue(value))
    fun date(fieldId: String, value: FillDate) = put(fieldId, FillValue.DateValue(value))

    private fun put(fieldId: String, value: FillValue) {
        require(fieldId.isNotBlank()) { "scenario field id cannot be blank" }
        require(values.put(fieldId, value) == null) { "duplicate scenario field id: $fieldId" }
    }

    internal fun build(id: String, name: String) = FillScenario(id, name, values.toMap())
}
