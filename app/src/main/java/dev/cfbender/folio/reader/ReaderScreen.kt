package dev.cfbender.folio.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dev.cfbender.folio.R
import dev.cfbender.folio.container
import dev.cfbender.folio.data.ReaderPrefs
import dev.cfbender.folio.data.ReaderTheme
import dev.cfbender.folio.epub.TocEntry
import dev.cfbender.folio.render.Typography
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Test tag of the node that carries the words of the page on screen. */
const val PAGE_TAG = "page"

fun pageColors(theme: ReaderTheme, systemDark: Boolean): PageColors = when (theme) {
    ReaderTheme.AUTO -> pageColors(if (systemDark) ReaderTheme.DARK else ReaderTheme.PAPER, systemDark)
    ReaderTheme.PAPER -> PageColors(paper = 0xFFFBF6EC.toInt(), ink = 0xFF221E19.toInt(), muted = 0xFF948A7D.toInt(), dark = false)
    ReaderTheme.SEPIA -> PageColors(paper = 0xFFF0E2C4.toInt(), ink = 0xFF3B2D1B.toInt(), muted = 0xFF9A8767.toInt(), dark = false)
    ReaderTheme.DARK -> PageColors(paper = 0xFF1B1A18.toInt(), ink = 0xFFCDC7BC.toInt(), muted = 0xFF7C766C.toInt(), dark = true)
}

/** The book's pages, drawn with whatever turn [controller] has in progress. */
@Composable
fun BookPages(
    geometry: ReaderGeometry,
    colors: PageColors,
    typeface: Typeface,
    previous: Sheet?,
    current: Sheet?,
    next: Sheet?,
    controller: TurnController,
    imageVersion: Int,
    bitmap: (String) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val painter = remember(typeface, density) { PagePainter(density, typeface, bitmap) }
    Canvas(modifier.fillMaxSize()) {
        val frame = controller.active?.let { turnFrame(geometry, it, previous, current, next) }
        drawIntoCanvas { painter.draw(it.nativeCanvas, geometry, colors, current, frame, imageVersion) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit, controller: TurnController = rememberTurnController()) {
    val context = LocalContext.current
    val container = context.container
    val vm: ReaderViewModel = viewModel(
        key = "reader:$bookId",
        factory = viewModelFactory {
            initializer { ReaderViewModel(bookId, container.library, container.settings, container.koSync, container.scope) }
        },
    )
    val scope = rememberCoroutineScope()
    val storedPrefs by container.settings.readerPrefs.collectAsStateWithLifecycle(initialValue = null)
    val systemDark = isSystemInDarkTheme()
    val colors = pageColors(storedPrefs?.theme ?: ReaderTheme.AUTO, systemDark)
    val typeface = remember { ResourcesCompat.getFont(context, R.font.literata) ?: Typeface.SERIF }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val activity = LocalActivity.current
    val fold = activity?.let { rememberVerticalFold(it) }
    val insets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val edgeInsets = EdgeInsets(
        insets.getLeft(density, layoutDirection),
        insets.getTop(density),
        insets.getRight(density, layoutDirection),
        insets.getBottom(density),
    )

    var chrome by rememberSaveable { mutableStateOf(false) }
    var showContents by rememberSaveable { mutableStateOf(false) }
    var showText by rememberSaveable { mutableStateOf(false) }
    controller.canTurn = { if (it == TurnDirection.FORWARD) vm.next != null else vm.previous != null }
    controller.onTurned = vm::turn

    ReaderWindow(activity, chrome, colors.dark)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    BackHandler(enabled = chrome) { chrome = false }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(colors.paper))) {
        val prefs = storedPrefs
        if (prefs != null) {
            val geometry = remember(constraints.maxWidth, constraints.maxHeight, density.density, edgeInsets, fold) {
                ReaderGeometry.of(constraints.maxWidth, constraints.maxHeight, density.density, edgeInsets, fold?.first, fold?.second)
            }
            val typography = remember(typeface, prefs, density.density, density.fontScale) {
                Typography(typeface, prefs.fontSizeSp * density.density * density.fontScale, prefs.lineSpacing)
            }
            SideEffect {
                controller.geometry = geometry
                vm.setLayout(LayoutSpec(geometry.pageSize, geometry.spread, typography, density.density))
            }
            BookPages(
                geometry, colors, typeface, vm.previous, vm.current, vm.next, controller, vm.imageVersion, vm::bitmap,
                Modifier
                    // The pages are drawn, not composed, so their words are published by hand.
                    .testTag(PAGE_TAG)
                    .semantics { text = AnnotatedString(vm.current?.text().orEmpty()) }
                    .pointerInput(Unit) {
                        pageGestures(controller) { tap ->
                            val third = size.width / 3f
                            when {
                                chrome -> chrome = false
                                tap.x < third -> controller.turn(TurnDirection.BACKWARD)
                                tap.x > size.width - third -> controller.turn(TurnDirection.FORWARD)
                                else -> chrome = true
                            }
                        }
                    },
            )
        }

        vm.error?.let { message ->
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(message, color = Color(colors.ink), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onBack) { Text("Back to library") }
            }
        }

        AnimatedVisibility(
            visible = chrome,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(vm.title, onBack, onContents = { showContents = true }, onText = { showText = true })
        }
        AnimatedVisibility(
            visible = chrome,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                progress = vm.progress,
                chapter = vm.toc.getOrNull(vm.tocIndex)?.title,
                syncError = vm.syncError,
                onSeek = vm::goToFraction,
            )
        }

        vm.remote?.let { offered ->
            RemoteProgressCard(
                offered,
                onJump = vm::acceptRemote,
                onDismiss = vm::dismissRemote,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            )
        }
    }

    if (showContents) {
        ContentsSheet(vm.toc, vm.tocIndex, onDismiss = { showContents = false }) { entry ->
            showContents = false
            chrome = false
            vm.goTo(entry)
        }
    }
    if (showText) {
        storedPrefs?.let { prefs ->
            TextSheet(prefs, onDismiss = { showText = false }) { changed ->
                scope.launch { container.settings.setReaderPrefs(changed) }
            }
        }
    }
}

