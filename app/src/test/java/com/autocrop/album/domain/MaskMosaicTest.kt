package com.autocrop.album.domain

import org.junit.Assert.*
import org.junit.Test

class MaskMosaicTest {
    @Test fun appliesTileAverageOnlyToSelectedPixels() {
        val pixels=intArrayOf(0xff000000.toInt(),0xffffffff.toInt(),0xffff0000.toInt(),0xff00ff00.toInt())
        val before=pixels.copyOf()
        MaskMosaic.renderTile(pixels,2,2,0,0,2,2,byteArrayOf(0,1,0,0),2,2)
        assertEquals(before[0],pixels[0]);assertEquals(before[2],pixels[2]);assertEquals(before[3],pixels[3])
        assertEquals(0xff7f7f3f.toInt(),pixels[1])
    }
    @Test fun previewMaskMapsToFullResolutionAtPixelCenters() {
        val mask=byteArrayOf(0,1,0,0)
        // A 2x2 mask projected onto 4x4 affects only the top-right 2x2 square.
        for(y in 0..3)for(x in 0..3)
            assertEquals(x>=2 && y<2,MaskMosaic.selected(mask,2,2,x,y,4,4))
    }
    @Test fun partialBorderTilesKeepOutsidePixelsAndAlpha() {
        val pixels=intArrayOf(0x40102030,0x80405060.toInt(),0xc0708090.toInt(),0x00abcdef)
        val before=pixels.copyOf()
        MaskMosaic.renderTile(pixels,1,3,3,1,4,4,ByteArray(16).apply { this[11]=1 },4,4)
        assertEquals(before[0],pixels[0]);assertEquals(before[2],pixels[2]);assertEquals(before[3],pixels[3])
        assertEquals(0x80,pixels[1] ushr 24)
    }
    @Test fun emptyMaskNeverChangesAnImageTile() {
        val pixels=IntArray(35){it*987654};val before=pixels.copyOf()
        MaskMosaic.renderTile(pixels,7,5,0,0,7,5,ByteArray(35),7,5)
        assertArrayEquals(before,pixels)
    }
}
