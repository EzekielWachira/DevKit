package io.devkit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.devkit.fillkit.FillActivationResult
import io.devkit.fillkit.FillCatalogInput
import io.devkit.fillkit.FillDate
import io.devkit.fillkit.FillKit
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillKitHost
import io.devkit.fillkit.FillKitSeed
import io.devkit.fillkit.FillContentHint
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillLocaleRegion
import io.devkit.fillkit.FillReproductionSpec
import io.devkit.fillkit.FillReproductionTokenCodec
import io.devkit.fillkit.FillScenarioCatalog
import io.devkit.fillkit.FillScenarioCatalogEntry
import io.devkit.fillkit.FillType
import io.devkit.fillkit.fillGenerator
import io.devkit.fillkit.fillKit
import io.devkit.fillkit.fillKitPack
import io.devkit.fillkit.fillKitSuggestion
import io.devkit.fillkit.fillLocalePack
import io.devkit.fillkit.fillPersona
import io.devkit.fillkit.fillScenario
import io.devkit.fillkit.generatorPack
import io.devkit.fillkit.personaPack
import io.devkit.fillkit.scenarioPack
import io.devkit.netdemo.NetworkDemoScreen
import io.devkit.netdemo.installDebugNetworking
import io.devkit.ui.theme.DevKitTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureFillKit()
        // Resolves to the debug or release variant of this function depending on
        // the build type; the release one does nothing.
        installDebugNetworking(this)
        setContent { DevKitTheme { FillKitSampleApp() } }
    }
}

/**
 * Application-wide FillKit configuration. Safe to call from the shared source set:
 * without `fillkit-debug` nothing reads it and every activation is rejected.
 *
 * Navigation stays application-owned — FillKit only asks for a form to be shown.
 */
private fun configureFillKit() {
    FillKit.configureDebug {
        pack(sampleTestingPack)
        navigableForms(*formRoutes.keys.toTypedArray())
        navigation { request ->
            val destination = formRoutes[request.formId] ?: return@navigation false
            currentScreen.value = destination
            true
        }
    }
}

private val returningCustomer = fillPersona(
    "returning-customer",
    "Returning Customer"
) {
    locale(FillLocale.Code("en-KE"))
    firstName("Amina")
    lastName("Wanjiku")
    email("amina.wanjiku@example.com")
    phone("+254712345678")
    city("Nairobi")
    country("Kenya")
}

private val newProvider = fillPersona(
    "new-provider",
    "New Provider"
) {
    firstName("Neema")
    lastName("Kamau")
    email("neema.kamau@example.com")
    value(
        "profession",
        "Plumber"
    )
}

private val experiencedProvider = fillPersona(
    "experienced-provider",
    "Experienced Provider"
) {
    firstName("Brian")
    lastName("Mwangi")
    email("brian.mwangi@example.com")
    value(
        "profession",
        "Electrician"
    )
    value(
        "yearsExperience",
        12
    )
    value(
        "businessType",
        "Company"
    )
}

private val internationalCustomer = fillPersona(
    "international-customer",
    "International Customer"
) {
    locale(FillLocale.Code("en-GB"))
    firstName("Amelia")
    lastName("Taylor")
    email("amelia.taylor@example.com")
    phone("+447123456789")
    city("London")
    country("United Kingdom")
}

private val samplePersonas = personaPack(
    "sample-personas",
    "Sample Personas"
) {
    persona(returningCustomer)
    persona(newProvider)
    persona(experiencedProvider)
    persona(internationalCustomer)
}

private val professionGenerator = fillGenerator<String>("profession") {
    oneOf(
        "Plumber",
        "Electrician",
        "Cleaner",
        "Carpenter"
    )
}

private val serviceGenerator = fillGenerator<String>("service") {
    oneOf(
        "House Cleaning",
        "Plumbing Repair",
        "Electrical Installation",
        "Furniture Assembly"
    )
}

private val businessNameGenerator = fillGenerator<String>("business-name") {
    "${
        oneOf(
            "Savanna",
            "Urban",
            "Prime",
            "Metro"
        )
    } ${
        oneOf(
            "Cleaning",
            "Electrical",
            "Plumbing",
            "Repairs"
        )
    } Ltd"
}

