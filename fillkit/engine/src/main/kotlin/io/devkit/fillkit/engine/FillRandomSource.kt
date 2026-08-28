package io.devkit.fillkit.engine

import io.devkit.fillkit.FillSeed
import io.devkit.fillkit.FillSeedDerivation
import kotlin.random.Random

/**
 * Hands out one independent random stream per stable namespace.
 *
 * A single globally consumed `Random(seed)` is not reproducible enough: inserting
 * a field shifts every later field's values. Every stream here is derived from
 * identifiers instead, so a field's values depend only on the master seed, the
 * generation counter and that field's own identity.
 */
class FillRandomSource(val seed: FillSeed, val generation: Int = 0) {

    fun stream(vararg namespace: String?): Random =
        Random(FillSeedDerivation.derive(seed.value, generation, *namespace))

    fun stream(namespace: List<String?>): Random =
        Random(FillSeedDerivation.derive(seed.value, generation, *namespace.toTypedArray()))

    fun withGeneration(value: Int): FillRandomSource =
        if (value == generation) this else FillRandomSource(seed, value)

    companion object {
        /** Namespace segments, kept as constants so a typo cannot silently reseed a stream. */
        const val PERSONA = "persona"
        const val FIELD = "field"
        const val VALUE = "value"
        const val GENERATOR = "generator"
        const val SCENARIO = "scenario"
        const val DEPENDENCY = "dep"
    }
}
