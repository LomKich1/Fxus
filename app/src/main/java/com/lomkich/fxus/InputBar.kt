package com.lomkich.fxus

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Size as AndroidSize

private enum class InputAction { MIC, SEND, STOP }

/** Какая панель сейчас открыта под полем ввода. */
private enum class Panel { NONE, MENU, PHOTOS }

/**
 * Поле ввода в стиле DeepSeek: сверху текст, снизу ряд «Рассуждение» / «Поиск» слева и «+» с кнопкой действия справа.
 * «+» открывает под полем панель из трёх пунктов (камера, фото, файлы). Панель лежит в потоке, а не поверх экрана
 * с затемнением, поэтому чат над ней остаётся живым. Над полем плашки ещё не отправленных вложений.
 */
@Composable
fun InputBar(
    busy: Boolean,
    pending: List<Attachment>,
    thinkOn: Boolean,
    searchOn: Boolean,
    onThink: () -> Unit,
    onSearch: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAttach: (List<Uri>) -> Unit,
    onPaste: (String) -> Unit,
    onRemove: (Attachment) -> Unit,
) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var text by rememberSaveable { mutableStateOf("") }
    var panel by rememberSaveable { mutableStateOf(Panel.NONE) }
    var photosExpanded by rememberSaveable { mutableStateOf(false) }
    // куда камера кладёт снимок; в rememberSaveable, потому что при повороте экрана камера может вернуться в новую активность
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val action = when {
        busy -> InputAction.STOP
        text.isBlank() && pending.isEmpty() -> InputAction.MIC
        else -> InputAction.SEND
    }

    val openPanel = { p: Panel ->
        focus.clearFocus()
        keyboard?.hide()
        panel = p
    }
    val closePanel = {
        panel = Panel.NONE
        photosExpanded = false
    }
    // назад: сначала сворачиваем развёрнутые фото, потом закрываем панель
    BackHandler(enabled = panel != Panel.NONE) {
        if (panel == Panel.PHOTOS && photosExpanded) photosExpanded = false else closePanel()
    }

    // Диктовка через системный распознаватель речи: своего аудио-кода у нас нет,
    // разрешение на микрофон не нужно. Распознанный текст дописывается в поле.
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) text = if (text.isBlank()) heard else "$text $heard"
    }

    // Системный выбор файлов, разрешения не нужны. Просим любые типы: у кода (.kt, .cpp…) MIME непредсказуемый,
    // поэтому текст это или картинка, решаем сами при чтении.
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onAttach(uris)
    }

    // Штатная камера: приложение просит снимок и получает его в наш файл. Разрешение CAMERA в манифесте
    // не объявлено, поэтому и запрашивать его не нужно.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) onAttach(listOf(uri))
        cameraUri = null
    }

    Column {
        if (pending.isNotEmpty()) {
            AttachmentRow(
                pending,
                chipBg = ColSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                thumb = 56.dp,
                onRemove = onRemove,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .glass(RoundedCornerShape(26.dp))
                .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 4.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text("Как я могу вам помочь сегодня?", color = ColMuted, fontSize = 16.sp)
                BasicTextField(
                    value = text,
                    onValueChange = { new ->
                        // огромная вставка из буфера уходит во вложение .txt, а поле остаётся с прежним текстом
                        val paste = Attachments.bigPaste(text, new)
                        if (paste != null) {
                            onPaste(paste.first)
                            text = paste.second
                        } else {
                            text = new
                        }
                    },
                    textStyle = TextStyle(color = ColText, fontSize = 16.sp),
                    cursorBrush = SolidColor(ColText),
                    maxLines = 6,
                    // тап в поле поднимает клавиатуру, и панель под полем тут же убираем
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused && panel != Panel.NONE) closePanel() },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // чипы прокручиваются, если экран узкий и они не помещаются рядом с кнопками справа
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToggleChip("Рассуждение", thinkOn, onThink) { AtomIcon(it) }
                    Spacer(Modifier.width(8.dp))
                    ToggleChip("Поиск", searchOn, onSearch) { GlobeIcon(it, 16.dp) }
                }
                AttachButton { if (panel == Panel.NONE) openPanel(Panel.MENU) else closePanel() }
                ActionButton(action) {
                    when (action) {
                        InputAction.STOP -> onStop()
                        InputAction.SEND -> {
                            onSend(text)
                            text = ""
                            closePanel()
                        }
                        InputAction.MIC -> {
                            try {
                                speech.launch(
                                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                )
                            } catch (e: ActivityNotFoundException) {
                                // на телефоне нет распознавателя речи, молча ничего не делаем
                            }
                        }
                    }
                }
            }
        }

        // панель под полем; animateContentSize плавно меняет высоту при открытии, закрытии и переходе меню -> фото
        Box(Modifier.fillMaxWidth().animateContentSize(tween(220))) {
            when (panel) {
                Panel.NONE -> {}
                Panel.MENU -> MenuPanel(
                    onCamera = {
                        closePanel()
                        val uri = newCameraUri(ctx)
                        cameraUri = uri
                        try {
                            camera.launch(uri)
                        } catch (e: ActivityNotFoundException) {
                            cameraUri = null   // на телефоне нет приложения камеры
                        }
                    },
                    onPhotos = { panel = Panel.PHOTOS },
                    onFiles = {
                        closePanel()
                        pickFiles.launch(arrayOf("*/*"))
                    },
                )
                Panel.PHOTOS -> PhotoPanel(
                    expanded = photosExpanded,
                    onExpanded = { photosExpanded = it },
                    onClose = closePanel,
                    onPick = onAttach,
                )
            }
        }
    }
}

