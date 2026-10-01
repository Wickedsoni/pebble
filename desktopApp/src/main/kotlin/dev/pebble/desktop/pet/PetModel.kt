package dev.pebble.desktop.pet

import androidx.compose.ui.graphics.Color

/** The cast. Flat, muted colours; shapes live in [PetPainter]. */
enum class Character(val displayName: String, val base: Color, val shade: Color) {
    PEBBLE("Pebble", Color(0xFFD97757), Color(0xFFB85F42)),
    MOCHI("Mochi", Color(0xFFC9B7A5), Color(0xFFA89582)),
    SPROUT("Sprout", Color(0xFF8FAE8A), Color(0xFF6F8F6B)),
    BOLT("Bolt", Color(0xFF9BA4AE), Color(0xFF79828D)),
    DRIP("Drip", Color(0xFF7FA9C9), Color(0xFF5F89AA)),
}

/** Evolution stage, unlocked by XP later. Adds a small accessory on top of any character. */
enum class Stage { BABY, TEEN, ADULT, LEGENDARY }

enum class Mood { IDLE, HAPPY, SLEEPY, THIRSTY, WORRIED, SAD, CELEBRATE, WORKING, NEEDS_INPUT, LOVE }

enum class Arms { DOWN, UP, WAVE, HOLD, HUG }

enum class Motion { STILL, HOP, SHAKE }

enum class Extra { ZZZ, SWEAT, HARDHAT, BANG, SPARKLES, HEART }

data class PetPose(
    val mood: Mood = Mood.IDLE,
    val arms: Arms = Arms.DOWN,
    val motion: Motion = Motion.STILL,
    val extras: Set<Extra> = emptySet(),
) {
    /** True when this pose needs continuous frames; still poses only redraw on blink/gaze. */
    val animated: Boolean get() = motion != Motion.STILL || arms == Arms.WAVE || Extra.SPARKLES in extras
}

fun Mood.defaultPose(): PetPose = when (this) {
    Mood.IDLE -> PetPose(this)
    Mood.HAPPY -> PetPose(this, Arms.UP, Motion.HOP)
    Mood.SLEEPY -> PetPose(this, extras = setOf(Extra.ZZZ))
    Mood.THIRSTY -> PetPose(this, Arms.HOLD)
    Mood.WORRIED -> PetPose(this, motion = Motion.SHAKE, extras = setOf(Extra.SWEAT))
    Mood.SAD -> PetPose(this)
    Mood.CELEBRATE -> PetPose(this, Arms.UP, Motion.HOP, setOf(Extra.SPARKLES))
    Mood.WORKING -> PetPose(this, extras = setOf(Extra.HARDHAT))
    Mood.NEEDS_INPUT -> PetPose(this, Arms.WAVE, extras = setOf(Extra.BANG))
    Mood.LOVE -> PetPose(this, Arms.HUG, extras = setOf(Extra.HEART))
}

/** Per-frame animation inputs, all derived from time and the cursor. */
data class PetFrame(
    val time: Float = 0f,
    /** 1 = eyes open, ~0.1 = closed. */
    val blink: Float = 1f,
    /** Where the eyes look, each in -1..1. */
    val lookX: Float = 0f,
    val lookY: Float = 0f,
    /** Extra squash after landing, 0..1. */
    val squash: Float = 0f,
)
