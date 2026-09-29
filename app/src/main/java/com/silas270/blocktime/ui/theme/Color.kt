package com.silas270.blocktime.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// ══════════════════════════════════════════════════════════════════════════════
// THEME-SWITCHABLE DESIGN TOKENS
// ══════════════════════════════════════════════════════════════════════════════
// Standardized semantic tokens with unified perceptual contrast steps across
// Dark ("Emirates Luxury") and Light ("Sky") themes.
// Delegating each token to `ActivePalette.current` ensures automatic recomposition
// across all Composables and Canvas draw passes.

data class BlocktimeColors(
    // ── Surfaces (3 visual depth tiers + border) ──────────────────────────────
    val background: Color,      // Tier 0: Canvas background
    val surface: Color,         // Tier 1: Cards, bottom sheets, modals
    val container: Color,       // Tier 2: Nested tiles, inputs, secondary surfaces
    val border: Color,          // Hairline outline / divider (holds contrast on Tier 0 & 1)

    // ── Typography & Content (3 WCAG contrast tiers) ──────────────────────────
    val textPrimary: Color,     // Tier 1: Headlines, titles, key numbers (AAA >= 7:1)
    val textSecondary: Color,   // Tier 2: Labels, captions, subtitles (AA >= 4.5:1)
    val textTertiary: Color,    // Tier 3: Placeholders, muted labels, disabled (~ 3:1)

    // ── Accents & Feedback (3 semantic hues, each with Solid + Subtle) ─────────
    val accent: Color,          // Primary brand / Gold / Highlights
    val accentSubtle: Color,    // Translucent accent (15-25%) for badge & glow containers
    val success: Color,         // Emerald green for completion, active, touchdown
    val successSubtle: Color,   // Translucent emerald for badges
    val danger: Color,          // Crimson red for destructive actions, warnings, stamps
    val dangerSubtle: Color,    // Translucent crimson for danger badges

    // ── 2D Tactical World Map ────────────────────────────────────────────────
    val mapOcean: Color,
    val mapUnvisitedLand: Color,
    val mapUnvisitedStroke: Color,
    val mapVisitedStroke: Color,
    val mapGraticule: Color,
) {
    // ── Backward-Compatibility Accessors ──────────────────────────────────────
    val midnight: Color get() = background
    val deepNavy: Color get() = surface
    val slate: Color get() = container
    val amber: Color get() = accent
    val softAmber: Color get() = accentSubtle
    val offWhite: Color get() = textPrimary
    val haze: Color get() = textSecondary
    val dim: Color get() = textTertiary
    val green: Color get() = success
    val crimsonRed: Color get() = danger
    val softCrimson: Color get() = dangerSubtle
}

val DarkPalette = BlocktimeColors(
    background          = Color(0xFF140D09),   // Deep dark espresso / black-leather base
    surface             = Color(0xFF2B1C14),   // Rich saddle leather & walnut brown card surface
    container           = Color(0xFF442E22),   // Warm chestnut secondary container
    border              = Color(0xFF3D2A1F),   // Warm leather edge border
    textPrimary         = Color(0xFFF8F5EE),   // Warm cream / champagne white text (14:1 contrast)
    textSecondary       = Color(0xFFA89886),   // Warm sand / desert taupe secondary text (6.5:1 contrast)
    textTertiary        = Color(0xFF6B584B),   // Muted mocha tertiary text (3.2:1 contrast)
    accent              = Color(0xFFD4AF37),   // Emirates signature champagne gold accent
    accentSubtle        = Color(0x33D4AF37),   // 20% translucent gold accent
    success             = Color(0xFF10B981),   // Emerald green accent
    successSubtle       = Color(0x3310B981),   // 20% translucent green
    danger              = Color(0xFFE54D4D),   // Luxury crimson red for destructive actions
    dangerSubtle        = Color(0x2EE54D4D),   // Translucent crimson red container
    mapOcean            = Color(0xFF19110B),   // Deep espresso ocean
    mapUnvisitedLand    = Color(0xFF332219),   // Warm saddle leather land
    mapUnvisitedStroke  = Color(0xFF5A4335),   // Muted leather country outline
    mapVisitedStroke    = Color(0xFFF8F5EE),   // Cream / white visited outline
    mapGraticule        = Color(0xFF5A4335),   // Subtle map route/grid lines
)

