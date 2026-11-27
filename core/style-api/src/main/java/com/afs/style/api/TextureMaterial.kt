package com.afs.style.api

import android.content.Context
import android.graphics.*

class SceneImage(context: Context, path: String) : AutoCloseable {
    var bitmap: Bitmap? = context.assets.open(path).use { BitmapFactory.decodeStream(it) }
        private set
    init { check(bitmap != null) { "Invalid scene texture: $path" } }
    override fun close() { bitmap = null }
}

class TextureMaterial(context: Context, source: String, bitmap: Bitmap) : AutoCloseable {
    private var shader: RuntimeShader? = RuntimeShader(context.assets.open(source).bufferedReader().use { it.readText() })
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val isClosed get() = shader == null
    init {
        val texture = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        texture.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        shader!!.setInputShader("image", texture)
        shader!!.setFloatUniform("textureSize", bitmap.width.toFloat(), bitmap.height.toFloat())
    }
    fun draw(canvas: Canvas, width: Float, height: Float, viewportWidth: Float, viewportHeight: Float,
             originX: Float, originY: Float, time: Float, fingerX: Float, fingerY: Float,
             pressure: Float, releaseAge: Float, material: Float, pageAge: Float = 10f,
             alpha: Int = 255) {
        val live = shader ?: return
        if (width <= 0f || height <= 0f || viewportWidth <= 0f || viewportHeight <= 0f) return
        live.setFloatUniform("resolution", viewportWidth, viewportHeight)
        live.setFloatUniform("extent", width, height)
        live.setFloatUniform("origin", originX, originY)
        live.setFloatUniform("time", time)
        live.setFloatUniform("finger", fingerX, fingerY)
        live.setFloatUniform("pressure", pressure)
        live.setFloatUniform("releaseAge", releaseAge)
        live.setFloatUniform("material", material)
        live.setFloatUniform("pageAge", pageAge)
        paint.alpha = alpha.coerceIn(0, 255)
        paint.shader = live
        canvas.drawRect(0f, 0f, width, height, paint)
    }
    override fun close() { paint.shader = null; shader = null }
}