// ---------- чипы «Рассуждение» / «Поиск» ----------

@Composable
private fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val fg = if (on) ColAccent else ColMuted
    val fill = if (on) ColAccent.copy(alpha = 0.14f) else Color.Transparent
    val edge = if (on) ColAccent.copy(alpha = 0.55f) else ColMuted.copy(alpha = 0.4f)
    Row(
        Modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, edge, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(fg)
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 13.sp, maxLines = 1)
    }
}

// ---------- меню «+» ----------

@Composable
private fun MenuPanel(onCamera: () -> Unit, onPhotos: () -> Unit, onFiles: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MenuTile("Камера", Modifier.weight(1f), onCamera) { CameraIcon(it) }
        MenuTile("Фото", Modifier.weight(1f), onPhotos) { ImageIcon(it) }
        MenuTile("Файлы", Modifier.weight(1f), onFiles) { FileIcon(it) }
    }
}

@Composable
private fun MenuTile(label: String, modifier: Modifier, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(ColSurface)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(ColUser), contentAlignment = Alignment.Center) {
            icon(ColText)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = ColText, fontSize = 14.sp)
    }
}

/** Новый пустой файл для снимка. Старые снимки стираем: свою копию (сжатую) вложение уже сделало. */
private fun newCameraUri(ctx: Context): Uri {
    val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
}

// ---------- панель с фото ----------

/**
 * Своя галерея вместо системного выбора. Сворачивается в нижнюю часть экрана (чат над ней остаётся живым),
 * по ручке тянется вверх и разворачивается почти на весь экран. Тап по фото выбирает его (цифра = порядок),
 * «Прикрепить» отправляет выбранное во вложения.
 */