val LightSkyPalette = BlocktimeColors(
    background          = Color(0xFF6EC2EF),   // Saturated sky blue background
    surface             = Color(0xFFFFFFFF),   // White cloud card surface
    container           = Color(0xFFBFE6FA),   // Lighter sky blue secondary container
    border              = Color(0xFF4A4A4A),   // Dark neutral charcoal gray
    textPrimary         = Color(0xFF0E2A44),   // Deep sky-navy text (13:1 contrast on white, 6.5:1 on sky)
    textSecondary       = Color(0xFF2C5478),   // Muted steel-blue secondary text (6.8:1 contrast on white)
    textTertiary        = Color(0xFF5C82A6),   // Softer steel blue tertiary text (3.5:1 contrast)
    accent              = Color(0xFFF2762E),   // Dynamic aviation orange
    accentSubtle        = Color(0x33F2762E),   // 20% translucent orange accent
    success             = Color(0xFF0E9668),   // Slightly deepened emerald
    successSubtle       = Color(0x330E9668),   // 20% translucent green
    danger              = Color(0xFFD53F3F),   // Slightly deepened crimson red
    dangerSubtle        = Color(0x2ED53F3F),   // Translucent crimson red container
    mapOcean            = Color(0xFF6EC2EF),   // Same blue as page background
    mapUnvisitedLand    = Color(0xFFEAF5FC),   // Near-white sky-tinted land
    mapUnvisitedStroke  = Color(0xFF3E93C9),   // Same vivid blue as outline
    mapVisitedStroke    = Color(0xFF0E2A44),   // Deep navy outline
    mapGraticule        = Color(0xFFBFE6FA),   // Secondary-container blue
)

/** Holds the palette currently in effect. [BlocktimeTheme] writes to [current] whenever the
 *  resolved theme mode changes; every token below reads through it. */
object ActivePalette {
    var current: BlocktimeColors by mutableStateOf(DarkPalette)
}

// ── Standardized Semantic Tokens (13 Tokens) ──────────────────────────────────
val Background: Color get() = ActivePalette.current.background
val Surface: Color get() = ActivePalette.current.surface
val Container: Color get() = ActivePalette.current.container
val Border: Color get() = ActivePalette.current.border

val TextPrimary: Color get() = ActivePalette.current.textPrimary
val TextSecondary: Color get() = ActivePalette.current.textSecondary
val TextTertiary: Color get() = ActivePalette.current.textTertiary

val Accent: Color get() = ActivePalette.current.accent
val AccentSubtle: Color get() = ActivePalette.current.accentSubtle
val Success: Color get() = ActivePalette.current.success
val SuccessSubtle: Color get() = ActivePalette.current.successSubtle
val Danger: Color get() = ActivePalette.current.danger
val DangerSubtle: Color get() = ActivePalette.current.dangerSubtle

val MapOcean: Color get() = ActivePalette.current.mapOcean
val MapUnvisitedLand: Color get() = ActivePalette.current.mapUnvisitedLand
val MapUnvisitedStroke: Color get() = ActivePalette.current.mapUnvisitedStroke
val MapVisitedStroke: Color get() = ActivePalette.current.mapVisitedStroke
val MapGraticule: Color get() = ActivePalette.current.mapGraticule

// ── Backward-Compatibility Aliases ───────────────────────────────────────────
val Midnight: Color get() = Background
val DeepNavy: Color get() = Surface
val Slate: Color get() = Container
val Amber: Color get() = Accent
val SoftAmber: Color get() = AccentSubtle
val OffWhite: Color get() = TextPrimary
val Haze: Color get() = TextSecondary
val Dim: Color get() = TextTertiary
val Green: Color get() = Success
val CrimsonRed: Color get() = Danger
val SoftCrimson: Color get() = DangerSubtle

