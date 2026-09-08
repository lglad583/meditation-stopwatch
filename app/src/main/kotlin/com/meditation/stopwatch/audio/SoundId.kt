package com.meditation.stopwatch.audio

/**
 * Every ambient sound the app can synthesise.  All sounds are generated procedurally at runtime –
 * there are no audio assets – so each one is described by a generator class (see SoundRegistry).
 */
enum class SoundId(val title: String, val subtitle: String, val defaultVolume: Float = 0f) {
    STREAM("Water stream", "a brook over stones"),
    RAIN("Rain", "steady rainfall on leaves"),
    THUNDER("Thunder", "distant rolling storms"),
    OCEAN("Ocean", "waves that follow your breath"),
    NATURE("Nature", "birdsong, crickets, soft wind"),
    WIND("Wind", "air moving through pines"),
    CAMPFIRE("Campfire", "crackle and low ember roar"),
    WHITE_NOISE("White noise", "flat spectrum hiss"),
    PINK_NOISE("Pink noise", "softer, natural balance"),
    BROWN_NOISE("Brown noise", "deep, rumbling warmth"),
    SINGING_BOWL("Singing bowl", "a drone that swells as you inhale"),
    BINAURAL("Binaural theta", "6 Hz beat – headphones only");
}
