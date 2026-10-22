package com.lomkich.fxus

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Чтение выбранных файлов во вложения. Все методы блокирующие, вызывать из Dispatchers.IO. */
object Attachments {

    /** Текстовый файл больше этого размера не берём: в контекст модели он всё равно не влезет. */
    const val MAX_TEXT_BYTES = 1_000_000

    /** Одна вставка из буфера длиннее этого превращается во вложение .txt. */
    const val PASTE_LIMIT = 2000

    /** Длинная сторона картинки после сжатия. Vision-модели всё равно ужимают до ~900 px. */
    private const val IMAGE_SIDE = 1024

    sealed interface Result {
        class Ok(val att: Attachment) : Result
        class Fail(val why: String) : Result
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "attachments").apply { mkdirs() }

    fun imageFile(ctx: Context, a: Attachment) = File(dir(ctx), a.file)

    fun delete(ctx: Context, list: List<Attachment>) {
        list.filter { it.kind == AttachKind.IMAGE }.forEach { imageFile(ctx, it).delete() }
    }

    fun load(ctx: Context, uri: Uri): Result {
        val name = displayName(ctx, uri)
        return try {
            val mime = ctx.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("image/")) loadImage(ctx, uri, name) else loadText(ctx, uri, name)
        } catch (e: Exception) {
            Result.Fail("Не удалось прочитать «$name»: ${e.message}")
        }
    }

    // ---------- текст ----------

    private fun loadText(ctx: Context, uri: Uri, name: String): Result {
        val input = ctx.contentResolver.openInputStream(uri) ?: return Result.Fail("Не удалось открыть «$name»")
        val bytes = input.use { readLimited(it, MAX_TEXT_BYTES) }
            ?: return Result.Fail("«$name» больше ${MAX_TEXT_BYTES / 1000} КБ, это слишком много для контекста модели.")
        if (bytes.isEmpty()) return Result.Fail("«$name» пустой.")
        // в настоящем тексте нулевых байтов не бывает, они значат бинарный файл (архив, exe, pdf)
        if (bytes.take(8000).any { it == 0.toByte() }) return Result.Fail("«$name» не похож на текстовый файл.")
        val text = decode(bytes)
        return Result.Ok(Attachment(AttachKind.TEXT, name, text = text, bytes = bytes.size))
    }

    /** Читает не больше max байт; если файл длиннее, возвращает null и не раздувает память. */
    private fun readLimited(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Сначала строго как UTF-8. Если не вышло, значит файл в старой кодировке:
     * блокнот в Windows по умолчанию мог сохранять русский текст как windows-1251.
     */
    fun decode(bytes: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, charset("windows-1251"))
        }
        return text.removePrefix("\uFEFF")
    }

    /**
     * Если за одно изменение в поле добавилось >= PASTE_LIMIT символов, это вставка из буфера.
     * Возвращает (вставленный текст, что осталось в поле) или null, если это обычный набор.
     * Вставку находим как «середину», которая отличается: общее начало и общий конец старого и нового текста не трогаем.
     */
    fun bigPaste(old: String, new: String): Pair<String, String>? {
        if (new.length - old.length < PASTE_LIMIT) return null
        var p = 0
        val maxP = minOf(old.length, new.length)
        while (p < maxP && old[p] == new[p]) p++
        var s = 0
        while (s < old.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        val inserted = new.substring(p, new.length - s)
        if (inserted.length < PASTE_LIMIT) return null
        return inserted to (new.substring(0, p) + new.substring(new.length - s))
    }

    // ---------- картинки ----------

    private fun loadImage(ctx: Context, uri: Uri, name: String): Result {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return Result.Fail("«$name» не удалось открыть как картинку.")

        // грубое уменьшение при чтении: 12-мегапиксельное фото целиком в памяти нам ни к чему
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return Result.Fail("«$name» не удалось открыть как картинку.")

        // JPEG не умеет прозрачность: прозрачные места стали бы чёрными, поэтому кладём на белый фон
        if (bmp.hasAlpha()) {
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                drawBitmap(bmp, 0f, 0f, null)
            }
            bmp = flat
        }

        // точной подгонки до IMAGE_SIDE и поворота по EXIF (фото с камеры лежат боком)
        val m = Matrix()
        val scale = IMAGE_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) m.postScale(scale, scale)
        val rotation = exifRotation(cr, uri)
        if (rotation != 0) m.postRotate(rotation.toFloat())
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)

        val fileName = "${UUID.randomUUID()}.jpg"
        val file = File(dir(ctx), fileName)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return Result.Ok(Attachment(AttachKind.IMAGE, name, file = fileName, bytes = file.length().toInt()))
    }

    private fun exifRotation(cr: ContentResolver, uri: Uri): Int = try {
        cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (e: Exception) {
        0
    }

    /** Маленькая копия для плашки в интерфейсе (1024 / 4 = 256 px). */
    fun thumb(ctx: Context, a: Attachment): Bitmap? =
        BitmapFactory.decodeFile(imageFile(ctx, a).path, BitmapFactory.Options().apply { inSampleSize = 4 })

    /** Картинка для запроса к модели в base64 (без переводов строки). */
    fun base64(ctx: Context, a: Attachment): String? {
        val f = imageFile(ctx, a)
        return if (f.exists()) Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else null
    }

    // ---------- мелочи ----------

    private fun displayName(ctx: Context, uri: Uri): String {
        val fromProvider = ctx.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun sizeLabel(bytes: Int): String = if (bytes < 1024) "$bytes Б" else "${(bytes + 512) / 1024} КБ"
}