// ══════════════════════════════════════════════════════════════════════════════
// SHARED TOKENS (same in both themes)
// ══════════════════════════════════════════════════════════════════════════════
// Deliberately not palette-switched: these are keyed to their own metaphor (medals, parchment,
// gold map markings) rather than the background theme, and already read fine against both a dark
// cockpit and a light sky.

// ── Achievement Medals ───────────────────────────────────────────────────────
val Gold       = Color(0xFFFFDF73)
val GoldDeep   = Color(0xFF8B6508)
val GoldIcon   = Color(0xFFFFF7DC)

val Silver     = Color(0xFFE8E6E1)
val SilverDeep = Color(0xFF5A534C)
val SilverIcon = Color(0xFFFFFFFF)

val Bronze     = Color(0xFFD48148)
val BronzeDeep = Color(0xFF6E3618)
val BronzeIcon = Color(0xFFFFE8DC)

/** Not a metal, and deliberately so - the unranked band (see `AchievementTier.RUBY`). Warm enough
 *  to sit beside the champagne golds rather than fight them, and clearly distinct from
 *  [Danger], which this palette reserves for destructive actions. */
val Ruby       = Color(0xFFE8607A)
val RubyDeep   = Color(0xFF6E1226)
val RubyIcon   = Color(0xFFFFDDE4)

// ── World Map (theme-shared markings) ───────────────────────────────────────
val MapVisitedLand              = Color(0xFFD4AF37)   // Champagne gold visited land
val MapCompletedContinentStroke = Color(0xFF10B981)   // Emerald green complete continent
val MapRouteArc                 = Color(0xFFD4AF37)   // Gold active flight route

// ── Logbook & Parchment ──────────────────────────────────────────────────────
val LogbookParchment            = Color(0xFFF5E6C0)   // Aged cream paper
val LogbookParchmentDark        = Color(0xFFEDD89A)   // Yellowed paper patch
val LogbookInkDark              = Color(0xFF1A1208)   // Near-black ink
val LogbookInkMid               = Color(0xFF6B5033)   // Warm sepia mid-tone
val LogbookInkFaint             = Color(0xFFB09870)   // Faded sepia labels
val LogbookMarginRed            = Color(0xFFCC1C1C)   // Red margin stamp line
val LogbookGrainDark            = Color(0xFF8B6914)   // Warm paper noise
val LogbookGrainLight           = Color(0xFFFFFFE0)   // Highlight paper noise

// ── Pilot Ranks & Challenges ─────────────────────────────────────────────────
val RankCommander               = Color(0xFFD4AF37)   // Gold
val RankCaptain                 = Color(0xFF10B981)   // Emerald Green
val RankFirstOfficer            = Color(0xFFA89886)   // Sand / Taupe
val ChallengeGold               = Color(0xFFFFD700)

// ── Shared challenges: the crew ──────────────────────────────────────────────
/**
 * One colour per pilot in a room, indexed by `ParticipantSnapshot.colorIndex` (join order, the
 * creator is 0). Theme-independent like the medals: a crew member keeps one colour in the team
 * bar, the crew list and the set checklist whichever theme the screen is in, and every hue is
 * distinct from the others on both the dark leather and the light sky. Amber is deliberately not
 * in the list, because the pilot's own slice is always Amber (docs/shared-challenges.md "Per
 * type"), and a crew member in the accent colour would read as self.
 */
val ParticipantPalette: List<Color> = listOf(
    Color(0xFF4FB3E8),   // Sky blue
    Color(0xFF3ECF8E),   // Mint green
    Color(0xFFE879B2),   // Rose pink
    Color(0xFFB08CFF),   // Lavender
    Color(0xFFFF8A5B),   // Coral
    Color(0xFF7FDBDB),   // Aqua
)

/** The colour a crew member is drawn in: self is always [Amber], everyone else takes their
 *  palette entry, wrapping past the palette's end rather than failing on a large room. */
fun participantColor(colorIndex: Int, isSelf: Boolean): Color =
    if (isSelf) Amber else ParticipantPalette[Math.floorMod(colorIndex, ParticipantPalette.size)]
