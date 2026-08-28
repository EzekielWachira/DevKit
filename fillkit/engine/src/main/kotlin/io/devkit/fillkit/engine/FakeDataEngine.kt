package io.devkit.fillkit.engine

import io.devkit.fillkit.FillDate
import io.devkit.fillkit.FillGenerationContext
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillType
import io.devkit.fillkit.engine.locale.LocaleData
import io.devkit.fillkit.engine.locale.LocaleDataRegistry
import kotlin.math.pow
import kotlin.math.round
import kotlin.random.Random

class FakeDataEngine(
    seed: Long? = null,
    requestedLocale: String = "en-US",
    localeRegistry: LocaleDataRegistry = LocaleDataRegistry(),
    private val personaGenerator: PersonaGenerator = PersonaGenerator(),
) {
    private val random = Random(seed ?: Random.nextLong())
    val locale: LocaleData = localeRegistry.resolve(requestedLocale)
    private val generators: List<TypeGenerator> = listOf(
        PersonTypeGenerator,
        ContactTypeGenerator,
        AddressTypeGenerator,
        BusinessTypeGenerator,
        GenericTypeGenerator,
    )

    fun newPersona(): FakePersona = personaGenerator.generate(random, locale)

    fun generationContext(fillLocale: FillLocale): FillGenerationContext =
        FillGenerationContext(fillLocale, random)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> generate(type: FillType<T>, persona: FakePersona): T {
        val generator = generators.firstOrNull { it.supports(type) }
            ?: error("No FillKit generator registered for ${type::class.simpleName}")
        return generator.generate(type, persona, locale, random) as T
    }
}

private interface TypeGenerator {
    fun supports(type: FillType<*>): Boolean
    fun generate(type: FillType<*>, persona: FakePersona, locale: LocaleData, random: Random): Any
}

private object PersonTypeGenerator : TypeGenerator {
    override fun supports(type: FillType<*>) = type === FillType.FirstName || type === FillType.LastName ||
        type === FillType.FullName || type === FillType.DateOfBirth || type === FillType.Age

    override fun generate(type: FillType<*>, persona: FakePersona, locale: LocaleData, random: Random): Any =
        when (type) {
            FillType.FirstName -> persona.firstName
            FillType.LastName -> persona.lastName
            FillType.FullName -> persona.fullName
            FillType.DateOfBirth -> persona.dateOfBirth
            FillType.Age -> persona.age
            else -> error("Unsupported person type")
        }
}

private object ContactTypeGenerator : TypeGenerator {
    override fun supports(type: FillType<*>) = type === FillType.Email || type === FillType.Username ||
        type is FillType.PhoneNumber || type === FillType.Website || type === FillType.Url || type is FillType.Password

    override fun generate(type: FillType<*>, persona: FakePersona, locale: LocaleData, random: Random): Any =
        when (type) {
            FillType.Email -> persona.email
            FillType.Username -> persona.username
            is FillType.PhoneNumber -> if (type.countryCode == null || type.countryCode.equals(locale.locale.takeLast(2), true)) {
                persona.phoneNumber
            } else {
                val countryCode = requireNotNull(type.countryCode)
                val callingCode = mapOf("KE" to "+254", "US" to "+1", "GB" to "+44")[countryCode.uppercase()]
                    ?: locale.countryCallingCode
                PersonaGenerator.phone(random, callingCode)
            }
            FillType.Website -> persona.company.website
            FillType.Url -> "https://${persona.username}.example.net/profile"
            is FillType.Password -> password(type, random)
            else -> error("Unsupported contact type")
        }

    private fun password(type: FillType.Password, random: Random): String {
        val categories = buildList {
            if (type.uppercase) add("ABCDEFGHJKLMNPQRSTUVWXYZ")
            if (type.lowercase) add("abcdefghijkmnopqrstuvwxyz")
            if (type.digits) add("23456789")
            if (type.specialCharacters) add("!@#%+-_")
        }
        val length = random.nextInt(type.minLength, type.maxLength + 1)
        val chars = categories.map { it.random(random) }.toMutableList()
        val all = categories.joinToString("")
        while (chars.size < length) chars += all.random(random)
        chars.shuffle(random)
        return chars.joinToString("")
    }
}

private object AddressTypeGenerator : TypeGenerator {
    override fun supports(type: FillType<*>) = type === FillType.StreetAddress || type === FillType.City ||
        type === FillType.Region || type === FillType.Country || type === FillType.PostalCode

    override fun generate(type: FillType<*>, persona: FakePersona, locale: LocaleData, random: Random): Any = when (type) {
        FillType.StreetAddress -> persona.address.street
        FillType.City -> persona.address.city
        FillType.Region -> persona.address.region
        FillType.Country -> persona.address.country
        FillType.PostalCode -> persona.address.postalCode
        else -> error("Unsupported address type")
    }
}

private object BusinessTypeGenerator : TypeGenerator {
    override fun supports(type: FillType<*>) = type === FillType.CompanyName || type === FillType.JobTitle

    override fun generate(type: FillType<*>, persona: FakePersona, locale: LocaleData, random: Random): Any = when (type) {
        FillType.CompanyName -> persona.company.name
        FillType.JobTitle -> persona.company.jobTitle
        else -> error("Unsupported business type")
    }
}

private object GenericTypeGenerator : TypeGenerator {
    override fun supports(type: FillType<*>) = type is FillType.Text || type is FillType.Integer ||
        type is FillType.Decimal || type is FillType.BooleanValue || type is FillType.Date || type is FillType.Selection

    override fun generate(type: FillType<*>, persona: FakePersona, locale: LocaleData, random: Random): Any = when (type) {
        is FillType.Text -> text(type, random)
        is FillType.Integer -> random.nextInt(type.range.first, type.range.last + 1)
        is FillType.Decimal -> {
            val factor = 10.0.pow(type.decimalPlaces)
            round((random.nextDouble() * (type.range.endInclusive - type.range.start) + type.range.start) * factor) / factor
        }
        is FillType.BooleanValue -> random.nextFloat() < type.probabilityTrue
        is FillType.Date -> {
            val min = DateMath.ordinal(type.min)
            val max = DateMath.ordinal(type.max)
            DateMath.fromOrdinal(random.nextInt(min, max + 1))
        }
        is FillType.Selection -> type.options.random(random)
        else -> error("Unsupported generic type")
    }

    private fun text(type: FillType.Text, random: Random): String {
        if (type.maxLength == 0) return ""
        val words = listOf("sample", "bright", "useful", "testing", "compose", "synthetic", "value")
        val target = random.nextInt(type.minLength, type.maxLength + 1)
        val source = buildString {
            while (length < target) {
                if (isNotEmpty()) append(' ')
                append(words.random(random))
            }
        }
        return source.take(target).padEnd(target, 'x')
    }
}
