package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import kotlin.math.hypot

class ShapeGeometryTest {
    @Test fun timerWorksWithoutMoveEventsAndIgnoresNibJitter() {
        val hold=HoldToStraighten()
        hold.begin(ShapePoint(0f,0f),0)
        hold.move(ShapePoint(100f,30f),100,2.5f)
        for (i in 1..50) hold.move(ShapePoint(100f+(i%2),30f),100+i*10L,2.5f)
        assertFalse(hold.trySnap(699,600))
        assertTrue(hold.trySnap(700,600))
        assertFalse(hold.trySnap(800,600))
        hold.cancel(); assertFalse(hold.trySnap(2000,600))
    }
    @Test fun movementRestartsDwellAndClosedScribbleNeverStraightens() {
        val hold=HoldToStraighten()
        hold.begin(ShapePoint(0f,0f),0)
        hold.move(ShapePoint(100f,0f),10,2f)
        hold.move(ShapePoint(130f,10f),550,2f)
        assertFalse(hold.trySnap(1000,600)); assertTrue(hold.trySnap(1150,600))
        hold.begin(ShapePoint(0f,0f),0)
        hold.move(ShapePoint(100f,0f),100,2f); hold.move(ShapePoint(10f,0f),200,2f)
        assertFalse(hold.trySnap(2000,600))
    }
    @Test fun circlesAreCircularAndPolygonsRetainCornersInEveryDragDirection() {
        for (end in listOf(ShapePoint(120f,80f),ShapePoint(-120f,-80f))) {
            val points=ShapeGeometry.points(ShapeKind.CIRCLE,ShapePoint(0f,0f),end)
            val cx=(points.minOf{it.x}+points.maxOf{it.x})/2
            val cy=(points.minOf{it.y}+points.maxOf{it.y})/2
            points.forEach { assertEquals(60f,hypot(it.x-cx,it.y-cy),.001f) }
            val rect=ShapeGeometry.points(ShapeKind.RECTANGLE,ShapePoint(0f,0f),end)
            assertEquals(5,rect.size); assertEquals(rect.first(),rect.last())
            assertTrue(rect.zipWithNext().all { (a,b) -> a.x==b.x || a.y==b.y })
        }
    }
    @Test fun legacyToolSettingsGainShapeToolAndTimingSurvivesRestart() {
        val shelf=ToolShelf.fromJson("""{"tools":[{"id":"pen-black","kind":"PEN"}],"selectedId":"pen-black"}""")!!
        assertEquals("pen-black",shelf.selectedId)
        val shape=shelf.tools.first{it.id=="shape-default"}
        shape.shapeKind=ShapeKind.RECTANGLE; shape.straightenDelayMillis=1250; shape.holdToStraightenEnabled=false
        val restored=ToolShelf.fromJson(shelf.toJson())!!.tools.first{it.id=="shape-default"}
        assertEquals(ShapeKind.RECTANGLE,restored.shapeKind); assertEquals(1250L,restored.straightenDelayMillis); assertFalse(restored.holdToStraightenEnabled)
        assertEquals(2000L,ToolPreset.fromJson(JSONObject(shape.toJson().toString()).put("straightenDelayMillis",99999))!!.straightenDelayMillis)
    }
    @Test fun pageJsonKeepsEditableShapeRecordsAndUnknownStrokeFields() {
        val json=JSONObject("""{"notebookId":"n","pageId":"p","objects":[],"strokes":[{"id":"s","kind":0,"color":1,"brushProfile":"shape-v1","widthMm":0.4,"points":[{"x":0,"y":0},{"x":100,"y":50}],"customFutureField":true}]}""")
        val restored=PageInsertDocument.fromJson(PageInsertDocument.fromJson(json).toJson())
        assertEquals("s",restored.strokes.getJSONObject(0).getString("id")); assertTrue(restored.strokes.getJSONObject(0).getBoolean("customFutureField"))
    }
}
