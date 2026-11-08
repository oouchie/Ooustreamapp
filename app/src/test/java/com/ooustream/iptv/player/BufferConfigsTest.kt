package com.ooustream.iptv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class BufferConfigsTest {
    private val mb = 1024L * 1024L

    @Test fun aftkrt384MbHeapGets76Mb() = assertEquals(80_530_636L, BufferConfigs.heapTargetBufferBytes(384 * mb).toLong())
    @Test fun ooustick256MbHeapGets51Mb() = assertEquals((256 * mb * 0.20).toLong(), BufferConfigs.heapTargetBufferBytes(256 * mb).toLong())
    @Test fun smallHeapNeverBelowLowMemoryCap() = assertEquals(20 * mb, BufferConfigs.heapTargetBufferBytes(64 * mb).toLong())
    @Test fun hugeHeapNeverAboveMedia3VideoDefault() = assertEquals(128 * mb, BufferConfigs.heapTargetBufferBytes(2048 * mb).toLong())
    @Test fun defaultUsesRuntimeMaxMemory() {
        val expected = BufferConfigs.heapTargetBufferBytes(Runtime.getRuntime().maxMemory())
        assertEquals(expected, BufferConfigs.heapTargetBufferBytes())
    }
}
