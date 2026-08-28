package io.devkit.fillkit.engine.locale

import io.devkit.fillkit.FillLocalePack
import io.devkit.fillkit.FillLocaleRegion.Oceania

internal object OceaniaLocalePacks {
    val all: List<FillLocalePack> = listOf(
        localePack(
            "en-AU", "Australia — English", Oceania, "Australia", "+61", "4", 8, listOf(3, 3, 3),
            given = "Charlotte,Oliver,Amelia,Jack,Isla,Noah,Mia,Henry",
            family = "Smith,Jones,Williams,Brown,Wilson,Taylor,Nguyen,Martin",
            cities = "Sydney,Melbourne,Brisbane,Perth,Adelaide,Canberra",
            areas = "New South Wales,Victoria,Queensland,Western Australia,South Australia,ACT",
            areaLabel = "State",
            postal = "2000,3000,4000,6000,5000,2600",
            streets = "Wattle Street,Harbour Road,Banksia Close",
            companySuffixes = "Pty Ltd,Ltd,Co.",
            jobTitles = "Software Engineer,Operations Lead,Electrician,Project Manager",
            currency = "AUD",
        ),
        localePack(
            "en-NZ", "New Zealand — English", Oceania, "New Zealand", "+64", "2", 8, listOf(2, 3, 4),
            given = "Charlotte,Jack,Isla,Oliver,Amelia,Leo,Mia,Hunter",
            family = "Smith,Williams,Brown,Wilson,Taylor,Anderson,Thompson,Walker",
            cities = "Auckland,Wellington,Christchurch,Hamilton,Dunedin,Tauranga",
            areas = "Auckland,Wellington,Canterbury,Waikato,Otago,Bay of Plenty",
            areaLabel = "Region",
            postal = "1010,6011,8011,3204",
            streets = "Kauri Road,Harbour View Close,Rata Street",
            companySuffixes = "Limited,Ltd,Co.",
            jobTitles = "Software Engineer,Operations Lead,Electrician,Project Manager",
            currency = "NZD",
        ),
    )
}
