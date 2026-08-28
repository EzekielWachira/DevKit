package io.devkit.fillkit.engine

import io.devkit.fillkit.FillDate

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
}
