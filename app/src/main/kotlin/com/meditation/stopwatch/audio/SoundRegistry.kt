package com.meditation.stopwatch.audio

import com.meditation.stopwatch.audio.generators.BinauralGenerator
import com.meditation.stopwatch.audio.generators.BrownNoiseGenerator
import com.meditation.stopwatch.audio.generators.CampfireGenerator
import com.meditation.stopwatch.audio.generators.NatureGenerator
import com.meditation.stopwatch.audio.generators.OceanGenerator
import com.meditation.stopwatch.audio.generators.PinkNoiseGenerator
import com.meditation.stopwatch.audio.generators.RainGenerator
import com.meditation.stopwatch.audio.generators.SingingBowlGenerator
import com.meditation.stopwatch.audio.generators.StreamGenerator
import com.meditation.stopwatch.audio.generators.ThunderGenerator
import com.meditation.stopwatch.audio.generators.WhiteNoiseGenerator
import com.meditation.stopwatch.audio.generators.WindGenerator

/** Maps every [SoundId] to a fresh generator instance. */
object SoundRegistry {
    fun create(id: SoundId): SoundGenerator = when (id) {
        SoundId.STREAM -> StreamGenerator()
        SoundId.RAIN -> RainGenerator()
        SoundId.THUNDER -> ThunderGenerator()
        SoundId.OCEAN -> OceanGenerator()
        SoundId.NATURE -> NatureGenerator()
        SoundId.WIND -> WindGenerator()
        SoundId.CAMPFIRE -> CampfireGenerator()
        SoundId.WHITE_NOISE -> WhiteNoiseGenerator()
        SoundId.PINK_NOISE -> PinkNoiseGenerator()
        SoundId.BROWN_NOISE -> BrownNoiseGenerator()
        SoundId.SINGING_BOWL -> SingingBowlGenerator()
        SoundId.BINAURAL -> BinauralGenerator()
    }
}
