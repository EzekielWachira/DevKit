package io.devkit.fillkit.engine

import io.devkit.fillkit.FillDate
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillPersona
import io.devkit.fillkit.FillValue

data class FakeAddress(
    val street: String,
    val city: String,
    val region: String,
    val country: String,
    val postalCode: String,
)

data class FakeCompany(
    val name: String,
    val jobTitle: String,
    val website: String,
)

data class FakePersona(
    val firstName: String,
    val lastName: String,
    val email: String,
    val username: String,
    val phoneNumber: String,
    val dateOfBirth: FillDate,
    val age: Int,
    val address: FakeAddress,
    val company: FakeCompany,
) {
    val fullName: String get() = "$firstName $lastName"

    fun toFillPersona(id: String, name: String, localeCode: String): FillPersona = FillPersona(
        id = id,
        name = name,
        locale = FillLocale.Code(localeCode),
        values = linkedMapOf(
            "firstName" to FillValue.Text(firstName),
            "lastName" to FillValue.Text(lastName),
            "fullName" to FillValue.Text(fullName),
            "email" to FillValue.Text(email),
            "username" to FillValue.Text(username),
            "phone" to FillValue.Text(phoneNumber),
            "phoneNumber" to FillValue.Text(phoneNumber),
            "dateOfBirth" to FillValue.DateValue(dateOfBirth),
            "age" to FillValue.Integer(age),
            "streetAddress" to FillValue.Text(address.street),
            "city" to FillValue.Text(address.city),
            "region" to FillValue.Text(address.region),
            "country" to FillValue.Text(address.country),
            "postalCode" to FillValue.Text(address.postalCode),
            "companyName" to FillValue.Text(company.name),
            "jobTitle" to FillValue.Text(company.jobTitle),
            "website" to FillValue.Text(company.website),
        ),
        metadata = mapOf("source" to "generated"),
    )
}
