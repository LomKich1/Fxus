package com.lomkich.fxus.comfy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun key(f: File, side: Int) = "${f.name}@$side"

    fun peek(f: File, side: Int): Bitmap? = cache.get(key(f, side))

    /** Декодирует с уменьшением так, чтобы большая сторона была не меньше [side] (если картинка больше). */
    fun load(f: File, side: Int): Bitmap? {
        peek(f, side)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        val big = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (big / (sample * 2) >= side) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        cache.put(key(f, side), bmp)
        return bmp
    }

    fun evict(name: String) {
        cache.snapshot().keys.filter { it.startsWith("$name@") }.forEach { cache.remove(it) }
    }
}

@Composable
fun rememberFileBitmap(file: File?, side: Int): Bitmap? {
    val state = produceState<Bitmap?>(
        initialValue = if (file != null) Thumbs.peek(file, side) else null,
        file,
        side
    ) {
        value = if (file == null) null else withContext(Dispatchers.IO) { Thumbs.load(file, side) }
    }
    return state.value
}