@Composable
private fun PhotoPanel(
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    onClose: () -> Unit,
    onPick: (List<Uri>) -> Unit,
) {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // запасной вариант, если доступ к фото не дали: системный выбор
    val system = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            onPick(uris)
            onClose()
        }
    }

    var ids by remember { mutableStateOf<List<Long>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<Long>() }

    LaunchedEffect(granted) {
        if (granted) {
            ids = withContext(Dispatchers.IO) { queryImageIds(ctx) }
            loaded = true
        } else {
            ask.launch(perm)
        }
    }

    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val panelH = if (expanded) screenH * 0.78f else screenH * 0.40f

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .height(panelH)
            .clip(RoundedCornerShape(24.dp))
            .background(ColSurface),
    ) {
        // ручка и шапка. Свайп вверх разворачивает, вниз сворачивает (а со свёрнутого закрывает).
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(expanded) {
                    val threshold = 40.dp.toPx()
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            when {
                                total < -threshold -> onExpanded(true)
                                total > threshold -> if (expanded) onExpanded(false) else onClose()
                            }
                        },
                        onDragCancel = {},
                        onVerticalDrag = { _, dy -> total += dy },
                    )
                },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onExpanded(!expanded) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(ColMuted.copy(alpha = 0.5f)))
            }
            Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
                    contentAlignment = Alignment.Center,
                ) { CloseIcon(ColText) }
                Text("Фото", color = ColText, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (chosen.isNotEmpty()) {
                    Text(
                        "Прикрепить (${chosen.size})",
                        color = ColOnAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(ColAccent)
                            .clickable {
                                val uris = chosen.map { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
                                chosen.clear()
                                onPick(uris)
                                onClose()
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }

        when {
            !granted -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Чтобы показать галерею прямо здесь, нужен доступ к фото.",
                    color = ColMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                PanelButton("Разрешить", primary = true) { ask.launch(perm) }
                Spacer(Modifier.height(8.dp))
                PanelButton("Системный выбор", primary = false) {
                    system.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            loaded && ids.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Фото не найдено", color = ColMuted, fontSize = 14.sp)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(ids, key = { it }) { id ->
                    PhotoCell(id, chosen.indexOf(id)) {
                        if (!chosen.remove(id)) chosen.add(id)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (primary) ColOnAccent else ColText,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (primary) ColAccent else ColUser)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

@Composable
private fun PhotoCell(id: Long, order: Int, onClick: () -> Unit) {
    val ctx = LocalContext.current
    // кэш, чтобы при прокрутке туда-сюда превью не декодировались заново
    val bmp by produceState<ImageBitmap?>(ThumbCache.get(id)?.asImageBitmap(), id) {
        if (value == null) value = withContext(Dispatchers.IO) { ThumbCache.load(ctx, id)?.asImageBitmap() }
    }
    Box(Modifier.aspectRatio(1f).background(ColUser).clickable(onClick = onClick)) {
        bmp?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (order >= 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(ColAccent),
                contentAlignment = Alignment.Center,
            ) {
                Text("${order + 1}", color = ColOnAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape))
        }
    }
}

/** Id последних фото, новые первыми. Сами картинки не читаем: превью грузятся лениво по мере прокрутки. */
private fun queryImageIds(ctx: Context, limit: Int = 1000): List<Long> {
    val out = ArrayList<Long>()
    val args = Bundle().apply {
        putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
        putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
        putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
    }
    try {
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            args,
            null,
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) out.add(c.getLong(col))
        }
    } catch (e: Exception) {
        // нет доступа или галерея недоступна: показываем пустой список
    }
    return out
}

/** Кэш превью галереи по размеру в памяти (24 МБ), а не по числу картинок. */
private object ThumbCache {
    private val cache = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    fun get(id: Long): Bitmap? = cache.get(id)

    fun load(ctx: Context, id: Long): Bitmap? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        return try {
            ctx.contentResolver.loadThumbnail(uri, AndroidSize(240, 240), null).also { cache.put(id, it) }
        } catch (e: Exception) {
            null
        }
    }
}

// ---------- иконки ----------

@Composable
private fun AtomIcon(color: Color, iconSize: Dp = 16.dp) {
    Canvas(Modifier.size(iconSize)) {
        val sw = 1.4.dp.toPx()
        val c = center
        val rx = size.width * 0.46f
        val ry = size.height * 0.18f
        for (angle in listOf(0f, 60f, 120f)) {
            rotate(angle, c) {
                drawOval(color, topLeft = Offset(c.x - rx, c.y - ry), size = Size(rx * 2f, ry * 2f), style = Stroke(sw))
            }
        }
        drawCircle(color, radius = sw * 1.3f, center = c)
    }
}

@Composable
private fun CameraIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.7.dp.toPx()
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.04f, h * 0.24f),
            size = Size(w * 0.92f, h * 0.66f),
            cornerRadius = CornerRadius(w * 0.14f),
            style = Stroke(sw),
        )
        drawCircle(color, radius = w * 0.2f, center = Offset(w / 2f, h * 0.57f), style = Stroke(sw))
        drawLine(color, Offset(w * 0.32f, h * 0.13f), Offset(w * 0.68f, h * 0.13f), sw, StrokeCap.Round)
    }
}

