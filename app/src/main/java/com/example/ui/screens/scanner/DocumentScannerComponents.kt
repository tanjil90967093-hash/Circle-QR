package com.example.ui.screens.scanner

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BrandBlue
import com.example.utils.DocumentQuad
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Live Camera Document Detection Overlay.
 * When a document boundary is detected by CameraX ImageAnalysis:
 * - Maps buffer coordinates to screen using ScaleType.FILL_CENTER
 * - Draws a clean outline over the paper (green when stable, blue when moving)
 * - Highlights the 4 corners with subtle corner handles
 * When no reliable boundary is detected:
 * - Displays standard corner bracket framing reticle.
 */
@Composable
fun LiveDocumentDetectionOverlay(
    detectedQuad: DocumentQuad?,
    bufferWidth: Int,
    bufferHeight: Int,
    isReliable: Boolean,
    isStable: Boolean,
    modifier: Modifier = Modifier
) {
    val outlineColor by animateColorAsState(
        targetValue = when {
            isStable -> Color(0xFF10B981) // Green for stable reliable document
            isReliable -> Color(0xFF3B82F6) // Blue for detecting/aligning
            else -> Color.White.copy(alpha = 0.5f)
        },
        animationSpec = tween(150),
        label = "outline_color"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val viewW = size.width
        val viewH = size.height

        if (detectedQuad != null && isReliable && bufferWidth > 0 && bufferHeight > 0) {
            val scale = max(viewW / bufferWidth.toFloat(), viewH / bufferHeight.toFloat())
            val scaledW = bufferWidth * scale
            val scaledH = bufferHeight * scale
            val offsetX = (viewW - scaledW) / 2f
            val offsetY = (viewH - scaledH) / 2f

            val pTL = Offset(offsetX + detectedQuad.topLeft.x * scale, offsetY + detectedQuad.topLeft.y * scale)
            val pTR = Offset(offsetX + detectedQuad.topRight.x * scale, offsetY + detectedQuad.topRight.y * scale)
            val pBR = Offset(offsetX + detectedQuad.bottomRight.x * scale, offsetY + detectedQuad.bottomRight.y * scale)
            val pBL = Offset(offsetX + detectedQuad.bottomLeft.x * scale, offsetY + detectedQuad.bottomLeft.y * scale)

            // Fill polygon with soft translucent tint
            val polyPath = Path().apply {
                moveTo(pTL.x, pTL.y)
                lineTo(pTR.x, pTR.y)
                lineTo(pBR.x, pBR.y)
                lineTo(pBL.x, pBL.y)
                close()
            }
            drawPath(polyPath, color = outlineColor.copy(alpha = if (isStable) 0.16f else 0.08f))
            drawPath(polyPath, color = outlineColor, style = Stroke(width = if (isStable) 2.5.dp.toPx() else 2.dp.toPx()))

            // 4 Corner indicators
            val cornerHandleRadius = if (isStable) 7.dp.toPx() else 5.dp.toPx()
            listOf(pTL, pTR, pBR, pBL).forEach { pt ->
                drawCircle(Color.White, radius = cornerHandleRadius + 2.dp.toPx(), center = pt)
                drawCircle(outlineColor, radius = cornerHandleRadius, center = pt)
            }
        } else {
            // Default Framing Guides Overlay
            val marginX = viewW * 0.08f
            val marginY = viewH * 0.12f
            val frameW = viewW - (marginX * 2)
            val frameH = viewH - (marginY * 2)

            val cornerLen = 36.dp.toPx()
            val strokeW = 3.5.dp.toPx()
            val cornerColor = Color.White
            val boundaryColor = Color.White.copy(alpha = 0.35f)

            // Outer subtle rectangular paper outline
            drawRoundRect(
                color = boundaryColor,
                topLeft = Offset(marginX, marginY),
                size = androidx.compose.ui.geometry.Size(frameW, frameH),
                cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // 4 Corner brackets
            // Top-Left
            drawLine(cornerColor, Offset(marginX, marginY), Offset(marginX + cornerLen, marginY), strokeW, StrokeCap.Round)
            drawLine(cornerColor, Offset(marginX, marginY), Offset(marginX, marginY + cornerLen), strokeW, StrokeCap.Round)

            // Top-Right
            drawLine(cornerColor, Offset(marginX + frameW, marginY), Offset(marginX + frameW - cornerLen, marginY), strokeW, StrokeCap.Round)
            drawLine(cornerColor, Offset(marginX + frameW, marginY), Offset(marginX + frameW, marginY + cornerLen), strokeW, StrokeCap.Round)

            // Bottom-Left
            drawLine(cornerColor, Offset(marginX, marginY + frameH), Offset(marginX + cornerLen, marginY + frameH), strokeW, StrokeCap.Round)
            drawLine(cornerColor, Offset(marginX, marginY + frameH), Offset(marginX, marginY + frameH - cornerLen), strokeW, StrokeCap.Round)

            // Bottom-Right
            drawLine(cornerColor, Offset(marginX + frameW, marginY + frameH), Offset(marginX + frameW - cornerLen, marginY + frameH), strokeW, StrokeCap.Round)
            drawLine(cornerColor, Offset(marginX + frameW, marginY + frameH), Offset(marginX + frameW, marginY + frameH - cornerLen), strokeW, StrokeCap.Round)
        }
    }
}

/**
 * Static Document Frame Overlay for fallback or standalone use.
 */
@Composable
fun DocumentFrameWithCorners(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cornerLen = 38.dp.toPx()
        val strokeW = 4.dp.toPx()
        val cornerColor = Color.White
        val boundaryColor = Color.White.copy(alpha = 0.55f)

        // Outer subtle rectangular paper outline
        drawRoundRect(
            color = boundaryColor,
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
            style = Stroke(width = 1.8.dp.toPx())
        )

        // Top Left corner bracket
        drawLine(cornerColor, Offset(0f, 0f), Offset(cornerLen, 0f), strokeW, StrokeCap.Round)
        drawLine(cornerColor, Offset(0f, 0f), Offset(0f, cornerLen), strokeW, StrokeCap.Round)

        // Top Right corner bracket
        drawLine(cornerColor, Offset(w, 0f), Offset(w - cornerLen, 0f), strokeW, StrokeCap.Round)
        drawLine(cornerColor, Offset(w, 0f), Offset(w, cornerLen), strokeW, StrokeCap.Round)

        // Bottom Left corner bracket
        drawLine(cornerColor, Offset(0f, h), Offset(cornerLen, h), strokeW, StrokeCap.Round)
        drawLine(cornerColor, Offset(0f, h), Offset(0f, h - cornerLen), strokeW, StrokeCap.Round)

        // Bottom Right corner bracket
        drawLine(cornerColor, Offset(w, h), Offset(w - cornerLen, h), strokeW, StrokeCap.Round)
        drawLine(cornerColor, Offset(w, h), Offset(w - cornerLen, h), strokeW, StrokeCap.Round)
    }
}

