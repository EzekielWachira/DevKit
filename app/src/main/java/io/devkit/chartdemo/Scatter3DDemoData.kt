package io.devkit.chartdemo

/**
 * Deterministic observations for the 3D scatter demos.
 *
 * ### Generated, and never randomly
 *
 * §149 asks for local data and no network, and there is a second reason to
 * generate it with a fixed sequence rather than with `Random()`: a demo that
 * looked different on every launch would make a rendering regression impossible
 * to spot by eye, and would make the screenshot and instrumentation tests
 * either flaky or useless. The generator below is a plain linear congruential
 * step, seeded per dataset, so every run of every build draws the same cloud.
 *
 * The numbers are shaped to look like something a reader could reason about —
 * three correlated survey variables — because a uniform cube of noise hides
 * exactly the structure a 3D scatter exists to reveal.
 */
object Scatter3DDemoData {

    /** One respondent: three measurements, plus two optional encodings. */
    data class Observation(
        val id: Int,
        val age: Double,
        val income: Double,
        val satisfaction: Double,
        val household: Double,
        val tenure: Double,
    )

    /** One reading from a rig: three physical quantities. */
    data class Reading(
        val id: Int,
        val temperature: Double,
        val pressure: Double,
        val humidity: Double,
    )

    /** The main survey: a hundred and twenty respondents. */
    val survey: List<Observation> = generateSurvey(seed = 20_240_119L, count = 120)

    /** Two groups over the same three variables, for the multi-series demo. */
    val control: List<Observation> = generateSurvey(seed = 5_150L, count = 70, incomeShift = -12_000.0)
    val treated: List<Observation> = generateSurvey(seed = 9_931L, count = 70, incomeShift = 18_000.0)

    /**
     * A large cloud, for the render-mode demo.
     *
     * Deliberately noisier than [survey]. At five thousand points a tight
     * correlation draws as one solid slab, which demonstrates that the chart
     * can hold the data and nothing about whether it can show its *depth* — and
     * depth is the whole subject. The wider spread keeps the cloud a volume.
     */
    val large: List<Observation> =
        generateSurvey(seed = 77_003L, count = 5_000, spread = 3.4, depthSpread = 1.0)

    /** A handful of readings, small enough for cube markers to be legible. */
    val readings: List<Reading> = List(48) { index ->
        val step = index / 48.0
        Reading(
            id = index,
            temperature = 12.0 + step * 26.0 + ((index * 37) % 11) * 0.4,
            pressure = 990.0 + ((index * 53) % 41) * 0.9,
            humidity = 30.0 + ((index * 29) % 61),
        )
    }

    /**
     * A survey-shaped cloud: income rises with age and satisfaction falls off
     * at both ends of it, with enough spread that the trend is a trend rather
     * than a line.
     */
    private fun generateSurvey(
        seed: Long,
        count: Int,
        incomeShift: Double = 0.0,
        spread: Double = 1.0,
        depthSpread: Double = 0.0,
    ): List<Observation> {
        var state = seed
        fun next(): Double {
            // A plain 48-bit LCG, the same one `java.util.Random` uses, run
            // here rather than through `Random` so the sequence is stated in
            // this file and cannot change with a platform version.
            state = (state * 0x5DEECE66DL + 0xB) and ((1L shl 48) - 1)
            return (state ushr 16).toDouble() / (1L shl 32).toDouble()
        }
        return List(count) { index ->
            val age = 18.0 + next() * 62.0
            val base = 22_000.0 + (age - 18.0) * 1_650.0
            val income = (base + (next() - 0.5) * 46_000.0 * spread + incomeShift)
                .coerceAtLeast(9_000.0)
            val mid = 1.0 - kotlin.math.abs(age - 46.0) / 34.0
            val satisfaction = (
                38.0 + mid * 38.0 * (1.0 - depthSpread) +
                    (next() - 0.5) * (28.0 + depthSpread * 90.0)
                ).coerceIn(0.0, 100.0)
            Observation(
                id = index,
                age = age,
                income = income,
                satisfaction = satisfaction,
                household = 1.0 + (next() * 5.0),
                tenure = next() * 30.0,
            )
        }
    }
}
