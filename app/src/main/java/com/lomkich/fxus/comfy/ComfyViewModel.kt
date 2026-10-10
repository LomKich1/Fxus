package com.lomkich.fxus.comfy

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

private fun JsonObject.patch(node: String, input: String, value: JsonElement): JsonObject {
    val n = this[node]?.jsonObject ?: error("В workflow нет ноды $node")
    val inputs = n["inputs"]?.jsonObject ?: error("У ноды $node нет inputs")
    val newNode = JsonObject(n + ("inputs" to JsonObject(inputs + (input to value))))
    return JsonObject(this + (node to newNode))
}

/** Загруженный и разобранный воркфлоу. */
class LoadedWorkflow(val id: String, val json: JsonObject, val spec: WorkflowSpec) {
    val title: String get() = WorkflowStore.title(id)

    /** Модель, прописанная в воркфлоу: пока ничего не выбрано, берётся она. */
    val defaultCkpt: String = spec.checkpoint
        ?.let { ((json[it] as? JsonObject)?.get("inputs") as? JsonObject)?.get("ckpt_name") }
        ?.let { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
}

/** Строка в списке выбора воркфлоу. */
data class WorkflowItem(
    val id: String,
    val title: String,
    val note: String,
    val enabled: Boolean,
    val canHide: Boolean,
    val canDelete: Boolean
)

class ComfyViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private val store = HistoryStore(app)
    private val saveLock = Mutex()

    val serverUrl = settings.url.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val tunnel = settings.tunnel.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val ckpt = settings.ckpt.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val seedFixed = settings.seedFixed.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val seedValue = settings.seed.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val turns = mutableStateListOf<Turn>()
    var size by mutableStateOf(Size.DEFAULT)
        private set
    val recentSizes = settings.recentSizes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Ключи уже проигранных анимаций появления (чтобы не повторять при скролле). */
    val animated: MutableSet<String> = mutableSetOf()

    // id по времени: уникальны и не пересекаются с восстановленными из истории
    private var nextId = System.currentTimeMillis()
    private var job: Job? = null
    private var activeClient: ComfyClient? = null

    private val wfStore = WorkflowStore(app)

    /** Активный воркфлоу; null только в первые миллисекунды после запуска. */
    var active by mutableStateOf<LoadedWorkflow?>(null)
        private set
    var workflowItems by mutableStateOf<List<WorkflowItem>>(emptyList())
        private set
    var hiddenItems by mutableStateOf<List<WorkflowItem>>(emptyList())
        private set
    private var hidden: Set<String> = emptySet()
    var wfBusy by mutableStateOf(false)
        private set
    var wfStatus by mutableStateOf<String?>(null)
        private set
    private var remoteList: List<RemoteWorkflow>? = null

    val defaultCkpt: String get() = active?.defaultCkpt.orEmpty()

    /** Есть ли в воркфлоу нода с моделью (иначе кнопку выбора модели прячем). */
    val hasCheckpoint: Boolean get() = active?.spec?.checkpoint != null

    private fun loadWorkflow(id: String): LoadedWorkflow {
        val bytes = wfStore.read(id) ?: throw WorkflowError("Файл воркфлоу не найден")
        val (json, spec) = WorkflowSpec.parse(bytes)
        return LoadedWorkflow(id, json, spec)
    }

