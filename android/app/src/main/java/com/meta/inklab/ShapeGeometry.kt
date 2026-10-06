package com.meta.inklab

import kotlin.math.*

enum class ShapeKind(val label: String) {
    LINE("直线"), ARROW("箭头"), CIRCLE("圆形"), ELLIPSE("椭圆"), RECTANGLE("矩形"), TRIANGLE("三角形"), DIAMOND("菱形")
}

data class ShapePoint(val x: Float, val y: Float)

object ShapeGeometry {
    fun points(kind: ShapeKind, start: ShapePoint, end: ShapePoint): List<ShapePoint> {
        var x = min(start.x, end.x); var y = min(start.y, end.y)
        var w = abs(end.x - start.x); var h = abs(end.y - start.y)
        fun p(px: Float, py: Float) = ShapePoint(px, py)
        return when (kind) {
            ShapeKind.LINE -> listOf(start, end)
            ShapeKind.ARROW -> {
                val angle = atan2(end.y-start.y, end.x-start.x)
                val head = min(18f * ShapeStrokeCodec.UNITS_PER_MM / (96f / 25.4f), hypot(end.x-start.x,end.y-start.y)*.25f)
                listOf(start,end,p(end.x-head*cos(angle-PI.toFloat()/6),end.y-head*sin(angle-PI.toFloat()/6)),end,
                    p(end.x-head*cos(angle+PI.toFloat()/6),end.y-head*sin(angle+PI.toFloat()/6)))
            }
            ShapeKind.CIRCLE, ShapeKind.ELLIPSE -> {
                if (kind == ShapeKind.CIRCLE) {
                    w = max(w,h); h = w
                    x = if (end.x >= start.x) start.x else start.x-w
                    y = if (end.y >= start.y) start.y else start.y-h
                }
                (0..128).map { val a = it*2*PI/128; p(x+w/2+w/2*cos(a).toFloat(), y+h/2+h/2*sin(a).toFloat()) }
            }
            ShapeKind.RECTANGLE -> listOf(p(x,y),p(x+w,y),p(x+w,y+h),p(x,y+h),p(x,y))
            ShapeKind.TRIANGLE -> listOf(p(x+w/2,y),p(x+w,y+h),p(x,y+h),p(x+w/2,y))
            ShapeKind.DIAMOND -> listOf(p(x+w/2,y),p(x+w,y+h/2),p(x+w/2,y+h),p(x,y+h/2),p(x+w/2,y))
        }
    }
}

class HoldToStraighten {
    private var start = ShapePoint(0f,0f)
    private var anchor = start
    private var last = start
    private var since = 0L
    private var length = 0f
    var active = false; private set
    var snapped = false; private set
    private fun distance(a: ShapePoint, b: ShapePoint) = hypot(a.x-b.x,a.y-b.y)
    fun begin(point: ShapePoint, now: Long) { start=point; anchor=point; last=point; since=now; length=0f; active=true; snapped=false }
    fun move(point: ShapePoint, now: Long, slop: Float) {
        if (!active) return
        last=point
        if (distance(point,anchor)>slop) { length += distance(point,anchor); anchor=point; since=now }
    }
    fun trySnap(now: Long, delay: Long): Boolean {
        val chord = distance(last,start)
        if (!active || snapped || now-since<delay.coerceIn(200L,2000L) || chord<8f || chord<length*.65f) return false
        snapped=true; return true
    }
    fun cancel() { active=false; snapped=false }
}
