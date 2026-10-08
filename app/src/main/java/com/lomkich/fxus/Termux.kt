package com.lomkich.fxus

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Отправка команд в Termux через его RUN_COMMAND (нужны allow-external-apps=true в termux.properties
 * и разрешение com.termux.permission.RUN_COMMAND). Ответа от Termux мы не получаем:
 * получилось или нет, видно только по тому, начал ли отвечать сервер Ollama.
 */
object Termux {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val HOME = "/data/data/com.termux/files/home"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val OLLAMA = "/data/data/com.termux/files/usr/bin/ollama"

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Фоновый запуск bash -c <script>. Возвращает текст ошибки или null, если команда ушла. */
    fun run(ctx: Context, script: String): String? {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(PACKAGE, "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        return try {
            ctx.startService(intent)
            null
        } catch (e: SecurityException) {
            "нет разрешения RUN_COMMAND"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** wake-lock, чтобы Android не усыпил Termux, и сервер в отрыве от сессии. Лог в ~/ollama.log. */
    val START_OLLAMA = "termux-wake-lock 2>/dev/null; nohup $OLLAMA serve > $HOME/ollama.log 2>&1 &"

    /** SIGTERM всем процессам с именем ollama (сервер и его раннеры), заодно снимаем wake-lock. */
    const val STOP_OLLAMA = "pkill -x ollama 2>/dev/null || killall ollama 2>/dev/null; termux-wake-unlock 2>/dev/null"
}
