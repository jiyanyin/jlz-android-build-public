package dev.jlz.presence.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import dev.jlz.presence.ui.theme.WorldFonts

/**
 * Text compatible with Material3 Text, with a deterministic bundled Chinese
 * fallback. Compose FontFamily specifies faces by weight, not a CSS-style
 * ordered fallback list: mixing fonts in one FontFamily does not solve CJK.
 */
@Composable
fun WorldText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current
) {
    val annotated = remember(text) { addChineseFallback(AnnotatedString(text)) }
    MaterialText(
        text = annotated, modifier = modifier, color = color,
        fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
        fontFamily = fontFamily, letterSpacing = letterSpacing,
        textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = overflow, softWrap = softWrap,
        maxLines = maxLines, minLines = minLines,
        onTextLayout = onTextLayout, style = style
    )
}

@Composable
fun WorldText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current
) {
    val annotated = remember(text) { addChineseFallback(text) }
    MaterialText(
        text = annotated, modifier = modifier, color = color,
        fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
        fontFamily = fontFamily, letterSpacing = letterSpacing,
        textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = overflow, softWrap = softWrap,
        maxLines = maxLines, minLines = minLines,
        onTextLayout = onTextLayout, style = style
    )
}

/** Adds a font override only for CJK ranges, leaving Latin typography intact. */
private fun addChineseFallback(source: AnnotatedString): AnnotatedString = buildAnnotatedString {
    append(source)
    val s = source.text
    var runStart = -1
    var index = 0
    while (index < s.length) {
        val codePoint = Character.codePointAt(s, index)
        val isChinese = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN ||
            codePoint in 0x3000..0x303F || codePoint in 0xFF00..0xFFEF
        if (isChinese && runStart < 0) runStart = index
        if (!isChinese && runStart >= 0) {
            addStyle(SpanStyle(fontFamily = WorldFonts.chineseSerif), runStart, index)
            runStart = -1
        }
        index += Character.charCount(codePoint)
    }
    if (runStart >= 0) addStyle(
        SpanStyle(fontFamily = WorldFonts.chineseSerif), runStart, s.length
    )
}
