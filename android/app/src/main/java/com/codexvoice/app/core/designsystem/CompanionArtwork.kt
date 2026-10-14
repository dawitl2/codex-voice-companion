package com.codexvoice.app.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance

/** Native artwork following the supplied blue/purple terminal-cloud references. */
@Composable
fun CompanionBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(if (dark) Color(0xFF0B0D14) else Color(0xFFFCFDFF))
            val alpha = if (dark) .16f else .24f
            drawRect(Brush.radialGradient(listOf(Color(0xFF8DBBFF).copy(alpha), Color.Transparent), Offset(size.width * .1f, size.height * .56f), size.width * .95f))
            drawRect(Brush.radialGradient(listOf(Color(0xFFC69AFF).copy(alpha), Color.Transparent), Offset(size.width, size.height * .7f), size.width * .85f))
            drawRect(Brush.radialGradient(listOf(Color(0xFF9FE8F7).copy(alpha * .7f), Color.Transparent), Offset(size.width * .45f, size.height * .7f), size.width * .7f))
        }
        content()
    }
}

@Composable
fun CodexCloud(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cloud = Path().apply {
            moveTo(w * .2f, h * .76f)
            cubicTo(w * .04f,h * .71f,w * .01f,h * .43f,w * .2f,h * .34f)
            cubicTo(w * .19f,h * .14f,w * .43f,h * .05f,w * .56f,h * .2f)
            cubicTo(w * .74f,h * .12f,w * .91f,h * .28f,w * .86f,h * .45f)
            cubicTo(w * 1.06f,h * .6f,w * .91f,h * .84f,w * .75f,h * .83f)
            cubicTo(w * .7f,h * 1.02f,w * .44f,h * .99f,w * .38f,h * .89f)
            cubicTo(w * .24f,h * .93f,w * .15f,h * .88f,w * .2f,h * .76f)
            close()
        }
        drawPath(cloud, Brush.linearGradient(listOf(Color(0xFFB45FFF),Color(0xFF6346FF),Color(0xFF129EFF),Color(0xFF2112FA)), Offset(w * .5f,0f),Offset(w * .4f,h)))
        drawPath(cloud, Brush.radialGradient(listOf(Color.White.copy(.5f),Color.Transparent),Offset(w * .42f,h * .28f),w * .5f))
        drawPath(cloud, Color(0xFF8F8BFF).copy(.7f), style = Stroke(w * .008f))
        val mark = Path().apply { moveTo(w * .32f,h * .43f); lineTo(w * .41f,h * .57f); lineTo(w * .32f,h * .71f) }
        drawPath(mark,Color.White,style=Stroke(w * .065f,cap=StrokeCap.Round,join=StrokeJoin.Round))
        drawLine(Color.White,Offset(w * .55f,h * .71f),Offset(w * .72f,h * .71f),w * .065f,StrokeCap.Round)
    }
}
