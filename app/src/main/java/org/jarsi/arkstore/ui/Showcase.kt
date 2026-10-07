package org.jarsi.arkstore.ui

import android.content.Context
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jarsi.arkstore.R
import org.jarsi.arkstore.data.AppMetadata

/**
 * What the developer publishes of the app beyond its summary, under it in the details: a
 * strip of screenshots, each opening to fill the screen, and the longer description. The
 * strip scrolls out to the edges of the screen past the details' [inset].
 */
@Composable
internal fun Showcase(metadata: AppMetadata, inset: Dp, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (metadata.screenshots.isNotEmpty()) {
            ScreenshotStrip(metadata.screenshots, inset)
        }
        metadata.description?.let { LongDescription(it) }
    }
}

@Composable
private fun ScreenshotStrip(addresses: List<String>, inset: Dp) {
    var viewing by rememberSaveable { mutableIntStateOf(NONE) }
    val shape = RoundedCornerShape(12.dp)
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = inset),
        modifier = Modifier
            .bleed(inset)
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
    ) {
        itemsIndexed(addresses) { index, address ->
            val description = stringResource(R.string.screenshot_description, index + 1, addresses.size)
            val painter = rememberImage(ScreenshotLoader, address)?.painter()
            val ratio = painter?.intrinsicSize
                ?.takeIf { it.width > 0f && it.height > 0f }
                ?.let { it.width / it.height }
                ?: PHONE_RATIO
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(ratio)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(role = Role.Image) { viewing = index }
                    .semantics { contentDescription = description }
            ) {
                if (painter != null) {
                    Image(
                        painter = painter,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
    if (viewing != NONE) {
        ScreenshotViewer(addresses, viewing, onDismiss = { viewing = NONE })
    }
}

/** The screenshots filling the screen, [start] first, swiped from one to the next. */
@Composable
private fun ScreenshotViewer(addresses: List<String>, start: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val pager = rememberPagerState(initialPage = start) { addresses.size }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val description = stringResource(R.string.screenshot_description, page + 1, addresses.size)
                val painter = rememberImage(ScreenshotViewerLoader, addresses[page])?.painter()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = null, indication = null, onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    if (painter != null) {
                        Image(
                            painter = painter,
                            contentDescription = description,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
            // The controls sit on the picture, which may be as white as they are.
            IconButton(
                onClick = onDismiss,
                colors = IconButtonDefaults.iconButtonColors(containerColor = SCRIM, contentColor = Color.White),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.action_dismiss)
                )
            }
            Text(
                text = "${pager.currentPage + 1} / ${addresses.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .background(SCRIM, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

/** The description at [address], once it has been fetched; nothing while it has not. */
@Composable
private fun LongDescription(address: String) {
    val context = LocalContext.current.applicationContext
    var text by remember(address) { mutableStateOf(Texts.cached(address)) }
    LaunchedEffect(address) {
        if (text == null) text = Texts.load(context, address)
    }
    text?.let {
        val lines = remember(it) { Descriptions.lines(it) }
        NotesText(lines, modifier = Modifier.padding(top = 12.dp))
    }
}

/**
 * Lets a row reach [inset] past either edge of its parent, which keeps that much clear of
 * the screen's edges, so that the row scrolls out to the edges instead.
 */
private fun Modifier.bleed(inset: Dp): Modifier = layout { measurable, constraints ->
    val extra = if (constraints.hasBoundedWidth) (inset * 2).roundToPx() else 0
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = minOf(constraints.minWidth + extra, Constraints.Infinity),
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else Constraints.Infinity
        )
    )
    layout(placeable.width - extra, placeable.height) {
        placeable.place(-extra / 2, 0)
    }
}

private const val NONE = -1
private val STRIP_HEIGHT = 220.dp
private const val PHONE_RATIO = 9f / 19.5f
private val SCRIM = Color.Black.copy(alpha = 0.5f)

/**
 * Texts fetched from the web, such as descriptions, kept like images are: in memory for
 * what is on screen, on disk for the next time, and not asked for again until the app
 * starts again once they could not be had.
 */
object Texts {
    private const val TAG = "Texts"
    private const val MAX_BYTES = 256 * 1024

    private val files = CachedFiles("texts", MAX_BYTES, parallel = 2)
    private val memory = LruCache<String, String>(16)
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun cached(address: String): String? = memory.get(address)

    suspend fun load(context: Context, address: String): String? {
        memory.get(address)?.let { return it }
        if (address in failed) return null
        val text = withContext(Dispatchers.IO) {
            try {
                String(files.bytes(context, address), Charsets.UTF_8)
            } catch (e: IOException) {
                Log.w(TAG, "No text from $address: $e")
                null
            }
        }
        if (text == null) failed += address else memory.put(address, text)
        return text
    }
}
