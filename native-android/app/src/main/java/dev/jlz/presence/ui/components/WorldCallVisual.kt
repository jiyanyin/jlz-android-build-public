package dev.jlz.presence.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import dev.jlz.presence.runtime.WorldContentCache

/** Shared appearance only: Callback and Gate retain separate state machines. */
@Composable fun worldCallColors(): List<Color> {
    val theme=WorldContentCache(LocalContext.current).text("theme.id","mist")
    return when(theme) {
        "deepsea" -> listOf(Color(0xFF071827),Color(0xFF17445A),Color(0xFF0B2539))
        "rose","gothic" -> listOf(Color(0xFF21171D),Color(0xFF533746),Color(0xFF281C29))
        else -> listOf(Color(0xFF171D34),Color(0xFF38435B),Color(0xFF202439))
    }
}
@Composable fun WorldCallPortrait(modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val bitmap=remember { runCatching { context.assets.open("world-between/jlz-home-portrait.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull() }
    bitmap?.let { Image(it.asImageBitmap(),"纪临洲",modifier,contentScale=ContentScale.Crop) }
}
@Composable fun ContentDisplayed(vararg keys:String) {
    val context=LocalContext.current
    LaunchedEffect(keys.toList()) { WorldContentCache(context).displayed(keys.toList()) }
}
