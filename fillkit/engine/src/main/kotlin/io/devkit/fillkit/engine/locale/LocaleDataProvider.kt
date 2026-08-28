package io.devkit.fillkit.engine.locale

data class LocaleData(
    val locale: String,
    val firstNames: List<String>,
    val lastNames: List<String>,
    val cities: List<String>,
    val regions: List<String>,
    val country: String,
    val countryCallingCode: String,
    val streetNames: List<String>,
    val companyPrefixes: List<String>,
    val companySuffixes: List<String>,
    val jobTitles: List<String>,
    val postalCodes: List<String>,
)

interface LocaleDataProvider {
    val data: LocaleData
}

internal object EnKeLocaleProvider : LocaleDataProvider {
    override val data = LocaleData(
        locale = "en-KE",
        firstNames = listOf("Amina", "Brian", "Faith", "Kevin", "David", "Mercy", "Wanjiku", "Otieno"),
        lastNames = listOf("Wanjiku", "Mwangi", "Njeri", "Otieno", "Kamau", "Achieng", "Kiptoo", "Mutua"),
        cities = listOf("Nairobi", "Mombasa", "Kisumu", "Nakuru", "Eldoret", "Thika", "Machakos"),
        regions = listOf("Nairobi", "Mombasa", "Kisumu", "Nakuru", "Uasin Gishu", "Kiambu", "Machakos"),
        country = "Kenya",
        countryCallingCode = "+254",
        streetNames = listOf("Acacia Way", "Savanna Lane", "Market Road", "Jamhuri Avenue", "Lake View Close"),
        companyPrefixes = listOf("Savanna", "Jua", "Imara", "Bahari", "Rift"),
        companySuffixes = listOf("Works Ltd", "Studio Ltd", "Services Ltd", "Labs Ltd", "Collective Ltd"),
        jobTitles = listOf("Product Designer", "Software Engineer", "Operations Lead", "Accountant", "Project Manager"),
        postalCodes = listOf("00100", "00200", "20100", "30100", "80100"),
    )
}

internal object EnUsLocaleProvider : LocaleDataProvider {
    override val data = LocaleData(
        locale = "en-US",
        firstNames = listOf("Olivia", "Noah", "Maya", "Ethan", "Sofia", "Liam", "Avery", "Jordan"),
        lastNames = listOf("Parker", "Reed", "Morgan", "Brooks", "Rivera", "Bennett", "Hayes", "Turner"),
        cities = listOf("Austin", "Portland", "Denver", "Madison", "Raleigh", "Phoenix"),
        regions = listOf("Texas", "Oregon", "Colorado", "Wisconsin", "North Carolina", "Arizona"),
        country = "United States",
        countryCallingCode = "+1",
        streetNames = listOf("Example Avenue", "Cedar Lane", "Market Street", "Sunset Drive", "Willow Court"),
        companyPrefixes = listOf("Northstar", "Cedar", "Bright", "Atlas", "Juniper"),
        companySuffixes = listOf("Works LLC", "Studio LLC", "Labs Inc", "Services LLC", "Collective Inc"),
        jobTitles = listOf("Product Manager", "Software Engineer", "Designer", "Analyst", "Operations Manager"),
        postalCodes = listOf("10001", "20001", "30301", "78701", "97201"),
    )
}

internal object EnGbLocaleProvider : LocaleDataProvider {
    override val data = LocaleData(
        locale = "en-GB",
        firstNames = listOf("Amelia", "Oliver", "Isla", "George", "Freya", "Harry", "Mia", "Theo"),
        lastNames = listOf("Taylor", "Davies", "Evans", "Wilson", "Thomas", "Roberts", "Clark", "Lewis"),
        cities = listOf("London", "Manchester", "Bristol", "Leeds", "Edinburgh", "Cardiff"),
        regions = listOf("Greater London", "Greater Manchester", "Bristol", "West Yorkshire", "Scotland", "Wales"),
        country = "United Kingdom",
        countryCallingCode = "+44",
        streetNames = listOf("Example Road", "Station Lane", "Kingfisher Close", "Meadow Way", "High Street"),
        companyPrefixes = listOf("Kingfisher", "Meadow", "North", "Thames", "Oak"),
        companySuffixes = listOf("Works Ltd", "Studio Ltd", "Labs Ltd", "Services Ltd", "Collective Ltd"),
        jobTitles = listOf("Product Manager", "Software Engineer", "Designer", "Consultant", "Operations Lead"),
        postalCodes = listOf("EC1A 1BB", "M1 1AE", "B1 1AA", "LS1 1UR", "EH1 1YZ"),
    )
}

class LocaleDataRegistry(
    providers: List<LocaleDataProvider> = listOf(EnKeLocaleProvider, EnUsLocaleProvider, EnGbLocaleProvider),
) {
    private val providersByTag = providers.associateBy { normalize(it.data.locale) }

    /** Exact tag, then matching region in English, then en-US. */
    fun resolve(requestedTag: String): LocaleData {
        val normalized = normalize(requestedTag)
        providersByTag[normalized]?.let { return it.data }
        val region = normalized.substringAfter('-', missingDelimiterValue = "")
        if (region.isNotEmpty()) providersByTag["en-$region"]?.let { return it.data }
        return providersByTag.getValue("en-US").data
    }

    private fun normalize(tag: String): String {
        val parts = tag.replace('_', '-').split('-')
        return if (parts.size >= 2) "${parts[0].lowercase()}-${parts[1].uppercase()}" else tag.lowercase()
    }
}