private enum class DragTarget {
    NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT
}

/**
 * Interactive Corner Dragging Quad Editor with Pixel-Perfect Aspect Mapping
 * and Live Magnifier / Loupe preview during dragging.
 */
@Composable
fun InteractiveQuadEditor(
    bitmap: Bitmap,
    quad: DocumentQuad,
    onQuadChanged: (DocumentQuad) -> Unit,
    modifier: Modifier = Modifier
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var activeDragTarget by remember { mutableStateOf(DragTarget.NONE) }
    var touchPointOnBitmap by remember { mutableStateOf<PointF?>(null) }

    val bmpW = bitmap.width.toFloat()
    val bmpH = bitmap.height.toFloat()

    // Calculate exact fitted display rect
    val (drawW, drawH, offsetX, offsetY, scale) = remember(containerSize, bmpW, bmpH) {
        if (containerSize.width > 0 && containerSize.height > 0 && bmpW > 0 && bmpH > 0) {
            val scaleVal = min(containerSize.width.toFloat() / bmpW, containerSize.height.toFloat() / bmpH)
            val dW = bmpW * scaleVal
            val dH = bmpH * scaleVal
            val oX = (containerSize.width - dW) / 2f
            val oY = (containerSize.height - dH) / 2f
            floatArrayOf(dW, dH, oX, oY, scaleVal)
        } else {
            floatArrayOf(0f, 0f, 0f, 0f, 1f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(quad, scale, offsetX, offsetY, bmpW, bmpH) {
                detectDragGestures(
                    onDragStart = { startPos ->
                        if (scale <= 0f) return@detectDragGestures
                        val tx = startPos.x
                        val ty = startPos.y

                        // Screen positions of 4 corners
                        val sTL = Offset(offsetX + quad.topLeft.x * scale, offsetY + quad.topLeft.y * scale)
                        val sTR = Offset(offsetX + quad.topRight.x * scale, offsetY + quad.topRight.y * scale)
                        val sBR = Offset(offsetX + quad.bottomRight.x * scale, offsetY + quad.bottomRight.y * scale)
                        val sBL = Offset(offsetX + quad.bottomLeft.x * scale, offsetY + quad.bottomLeft.y * scale)

                        val hitRadius = 50.dp.toPx()
                        val dTL = hypot((tx - sTL.x).toDouble(), (ty - sTL.y).toDouble())
                        val dTR = hypot((tx - sTR.x).toDouble(), (ty - sTR.y).toDouble())
                        val dBR = hypot((tx - sBR.x).toDouble(), (ty - sBR.y).toDouble())
                        val dBL = hypot((tx - sBL.x).toDouble(), (ty - sBL.y).toDouble())

                        val minD = minOf(dTL, dTR, dBR, dBL)
                        activeDragTarget = when {
                            minD <= hitRadius && minD == dTL -> DragTarget.TOP_LEFT
                            minD <= hitRadius && minD == dTR -> DragTarget.TOP_RIGHT
                            minD <= hitRadius && minD == dBR -> DragTarget.BOTTOM_RIGHT
                            minD <= hitRadius && minD == dBL -> DragTarget.BOTTOM_LEFT
                            else -> DragTarget.NONE
                        }

                        if (activeDragTarget != DragTarget.NONE) {
                            val bmpX = ((tx - offsetX) / scale).coerceIn(0f, bmpW)
                            val bmpY = ((ty - offsetY) / scale).coerceIn(0f, bmpH)
                            touchPointOnBitmap = PointF(bmpX, bmpY)
                        }
                    },
                    onDragEnd = {
                        activeDragTarget = DragTarget.NONE
                        touchPointOnBitmap = null
                    },
                    onDragCancel = {
                        activeDragTarget = DragTarget.NONE
                        touchPointOnBitmap = null
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (scale <= 0f || activeDragTarget == DragTarget.NONE) return@detectDragGestures

                        val tx = change.position.x
                        val ty = change.position.y

                        val bmpX = ((tx - offsetX) / scale).coerceIn(0f, bmpW)
                        val bmpY = ((ty - offsetY) / scale).coerceIn(0f, bmpH)
                        touchPointOnBitmap = PointF(bmpX, bmpY)

                        val newQuad = when (activeDragTarget) {
                            DragTarget.TOP_LEFT -> quad.copy(topLeft = PointF(bmpX, bmpY))
                            DragTarget.TOP_RIGHT -> quad.copy(topRight = PointF(bmpX, bmpY))
                            DragTarget.BOTTOM_RIGHT -> quad.copy(bottomRight = PointF(bmpX, bmpY))
                            DragTarget.BOTTOM_LEFT -> quad.copy(bottomLeft = PointF(bmpX, bmpY))
                            else -> quad
                        }
                        onQuadChanged(newQuad)
                    }
                )
            }
    ) {
        // Image rendered in center
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Document Image to Crop",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        // Overlay canvas with edges, semi-transparent mask, and corner handles
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (scale > 0f) {
                val pTL = Offset(offsetX + quad.topLeft.x * scale, offsetY + quad.topLeft.y * scale)
                val pTR = Offset(offsetX + quad.topRight.x * scale, offsetY + quad.topRight.y * scale)
                val pBR = Offset(offsetX + quad.bottomRight.x * scale, offsetY + quad.bottomRight.y * scale)
                val pBL = Offset(offsetX + quad.bottomLeft.x * scale, offsetY + quad.bottomLeft.y * scale)

                // Fill polygon with soft highlight
                val polyPath = Path().apply {
                    moveTo(pTL.x, pTL.y)
                    lineTo(pTR.x, pTR.y)
                    lineTo(pBR.x, pBR.y)
                    lineTo(pBL.x, pBL.y)
                    close()
                }
                drawPath(polyPath, color = BrandBlue.copy(alpha = 0.22f))
                drawPath(polyPath, color = BrandBlue, style = Stroke(width = 3.dp.toPx()))

                // 4 Connecting corner handles
                val handleRadiusOuter = 16.dp.toPx()
                val handleRadiusInner = 11.dp.toPx()

                val corners = listOf(
                    pTL to (activeDragTarget == DragTarget.TOP_LEFT),
                    pTR to (activeDragTarget == DragTarget.TOP_RIGHT),
                    pBR to (activeDragTarget == DragTarget.BOTTOM_RIGHT),
                    pBL to (activeDragTarget == DragTarget.BOTTOM_LEFT)
                )

                corners.forEach { (pt, isActive) ->
                    val colorOuter = if (isActive) Color(0xFFF59E0B) else Color.White
                    val colorInner = if (isActive) Color(0xFFD97706) else BrandBlue
                    val rOuter = if (isActive) handleRadiusOuter * 1.3f else handleRadiusOuter
                    val rInner = if (isActive) handleRadiusInner * 1.3f else handleRadiusInner

                    drawCircle(color = colorOuter, radius = rOuter, center = pt)
                    drawCircle(color = colorInner, radius = rInner, center = pt)
                }

                // Midpoints on each edge for visual clarity
                val midTop = Offset((pTL.x + pTR.x) / 2f, (pTL.y + pTR.y) / 2f)
                val midRight = Offset((pTR.x + pBR.x) / 2f, (pTR.y + pBR.y) / 2f)
                val midBottom = Offset((pBL.x + pBR.x) / 2f, (pBL.y + pBR.y) / 2f)
                val midLeft = Offset((pTL.x + pBL.x) / 2f, (pTL.y + pBL.y) / 2f)

                listOf(midTop, midRight, midBottom, midLeft).forEach { mid ->
                    drawCircle(color = Color.White, radius = 6.dp.toPx(), center = mid)
                    drawCircle(color = BrandBlue, radius = 4.dp.toPx(), center = mid)
                }
            }
        }

        // Live Magnifier / Loupe preview during active drag
        if (activeDragTarget != DragTarget.NONE && touchPointOnBitmap != null) {
            val pt = touchPointOnBitmap!!
            val loupeSize = 100.dp

            val loupeAlignment = if (pt.y < bmpH * 0.45f) Alignment.BottomCenter else Alignment.TopCenter

            Box(
                modifier = Modifier
                    .align(loupeAlignment)
                    .padding(20.dp)
                    .size(loupeSize)
                    .shadow(12.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(3.dp, BrandBlue, CircleShape)
            ) {
                val cropSide = (bmpW * 0.18f).toInt().coerceIn(40, 260)
                val startX = (pt.x - cropSide / 2).toInt().coerceIn(0, (bmpW - cropSide).toInt().coerceAtLeast(0))
                val startY = (pt.y - cropSide / 2).toInt().coerceIn(0, (bmpH - cropSide).toInt().coerceAtLeast(0))

                val subBmp = remember(pt.x, pt.y) {
                    try {
                        Bitmap.createBitmap(bitmap, startX, startY, cropSide.coerceAtMost(bitmap.width - startX), cropSide.coerceAtMost(bitmap.height - startY))
                    } catch (_: Exception) {
                        null
                    }
                }

                if (subBmp != null) {
                    Image(
                        bitmap = subBmp.asImageBitmap(),
                        contentDescription = "Magnifier Loupe",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Crosshair at center of loupe
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val chLen = 14.dp.toPx()
                    drawLine(Color.Red, Offset(cx - chLen, cy), Offset(cx + chLen, cy), strokeWidth = 2.dp.toPx())
                    drawLine(Color.Red, Offset(cx, cy - chLen), Offset(cx, cy + chLen), strokeWidth = 2.dp.toPx())
                    drawCircle(Color.Red, radius = 3.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 1.5.dp.toPx()))
                }
            }
        }
    }
}
