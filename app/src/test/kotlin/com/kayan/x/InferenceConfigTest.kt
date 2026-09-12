package com.kayan.x

import com.kayan.x.engine.InferenceConfig
import com.kayan.x.engine.ModelPreset
import org.junit.Assert.*
import org.junit.Test

class InferenceConfigTest {

    @Test fun `default config is valid`() {
        val config = InferenceConfig(nGpuLayers = 0)
        assertFalse(config.isUserOverride)
        assertEquals(4096, config.nCtx)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid nCtx throws`() {
        InferenceConfig(nCtx = 64, nGpuLayers = 0)  // < 128
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid temperature throws`() {
        InferenceConfig(temperature = 3.0f, nGpuLayers = 0)  // > 2.0
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative gpu layers below -1 throws`() {
        InferenceConfig(nGpuLayers = -2)
    }

    @Test fun `nGpuLayers of -1 is valid (offload all)`() {
        val config = InferenceConfig(nGpuLayers = -1)
        assertEquals(-1, config.nGpuLayers)
    }

    @Test fun `nGpuLayers of 0 is valid (CPU only)`() {
        val config = InferenceConfig(nGpuLayers = 0)
        assertEquals(0, config.nGpuLayers)
    }

    @Test fun `preset 3B has correct defaults`() {
        val preset = ModelPreset.SIZE_3B
        assertEquals(4096, preset.nCtx)
        assertEquals(512,  preset.nBatch)
        assertEquals(3.0f, preset.paramsBillions)
    }

    @Test fun `preset 7B has smaller batch than 3B`() {
        assertTrue(ModelPreset.SIZE_7B.nBatch <= ModelPreset.SIZE_3B.nBatch)
    }

    @Test fun `fromLabel returns correct preset`() {
        assertEquals(ModelPreset.SIZE_1_5B, ModelPreset.fromLabel("1.5B"))
        assertEquals(ModelPreset.SIZE_7B,   ModelPreset.fromLabel("7B"))
        assertEquals(ModelPreset.SIZE_3B,   ModelPreset.fromLabel("unknown"))  // fallback
    }

    @Test fun `isUserOverride is false by default`() {
        assertFalse(InferenceConfig(nGpuLayers = 8).isUserOverride)
    }
}
