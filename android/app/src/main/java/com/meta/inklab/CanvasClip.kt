package com.meta.inklab

import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build

@Suppress("DEPRECATION")
internal fun Canvas.rejects(bounds: RectF): Boolean =
    if (Build.VERSION.SDK_INT >= 30) quickReject(bounds) else quickReject(bounds, Canvas.EdgeType.AA)
