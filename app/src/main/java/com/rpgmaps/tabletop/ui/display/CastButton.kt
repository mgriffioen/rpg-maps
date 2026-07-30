package com.rpgmaps.tabletop.ui.display

import android.view.ContextThemeWrapper
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.rpgmaps.tabletop.R

/**
 * The standard Cast icon and device picker.
 *
 * This is the framework's own button rather than a hand-rolled device list
 * because it carries the discovery lifecycle, the picker dialog and the
 * "connecting..." states with it -- all of which are easy to get subtly wrong.
 *
 * It is a plain View, and it throws if its context cannot resolve AppCompat
 * attributes, hence the [ContextThemeWrapper].
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val themed = ContextThemeWrapper(context, R.style.Theme_RpgMaps_MediaRoute)
            MediaRouteButton(themed).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                runCatching {
                    CastButtonFactory.setUpMediaRouteButton(context.applicationContext, this)
                }
            }
        },
    )
}