private val providerGenerators = generatorPack(
    "home-services",
    "Home Services"
) {
    generator(professionGenerator)
    generator(serviceGenerator)
    generator(businessNameGenerator)
}

private val happyPath = fillScenario(
    id = "happy-path",
    name = "Happy Path",
    targetForm = "registration",
    description = "A returning customer completing registration with valid data.",
    category = "Happy path",
    tags = setOf("happy-path", "smoke"),
) {
    persona("returning-customer")
    text("password", "FillKit-Test-42!")
    boolean("acceptTerms", true)
}

private val providerScenarios = scenarioPack("provider", "Provider Onboarding", version = "2") {
    scenario(happyPath)
    scenario(
        id = "experienced-provider",
        name = "Experienced Provider",
        targetForm = "registration",
        description = "Established provider with a company profile and generated business data.",
        category = "Happy path",
        tags = setOf("happy-path", "provider"),
    ) {
        persona("experienced-provider")
        generated("businessName", "business-name")
        generated("service", "service")
    }
    scenario(
        id = "international-checkout",
        name = "International Customer",
        targetForm = "checkout-address",
        description = "Non-local shipping address for checkout formatting and validation.",
        category = "Checkout",
        tags = setOf("checkout", "international"),
    ) {
        persona("international-customer")
    }
    scenario(
        id = "japanese-checkout",
        name = "Japanese Customer",
        targetForm = "checkout-address",
        description = "Locale-specific scenario: activating it switches the form to ja-JP, " +
            "so names, address and phone all come from the Japanese pack.",
        category = "Checkout",
        tags = setOf("checkout", "locale", "unicode"),
    ) {
        locale("ja-JP")
    }
}

private val validationScenarios = scenarioPack("validation", "Validation", version = "3") {
    scenario(
        id = "minimum-values",
        name = "Minimum Values",
        targetForm = "registration",
        description = "Populates every constrained field with its shortest allowed value.",
        category = "Validation",
        tags = setOf("validation", "edge-case"),
    ) {
        text("firstName", "A")
        text("lastName", "B")
        text("email", "a@b.co")
    }
    scenario(
        id = "maximum-values",
        name = "Maximum Values",
        targetForm = "registration",
        description = "Populates every constrained field with its largest allowed value to test " +
            "clipping, layout behavior and validation boundaries.",
        category = "Validation",
        tags = setOf("validation", "edge-case"),
    ) {
        include(happyPath)
        text("firstName", "Amina-Extremely-Long-Synthetic-First-Name")
        text("businessName", "Savanna International Home Services and Property Maintenance Collective Limited")
    }
    scenario(
        id = "validation-errors",
        name = "Validation Errors",
        targetForm = "registration",
        description = "Deliberately invalid input for error-state screenshots.",
        category = "Validation",
        tags = setOf("validation", "errors"),
    ) {
        text("email", "not-an-email")
        text("phone", "123")
        text("password", "short")
        boolean("acceptTerms", false)
    }
}

// Composition rather than duplication: the Swahili variant inherits Kenya's
// geography, phone plan and postal codes from the built-in en-KE pack and only
// replaces the names and the localized field labels.
private val swahiliKenya = fillLocalePack(
    code = "sw-KE",
    displayName = "Kenya — Kiswahili (sample)",
    id = "sample-sw-ke",
    version = "1",
    region = FillLocaleRegion.Africa,
) {
    extends("en-KE")
    person {
        givenNames("Amani", "Baraka", "Neema", "Juma", "Tumaini", "Zuri")
        familyNames("Mwangi", "Wanjiku", "Otieno", "Kamau")
    }
    business { suffixes("Ltd", "Kampuni") }
    semanticAliases {
        "jina" mapsTo FillContentHint.FirstName
        "barua pepe" mapsTo FillContentHint.Email
        "simu" mapsTo FillContentHint.Phone
    }
}

private val sampleTestingPack = fillKitPack("sample-testing", "Sample Testing", version = 4) {
    locale(swahiliKenya)
    generators(providerGenerators)
    personas(samplePersonas)
    scenarios(providerScenarios)
    scenarios(validationScenarios)
}

/**
 * A toolkit demonstrated by the sample.
 *
 * Grouping by kit is what keeps both the dashboard and the drawer readable as
 * DevKit grows: a new toolkit adds a section, not another peer item in a flat list.
 */
