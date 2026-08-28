package io.devkit.fillkit

import kotlin.random.Random
import kotlin.reflect.KClass

/** Context visible to a custom generator without exposing Android or UI state. */
interface FillGeneratorScope {
    val random: Random
    val locale: FillLocalePack
    val persona: FillPersona?

    fun <T> oneOf(vararg values: T): T
    fun <T> weighted(vararg values: Pair<T, Int>): T
    fun integer(min: Int, max: Int): Int
    fun decimal(min: Double, max: Double): Double
    fun boolean(probabilityTrue: Float = 0.5f): Boolean
    fun chance(probability: Float): Boolean = boolean(probability)
    fun text(length: Int, alphabet: String = DEFAULT_ALPHABET): String
    fun <T> optional(probability: Float = 0.5f, block: FillGeneratorScope.() -> T): T?
    fun <T> repeat(count: Int, block: FillGeneratorScope.(Int) -> T): List<T>
    fun <T : Any> generate(id: String, valueClass: KClass<T>): T

    companion object {
        const val DEFAULT_ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}

/** V1 compatibility context for map-keyed generators. Prefer [FillGeneratorScope]. */
data class FillGenerationContext(
    val locale: FillLocale,
    val random: Random,
)

inline fun <reified T : Any> FillGeneratorScope.generate(id: String): T = generate(id, T::class)

/** A typed, identified generator suitable for registration and composition. */
class FillGenerator<T : Any>(
    val id: String,
    val valueClass: KClass<T>,
    private val block: FillGeneratorScope.() -> T,
) {
    init {
        require(id.isNotBlank()) { "generator id cannot be blank" }
    }

    fun generate(scope: FillGeneratorScope): T = block(scope)

    /** Keeps `FillGenerator<T> { context -> ... }` source-compatible with FillKit 0.1. */
    @Suppress("UNCHECKED_CAST")
    constructor(legacy: (FillGenerationContext) -> T) : this(
        id = "legacy-map-generator",
        valueClass = Any::class as KClass<T>,
        block = { legacy(FillGenerationContext(FillLocale.Code(locale.code), random)) },
    )
}

inline fun <reified T : Any> fillGenerator(
    id: String,
    noinline block: FillGeneratorScope.() -> T,
): FillGenerator<T> = FillGenerator(id, T::class, block)

data class FillGeneratorPack(
    override val id: String,
    override val name: String,
    val generators: List<FillGenerator<*>>,
    override val version: String? = null,
) : FillVersionedPack {
    init {
        require(id.isNotBlank()) { "generator pack id cannot be blank" }
        require(name.isNotBlank()) { "generator pack name cannot be blank" }
        requireUniqueIds("generator", generators.map(FillGenerator<*>::id))
    }
}

fun generatorPack(
    id: String,
    name: String,
    version: String? = null,
    block: FillGeneratorPackBuilder.() -> Unit,
): FillGeneratorPack = FillGeneratorPackBuilder().apply(block).build(id, name, version)

class FillGeneratorPackBuilder internal constructor() {
    private val generators = mutableListOf<FillGenerator<*>>()
    fun generator(value: FillGenerator<*>) { generators += value }
    internal fun build(id: String, name: String, version: String? = null) =
        FillGeneratorPack(id, name, generators.toList(), version)
}
