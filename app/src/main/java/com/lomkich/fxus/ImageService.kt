package com.lomkich.fxus

import com.lomkich.fxus.comfy.ComfyViewModel
import com.lomkich.fxus.comfy.Size

/** Что чату нужно от генератора картинок. Так ChatViewModel не знает про ComfyUI. */
interface ImageService {
    /** Запускает генерацию. Возвращает id картинки или null, если генерация уже идёт. */
    fun generate(prompt: String, width: Int, height: Int): Long?
}

class ComfyImageService(private val comfy: ComfyViewModel) : ImageService {
    override fun generate(prompt: String, width: Int, height: Int): Long? =
        comfy.send(prompt, Size(width, height))
}