private enum class SampleKit(val title: String, val tagline: String) {
    FillKit("FillKit", "Fill Compose forms with coherent synthetic data"),
    NetKit("NetKit", "Simulate offline, latency, timeouts and HTTP errors"),
}

/** A sample destination. */
private enum class SampleScreen(
    val title: String,
    val shortLabel: String,
    val kit: SampleKit,
    val summary: String,
) {
    Registration(
        "Registration",
        "R",
        SampleKit.FillKit,
        "Personas, locales and scenario fills",
    ),
    Business(
        "Business onboarding",
        "B",
        SampleKit.FillKit,
        "A longer form with mixed field types",
    ),
    Checkout(
        "Checkout address",
        "C",
        SampleKit.FillKit,
        "Address and payment field mapping",
    ),
    SmartFields(
        "Smart Fields",
        "S",
        SampleKit.FillKit,
        "Suggestions, ContentType and semantics",
    ),
    Qa(
        "QA and reproduction",
        "Q",
        SampleKit.FillKit,
        "Scenario launcher, seeds and tokens",
    ),
    Network(
        "Network scenarios",
        "N",
        SampleKit.NetKit,
        "Offline, latency, timeouts and HTTP overrides",
    ),
}

/** Destinations in display order, grouped by the kit they demonstrate. */
private val destinationsByKit: Map<SampleKit, List<SampleScreen>> =
    SampleScreen.entries.groupBy(SampleScreen::kit)

/** Also used by the sample's instrumentation tests to reach the drawer. */
internal const val OpenNavigationMenu = "Open the navigation menu"

/**
 * Stable handles for the sample's two entry points.
 *
 * The drawer is composed even while closed, so a destination's title matches
 * both there and on the dashboard; tests target these instead of user-visible
 * strings. The argument is a [SampleScreen] name, e.g. `"Registration"`.
 */
internal object SampleTestTags {
    fun dashboardCard(destination: String) = "sample:dashboard:$destination"
    fun drawerItem(destination: String) = "sample:drawer:$destination"
    const val DrawerDashboard = "sample:drawer:dashboard"
}

/** The dashboard's own title, in the top bar and the drawer. */
internal const val DashboardTitle = "DevKit"

/** The application decides how a FillKit form id maps to one of its destinations. */
private val formRoutes = mapOf(
    "registration" to SampleScreen.Registration,
    "business-onboarding" to SampleScreen.Business,
    "checkout-address" to SampleScreen.Checkout,
    "smart-fields" to SampleScreen.SmartFields,
)

/**
 * The visible destination, or `null` for the dashboard.
 *
 * FillKit's QA launcher and deep links navigate the sample by writing here, so
 * this stays a plain state holder rather than a navigation graph.
 */
private val currentScreen = mutableStateOf<SampleScreen?>(null)

/**
 * Returns the sample to the dashboard.
 *
 * [currentScreen] is process-wide because FillKit's navigation callback is
 * configured outside composition, so it outlives an Activity — and therefore
 * outlives a single instrumentation test. Tests call this to start from a known
 * screen instead of inheriting wherever the previous test finished.
 */
internal fun resetSampleNavigation() {
    currentScreen.value = null
}

/**
 * The sample shell.
 *
 * Two ways in, because they answer different questions. The **dashboard** is the
 * landing screen and says what each toolkit is for — it is where someone opening
 * DevKit for the first time starts. The **drawer** is for switching once you
 * already know, without going home first.
 *
 * Both write to [currentScreen], so FillKit's QA launcher and deep links keep
 * navigating the sample exactly as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FillKitSampleApp() {
    var screen by currentScreen
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // From a demo, back returns to the dashboard rather than leaving the app.
    // Guarded on the drawer so its own back handling still closes it first.
    BackHandler(enabled = screen != null && !drawerState.isOpen) { screen = null }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SampleDrawerSheet(
                selected = screen,
                onSelect = { destination ->
                    screen = destination
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(screen?.title ?: DashboardTitle) },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            // The glyph is drawn, not an Icon, so it carries no
                            // label of its own; the button has to supply one.
                            modifier = Modifier.semantics {
                                contentDescription = OpenNavigationMenu
                            },
                        ) {
                            MenuGlyph()
                        }
                    },
                )
            },
        ) { padding ->
            when (screen) {
                null -> DashboardScreen(
                    onOpen = { destination -> screen = destination },
                    modifier = Modifier.padding(padding),
                )

                SampleScreen.Registration -> RegistrationExample(Modifier.padding(padding))
                SampleScreen.Business -> BusinessExample(Modifier.padding(padding))
                SampleScreen.Checkout -> CheckoutExample(Modifier.padding(padding))
                SampleScreen.SmartFields -> SmartFieldsExample(Modifier.padding(padding))
                SampleScreen.Qa -> QaReproductionExample(Modifier.padding(padding))
                SampleScreen.Network -> NetworkDemoScreen(Modifier.padding(padding))
            }
        }
    }
}

/**
 * The landing screen: every demo as a launchable card, grouped by toolkit.
 *
 * Each section leads with what the kit is for, so the dashboard doubles as the
 * sample's table of contents rather than being a grid of bare names.
 */
