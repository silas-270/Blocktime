package com.silas270.blocktime.data.model

/**
 * A curated challenge template a player can start from the Challenges screen -
 * name/description/definition, but no instance state: `ChallengeRepository.startCuratedChallenge`
 * assigns an id, position pointer (Route), and zeroed progress when it instantiates a [Challenge]
 * row from one of these. A handful of real examples per type, per docs/challenges.md's
 * "Where it comes from" - not exhaustive content, that's later authoring work.
 */
data class CuratedChallengeTemplate(
    val catalogId: String,
    val name: String,
    val description: String,
    val type: ChallengeType,
    val iconName: String? = null,
    val originIata: String? = null,
    val destIata: String? = null,
    /** ROUTE only: the [PredefinedRoute] this template instantiates, instead of a free-form
     *  [originIata]/[destIata] pair. The itinerary lives in [PredefinedRouteCatalog]; the framing
     *  (name/description/icon) lives here, so one route can be presented more than one way. */
    val predefinedRouteId: String? = null,
    val setDefinition: ChallengeSetDefinition? = null,
    val targetDistanceKm: Double? = null,
    val targetDays: Int? = null
)

object CuratedChallengeCatalog {
    val ALL: List<CuratedChallengeTemplate> = listOf(
        // Fixed Routes
        CuratedChallengeTemplate(
            catalogId = "route_spirit_of_st_louis",
            name = "Spirit of St. Louis",
            description = "Recreate Lindbergh's historic 1927 solo transatlantic flight from New York to Paris.",
            type = ChallengeType.ROUTE,
            iconName = "flight_takeoff",
            originIata = "JFK",
            destIata = "CDG"
        ),
        CuratedChallengeTemplate(
            catalogId = "route_around_the_world_80_days",
            name = "Around the World in 80 Days",
            description = "Follow Phileas Fogg's legendary circumnavigation across London, Mumbai, Hong Kong, and San Francisco.",
            type = ChallengeType.ROUTE,
            iconName = "explore",
            predefinedRouteId = "around_the_world_80_days"
        ),
        CuratedChallengeTemplate(
            catalogId = "route_race_of_mercy",
            name = "Great Race of Mercy",
            description = "Fly the 1925 Alaskan diphtheria antitoxin relay corridor connecting Anchorage, Fairbanks, and Nome.",
            type = ChallengeType.ROUTE,
            iconName = "medical_services",
            predefinedRouteId = "race_of_mercy"
        ),
        CuratedChallengeTemplate(
            catalogId = "route_silk_road",
            name = "Silk Road of Marco Polo",
            description = "Retrace Marco Polo's epic merchant voyage from Venice to Beijing through the historic crossroads of Samarkand and Xi'an.",
            type = ChallengeType.ROUTE,
            iconName = "navigation",
            predefinedRouteId = "silk_road"
        ),
        CuratedChallengeTemplate(
            catalogId = "route_project_sunrise",
            name = "Project Sunrise",
            description = "Conquer the world's longest nonstop commercial flight path connecting Melbourne all the way to London.",
            type = ChallengeType.ROUTE,
            iconName = "wb_sunny",
            originIata = "MEL",
            destIata = "LHR"
        ),

        // Open Routes
        CuratedChallengeTemplate(
            catalogId = "route_roof_of_world",
            name = "Roof of the World Gateway",
            description = "Navigate the high-altitude mountain corridor from Delhi into Leh, Ladakh.",
            type = ChallengeType.ROUTE,
            iconName = "terrain",
            originIata = "DEL",
            destIata = "IXL"
        ),
        CuratedChallengeTemplate(
            catalogId = "route_okavango_safari",
            name = "Okavango Safari",
            description = "Fly from Johannesburg to the gateway of Botswana's wildlife-rich Okavango Delta in Maun.",
            type = ChallengeType.ROUTE,
            iconName = "park",
            originIata = "JNB",
            destIata = "MUB"
        ),
        CuratedChallengeTemplate(
            catalogId = "route_napoleon_exile",
            name = "Napoleon's Exile",
            description = "Fly across the South Atlantic from Johannesburg into the remote island sanctuary of Saint Helena.",
            type = ChallengeType.ROUTE,
            iconName = "anchor",
            originIata = "JNB",
            destIata = "HLE"
        ),
        CuratedChallengeTemplate(
            catalogId = "route_patagonian_glaciers",
            name = "Patagonian Glaciers",
            description = "Journey from Buenos Aires down to El Calafate at the edge of the Southern Patagonian Ice Field.",
            type = ChallengeType.ROUTE,
            iconName = "ac_unit",
            originIata = "EZE",
            destIata = "FTE"
        ),
        CuratedChallengeTemplate(
            catalogId = "route_europe_to_lubango",
            name = "Europe to Lubango",
            description = "Chart onward international connections from Stuttgart all the way into Southern Angola.",
            type = ChallengeType.ROUTE,
            iconName = "map",
            originIata = "STR",
            destIata = "SDD"
        ),

        // Set-Completion
        CuratedChallengeTemplate(
            catalogId = "set_all_continents",
            name = CuratedChallengeSets.ALL_CONTINENTS.displayName,
            description = "Land on all six inhabited continents across the globe.",
            type = ChallengeType.SET_COMPLETION,
            iconName = "public",
            setDefinition = CuratedChallengeSets.ALL_CONTINENTS
        ),
        CuratedChallengeTemplate(
            catalogId = "set_g7_capitals",
            name = CuratedChallengeSets.G7_CAPITALS.displayName,
            description = "Visit the capital airport of every G7 nation.",
            type = ChallengeType.SET_COMPLETION,
            iconName = "location_city",
            setDefinition = CuratedChallengeSets.G7_CAPITALS
        ),
        CuratedChallengeTemplate(
            catalogId = "set_european_explorer",
            name = CuratedChallengeSets.EUROPEAN_EXPLORER.displayName,
            description = "Land in 5 iconic European nations.",
            type = ChallengeType.SET_COMPLETION,
            iconName = "flag",
            setDefinition = CuratedChallengeSets.EUROPEAN_EXPLORER
        ),
        CuratedChallengeTemplate(
            catalogId = "set_asian_odyssey",
            name = CuratedChallengeSets.ASIAN_ODYSSEY.displayName,
            description = "Land in 5 iconic Asian nations.",
            type = ChallengeType.SET_COMPLETION,
            iconName = "temple_buddhist",
            setDefinition = CuratedChallengeSets.ASIAN_ODYSSEY
        ),
        CuratedChallengeTemplate(
            catalogId = "set_african_safari",
            name = CuratedChallengeSets.AFRICAN_SAFARI.displayName,
            description = "Land in 5 iconic African nations.",
            type = ChallengeType.SET_COMPLETION,
            iconName = "explore",
            setDefinition = CuratedChallengeSets.AFRICAN_SAFARI
        ),
        CuratedChallengeTemplate(
            catalogId = "set_north_american_tour",
            name = CuratedChallengeSets.NORTH_AMERICAN_TOUR.displayName,
            description = "Land in 5 iconic North American nations.",
            type = ChallengeType.SET_COMPLETION,
            iconName = "travel_explore",
            setDefinition = CuratedChallengeSets.NORTH_AMERICAN_TOUR
        ),
        CuratedChallengeTemplate(
            catalogId = "set_south_american_discovery",
            name = CuratedChallengeSets.SOUTH_AMERICAN_DISCOVERY.displayName,
            description = "Land in 5 iconic South American nations.",
            type = ChallengeType.SET_COMPLETION,
            iconName = "forest",
            setDefinition = CuratedChallengeSets.SOUTH_AMERICAN_DISCOVERY
        ),
        CuratedChallengeTemplate(
            catalogId = "set_pacific_island_hopper",
            name = CuratedChallengeSets.PACIFIC_ISLAND_HOPPER.displayName,
            description = "Land in 5 iconic Oceania countries and island territories.",
            type = ChallengeType.SET_COMPLETION,
            iconName = "surfing",
            setDefinition = CuratedChallengeSets.PACIFIC_ISLAND_HOPPER
        ),

        // Distance
        CuratedChallengeTemplate(
            catalogId = "distance_planetary_core",
            name = "Planetary Core",
            description = "Fly a cumulative 7,918 miles under this challenge (the Earth's diameter).",
            type = ChallengeType.DISTANCE,
            iconName = "public",
            targetDistanceKm = 12_742.0
        ),
        CuratedChallengeTemplate(
            catalogId = "distance_around_the_earth",
            name = "Around the Earth",
            description = "Fly a cumulative 24,901 miles under this challenge (the Earth's circumference).",
            type = ChallengeType.DISTANCE,
            iconName = "explore",
            targetDistanceKm = 40_075.0
        ),
        CuratedChallengeTemplate(
            catalogId = "distance_50k_club",
            name = "50k Miles Club",
            description = "Fly a cumulative 50,000 miles under this challenge to earn gold airline status.",
            type = ChallengeType.DISTANCE,
            iconName = "military_tech",
            targetDistanceKm = 80_467.0
        ),

        // Streaks
        CuratedChallengeTemplate(
            catalogId = "streak_mid",
            name = "Mid Streak",
            description = "Fly on 3 consecutive calendar days without missing a day.",
            type = ChallengeType.STREAK,
            iconName = "event_repeat",
            targetDays = 3
        ),
        CuratedChallengeTemplate(
            catalogId = "streak_long",
            name = "Long Streak",
            description = "Fly on 5 consecutive calendar days without missing a day.",
            type = ChallengeType.STREAK,
            iconName = "fire",
            targetDays = 5
        )
    )

    fun find(catalogId: String): CuratedChallengeTemplate? = ALL.find { it.catalogId == catalogId }
}
