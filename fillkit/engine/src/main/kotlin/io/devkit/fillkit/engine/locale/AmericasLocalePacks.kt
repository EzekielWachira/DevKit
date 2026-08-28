package io.devkit.fillkit.engine.locale

import io.devkit.fillkit.FillContentHint
import io.devkit.fillkit.FillLocalePack
import io.devkit.fillkit.FillLocaleRegion.NorthAmerica
import io.devkit.fillkit.FillLocaleRegion.SouthAmerica
import io.devkit.fillkit.fillLocalePack

internal object AmericasLocalePacks {
    val all: List<FillLocalePack> = listOf(
        localePack(
            "en-US", "United States — English", NorthAmerica, "United States", "+1", "2,3,4,5,6,7,8,9", 9, listOf(3, 3, 4),
            given = "Olivia,Noah,Maya,Ethan,Sofia,Liam,Avery,Jordan",
            family = "Parker,Reed,Morgan,Brooks,Rivera,Bennett,Hayes,Turner",
            cities = "Austin,Portland,Denver,Madison,Raleigh,Phoenix",
            areas = "Texas,Oregon,Colorado,Wisconsin,North Carolina,Arizona",
            areaLabel = "State",
            postal = "73301,97201,80202,53703,27601",
            streets = "Maple Street,Cedar Avenue,Willow Court",
            companySuffixes = "LLC,Inc.,Corp.",
            jobTitles = "Software Engineer,Operations Lead,Electrician,Project Manager",
            currency = "USD",
            nationalPrefix = "1-",
        ),
        localePack(
            "en-CA", "Canada — English", NorthAmerica, "Canada", "+1", "2,3,4,5,6,7,8,9", 9, listOf(3, 3, 4),
            given = "Emma,Liam,Charlotte,Noah,Olivia,William,Sophie,Lucas",
            family = "Martin,Wilson,Tremblay,Brown,Roy,Gagnon,Lee,Clark",
            cities = "Toronto,Vancouver,Montréal,Calgary,Ottawa,Halifax",
            areas = "Ontario,British Columbia,Québec,Alberta,Nova Scotia",
            areaLabel = "Province",
            postal = "M5V 3A8,V6B 1A1,H2Y 1C6,T2P 1J9,K1P 1J1",
            streets = "Birch Street,Lakeshore Road,Spruce Crescent",
            companySuffixes = "Inc.,Ltd.,Corp.",
            jobTitles = "Software Engineer,Operations Lead,Electrician,Project Manager",
            currency = "CAD",
            nationalPrefix = "1-",
        ),
        fillLocalePack("fr-CA", "Canada — French", id = "builtin-fr-ca", version = BUILT_IN_VERSION, region = NorthAmerica) {
            // Same geography, postal rules and phone plan as en-CA; French names and labels.
            extends("en-CA")
            person {
                givenNames("Océane", "Félix", "Rosalie", "Thomas", "Alice", "Édouard", "Camille", "Antoine")
                familyNames("Tremblay", "Gagnon", "Roy", "Côté", "Bouchard", "Gauthier", "Morin", "Lavoie")
            }
            location {
                localizedCountry("Canada")
                administrativeAreaLabel("Province")
                streetNames("Rue des Érables", "Avenue du Parc", "Chemin du Lac")
            }
            business {
                suffixes("Inc.", "Ltée", "S.E.N.C.")
                jobTitles("Ingénieur logiciel", "Responsable des opérations", "Électricien", "Chef de projet")
            }
            semanticAliases { FRENCH_ALIASES.forEach { (label, hint) -> alias(label, hint) } }
        },
        localePack(
            "es-MX", "Mexico — Spanish", NorthAmerica, "México", "+52", "55,33,81", 8, listOf(2, 4, 4),
            given = "Sofía,Santiago,Valentina,Mateo,Regina,Diego,Ximena,Emiliano",
            family = "Hernández,García,Martínez,López,González,Pérez,Sánchez,Ramírez",
            cities = "Ciudad de México,Guadalajara,Monterrey,Puebla,Querétaro,Mérida",
            areas = "Ciudad de México,Jalisco,Nuevo León,Puebla,Querétaro,Yucatán",
            areaLabel = "Estado",
            postal = "01000,44100,64000,72000,76000",
            streets = "Calle Reforma,Avenida Juárez,Privada del Roble",
            companySuffixes = "S.A. de C.V.,S. de R.L.,S.C.",
            jobTitles = "Ingeniero de software,Responsable de operaciones,Electricista,Gerente de proyecto",
            currency = "MXN",
            localizedCountry = "México",
            familyNameCount = 2,
            aliases = SPANISH_ALIASES,
        ),
        localePack(
            "pt-BR", "Brazil — Portuguese", SouthAmerica, "Brasil", "+55", "11,21,31,41", 9, listOf(2, 5, 4),
            given = "Alice,Miguel,Helena,Arthur,Laura,Heitor,Manuela,Théo",
            family = "Silva,Santos,Oliveira,Souza,Lima,Pereira,Costa,Almeida",
            cities = "São Paulo,Rio de Janeiro,Belo Horizonte,Curitiba,Porto Alegre,Recife",
            areas = "São Paulo,Rio de Janeiro,Minas Gerais,Paraná,Rio Grande do Sul,Pernambuco",
            areaLabel = "Estado",
            postal = "01310-100,20040-002,30130-010,80010-010",
            streets = "Rua das Palmeiras,Avenida Paulista,Travessa do Sol",
            companySuffixes = "Ltda,S.A.,MEI",
            jobTitles = "Engenheiro de software,Líder de operações,Eletricista,Gerente de projeto",
            currency = "BRL",
            localizedCountry = "Brasil",
            familyNameCount = 2,
            aliases = PORTUGUESE_ALIASES,
        ),
        localePack(
            "es-AR", "Argentina — Spanish", SouthAmerica, "Argentina", "+54", "11,351,341", 8, listOf(2, 4, 4),
            given = "Martina,Benjamín,Emma,Bautista,Catalina,Joaquín,Isabella,Thiago",
            family = "González,Rodríguez,Gómez,Fernández,López,Díaz,Martínez,Pérez",
            cities = "Buenos Aires,Córdoba,Rosario,Mendoza,La Plata,Salta",
            areas = "Buenos Aires,Córdoba,Santa Fe,Mendoza,Salta",
            areaLabel = "Provincia",
            postal = "C1000,X5000,S2000,M5500",
            streets = "Calle Corrientes,Avenida San Martín,Pasaje del Sur",
            companySuffixes = "S.A.,S.R.L.,S.A.S.",
            jobTitles = "Ingeniero de software,Responsable de operaciones,Electricista,Jefe de proyecto",
            currency = "ARS",
            familyNameCount = 2,
            aliases = SPANISH_ALIASES,
        ),
        localePack(
            "es-CO", "Colombia — Spanish", SouthAmerica, "Colombia", "+57", "30,31,32", 8, listOf(3, 3, 4),
            given = "Sofía,Samuel,Isabella,Matías,Valeria,Sebastián,Mariana,Nicolás",
            family = "Rodríguez,Gómez,González,Martínez,Ramírez,Moreno,Muñoz,Rojas",
            cities = "Bogotá,Medellín,Cali,Barranquilla,Cartagena,Bucaramanga",
            areas = "Cundinamarca,Antioquia,Valle del Cauca,Atlántico,Bolívar,Santander",
            areaLabel = "Departamento",
            postal = "110111,050001,760001,080001",
            streets = "Carrera 7,Calle 100,Diagonal 25",
            companySuffixes = "S.A.S.,S.A.,Ltda.",
            jobTitles = "Ingeniero de software,Responsable de operaciones,Electricista,Jefe de proyecto",
            currency = "COP",
            familyNameCount = 2,
            aliases = SPANISH_ALIASES,
        ),
        localePack(
            "es-CL", "Chile — Spanish", SouthAmerica, "Chile", "+56", "9", 8, listOf(1, 4, 4),
            given = "Emilia,Agustín,Isidora,Vicente,Antonella,Maximiliano,Florencia,Benjamín",
            family = "González,Muñoz,Rojas,Díaz,Pérez,Soto,Contreras,Silva",
            cities = "Santiago,Valparaíso,Concepción,Antofagasta,Temuco,La Serena",
            areas = "Región Metropolitana,Valparaíso,Biobío,Antofagasta,La Araucanía",
            areaLabel = "Región",
            postal = "8320000,2340000,4030000",
            streets = "Avenida Providencia,Calle Los Aromos,Pasaje Andino",
            companySuffixes = "SpA,S.A.,Ltda.",
            jobTitles = "Ingeniero de software,Responsable de operaciones,Electricista,Jefe de proyecto",
            currency = "CLP",
            familyNameCount = 2,
            aliases = SPANISH_ALIASES,
        ),
        localePack(
            "es-PE", "Peru — Spanish", SouthAmerica, "Perú", "+51", "9", 8, listOf(3, 3, 3),
            given = "Valentina,Thiago,Camila,Santiago,Luciana,Mateo,Fernanda,Adriano",
            family = "Quispe,Flores,Rojas,Vargas,Huamán,Castillo,Ramos,Chávez",
            cities = "Lima,Arequipa,Trujillo,Cusco,Piura,Chiclayo",
            areas = "Lima,Arequipa,La Libertad,Cusco,Piura,Lambayeque",
            areaLabel = "Departamento",
            postal = "15001,04001,13001,08001",
            streets = "Avenida Arequipa,Jirón Union,Calle Los Sauces",
            companySuffixes = "S.A.C.,S.A.,E.I.R.L.",
            jobTitles = "Ingeniero de software,Responsable de operaciones,Electricista,Jefe de proyecto",
            currency = "PEN",
            localizedCountry = "Perú",
            familyNameCount = 2,
            aliases = SPANISH_ALIASES,
        ),
    )
}