@Composable
private fun DashboardScreen(
    onOpen: (SampleScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "intro") {
            Text(
                text = "Debug and QA tooling samples. Pick a demo, or switch any time " +
                    "from the menu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        destinationsByKit.forEach { (kit, destinations) ->
            item(key = "kit-${kit.name}") {
                Column(
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = kit.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = kit.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(destinations, key = { it.name }) { destination ->
                DashboardCard(destination = destination, onClick = { onOpen(destination) })
            }
        }
    }
}

@Composable
private fun DashboardCard(destination: SampleScreen, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(SampleTestTags.dashboardCard(destination.name)),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DestinationBadge(destination.shortLabel)
            Column(Modifier.weight(1f)) {
                Text(
                    text = destination.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = destination.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ChevronGlyph()
        }
    }
}

/** Destinations grouped by the kit they demonstrate, plus a way back to the dashboard. */
@Composable
private fun SampleDrawerSheet(
    selected: SampleScreen?,
    onSelect: (SampleScreen?) -> Unit,
) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = DashboardTitle,
                modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Debug and QA tooling samples",
                modifier = Modifier.padding(start = 28.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            NavigationDrawerItem(
                selected = selected == null,
                onClick = { onSelect(null) },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .testTag(SampleTestTags.DrawerDashboard),
                icon = { DestinationBadge("◆") },
                label = { Text("Dashboard") },
            )
            HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))

            destinationsByKit.entries.forEachIndexed { index, (kit, destinations) ->
                Text(
                    text = kit.title.uppercase(),
                    modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                destinations.forEach { destination ->
                    NavigationDrawerItem(
                        selected = destination == selected,
                        onClick = { onSelect(destination) },
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .testTag(SampleTestTags.drawerItem(destination.name)),
                        icon = { DestinationBadge(destination.shortLabel) },
                        label = {
                            Column {
                                Text(destination.title)
                                Text(
                                    text = destination.summary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                // Between groups only — a trailing rule under the last one
                // would read as a section that never arrives.
                if (index < destinationsByKit.size - 1) {
                    HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** The single-letter marker the sample has always used for a destination. */
@Composable
private fun DestinationBadge(label: String) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * A drawn hamburger. The workspace does not depend on `material-icons`, so the
 * sample keeps drawing its own marks rather than adding an artifact for one glyph.
 */
@Composable
private fun MenuGlyph() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(18.dp)) {
        val stroke = 2.dp.toPx()
        listOf(0f, size.height / 2f, size.height - stroke).forEach { y ->
            drawLine(
                color = color,
                start = Offset(0f, y + stroke / 2f),
                end = Offset(size.width, y + stroke / 2f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Decorative "opens something" affordance; the card itself carries the label. */
@Composable
private fun ChevronGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(width = 8.dp, height = 14.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(
            color = color,
            start = Offset(0f, stroke / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width, size.height / 2f),
            end = Offset(0f, size.height - stroke / 2f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

private val CardShape = RoundedCornerShape(16.dp)

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
                modifier = Modifier
                    .fillMaxWidth()
                    .fillKit(
                        id = "smartUsername",
                        type = FillType.Username,
                        state = username,
                        contentType = ContentType.Username,
                    ),
            )
            Text("ContentType-assisted registration (type inferred)")
            OutlinedTextField(
                state = email,
                label = { Text("Email") },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillKit(
                        id = "smartEmail",
                        state = email,
                        contentType = ContentType.EmailAddress,
                    ),
            )
            Text("ContentType exact suggestion")
            OutlinedTextField(
                state = phone,
                label = { Text("Phone") },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillKitSuggestion(
                        state = phone,
                        id = "smartPhone",
                        label = "Phone",
                        contentType = ContentType.PhoneNumber,
                    ),
            )
            Text("Label heuristic suggestion")
            OutlinedTextField(
                state = firstName,
                label = { Text("First name") },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillKitSuggestion(
                        state = firstName,
                        id = "smartFirstName",
                        label = "First name",
                    ),
            )
            OutlinedTextField(
                state = city,
                label = { Text("City or town") },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillKitSuggestion(
                        state = city,
                        id = "smartCity",
                        label = "City or town",
                    ),
            )
            Text("Ambiguous labels remain low confidence")
            BasicTextField(
                state = ambiguousCode,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(16.dp)
                    .fillKitSuggestion(
                        state = ambiguousCode,
                        id = "referralCode",
                        label = "Code"
                    ),
                decorator = { inner -> Column { Text("Referral code"); inner() } },
            )
            OutlinedTextField(
                state = otp,
                label = { Text("SMS one-time code") },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillKit(
                        id = "smartOtp",
                        type = FillType.OtpCode(),
                        state = otp,
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
        FillKitConfig(
            locale = FillLocale.Code("en-KE"),
            seed = 845912L,
            packs = listOf(sampleTestingPack)
        )
    }
    FillKitHost(
        "registration",
        modifier = modifier.fillMaxSize(),
        config = config
    ) {
        FormColumn {
            SampleTextField(
                "First name",
                state.firstName,
                { state = state.copy(firstName = it) },
                "firstName",
                FillType.FirstName,
                "Personal"
            )
            SampleTextField(
                "Last name",
                state.lastName,
                { state = state.copy(lastName = it) },
                "lastName",
                FillType.LastName,
                "Personal"
            )
            SampleTextField(
                "Email",
                state.email,
                { state = state.copy(email = it) },
                "email",
                FillType.Email,
                "Contact"
            )
            SampleTextField(
                "Phone",
                state.phone,
                { state = state.copy(phone = it) },
                "phone",
                FillType.PhoneNumber("KE"),
                "Contact"
            )
            SampleTextField(
                "Profession",
                state.profession,
                { state = state.copy(profession = it) },
                "profession",
                FillType.Custom(
                    "profession",
                    String::class
                ),
                "Provider"
            )
            SampleTextField(
                "Service",
                state.service,
                { state = state.copy(service = it) },
                "service",
                FillType.Custom(
                    "service",
                    String::class
                ),
                "Provider"
            )
            SampleTextField(
                "Business name",
                state.businessName,
                { state = state.copy(businessName = it) },
                "businessName",
                FillType.Custom(
                    "business-name",
                    String::class
                ),
                "Provider"
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = { state = state.copy(password = it) },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillKit(
                        id = "password",
                        type = FillType.Password(minLength = 12),
                        value = state.password,
                        onFill = { state = state.copy(password = it) },
                        group = "Personal",
                    ),
            )
            OutlinedTextField(
                value = state.dateOfBirth?.toString()
                    .orEmpty(),
                onValueChange = { value ->
                    parseDate(value)?.let {
                        state = state.copy(dateOfBirth = it)
                    }
                },
                label = { Text("Date of birth (YYYY-MM-DD)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillKit(
                        id = "dateOfBirth",
                        type = FillType.DateOfBirth,
                        value = state.dateOfBirth,
                        onFill = { state = state.copy(dateOfBirth = it) },
                        onClear = { state = state.copy(dateOfBirth = null) },
                        group = "Personal",
                    ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.acceptTerms,
                    onCheckedChange = { state = state.copy(acceptTerms = it) },
                    modifier = Modifier.fillKit(
                        id = "acceptTerms",
                        label = "Accept terms",
                        type = FillType.BooleanValue(1f),
                        value = state.acceptTerms,
                        onFill = { state = state.copy(acceptTerms = it) },
                        onClear = { state = state.copy(acceptTerms = false) },
                    ),
                )
                Text("Accept terms")
            }
        }
    }
}

private data class BusinessState(
    val name: String = "",
    val industry: String = "",
    val email: String = "",
    val phone: String = "",
    val website: String = "",
    val country: String = "",
    val city: String = "",
    val address: String = "",
    val years: Int = 0,
    val employees: Int = 0,
)

@Composable
private fun BusinessExample(modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf(BusinessState()) }
    FillKitHost(
        "business-onboarding",
        modifier = modifier.fillMaxSize(),
        config = FillKitConfig(
            locale = FillLocale.Code("en-NG"),
            packs = listOf(sampleTestingPack)
        ),
    ) {
        FormColumn {
            SampleTextField(
                "Business name",
                state.name,
                { state = state.copy(name = it) },
                "businessName",
                FillType.Custom(
                    "business-name",
                    String::class
                ),
                "Business"
            )
            SampleTextField(
                "Industry",
                state.industry,
                { state = state.copy(industry = it) },
                "industry",
                FillType.Selection(
                    listOf(
                        "Construction",
                        "Hospitality",
                        "Technology",
                        "Retail"
                    )
                ),
                "Business"
            )
            SampleTextField(
                "Email",
                state.email,
                { state = state.copy(email = it) },
                "email",
                FillType.Email,
                "Contact"
            )
            SampleTextField(
                "Phone",
                state.phone,
                { state = state.copy(phone = it) },
                "phone",
                FillType.PhoneNumber(),
                "Contact"
            )
            SampleTextField(
                "Website",
                state.website,
                { state = state.copy(website = it) },
                "website",
                FillType.Website,
                "Contact"
            )
            SampleTextField(
                "Country",
                state.country,
                { state = state.copy(country = it) },
                "country",
                FillType.Country,
                "Address"
            )
            SampleTextField(
                "City",
                state.city,
                { state = state.copy(city = it) },
                "city",
                FillType.City,
                "Address"
            )
            SampleTextField(
                "Address",
                state.address,
                { state = state.copy(address = it) },
                "address",
                FillType.StreetAddress,
                "Address"
            )
            NumberField(
                "Years operating",
                state.years,
                { state = state.copy(years = it) },
                "yearsOperating",
                1..40
            )
            NumberField(
                "Employee count",
                state.employees,
                { state = state.copy(employees = it) },
                "employeeCount",
                1..500
            )
        }
    }
}

private data class CheckoutState(
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val country: String = "",
    val city: String = "",
    val address: String = "",
    val postalCode: String = "",
    val internationalPhone: String = "",
)

@Composable
private fun CheckoutExample(modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf(CheckoutState()) }
    FillKitHost(
        "checkout-address",
        modifier = modifier.fillMaxSize(),
        config = FillKitConfig(locale = FillLocale.Code("en-US"))
    ) {
        FormColumn {
            SampleTextField(
                "Full name",
                state.fullName,
                { state = state.copy(fullName = it) },
                "fullName",
                FillType.FullName,
                "Personal"
            )
            SampleTextField(
                "Email",
                state.email,
                { state = state.copy(email = it) },
                "email",
                FillType.Email,
                "Contact"
            )
            SampleTextField(
                "Phone",
                state.phone,
                { state = state.copy(phone = it) },
                "phone",
                FillType.PhoneNumber(),
                "Contact"
            )
            SampleTextField(
                "Country",
                state.country,
                { state = state.copy(country = it) },
                "country",
                FillType.Country,
                "Address"
            )
            SampleTextField(
                "City",
                state.city,
                { state = state.copy(city = it) },
                "city",
                FillType.City,
                "Address"
            )
            SampleTextField(
                "Address",
                state.address,
                { state = state.copy(address = it) },
                "address",
                FillType.StreetAddress,
                "Address"
            )
            SampleTextField(
                "Postal code",
                state.postalCode,
                { state = state.copy(postalCode = it) },
                "postalCode",
                FillType.PostalCode,
                "Address"
            )
            // Field-level locale override: this one field always uses the UK
            // numbering plan, whatever locale the rest of the form is on.
            OutlinedTextField(
                value = state.internationalPhone,
                onValueChange = { state = state.copy(internationalPhone = it) },
                label = { Text("International phone (en-GB)") },
                modifier = Modifier.fillMaxWidth().fillKit(
                    id = "internationalPhone",
                    type = FillType.PhoneNumber(),
                    value = state.internationalPhone,
                    onFill = { state = state.copy(internationalPhone = it) },
                    label = "International phone",
                    group = "Contact",
                    locale = FillLocale.Code("en-GB"),
                ),
            )
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
        modifier = Modifier
            .fillMaxWidth()
            .fillKit(
                id,
                type,
                value,
                onChange,
                label,
                group
            ),
    )
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    id: String,
    range: IntRange
) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = {
            it.toIntOrNull()
                ?.let(onChange)
        },
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .fillKit(
                id = id,
                type = FillType.Integer(range),
                value = value,
                onFill = onChange,
                group = "Business metrics",
                onClear = { onChange(0) },
            ),
    )
}

private fun parseDate(value: String): FillDate? = runCatching {
    val parts = value.split('-')
        .map(String::toInt)
    FillDate(
        parts[0],
        parts[1],
        parts[2]
    )
}.getOrNull()

// --- QA and reproduction demo ------------------------------------------------

/**
 * Demonstrates the QA → developer → regression-test workflow from application
 * code. Everything here goes through `fillkit-api`: the same catalog the debug
 * panel shows, the same activation request a deep link produces, and the same
 * reproduction token an instrumentation test can replay.
 */
@Composable
private fun QaReproductionExample(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val catalog = remember {
        FillScenarioCatalog.build(
            FillCatalogInput(
                packs = FillKit.debugConfig.packs.ifEmpty { listOf(sampleTestingPack) },
                personaIds = samplePersonas.personas.mapTo(mutableSetOf()) { it.id },
                generatorIds = providerGenerators.generators.mapTo(mutableSetOf()) { it.id },
                navigableForms = formRoutes.keys,
                hasNavigator = FillKit.debugConfig.navigator != null,
            ),
        )
    }
    var seed by remember { mutableStateOf(FillKitSeed.of(845912L).value.toString()) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var lastSpec by remember { mutableStateOf<FillReproductionSpec?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Launch any scenario into its own screen, then copy the reproduction " +
                    "for a bug report or an instrumentation test.",
            )
        }
        item {
            OutlinedTextField(
                value = seed,
                onValueChange = { value -> seed = value.filter(Char::isDigit).take(12) },
                label = { Text("Seed") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(catalog.entries, key = { "${it.packId}/${it.id}" }) { entry ->
            ScenarioLaunchCard(
                entry = entry,
                onLaunch = {
                    val request = entry.launchRequest(seed = FillKitSeed.parseOrNull(seed)?.value)
                    lastSpec = request.toReproductionSpec()
                    outcome = when (val result = FillKit.activate(request)) {
                        is FillActivationResult.Applied -> "Applied to ${request.formId}"
                        is FillActivationResult.PartiallyApplied ->
                            "Applied with warnings: ${result.warnings.joinToString()}"
                        is FillActivationResult.Pending -> "Queued until ${request.formId} opens"
                        is FillActivationResult.Rejected -> "Rejected: ${result.message}"
                    }
                },
            )
        }
        outcome?.let { item { Text(it) } }
        lastSpec?.let { spec ->
            item { ReproductionCard(spec, "${context.packageName}.fillkit") }
        }
    }
}

@Composable
private fun ScenarioLaunchCard(entry: FillScenarioCatalogEntry, onLaunch: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(entry.name, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
        entry.scenario.description?.let {
            Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        Text(
            listOfNotNull(entry.targetForm, entry.scenario.category).plus(entry.tags.map { "#$it" })
                .joinToString(" • "),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
        entry.blockingReason?.let {
            Text(
                "Cannot launch — $it",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.FilledTonalButton(onClick = onLaunch, enabled = entry.launchable) {
                Text("Launch")
            }
        }
    }
}

@Composable
private fun ReproductionCard(spec: FillReproductionSpec, scheme: String) {
    val token = FillReproductionTokenCodec.encodeOrNull(spec)
    val deepLink = token?.let { "$scheme://reproduce/$it" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Reproduction", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
        Text(spec.describe(token), style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        deepLink?.let {
            Text(
                "adb shell am start -a android.intent.action.VIEW -d \"$it\"",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            "In a Compose test: composeRule.applyFillKitReproduction(\"${token.orEmpty()}\")",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
    }
}
