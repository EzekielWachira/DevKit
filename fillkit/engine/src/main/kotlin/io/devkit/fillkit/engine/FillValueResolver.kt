package io.devkit.fillkit.engine

import io.devkit.fillkit.FillDate
import io.devkit.fillkit.FillGenerator
import io.devkit.fillkit.FillGeneratorScope
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillLocalePack
import io.devkit.fillkit.FillPersona
import io.devkit.fillkit.FillScenario
import io.devkit.fillkit.FillScenarioGenerator
import io.devkit.fillkit.FillType
import io.devkit.fillkit.FillValue
import io.devkit.fillkit.generatorId
import kotlin.math.pow
import kotlin.math.round
import kotlin.random.Random
import kotlin.reflect.KClass

/** All inputs needed to resolve one field without UI or Android dependencies. */
data class FillResolutionRequest<T : Any>(
    val fieldId: String,
    val type: FillType<T>,
    val scenario: FillScenario? = null,
    val persona: FillPersona? = null,
    val fieldGenerator: FillGenerator<T>? = null,
)

/**
 * Central precedence engine:
 * scenario value → scenario generator → field generator → saved persona →
 * field/type registered generator → built-in type generator.
 */
class FillValueResolver(
    val locale: FillLocalePack,
    generators: List<Pair<String, FillGenerator<*>>> = emptyList(),
    seed: Long? = null,
) {
    private val random = Random(seed ?: Random.nextLong())
    private val registered = linkedMapOf<String, FillGenerator<*>>().apply {
        generators.forEach { (id, generator) -> this[id] = generator }
    }.toMap()
    private val dependencyStack = ThreadLocal.withInitial { mutableListOf<String>() }

    @Synchronized
    fun generatedPersona(id: String = "generated", name: String = "Random"): FillPersona =
        PersonaGenerator().generate(random, locale).toFillPersona(id, name, locale.code)

    @Synchronized
    fun <T : Any> resolve(request: FillResolutionRequest<T>, generatedPersona: FillPersona): T {
        request.scenario?.values?.get(request.fieldId)?.let { return checked(request.type, it.raw, "scenario value") }
        request.scenario?.generators?.get(request.fieldId)?.let {
            return checked(request.type, scenarioValue(it, request.persona, generatedPersona), "scenario generator")
        }
        request.fieldGenerator?.let { return checked(request.type, run(it, request.persona), "field generator") }
        request.persona?.values?.get(request.fieldId)?.let { return checked(request.type, it.raw, "persona value") }

        val effective = effectivePersona(generatedPersona, request.persona)
        if (request.persona != null && request.type !is FillType.Custom<*>) {
            return checked(request.type, builtIn(request.type, effective), "persona-aware generator")
        }
        registered[request.fieldId]?.let { return checked(request.type, runUntyped(it, request.persona), "field-id generator") }
        registered[request.type.generatorId()]?.let { return checked(request.type, runUntyped(it, request.persona), "type generator") }
        return checked(request.type, builtIn(request.type, effective), "built-in generator")
    }

    private fun scenarioValue(value: FillScenarioGenerator, persona: FillPersona?, generated: FillPersona): Any = when (value) {
        is FillScenarioGenerator.Registered -> runRegistered(value.generatorId, persona)
        is FillScenarioGenerator.Inline -> runUntyped(value.generator, persona)
        is FillScenarioGenerator.Type -> builtIn(value.type, effectivePersona(generated, persona))
    }

    private fun runRegistered(id: String, persona: FillPersona?): Any =
        registered[id]?.let { runUntyped(it, persona) }
            ?: throw IllegalArgumentException("unknown FillKit generator: $id")

    private fun runUntyped(generator: FillGenerator<*>, persona: FillPersona?): Any = runWithCycle(generator.id) {
        generator.generate(scope(persona))
    }

    private fun <T : Any> run(generator: FillGenerator<T>, persona: FillPersona?): T = runWithCycle(generator.id) {
        generator.generate(scope(persona))
    }

    private fun scope(persona: FillPersona?) = object : FillGeneratorScope {
        override val random: Random get() = this@FillValueResolver.random
        override val locale: FillLocalePack get() = this@FillValueResolver.locale
        override val persona: FillPersona? = persona

        override fun <T> oneOf(vararg values: T): T {
            require(values.isNotEmpty()) { "oneOf requires at least one value" }
            return values[random.nextInt(values.size)]
        }

        override fun <T> weighted(vararg values: Pair<T, Int>): T {
            require(values.isNotEmpty()) { "weighted requires at least one value" }
            require(values.all { it.second > 0 }) { "weighted values must have positive weights" }
            val total = values.sumOf(Pair<T, Int>::second)
            var cursor = random.nextInt(total)
            values.forEach { (value, weight) ->
                if (cursor < weight) return value
                cursor -= weight
            }
            return values.last().first
        }

        override fun integer(min: Int, max: Int): Int {
            require(min <= max) { "integer requires min <= max" }
            return if (min == max) min else random.nextLong(min.toLong(), max.toLong() + 1L).toInt()
        }

        override fun decimal(min: Double, max: Double): Double {
            require(min.isFinite() && max.isFinite() && min <= max) { "decimal requires a finite min <= max" }
            return if (min == max) min else random.nextDouble(min, max)
        }

        override fun boolean(probabilityTrue: Float): Boolean {
            require(probabilityTrue in 0f..1f) { "probability must be in 0..1" }
            return random.nextFloat() < probabilityTrue
        }

        override fun text(length: Int, alphabet: String): String {
            require(length >= 0) { "text length cannot be negative" }
            require(alphabet.isNotEmpty()) { "text alphabet cannot be empty" }
            return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }

        override fun <T> optional(probability: Float, block: FillGeneratorScope.() -> T): T? =
            if (boolean(probability)) block(this) else null

        override fun <T> repeat(count: Int, block: FillGeneratorScope.(Int) -> T): List<T> {
            require(count >= 0) { "repeat count cannot be negative" }
            return List(count) { block(this, it) }
        }

        override fun <T : Any> generate(id: String, valueClass: KClass<T>): T {
            val value = runRegistered(id, persona)
            require(valueClass.isInstance(value)) {
                "generator $id returned ${value::class.simpleName}, expected ${valueClass.simpleName}"
            }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
    }

    private fun effectivePersona(generated: FillPersona, active: FillPersona?): FillPersona {
        if (active == null) return generated
        val values = generated.values.toMutableMap().apply { putAll(active.values) }
        val first = (active.values["firstName"] as? FillValue.Text)?.value
        val last = (active.values["lastName"] as? FillValue.Text)?.value
        if (first != null && last != null) {
            val normalized = PersonaGenerator.normalize("$first.$last")
            if ("fullName" !in active.values) values["fullName"] = FillValue.Text("$first $last")
            if ("username" !in active.values) values["username"] = FillValue.Text(normalized)
            if ("email" !in active.values) values["email"] = FillValue.Text("$normalized@example.com")
        }
        return active.copy(values = values)
    }

    private fun builtIn(type: FillType<*>, persona: FillPersona): Any = when (type) {
        FillType.FirstName -> persona.raw("firstName")
        FillType.LastName -> persona.raw("lastName")
        FillType.FullName -> persona.raw("fullName")
        FillType.Username -> persona.raw("username")
        FillType.DateOfBirth -> persona.raw("dateOfBirth")
        FillType.Age -> persona.raw("age")
        FillType.Email -> persona.raw("email")
        is FillType.PhoneNumber -> type.countryCode.let { requestedCountry ->
            if (requestedCountry == null || locale.code.endsWith(requestedCountry, true)) {
                persona.raw("phone")
            } else {
                val code = callingCodes[requestedCountry.uppercase()] ?: requireNotNull(locale.phone).countryCode
                PersonaGenerator.phone(random, code)
            }
        }
        FillType.StreetAddress -> persona.raw("streetAddress")
        FillType.City -> persona.raw("city")
        FillType.Region -> persona.raw("region")
        FillType.Country -> persona.raw("country")
        FillType.PostalCode -> persona.raw("postalCode")
        FillType.CompanyName -> persona.raw("companyName")
        FillType.JobTitle -> persona.raw("jobTitle")
        FillType.Website -> persona.raw("website")
        FillType.Url -> "https://${persona.raw("username")}.example.net/profile"
        is FillType.Password -> password(type)
        is FillType.Text -> text(type)
        is FillType.Integer -> if (type.range.first == type.range.last) type.range.first else {
            random.nextLong(type.range.first.toLong(), type.range.last.toLong() + 1L).toInt()
        }
        is FillType.Decimal -> {
            val factor = 10.0.pow(type.decimalPlaces)
            round((random.nextDouble() * (type.range.endInclusive - type.range.start) + type.range.start) * factor) / factor
        }
        is FillType.BooleanValue -> random.nextFloat() < type.probabilityTrue
        is FillType.Date -> DateMath.fromOrdinal(random.nextInt(DateMath.ordinal(type.min), DateMath.ordinal(type.max) + 1))
        is FillType.Selection -> type.options.random(random)
        is FillType.Custom<*> -> throw IllegalArgumentException("no custom generator registered for key \"${type.key}\"")
    }

    private fun FillPersona.raw(key: String): Any = requireNotNull(values[key]) { "persona value missing after fallback: $key" }.raw

    private fun password(type: FillType.Password): String {
        val categories = buildList {
            if (type.uppercase) add("ABCDEFGHJKLMNPQRSTUVWXYZ")
            if (type.lowercase) add("abcdefghijkmnopqrstuvwxyz")
            if (type.digits) add("23456789")
            if (type.specialCharacters) add("!@#%+-_")
        }
        val length = if (type.minLength == type.maxLength) type.minLength else random.nextInt(type.minLength, type.maxLength + 1)
        val chars = categories.map { it.random(random) }.toMutableList()
        while (chars.size < length) chars += categories.joinToString("").random(random)
        chars.shuffle(random)
        return chars.joinToString("")
    }

    private fun text(type: FillType.Text): String {
        if (type.maxLength == 0) return ""
        val target = if (type.minLength == type.maxLength) type.minLength else random.nextInt(type.minLength, type.maxLength + 1)
        val words = listOf("sample", "bright", "useful", "testing", "compose", "synthetic", "value")
        return buildString {
            while (length < target) {
                if (isNotEmpty()) append(' ')
                append(words.random(random))
            }
        }.take(target).padEnd(target, 'x')
    }

    private fun <T> runWithCycle(id: String, block: () -> T): T {
        val stack = requireNotNull(dependencyStack.get())
        if (id in stack) {
            val cycle = (stack.dropWhile { it != id } + id).joinToString(" -> ")
            throw IllegalStateException("FillKit generator dependency cycle detected: $cycle")
        }
        stack += id
        return try { block() } finally { stack.removeAt(stack.lastIndex) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> checked(type: FillType<T>, value: Any, source: String): T {
        val expected = type.valueClass()
        require(expected.isInstance(value)) {
            "$source returned ${value::class.simpleName}, expected ${expected.simpleName}"
        }
        return value as T
    }

    companion object {
        private val callingCodes = mapOf(
            "KE" to "+254", "NG" to "+234", "UG" to "+256", "TZ" to "+255", "RW" to "+250",
            "GH" to "+233", "ZA" to "+27", "US" to "+1", "GB" to "+44", "CA" to "+1", "AU" to "+61",
        )
    }
}

private fun FillType<*>.valueClass(): KClass<*> = when (this) {
    FillType.Age, is FillType.Integer -> Int::class
    is FillType.Decimal -> Double::class
    is FillType.BooleanValue -> Boolean::class
    FillType.DateOfBirth, is FillType.Date -> FillDate::class
    is FillType.Custom<*> -> valueClass
    else -> String::class
}
