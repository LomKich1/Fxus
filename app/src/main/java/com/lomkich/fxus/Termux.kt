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

    private const val KILL = "pkill -x ollama 2>/dev/null || killall ollama 2>/dev/null"

    /**
     * Сторож простоя. Живёт в Termux независимо от приложения, поэтому работает, даже если Android убил Fxus.
     * Идея: сервер запущен с OLLAMA_KEEP_ALIVE=<N>m, значит модель выгружается ровно через N минут после последнего
     * запроса. Раз в 20 с смотрим `ollama ps`:
     *  - модель была загружена и пропала = N минут никто не пользовался, гасим сервер;
     *  - модель так и не загружали, а прошло N минут с запуска, гасим;
     *  - сервер перестал отвечать (остановили руками), сторож выходит сам.
     * Так учитывается и использование из других клиентов. «¤» заменяется на «$», чтобы Kotlin не трактовал
     * shell-переменные как свои шаблоны.
     */
    private val WATCHDOG = """
(
  start=¤(date +%s); ready=0; seen=0
  while true; do
    now=¤(date +%s)
    if out=¤(@OLLAMA@ ps 2>/dev/null); then
      ready=1
      if [ ¤(printf '%s\n' "¤out" | tail -n +2 | grep -c .) -gt 0 ]; then
        seen=1
      elif [ ¤seen = 1 ] || [ ¤((now - start)) -ge @LIMIT@ ]; then
        @KILL@; termux-wake-unlock 2>/dev/null; break
      fi
    elif [ ¤ready = 1 ] || [ ¤((now - start)) -ge 180 ]; then
      break
    fi
    sleep 20
  done
) >/dev/null 2>&1 &
""".trimIndent().replace('¤', '$')

    /**
     * Команда запуска: wake-lock, чтобы Android не усыпил Termux, сервер в отрыве от сессии (лог в ~/ollama.log)
     * и, если idleMin > 0, сторож простоя.
     */
    fun startScript(idleMin: Int): String {
        val keep = if (idleMin > 0) "export OLLAMA_KEEP_ALIVE=${idleMin}m; " else ""
        val serve = "termux-wake-lock 2>/dev/null; ${keep}nohup $OLLAMA serve > $HOME/ollama.log 2>&1 &"
        if (idleMin <= 0) return serve
        val watchdog = WATCHDOG
            .replace("@OLLAMA@", OLLAMA)
            .replace("@LIMIT@", (idleMin * 60).toString())
            .replace("@KILL@", KILL)
        return serve + "\n" + watchdog
    }

    /** SIGTERM всем процессам с именем ollama (сервер и его раннеры), заодно снимаем wake-lock. */
    const val STOP_OLLAMA = "pkill -x ollama 2>/dev/null || killall ollama 2>/dev/null; termux-wake-unlock 2>/dev/null"
}
