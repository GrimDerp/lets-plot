/*
 * Copyright (c) 2021. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package org.jetbrains.letsPlot.livemap.api

import org.jetbrains.letsPlot.commons.intern.spatial.GeoRectangle
import org.jetbrains.letsPlot.commons.intern.spatial.LonLat
import org.jetbrains.letsPlot.commons.intern.typedGeometry.Vec

class GeoObject(
    val id: String,
    val centroid: Vec<LonLat>,
    val bbox: GeoRectangle,
    val position: GeoRectangle,
)
