package com.kennycason.kumo.collide

import dev.etorix.panoscrobbler.graphics.KumoPoint
import dev.etorix.panoscrobbler.graphics.KumoRect
import com.kennycason.kumo.image.CollisionRaster

/**
 * Created by kenny on 6/29/14.
 */
interface Collidable {
    fun collide(collidable: Collidable): Boolean
    val position: KumoPoint
    val dimension: KumoRect
    val collisionRaster: CollisionRaster
}
