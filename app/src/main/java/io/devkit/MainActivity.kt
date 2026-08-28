package io.devkit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.devkit.fillkit.FillDate
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillKitHost
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillType
import io.devkit.fillkit.fillGenerator
import io.devkit.fillkit.fillKit
import io.devkit.fillkit.fillKitSuggestion
import io.devkit.fillkit.fillKitPack
import io.devkit.fillkit.fillLocalePack
import io.devkit.fillkit.fillPersona
import io.devkit.fillkit.fillScenario
import io.devkit.fillkit.generatorPack
import io.devkit.fillkit.personaPack
import io.devkit.fillkit.scenarioPack
import io.devkit.ui.theme.DevKitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DevKitTheme { FillKitSampleApp() } }
    }
}

private val returningCustomer = fillPersona("returning-customer", "Returning Customer") {
    locale(FillLocale.Code("en-KE"))
    firstName("Amina")
    lastName("Wanjiku")
    email("amina.wanjiku@example.com")
    phone("+254712345678")
    city("Nairobi")
    country("Kenya")
}

private val newProvider = fillPersona("new-provider", "New Provider") {
    firstName("Neema")
    lastName("Kamau")
    email("neema.kamau@example.com")
    value("profession", "Plumber")
}

private val experiencedProvider = fillPersona("experienced-provider", "Experienced Provider") {
    firstName("Brian")
    lastName("Mwangi")
    email("brian.mwangi@example.com")
    value("profession", "Electrician")
    value("yearsExperience", 12)
    value("businessType", "Company")
}

private val internationalCustomer = fillPersona("international-customer", "International Customer") {
    locale(FillLocale.Code("en-GB"))
    firstName("Amelia")
    lastName("Taylor")
    email("amelia.taylor@example.com")
    phone("+447123456789")
    city("London")
    country("United Kingdom")
}

private val samplePersonas = personaPack("sample-personas", "Sample Personas") {
    persona(returningCustomer)
    persona(newProvider)
    persona(experiencedProvider)
    persona(internationalCustomer)
}

private val professionGenerator = fillGenerator<String>("profession") {
    oneOf("Plumber", "Electrician", "Cleaner", "Carpenter")
}

private val serviceGenerator = fillGenerator<String>("service") {
    oneOf("House Cleaning", "Plumbing Repair", "Electrical Installation", "Furniture Assembly")
}

private val businessNameGenerator = fillGenerator<String>("business-name") {
    "${oneOf("Savanna", "Urban", "Prime", "Metro")} ${oneOf("Cleaning", "Electrical", "Plumbing", "Repairs")} Ltd"
}

private val providerGenerators = generatorPack("home-services", "Home Services") {
    generator(professionGenerator)
    generator(serviceGenerator)
    generator(businessNameGenerator)
}

private val happyPath = fillScenario("happy-path", "Happy Path") {
    persona("returning-customer")
    text("password", "FillKit-Test-42!")
    boolean("acceptTerms", true)
}

private val providerScenarios = scenarioPack("provider", "Provider") {
    scenario(happyPath)
    scenario("experienced-provider", "Experienced Provider") {
        persona("experienced-provider")
        generated("businessName", "business-name")
        generated("service", "service")
    }
}

private val validationScenarios = scenarioPack("validation", "Validation") {
    scenario("minimum-values", "Minimum Values") {
        text("firstName", "A")
        text("lastName", "B")
        text("email", "a@b.co")
    }
    scenario("long-values", "Long Values") {
        include(happyPath)
        text("firstName", "Amina-Extremely-Long-Synthetic-First-Name")
        text("businessName", "Savanna International Home Services and Property Maintenance Collective Limited")
    }
    scenario("validation-errors", "Validation Errors") {
        text("email", "not-an-email")
        text("phone", "123")
        text("password", "short")
        boolean("acceptTerms", false)
    }
}

private val swahiliKenya = fillLocalePack("sw-KE", "Kenya — Swahili") {
    firstNames("Amina", "Baraka", "Neema", "Juma")
    lastNames("Mwangi", "Wanjiku", "Otieno", "Kamau")
    cities("Nairobi", "Mombasa", "Kisumu")
    phone { countryCode = "+254"; formats("7########", "1########") }
}

private val sampleTestingPack = fillKitPack("sample-testing", "Sample Testing") {
    locale(swahiliKenya)
    generators(providerGenerators)
    personas(samplePersonas)
    scenarios(providerScenarios)
    scenarios(validationScenarios)
}