/** Left and right edges of a vertical fold or hinge in the window, if there is one. */
@Composable
private fun rememberVerticalFold(activity: Activity): Pair<Int, Int>? {
    val info by remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
        .collectAsStateWithLifecycle(initialValue = null)
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }
    return fold?.let { it.bounds.left to it.bounds.right }
}

/** While reading, the screen stays on and the system bars step aside unless the controls are up. */
@Composable
private fun ReaderWindow(activity: Activity?, chrome: Boolean, dark: Boolean) {
    val view = LocalView.current
    val window = activity?.window ?: return
    val bars = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    DisposableEffect(bars) {
        val lightStatus = bars.isAppearanceLightStatusBars
        val lightNavigation = bars.isAppearanceLightNavigationBars
        view.keepScreenOn = true
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            view.keepScreenOn = false
            bars.show(WindowInsetsCompat.Type.systemBars())
            bars.isAppearanceLightStatusBars = lightStatus
            bars.isAppearanceLightNavigationBars = lightNavigation
        }
    }
    SideEffect {
        if (chrome) bars.show(WindowInsetsCompat.Type.systemBars()) else bars.hide(WindowInsetsCompat.Type.systemBars())
        bars.isAppearanceLightStatusBars = !dark
        bars.isAppearanceLightNavigationBars = !dark
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(title: String, onBack: () -> Unit, onContents: () -> Unit, onText: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to library") }
            },
            actions = {
                IconButton(onClick = onContents) { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "Contents") }
                IconButton(onClick = onText) { Icon(Icons.Outlined.FormatSize, contentDescription = "Text settings") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun ReaderBottomBar(progress: Float, chapter: String?, syncError: String?, onSeek: (Float) -> Unit) {
    // While a thumb is being dragged the slider shows the finger, not the page.
    var dragged by remember { mutableStateOf<Float?>(null) }
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chapter.orEmpty(),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${((dragged ?: progress) * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = dragged ?: progress,
                onValueChange = { dragged = it },
                onValueChangeFinished = {
                    dragged?.let(onSeek)
                    dragged = null
                },
                modifier = Modifier.semantics { contentDescription = "Position in book" },
            )
            if (syncError != null) {
                Text(
                    "Progress not synced: $syncError",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RemoteProgressCard(offered: RemoteProgress, onJump: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 520.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val where = offered.percentage?.let { "${(it * 100).roundToInt()}%" } ?: "a different page"
            val who = offered.device?.takeIf { it.isNotBlank() } ?: "Another device"
            Text("$who is at $where", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("Stay", color = MaterialTheme.colorScheme.inverseOnSurface) }
            TextButton(onClick = onJump) { Text("Jump there", color = MaterialTheme.colorScheme.inversePrimary) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(toc: List<TocEntry>, currentIndex: Int, onDismiss: () -> Unit, onPick: (TocEntry) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Contents", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        if (toc.isEmpty()) {
            Text("This book has no table of contents.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
        }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
        LazyColumn(state = listState) {
            itemsIndexed(toc) { index, entry ->
                val selected = index == currentIndex
                Text(
                    entry.title,
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(entry) }
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .padding(start = (24 + 16 * entry.depth.coerceAtMost(4)).dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSheet(prefs: ReaderPrefs, onDismiss: () -> Unit, onChange: (ReaderPrefs) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Text size", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                FilledTonalIconButton(
                    onClick = { onChange(prefs.copy(fontSizeSp = prefs.fontSizeSp - 1f)) },
                    enabled = prefs.fontSizeSp > ReaderPrefs.FONT_SIZES.start,
                ) { Icon(Icons.Outlined.Remove, contentDescription = "Smaller text") }
                Text(
                    prefs.fontSizeSp.roundToInt().toString(),
                    Modifier.width(48.dp),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                FilledTonalIconButton(
                    onClick = { onChange(prefs.copy(fontSizeSp = prefs.fontSizeSp + 1f)) },
                    enabled = prefs.fontSizeSp < ReaderPrefs.FONT_SIZES.endInclusive,
                ) { Icon(Icons.Outlined.Add, contentDescription = "Larger text") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Line spacing", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, value) in listOf("Tight" to 1.35f, "Normal" to 1.5f, "Loose" to 1.7f)) {
                        FilterChip(
                            selected = prefs.lineSpacing == value,
                            onClick = { onChange(prefs.copy(lineSpacing = value)) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Page", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (theme in ReaderTheme.entries) {
                        val swatch = pageColors(theme, isSystemInDarkTheme())
                        FilterChip(
                            selected = prefs.theme == theme,
                            onClick = { onChange(prefs.copy(theme = theme)) },
                            label = { Text(theme.name.lowercase().replaceFirstChar(Char::uppercase)) },
                            leadingIcon = {
                                if (theme != ReaderTheme.AUTO) {
                                    Box(Modifier.size(16.dp).clip(CircleShape).background(Color(swatch.paper))) {
                                        Box(Modifier.align(Alignment.Center).size(6.dp).clip(CircleShape).background(Color(swatch.ink)))
                                    }
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
