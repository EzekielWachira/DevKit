package io.devkit.fillkit.engine.locale

import io.devkit.fillkit.FillContentHint
import io.devkit.fillkit.FillLocalePack
import io.devkit.fillkit.FillLocaleRegion.Africa
import io.devkit.fillkit.fillLocalePack

internal object AfricaLocalePacks {
    val all: List<FillLocalePack> = listOf(
        localePack(
            "en-KE", "Kenya — English", Africa, "Kenya", "+254", "7,1", 8, listOf(3, 3, 3),
            given = "Amina,Brian,Faith,Kevin,Mercy,David,Neema,Baraka",
            family = "Wanjiku,Mwangi,Njeri,Otieno,Kamau,Achieng,Kiptoo,Mutua",
            cities = "Nairobi,Mombasa,Kisumu,Nakuru,Eldoret,Thika",
            areas = "Nairobi,Mombasa,Kisumu,Nakuru,Uasin Gishu,Kiambu",
            areaLabel = "County",
            postal = "00100,00200,20100,30100,80100",
            streets = "Acacia Way,Ngong Lane,Lakeview Close",
            companySuffixes = "Ltd,Limited,Services Ltd",
            jobTitles = "Software Engineer,Operations Lead,Electrician,Project Manager",
            currency = "KES",
        ),
        // sw-KE shares Kenya's geography, phone rules and postal codes with en-KE;
        // only the names and the localized field labels differ.
        fillLocalePack("sw-KE", "Kenya — Kiswahili", id = "builtin-sw-ke", version = BUILT_IN_VERSION, region = Africa) {
            extends("en-KE")
            person {
                givenNames("Amani", "Baraka", "Halima", "Juma", "Neema", "Rehema", "Salama", "Tumaini")
                familyNames("Mwangi", "Wanjiku", "Otieno", "Kamau", "Achieng", "Mutiso", "Kiptoo", "Njoroge")
            }
            business { suffixes("Ltd", "Kampuni", "Huduma Ltd") }
            semanticAliases {
                "jina" mapsTo FillContentHint.FirstName
                "jina la kwanza" mapsTo FillContentHint.FirstName
                "jina la ukoo" mapsTo FillContentHint.LastName
                "barua pepe" mapsTo FillContentHint.Email
                "simu" mapsTo FillContentHint.Phone
                "mji" mapsTo FillContentHint.City
                "anwani" mapsTo FillContentHint.Street
                "nchi" mapsTo FillContentHint.Country
                "nenosiri" mapsTo FillContentHint.Password
            }
        },
        localePack(
            "en-NG", "Nigeria — English", Africa, "Nigeria", "+234", "70,80,81,90", 8, listOf(3, 3, 4),
            given = "Adaeze,Chinedu,Amina,Tunde,Yetunde,Emeka,Ifeoma,Kelechi",
            family = "Okafor,Adeyemi,Balogun,Eze,Olawale,Obi,Yusuf,Nwosu",
            cities = "Lagos,Abuja,Kano,Ibadan,Port Harcourt,Enugu",
            areas = "Lagos,FCT,Kano,Oyo,Rivers,Enugu",
            areaLabel = "State",
            postal = "100001,900001,700001,200001,500001",
            streets = "Adeola Close,Marina Way,Palm Grove Road",
            companySuffixes = "Ltd,Nigeria Ltd,Enterprises",
            jobTitles = "Software Engineer,Logistics Lead,Electrician,Account Manager",
            currency = "NGN",
        ),
        localePack(
            "en-UG", "Uganda — English", Africa, "Uganda", "+256", "7", 8, listOf(3, 3, 3),
            given = "Achan,Daniel,Esther,Isaac,Joyce,Moses,Naomi,Samuel",
            family = "Okello,Namubiru,Kato,Nakato,Odongo,Nsubuga,Akello,Ssemanda",
            cities = "Kampala,Entebbe,Jinja,Mbarara,Gulu,Fort Portal",
            areas = "Central,Eastern,Western,Northern,Wakiso",
            areaLabel = "Region",
            postal = null,
            streets = "Kira Close,Nile Avenue,Hill View Road",
            companySuffixes = "Ltd,Uganda Ltd,Services Ltd",
            jobTitles = "Software Engineer,Field Officer,Electrician,Programme Lead",
            currency = "UGX",
        ),
        localePack(
            "en-TZ", "Tanzania — English", Africa, "Tanzania", "+255", "6,7", 8, listOf(3, 3, 3),
            given = "Asha,Baraka,Halima,Juma,Neema,Omari,Rehema,Yusuf",
            family = "Mushi,Mwinyi,Mrema,Kimaro,Msuya,Mollel,Salum,Mbwana",
            cities = "Dar es Salaam,Dodoma,Arusha,Mwanza,Mbeya,Morogoro",
            areas = "Dar es Salaam,Dodoma,Arusha,Mwanza,Mbeya",
            areaLabel = "Region",
            postal = "11101,41101,23101,33101,53101",
            streets = "Bahari Road,Uhuru Close,Kilimanjaro Lane",
            companySuffixes = "Ltd,Tanzania Ltd,Company Ltd",
            jobTitles = "Software Engineer,Operations Lead,Electrician,Project Manager",
            currency = "TZS",
        ),
        fillLocalePack("sw-TZ", "Tanzania — Kiswahili", id = "builtin-sw-tz", version = BUILT_IN_VERSION, region = Africa) {
            extends("en-TZ")
            person {
                givenNames("Amani", "Furaha", "Imani", "Jabari", "Nuru", "Pendo", "Subira", "Zuri")
                familyNames("Mushi", "Kimaro", "Msuya", "Mollel", "Salum", "Mbwana", "Mrema", "Mwinyi")
            }
            business { suffixes("Ltd", "Kampuni", "Huduma Ltd") }
            semanticAliases {
                "jina" mapsTo FillContentHint.FirstName
                "jina la ukoo" mapsTo FillContentHint.LastName
                "barua pepe" mapsTo FillContentHint.Email
                "simu" mapsTo FillContentHint.Phone
                "mji" mapsTo FillContentHint.City
            }
        },
        localePack(
            "en-RW", "Rwanda — English", Africa, "Rwanda", "+250", "78,72", 7, listOf(3, 3, 3),
            given = "Aline,Claude,Divine,Eric,Grace,Keza,Patrick,Shema",
            family = "Uwimana,Habimana,Mukamana,Niyonsenga,Ingabire,Mugisha,Kayitesi,Munyaneza",
            cities = "Kigali,Huye,Musanze,Rubavu,Rusizi,Muhanga",
            areas = "Kigali,Southern,Northern,Western,Eastern",
            areaLabel = "Province",
            postal = null,
            streets = "KG 11 Ave,Umuganda Close,Kivu View Road",
            companySuffixes = "Ltd,Rwanda Ltd,Company Ltd",
            jobTitles = "Software Engineer,Operations Lead,Electrician,Programme Lead",
            currency = "RWF",
        ),
        localePack(
            "en-GH", "Ghana — English", Africa, "Ghana", "+233", "24,54,20", 7, listOf(2, 3, 4),
            given = "Abena,Akosua,Ama,Kojo,Kofi,Kwame,Nana,Yaw",
            family = "Mensah,Owusu,Boateng,Asante,Ofori,Adjei,Aidoo,Appiah",
            cities = "Accra,Kumasi,Tamale,Takoradi,Cape Coast,Koforidua",
            areas = "Greater Accra,Ashanti,Northern,Western,Central,Eastern",
            areaLabel = "Region",
            postal = "GA100,AK100,NT100,WR100",
            streets = "Independence Close,Cocoa Lane,Labone Road",
            companySuffixes = "Ltd,Ghana Ltd,Company Ltd",
            jobTitles = "Software Engineer,Operations Lead,Electrician,Project Manager",
            currency = "GHS",
        ),
        localePack(
            "en-ZA", "South Africa — English", Africa, "South Africa", "+27", "6,7,8", 8, listOf(2, 3, 4),
            given = "Amahle,Anele,Lerato,Lethabo,Neo,Thabo,Zanele,Sipho",
            family = "Dlamini,Nkosi,Mokoena,Khumalo,Mthembu,Naidoo,Jacobs,Ndlovu",
            cities = "Johannesburg,Cape Town,Durban,Pretoria,Gqeberha,Bloemfontein",
            areas = "Gauteng,Western Cape,KwaZulu-Natal,Eastern Cape,Free State",
            areaLabel = "Province",
            postal = "2000,8001,4001,0002,6001",
            streets = "Protea Close,Table View Road,Jacaranda Lane",
            companySuffixes = "Pty Ltd,Ltd,Holdings Pty Ltd",
            jobTitles = "Software Engineer,Operations Lead,Electrician,Project Manager",
            currency = "ZAR",
        ),
        fillLocalePack("af-ZA", "South Africa — Afrikaans", id = "builtin-af-za", version = BUILT_IN_VERSION, region = Africa) {
            extends("en-ZA")
            person {
                givenNames("Anika", "Dirk", "Elsabe", "Hendrik", "Jana", "Pieter", "Ruan", "Marlize")
                familyNames("Van der Merwe", "Botha", "Nel", "Pretorius", "Du Plessis", "Venter", "Coetzee", "Fourie")
            }
            business { suffixes("Edms Bpk", "Pty Ltd", "Beperk") }
            semanticAliases {
                "naam" mapsTo FillContentHint.FirstName
                "voornaam" mapsTo FillContentHint.FirstName
                "van" mapsTo FillContentHint.LastName
                "e-pos" mapsTo FillContentHint.Email
                "selfoon" mapsTo FillContentHint.Phone
                "stad" mapsTo FillContentHint.City
                "wagwoord" mapsTo FillContentHint.Password
            }
        },
        localePack(
            "fr-SN", "Senegal — French", Africa, "Sénégal", "+221", "77,78,76", 7, listOf(3, 2, 2, 2),
            given = "Aminata,Cheikh,Fatou,Ibrahima,Khady,Mamadou,Ndeye,Ousmane",
            family = "Diop,Ndiaye,Fall,Sow,Ba,Gueye,Sarr,Faye",
            cities = "Dakar,Thiès,Saint-Louis,Ziguinchor,Kaolack,Touba",
            areas = "Dakar,Thiès,Saint-Louis,Ziguinchor,Kaolack",
            areaLabel = "Région",
            postal = "10000,21000,32000",
            streets = "Rue des Baobabs,Avenue Léopold,Allée du Port",
            companySuffixes = "SARL,SA,SUARL",
            jobTitles = "Ingénieur logiciel,Responsable des opérations,Électricien,Chef de projet",
            currency = "XOF",
            localizedCountry = "Sénégal",
            aliases = FRENCH_ALIASES,
        ),
        localePack(
            "fr-CI", "Côte d'Ivoire — French", Africa, "Côte d'Ivoire", "+225", "07,05,01", 8, listOf(2, 2, 2, 2),
            given = "Adjoua,Affoué,Konan,Kouadio,Mariam,Yao,Aya,Serge",
            family = "Koffi,Kouassi,Konan,Traoré,Bamba,Yao,Diarra,Aka",
            cities = "Abidjan,Bouaké,Yamoussoukro,Daloa,San-Pédro,Korhogo",
            areas = "Abidjan,Vallée du Bandama,Lacs,Haut-Sassandra,Bas-Sassandra",
            areaLabel = "District",
            postal = null,
            streets = "Boulevard Lagunaire,Rue du Plateau,Avenue des Palmiers",
            companySuffixes = "SARL,SA,SAS",
            jobTitles = "Ingénieur logiciel,Responsable des opérations,Électricien,Chef de projet",
            currency = "XOF",
            localizedCountry = "Côte d'Ivoire",
            aliases = FRENCH_ALIASES,
        ),
        localePack(
            "fr-CM", "Cameroon — French", Africa, "Cameroun", "+237", "6", 8, listOf(1, 2, 2, 2, 2),
            given = "Achille,Bertrand,Chantal,Estelle,Landry,Marlyse,Serge,Yvette",
            family = "Nkeng,Mbarga,Fotso,Tchoua,Ngono,Essomba,Biya,Mvondo",
            cities = "Douala,Yaoundé,Bafoussam,Garoua,Bamenda,Kribi",
            areas = "Littoral,Centre,Ouest,Nord,Nord-Ouest,Sud",
            areaLabel = "Région",
            postal = null,
            streets = "Rue Bonanjo,Avenue Kennedy,Boulevard de la Liberté",
            companySuffixes = "SARL,SA,Établissements",
            jobTitles = "Ingénieur logiciel,Responsable des opérations,Électricien,Chef de projet",
            currency = "XAF",
            localizedCountry = "Cameroun",
            aliases = FRENCH_ALIASES,
        ),
    )
}

internal val FRENCH_ALIASES: Map<String, FillContentHint> = mapOf(
    "prénom" to FillContentHint.FirstName,
    "nom" to FillContentHint.LastName,
    "nom de famille" to FillContentHint.LastName,
    "adresse e-mail" to FillContentHint.Email,
    "courriel" to FillContentHint.Email,
    "téléphone" to FillContentHint.Phone,
    "numéro de téléphone" to FillContentHint.Phone,
    "ville" to FillContentHint.City,
    "adresse" to FillContentHint.Street,
    "code postal" to FillContentHint.PostalCode,
    "pays" to FillContentHint.Country,
    "mot de passe" to FillContentHint.Password,
    "date de naissance" to FillContentHint.BirthDate,
)