private enum class SampleScreen(val title: String, val shortLabel: String) {
    Registration("Registration", "R"),
    Business("Business onboarding", "B"),
    Checkout("Checkout address", "C"),
    SmartFields("Smart Fields", "S"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FillKitSampleApp() {
    var screen by remember { mutableStateOf(SampleScreen.Registration) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(screen.title) }) },
        bottomBar = {
            NavigationBar {
                SampleScreen.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = screen == destination,
                        onClick = { screen = destination },
                        icon = { Text(destination.shortLabel) },
                        label = { Text(destination.title.substringBefore(' ')) },
                    )
                }
            }
        },
    ) { padding ->
        when (screen) {
            SampleScreen.Registration -> RegistrationExample(Modifier.padding(padding))
            SampleScreen.Business -> BusinessExample(Modifier.padding(padding))
            SampleScreen.Checkout -> CheckoutExample(Modifier.padding(padding))
            SampleScreen.SmartFields -> SmartFieldsExample(Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartFieldsExample(modifier: Modifier = Modifier) {
    val username = rememberTextFieldState()
    val email = rememberTextFieldState()
    val phone = rememberTextFieldState()
    val firstName = rememberTextFieldState()
    val city = rememberTextFieldState()
    val ambiguousCode = rememberTextFieldState()
    val otp = rememberTextFieldState()
    FillKitHost(
        "smart-fields",
        modifier = modifier.fillMaxSize(),
        config = FillKitConfig(
            locale = FillLocale.Code("en-KE"),
            suggestionMode = io.devkit.fillkit.FieldSuggestionMode.Suggest,
        ),
    ) {
        FormColumn {
            Text("Explicit TextFieldState registration")
            OutlinedTextField(
                state = username,
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth().fillKit(
                    id = "smartUsername", type = FillType.Username, state = username,
                    contentType = ContentType.Username,
                ),
            )
            Text("ContentType-assisted registration (type inferred)")
            OutlinedTextField(
                state = email,
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth().fillKit(
                    id = "smartEmail", state = email, contentType = ContentType.EmailAddress,
                ),
            )
            Text("ContentType exact suggestion")
            OutlinedTextField(
                state = phone,
                label = { Text("Phone") },
                modifier = Modifier.fillMaxWidth().fillKitSuggestion(
                    state = phone, id = "smartPhone", label = "Phone",
                    contentType = ContentType.PhoneNumber,
                ),
            )
            Text("Label heuristic suggestion")
            OutlinedTextField(
                state = firstName,
                label = { Text("First name") },
                modifier = Modifier.fillMaxWidth().fillKitSuggestion(
                    state = firstName, id = "smartFirstName", label = "First name",
                ),
            )
            OutlinedTextField(
                state = city,
                label = { Text("City or town") },
                modifier = Modifier.fillMaxWidth().fillKitSuggestion(
                    state = city, id = "smartCity", label = "City or town",
                ),
            )
            Text("Ambiguous labels remain low confidence")
            BasicTextField(
                state = ambiguousCode,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(16.dp)
                    .fillKitSuggestion(state = ambiguousCode, id = "referralCode", label = "Code"),
                decorator = { inner -> Column { Text("Referral code"); inner() } },
            )
            OutlinedTextField(
                state = otp,
                label = { Text("SMS one-time code") },
                modifier = Modifier.fillMaxWidth().fillKit(
                    id = "smartOtp", type = FillType.OtpCode(), state = otp,
                    contentType = ContentType.SmsOtpCode,
                ),
            )
        }
    }
}

private data class RegistrationState(
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val phone: String = "",
    val password: String = "",
    val dateOfBirth: FillDate? = null,
    val acceptTerms: Boolean = false,
    val profession: String = "",
    val service: String = "",
    val businessName: String = "",
)

@Composable
private fun RegistrationExample(modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf(RegistrationState()) }
    val config = remember {
        FillKitConfig(locale = FillLocale.Code("en-KE"), seed = 845912L, packs = listOf(sampleTestingPack))
    }
    FillKitHost("registration", modifier = modifier.fillMaxSize(), config = config) {
        FormColumn {
            SampleTextField("First name", state.firstName, { state = state.copy(firstName = it) }, "firstName", FillType.FirstName, "Personal")
            SampleTextField("Last name", state.lastName, { state = state.copy(lastName = it) }, "lastName", FillType.LastName, "Personal")
            SampleTextField("Email", state.email, { state = state.copy(email = it) }, "email", FillType.Email, "Contact")
            SampleTextField("Phone", state.phone, { state = state.copy(phone = it) }, "phone", FillType.PhoneNumber("KE"), "Contact")
            SampleTextField("Profession", state.profession, { state = state.copy(profession = it) }, "profession", FillType.Custom("profession", String::class), "Provider")
            SampleTextField("Service", state.service, { state = state.copy(service = it) }, "service", FillType.Custom("service", String::class), "Provider")
            SampleTextField("Business name", state.businessName, { state = state.copy(businessName = it) }, "businessName", FillType.Custom("business-name", String::class), "Provider")
            OutlinedTextField(
                value = state.password,
                onValueChange = { state = state.copy(password = it) },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().fillKit(
                    id = "password", type = FillType.Password(minLength = 12), value = state.password,
                    onFill = { state = state.copy(password = it) }, group = "Personal",
                ),
            )
            OutlinedTextField(
                value = state.dateOfBirth?.toString().orEmpty(),
                onValueChange = { value -> parseDate(value)?.let { state = state.copy(dateOfBirth = it) } },
                label = { Text("Date of birth (YYYY-MM-DD)") },
                modifier = Modifier.fillMaxWidth().fillKit(
                    id = "dateOfBirth", type = FillType.DateOfBirth, value = state.dateOfBirth,
                    onFill = { state = state.copy(dateOfBirth = it) },
                    onClear = { state = state.copy(dateOfBirth = null) }, group = "Personal",
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.acceptTerms,
                    onCheckedChange = { state = state.copy(acceptTerms = it) },
                    modifier = Modifier.fillKit(
                        id = "acceptTerms", label = "Accept terms", type = FillType.BooleanValue(1f),
                        value = state.acceptTerms, onFill = { state = state.copy(acceptTerms = it) },
                        onClear = { state = state.copy(acceptTerms = false) },
                    ),
                )
                Text("Accept terms")
            }
        }
    }
}

private data class BusinessState(
    val name: String = "", val industry: String = "", val email: String = "", val phone: String = "",
    val website: String = "", val country: String = "", val city: String = "", val address: String = "",
    val years: Int = 0, val employees: Int = 0,
)

@Composable
private fun BusinessExample(modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf(BusinessState()) }
    FillKitHost(
        "business-onboarding",
        modifier = modifier.fillMaxSize(),
        config = FillKitConfig(locale = FillLocale.Code("en-NG"), packs = listOf(sampleTestingPack)),
    ) {
        FormColumn {
            SampleTextField("Business name", state.name, { state = state.copy(name = it) }, "businessName", FillType.Custom("business-name", String::class), "Business")
            SampleTextField("Industry", state.industry, { state = state.copy(industry = it) }, "industry", FillType.Selection(listOf("Construction", "Hospitality", "Technology", "Retail")), "Business")
            SampleTextField("Email", state.email, { state = state.copy(email = it) }, "email", FillType.Email, "Contact")
            SampleTextField("Phone", state.phone, { state = state.copy(phone = it) }, "phone", FillType.PhoneNumber(), "Contact")
            SampleTextField("Website", state.website, { state = state.copy(website = it) }, "website", FillType.Website, "Contact")
            SampleTextField("Country", state.country, { state = state.copy(country = it) }, "country", FillType.Country, "Address")
            SampleTextField("City", state.city, { state = state.copy(city = it) }, "city", FillType.City, "Address")
            SampleTextField("Address", state.address, { state = state.copy(address = it) }, "address", FillType.StreetAddress, "Address")
            NumberField("Years operating", state.years, { state = state.copy(years = it) }, "yearsOperating", 1..40)
            NumberField("Employee count", state.employees, { state = state.copy(employees = it) }, "employeeCount", 1..500)
        }
    }
}

private data class CheckoutState(
    val fullName: String = "", val email: String = "", val phone: String = "", val country: String = "",
    val city: String = "", val address: String = "", val postalCode: String = "",
)

@Composable
private fun CheckoutExample(modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf(CheckoutState()) }
    FillKitHost("checkout-address", modifier = modifier.fillMaxSize(), config = FillKitConfig(locale = FillLocale.Code("en-US"))) {
        FormColumn {
            SampleTextField("Full name", state.fullName, { state = state.copy(fullName = it) }, "fullName", FillType.FullName, "Personal")
            SampleTextField("Email", state.email, { state = state.copy(email = it) }, "email", FillType.Email, "Contact")
            SampleTextField("Phone", state.phone, { state = state.copy(phone = it) }, "phone", FillType.PhoneNumber(), "Contact")
            SampleTextField("Country", state.country, { state = state.copy(country = it) }, "country", FillType.Country, "Address")
            SampleTextField("City", state.city, { state = state.copy(city = it) }, "city", FillType.City, "Address")
            SampleTextField("Address", state.address, { state = state.copy(address = it) }, "address", FillType.StreetAddress, "Address")
            SampleTextField("Postal code", state.postalCode, { state = state.copy(postalCode = it) }, "postalCode", FillType.PostalCode, "Address")
        }
    }
}

@Composable
private fun FormColumn(content: @Composable () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } } }
}

@Composable
private fun SampleTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    id: String,
    type: FillType<String>,
    group: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().fillKit(id, type, value, onChange, label, group),
    )
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit, id: String, range: IntRange) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { it.toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().fillKit(
            id = id, type = FillType.Integer(range), value = value, onFill = onChange,
            group = "Business metrics", onClear = { onChange(0) },
        ),
    )
}

private fun parseDate(value: String): FillDate? = runCatching {
    val parts = value.split('-').map(String::toInt)
    FillDate(parts[0], parts[1], parts[2])
}.getOrNull()