@Composable
private fun ImageIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.7.dp.toPx()
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.06f, h * 0.1f),
            size = Size(w * 0.88f, h * 0.8f),
            cornerRadius = CornerRadius(w * 0.14f),
            style = Stroke(sw),
        )
        drawCircle(color, radius = w * 0.07f, center = Offset(w * 0.32f, h * 0.35f))
        val hills = Path().apply {
            moveTo(w * 0.12f, h * 0.78f)
            lineTo(w * 0.38f, h * 0.52f)
            lineTo(w * 0.58f, h * 0.7f)
            lineTo(w * 0.72f, h * 0.58f)
            lineTo(w * 0.88f, h * 0.76f)
        }
        drawPath(hills, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun FileIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.7.dp.toPx()
        val page = Path().apply {
            moveTo(w * 0.2f, h * 0.06f)
            lineTo(w * 0.6f, h * 0.06f)
            lineTo(w * 0.82f, h * 0.28f)
            lineTo(w * 0.82f, h * 0.94f)
            lineTo(w * 0.2f, h * 0.94f)
            close()
        }
        drawPath(page, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val tip = Offset(w * 0.51f, h * 0.46f)
        drawLine(color, Offset(w * 0.51f, h * 0.8f), tip, sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.38f, h * 0.58f), tip, sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.64f, h * 0.58f), tip, sw, StrokeCap.Round)
    }
}

@Composable
private fun CloseIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val sw = 1.8.dp.toPx()
        drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), sw, StrokeCap.Round)
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), sw, StrokeCap.Round)
    }
}

// ---------- кнопка действия (микрофон / отправить / стоп) ----------

/**
 * Смена иконки: кнопка сжимается в ноль, меняется содержимое, растёт обратно.
 * Нажимаемая область фиксированные 44dp, сам круг 36dp.
 */
@Composable
private fun ActionButton(action: InputAction, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(action) }
    LaunchedEffect(action) {
        if (shown != action) {
            scale.animateTo(0f, tween(110))
            shown = action
        }
        scale.animateTo(1f, tween(110))
    }
    val isMic = shown == InputAction.MIC
    Box(
        Modifier
            .size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .clip(CircleShape)
                .background(if (isMic) ColUser else ColAccent),
            contentAlignment = Alignment.Center,
        ) {
            ActionIcon(shown, if (isMic) ColText else ColOnAccent)
        }
    }
}

@Composable
private fun ActionIcon(action: InputAction, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()
        when (action) {
            InputAction.SEND -> {
                // стрелка вверх: ствол и два крыла
                drawLine(color, Offset(w / 2f, h * 0.92f), Offset(w / 2f, h * 0.08f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.14f, h * 0.44f), Offset(w / 2f, h * 0.08f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.86f, h * 0.44f), Offset(w / 2f, h * 0.08f), sw, StrokeCap.Round)
            }
            InputAction.STOP -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = Size(w * 0.68f, h * 0.68f),
                    cornerRadius = CornerRadius(w * 0.12f),
                )
            }
            InputAction.MIC -> {
                // капсула, дуга-подставка снизу и ножка
                drawRoundRect(
                    color,
                    topLeft = Offset(w * 0.35f, 0f),
                    size = Size(w * 0.30f, h * 0.58f),
                    cornerRadius = CornerRadius(w * 0.15f),
                )
                drawArc(
                    color,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.15f, h * 0.18f),
                    size = Size(w * 0.70f, h * 0.55f),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
                drawLine(color, Offset(w / 2f, h * 0.73f), Offset(w / 2f, h * 0.95f), sw, StrokeCap.Round)
            }
        }
    }
}
