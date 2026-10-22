package com.motioncut.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoInfoTest {

    @Test
    fun resolutionIsShownAsWidthByHeight() {
        assertEquals("1920×1080", VideoInfo(10_000L, 1920, 1080).resolutionLabel)
        assertEquals("1080×1920", VideoInfo(10_000L, 1080, 1920).resolutionLabel)
    }

    @Test
    fun unknownOrEmptySizeHasNoLabel() {
        assertNull(VideoInfo(10_000L, null, null).resolutionLabel)
        assertNull(VideoInfo(10_000L, 1920, null).resolutionLabel)
        assertNull(VideoInfo(10_000L, null, 1080).resolutionLabel)
        assertNull(VideoInfo(10_000L, 0, 1080).resolutionLabel)
        assertNull(VideoInfo(null, null, null).resolutionLabel)
    }
}
