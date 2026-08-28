package io.devkit.fillkit.engine.locale

import io.devkit.fillkit.FillLocalePack

/**
 * Curated, synthetic-safe locale modules shipped by FillKit.
 *
 * Grouped by region so the datasets can move into per-region artifacts later
 * without changing any public API: the registry only ever sees a list of packs.
 */
object BuiltInLocalePacks {
    val africa: List<FillLocalePack> get() = AfricaLocalePacks.all
    val europe: List<FillLocalePack> get() = EuropeLocalePacks.all
    val americas: List<FillLocalePack> get() = AmericasLocalePacks.all
    val asia: List<FillLocalePack> get() = AsiaLocalePacks.all
    val middleEast: List<FillLocalePack> get() = MiddleEastLocalePacks.all
    val oceania: List<FillLocalePack> get() = OceaniaLocalePacks.all

    val all: List<FillLocalePack> = africa + europe + americas + asia + middleEast + oceania
}