    init {
        viewModelScope.launch {
            hidden = settings.hiddenWorkflows.first()
            val id = settings.workflow.first()
            active = withContext(Dispatchers.IO) {
                runCatching { loadWorkflow(id) }.getOrElse { loadWorkflow(WorkflowStore.BUILTIN) }
            }
            rebuildItems()
        }
        viewModelScope.launch { size = settings.size.first() }
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { store.load() }
            val restored = items.mapNotNull { s ->
                val f = store.fileOf(s.file)
                if (!f.exists()) null else Turn(
                    id = s.id,
                    prompt = s.prompt,
                    size = Size.parse(s.size) ?: Size.DEFAULT,
                    stage = "",
                    tags = s.tags,
                    file = f,
                    running = false
                )
            }
            restored.forEach {
                animated.add("u${it.id}")
                animated.add("b${it.id}")
            }
            turns.addAll(0, restored)
            nextId = maxOf(nextId, (restored.maxOfOrNull { it.id } ?: 0L) + 1)
        }
    }

    fun selectSize(s: Size) {
        size = s
        viewModelScope.launch {
            settings.setSize(s)
            if (Size.PRESETS.none { it.size == s }) settings.rememberCustomSize(s)
        }
    }

    fun setCkpt(name: String) {
        viewModelScope.launch { settings.setCkpt(name) }
    }

    /** Список чекпоинтов: сначала с основного адреса, потом через туннель; null, если никто не ответил. */
    suspend fun scanCheckpoints(): List<String>? {
        val urls = listOf(settings.url.first(), settings.tunnel.first()).filter { it.isNotBlank() }.distinct()
        return withContext(Dispatchers.IO) {
            for (u in urls) {
                val list = runCatching { ComfyClient(u).listCheckpoints().sortedBy { it.lowercase() } }.getOrNull()
                if (list != null) return@withContext list
            }
            null
        }
    }

    // ---------- воркфлоу: список, выбор, импорт, синхронизация с ПК ----------

    private fun rebuildItems() {
        val remote = remoteList
        val all = mutableListOf<WorkflowItem>()
        fun add(id: String, note: String, enabled: Boolean = true) {
            all += WorkflowItem(
                id = id,
                title = WorkflowStore.title(id),
                note = note,
                enabled = enabled,
                canHide = id != WorkflowStore.BUILTIN,
                canDelete = wfStore.exists(id)
            )
        }
        add(WorkflowStore.BUILTIN, "в приложении")
        remote?.forEach { r ->
            val id = "pc:${r.name}"
            val cached = wfStore.hashOf(id)
            val note = when {
                !r.valid -> "на ПК · не API-формат"
                cached == null -> "на ПК · не скачан"
                cached == r.hash -> "с ПК · актуальный"
                else -> "с ПК · есть обновление"
            }
            add(id, note, r.valid)
        }
        val remoteNames = remote?.map { it.name }?.toSet().orEmpty()
        wfStore.names("pc").filter { it !in remoteNames }.forEach { n ->
            add("pc:$n", if (remote == null) "с ПК · из кэша" else "с ПК · на ПК удалён")
        }
        wfStore.names("local").forEach { n -> add("local:$n", "с телефона") }

        val (hid, vis) = all.partition { it.id in hidden }
        workflowItems = vis
        hiddenItems = hid
    }

    /** Если убрали активный воркфлоу, возвращаемся на встроенный. */
    private suspend fun fallbackIfActive(id: String) {
        if (active?.id != id) return
        active = withContext(Dispatchers.IO) { loadWorkflow(WorkflowStore.BUILTIN) }
        settings.setWorkflow(WorkflowStore.BUILTIN)
    }

    fun hideWorkflow(item: WorkflowItem) {
        if (!item.canHide) return
        viewModelScope.launch {
            hidden = hidden + item.id
            settings.setHiddenWorkflows(hidden)
            fallbackIfActive(item.id)
            wfStatus = "«${item.title}» скрыт. Вернуть можно в разделе «Скрытые»"
            rebuildItems()
        }
    }

    fun unhideWorkflow(item: WorkflowItem) {
        viewModelScope.launch {
            hidden = hidden - item.id
            settings.setHiddenWorkflows(hidden)
            wfStatus = null
            rebuildItems()
        }
    }

    /** Удаляет файл с телефона. Файлы на ПК не трогаем. */
    fun deleteWorkflow(item: WorkflowItem) {
        if (!item.canDelete) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { wfStore.delete(item.id) }
            if (item.id.startsWith("local:") && item.id in hidden) {
                hidden = hidden - item.id
                settings.setHiddenWorkflows(hidden)
            }
            fallbackIfActive(item.id)
            val stillOnPc = remoteList?.any { "pc:${it.name}" == item.id } == true
            wfStatus = if (stillOnPc) {
                "Удалил копию с телефона. На ПК файл остался: чтобы убрать из списка, скрой его"
            } else {
                "Удалил «${item.title}»"
            }
            rebuildItems()
        }
    }

    /** Адрес, по которому ПК сейчас отвечает: дом, поиск в локалке, туннель. */
    private suspend fun reachableUrl(): String? {
        val home = settings.url.first()
        val tun = settings.tunnel.first()
        if (home.isNotBlank() && LanDiscovery.ping(home)) return home
        if (LanDiscovery.isLanUrl(home)) {
            LanDiscovery.find(LanDiscovery.portOf(home))?.let {
                settings.setUrl(it)
                return it
            }
        }
        if (tun.isNotBlank() && LanDiscovery.ping(tun)) return tun
        return null
    }

    /** Открытие списка: подтягиваем с ПК, что там лежит, и сверяем с кэшем. */
    fun refreshWorkflows() {
        rebuildItems()
        if (wfBusy) return
        viewModelScope.launch {
            wfBusy = true
            wfStatus = "Ищу ПК…"
            try {
                val url = reachableUrl()
                if (url == null) {
                    remoteList = null
                    wfStatus = "ПК недоступен, показываю сохранённые"
                } else {
                    val res = withContext(Dispatchers.IO) { runCatching { ComfyClient(url).listWorkflows() } }
                    res.onSuccess {
                        remoteList = it.sortedBy { w -> w.name.lowercase() }
                        wfStatus = if (it.isEmpty()) "На ПК в папке api_workflows пока пусто" else null
                    }.onFailure {
                        remoteList = null
                        wfStatus = if (it.message == "HTTP 404") {
                            "На ПК нет расширения app_bridge (или ComfyUI не перезапускали)"
                        } else {
                            "Не удалось получить список: ${it.message}"
                        }
                    }
                }
            } finally {
                wfBusy = false
                rebuildItems()
            }
        }
    }

    /** Докачивает файл с ПК, если его нет или он изменился. Без связи молча оставляет кэш. */
    private suspend fun syncPc(id: String) {
        val name = id.removePrefix("pc:")
        val remote = remoteList?.firstOrNull { it.name == name }
        val cached = wfStore.hashOf(id)
        if (remote == null && cached != null) return
        if (remote != null && remote.valid && cached == remote.hash) return
        val url = reachableUrl()
        if (url == null) {
            if (cached != null) return
            throw WorkflowError("ПК недоступен, а файл ещё не скачан")
        }
        val bytes = withContext(Dispatchers.IO) { ComfyClient(url).fetchWorkflow(name) }
        WorkflowSpec.parse(bytes)   // сначала проверяем, потом кладём в кэш
        wfStore.write(id, bytes)
    }

    fun selectWorkflow(item: WorkflowItem) {
        if (wfBusy || !item.enabled) return
        viewModelScope.launch {
            wfBusy = true
            wfStatus = null
            try {
                if (item.id.startsWith("pc:")) {
                    try {
                        syncPc(item.id)
                    } catch (e: java.io.IOException) {
                        if (wfStore.read(item.id) == null) throw WorkflowError("Не удалось скачать: ${e.message}")
                    }
                }
                val loaded = withContext(Dispatchers.IO) { loadWorkflow(item.id) }
                active = loaded
                settings.setWorkflow(item.id)
            } catch (e: WorkflowError) {
                wfStatus = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                wfStatus = "Не получилось: ${e.message ?: e}"
            } finally {
                wfBusy = false
                rebuildItems()
            }
        }
    }

    fun importWorkflow(uri: Uri) {
        viewModelScope.launch {
            wfBusy = true
            wfStatus = null
            try {
                val ctx = getApplication<Application>()
                val (name, bytes) = withContext(Dispatchers.IO) {
                    val n = ctx.contentResolver
                        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        ?: "workflow.json"
                    val b = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw WorkflowError("Не смог прочитать файл")
                    if (b.size > 5_000_000) throw WorkflowError("Файл слишком большой для воркфлоу")
                    n to b
                }
                WorkflowSpec.parse(bytes)
                val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val id = "local:" + if (clean.endsWith(".json", true)) clean else "$clean.json"
                wfStore.write(id, bytes)
                val loaded = withContext(Dispatchers.IO) { loadWorkflow(id) }
                active = loaded
                settings.setWorkflow(id)
                wfStatus = "Импортировал: ${loaded.title}"
            } catch (e: WorkflowError) {
                wfStatus = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                wfStatus = "Не получилось: ${e.message ?: e}"
            } finally {
                wfBusy = false
                rebuildItems()
            }
        }
    }

    /** Перед генерацией: если активный воркфлоу с ПК и там он изменился, берём свежий. Тихо, без ошибок. */
    private fun quickSync(client: ComfyClient, cur: LoadedWorkflow): LoadedWorkflow? {
        val name = cur.id.removePrefix("pc:")
        val r = client.listWorkflows().firstOrNull { it.name == name } ?: return null
        if (!r.valid || r.hash == wfStore.hashOf(cur.id)) return null
        val bytes = client.fetchWorkflow(name)
        val (json, spec) = WorkflowSpec.parse(bytes)
        wfStore.write(cur.id, bytes)
        return LoadedWorkflow(cur.id, json, spec)
    }

    fun saveSettings(url: String, tunnel: String, seedFixed: Boolean, seed: String) {
        viewModelScope.launch {
            settings.setUrl(url)
            settings.setTunnel(tunnel)
            settings.setSeed(seedFixed, seed)
        }
    }

    /** Останавливает локально и сразу (не ждём сервер), а interrupt шлём в фоне «по возможности». */
    fun stop() {
        val c = activeClient
        job?.cancel()
        if (c != null) viewModelScope.launch(Dispatchers.IO) { runCatching { c.interrupt() } }
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || job?.isActive == true) return
        val id = nextId++
        val sizeOpt = size
        turns.add(Turn(id = id, prompt = text, size = sizeOpt))
        startGeneration(id, text, sizeOpt)
    }

    /** Повтор неудавшейся генерации в том же сообщении. */
    fun retry(id: Long) {
        if (job?.isActive == true) return
        val t = turns.firstOrNull { it.id == id } ?: return
        update(id) { Turn(id = it.id, prompt = it.prompt, size = it.size) }
        startGeneration(id, t.prompt, t.size)
    }

    /** Удаляет сообщение вместе с картинкой (и из галереи, и с диска). */
    fun delete(id: Long) {
        if (removeTurn(id)) persist()
    }

    fun deleteMany(ids: Set<Long>) {
        var changed = false
        ids.forEach { if (removeTurn(it)) changed = true }
        if (changed) persist()
    }

    private fun removeTurn(id: Long): Boolean {
        val i = turns.indexOfFirst { it.id == id }
        if (i < 0) return false
        val t = turns[i]
        if (t.running) stop()
        turns.removeAt(i)
        animated.remove("u$id")
        animated.remove("b$id")
        t.file?.let { f ->
            Thumbs.evict(f.name)
            viewModelScope.launch(Dispatchers.IO) { f.delete() }
        }
        return true
    }

    private fun persist() {
        val snapshot = turns.mapNotNull { t ->
            t.file?.let { Saved(t.id, t.prompt, t.tags, it.name, t.size.key) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            saveLock.withLock { runCatching { store.save(snapshot) } }
        }
    }

    private fun startGeneration(id: Long, text: String, sizeOpt: Size) {
        job = viewModelScope.launch {
            activeClient = null
            try {
                var url = settings.url.first()
                // адрес в локалке мог поменяться (хотспот выдал другой IP): ищем ПК заново
                if (LanDiscovery.isLanUrl(url) && !LanDiscovery.ping(url)) {
                    update(id) { it.copy(stage = "Ищу ПК в сети…") }
                    val found = LanDiscovery.find(LanDiscovery.portOf(url))
                    if (found != null) {
                        url = found
                        settings.setUrl(found)
                    } else {
                        // ПК не в локалке: пробуем туннель
                        val tun = settings.tunnel.first()
                        if (tun.isNotBlank() && LanDiscovery.ping(tun)) url = tun
                    }
                    update(id) { it.copy(stage = "Отправляю…") }
                }
                val client = ComfyClient(url)
                activeClient = client
                val fixed = settings.seedFixed.first()
                val fixedSeed = settings.seed.first().toLongOrNull()
                val seed = if (fixed && fixedSeed != null) fixedSeed else Random.nextLong() ushr 14
                var cur = active ?: withContext(Dispatchers.IO) { loadWorkflow(WorkflowStore.BUILTIN) }
                if (cur.id.startsWith("pc:")) {
                    val before = cur
                    val fresh = withContext(Dispatchers.IO) { runCatching { quickSync(client, before) }.getOrNull() }
                    if (fresh != null) {
                        cur = fresh
                        active = fresh
                    }
                }
                val spec = cur.spec
                var wf = cur.json.patch(spec.prompt.node, spec.prompt.input, JsonPrimitive(text))
                spec.seed?.let { wf = wf.patch(it.node, it.input, JsonPrimitive(seed)) }
                spec.latent?.let {
                    wf = wf.patch(it, "width", JsonPrimitive(sizeOpt.w)).patch(it, "height", JsonPrimitive(sizeOpt.h))
                }
                val ck = settings.ckpt.first()
                val ckNode = spec.checkpoint
                if (ck.isNotBlank() && ckNode != null) wf = wf.patch(ckNode, "ckpt_name", JsonPrimitive(ck))

                client.generate(wf, spec).collect { ev ->
                    when (ev) {
                        is GenEvent.Stage -> update(id) { it.copy(stage = ev.text) }
                        is GenEvent.Progress -> update(id) { it.copy(percent = ev.value * 100 / ev.max) }
                        is GenEvent.Preview -> update(id) { it.copy(preview = ev.bitmap) }
                        is GenEvent.Tags -> update(id) { it.copy(tags = ev.text) }
                        is GenEvent.Done -> {
                            update(id) { it.copy(stage = "Загружаю картинку…") }
                            val bytes = ev.images.firstOrNull()?.let { client.fetchImageBytes(it) }
                            val file = bytes?.let {
                                withContext(Dispatchers.IO) {
                                    // сохраняем оригинал и сразу прогреваем кэш, чтобы не мигало
                                    store.write(it).also { f -> Thumbs.load(f, 1280) }
                                }
                            }
                            update(id) {
                                it.copy(
                                    file = file,
                                    preview = if (file == null) it.preview else null,
                                    running = false,
                                    error = if (file == null) "Не удалось получить картинку" else null
                                )
                            }
                            if (file != null) persist()
                        }
                        is GenEvent.Failed -> update(id) { it.copy(error = ev.message, running = false) }
                    }
                }
            } catch (e: CancellationException) {
                update(id) { it.copy(running = false, error = it.error ?: "Остановлено") }
                throw e
            } catch (e: Exception) {
                update(id) { it.copy(error = e.message ?: e.toString(), running = false) }
            } finally {
                // что бы ни случилось, интерфейс не должен остаться «в процессе»
                update(id) { if (it.running) it.copy(running = false) else it }
            }
        }
    }

    private fun update(id: Long, f: (Turn) -> Turn) {
        val i = turns.indexOfFirst { it.id == id }
        if (i >= 0) turns[i] = f(turns[i])
    }
}
