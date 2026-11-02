package com.autocrop.album.domain

import org.junit.Assert.*
import org.junit.Test

class EditableMaskTest {
    @Test fun selectionsAccumulateAndUndoOneWholeSelection() {
        val mask = EditableMask(4, 1)
        mask.addSelection(byteArrayOf(1, 0, 0, 0))
        mask.addSelection(byteArrayOf(0, 0, 1, 0))
        assertArrayEquals(byteArrayOf(1, 0, 1, 0), mask.copyPixels())
        assertTrue(mask.undo());assertArrayEquals(byteArrayOf(1, 0, 0, 0),mask.copyPixels())
        assertTrue(mask.undo());assertFalse(mask.hasSelection)
    }
    @Test fun oneStrokeIsOneUndoIncludingInterpolatedFastDrag() {
        val mask = EditableMask(50, 10)
        mask.beginStroke();mask.paintLine(2f, 5f, 47f, 5f, 2f, true);mask.endStroke()
        for (x in 2..46) assertTrue(mask.selected(x, 5))
        mask.undo();assertFalse(mask.hasSelection)
    }
    @Test fun eraseAndUndoPreserveOtherSelections() {
        val mask = EditableMask(10, 10)
        mask.addSelection(ByteArray(100) { 1 })
        val before = mask.copyPixels()
        mask.beginStroke();mask.paintLine(5f, 5f, 5f, 5f, 2f, false);mask.endStroke()
        assertFalse(mask.selected(5, 5));assertTrue(mask.selected(0, 0))
        mask.undo();assertArrayEquals(before,mask.copyPixels())
    }
    @Test fun pinchOrCancelledTouchRollsBackOnlyCurrentStroke() {
        val mask = EditableMask(10,10)
        val point = ByteArray(100).apply { this[0]=1 }
        mask.addSelection(point)
        mask.beginStroke();mask.paintLine(5f,5f,9f,9f,2f,true);mask.cancelStroke()
        assertArrayEquals(point,mask.copyPixels())
        mask.undo();assertFalse(mask.hasSelection)
    }
    @Test fun outOfImageBrushDoesNotSelectOrAddUndoEntry() {
        val mask = EditableMask(10,10)
        mask.beginStroke();mask.paintLine(-20f,-20f,-15f,-15f,2f,true);mask.endStroke()
        assertFalse(mask.hasSelection);assertFalse(mask.canUndo)
    }
    @Test fun snapshotsAreNotMutableAliasesAndNoopSelectionDoesNotConsumeUndo() {
        val mask = EditableMask(2,1)
        mask.addSelection(byteArrayOf(1,0));mask.addSelection(byteArrayOf(1,0))
        mask.copyPixels()[1]=1
        assertFalse(mask.selected(1,0))
        assertTrue(mask.undo());assertFalse(mask.canUndo)
    }
}
