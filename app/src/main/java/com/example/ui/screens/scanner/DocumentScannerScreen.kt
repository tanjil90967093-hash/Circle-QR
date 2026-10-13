package com.example.ui.screens.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.UtilityHubApp
import com.example.ui.components.AppBackButton
import com.example.ui.components.AppTopBar
import com.example.ui.components.LoadingState
import com.example.ui.components.PermissionNoticeCard
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.theme.BrandBlue
import com.example.utils.DocumentFilter
import com.example.utils.DocumentProcessor
import com.example.utils.DocumentQuad
import com.example.utils.PdfUtils
import com.example.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DocScanStep {
    CAMERA,
    CROP_PERSPECTIVE,
    ENHANCE_AND_OCR,
    PAGE_MANAGEMENT
}

data class ScannedPageItem(
    val original: Bitmap,
    var processed: Bitmap,
    var activeFilter: DocumentFilter = DocumentFilter.ENHANCE,
    var rotationAngle: Float = 0f,
    var ocrText: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScannerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember { UtilityHubApp.instance.repository }
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted -> hasCameraPermission = isGranted }

    // Multi-page document storage
    val scannedPages = remember { mutableStateListOf<ScannedPageItem>() }
    var selectedPageIndex by remember { mutableStateOf(0) }

    // Flow Step State
    var currentStep by remember { mutableStateOf(DocScanStep.CAMERA) }

    // Transient captured image pending crop/enhance
    var pendingOriginalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectedQuad by remember { mutableStateOf<DocumentQuad?>(null) }

    // Camera hardware states
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }
    var isFlashSupported by remember { mutableStateOf(false) }
    var currentZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var minZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var maxZoomRatio by remember { mutableFloatStateOf(5.0f) }

    // Processing & Output
    var isProcessing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("Processing...") }
    var createdPdfFile by remember { mutableStateOf<File?>(null) }
    var customPdfFileName by remember { mutableStateOf("") }
    var showFilenameDialog by remember { mutableStateOf(false) }
    var showOcrDialog by remember { mutableStateOf(false) }
    var showCompareOriginal by remember { mutableStateOf(false) }

    // Sync torch
    LaunchedEffect(isFlashOn, cameraControl) {
        if (isFlashSupported) {
            cameraControl?.enableTorch(isFlashOn)
        }
    }

    // Gallery Import
    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isProcessing = true
                processingMessage = "Importing photos..."
                withContext(Dispatchers.IO) {
                    for (uri in uris) {
                        try {
                            var bmp: Bitmap? = null
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                val rawBmp = BitmapFactory.decodeStream(stream)
                                context.contentResolver.openInputStream(uri)?.use { exifStream ->
                                    bmp = if (rawBmp != null) DocumentProcessor.fixOrientation(rawBmp, exifStream) else null
                                }
                            }
                            if (bmp != null) {
                                val quad = DocumentProcessor.detectDocumentCorners(bmp!!)
                                val cropped = DocumentProcessor.cropPerspective(bmp!!, quad)
                                val enhanced = DocumentProcessor.applyFilter(cropped, DocumentFilter.ENHANCE)
                                val ocr = DocumentProcessor.extractTextPreview(enhanced)
                                scannedPages.add(
                                    ScannedPageItem(
                                        original = bmp!!,
                                        processed = enhanced,
                                        activeFilter = DocumentFilter.ENHANCE,
                                        ocrText = ocr
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                isProcessing = false
                if (scannedPages.isNotEmpty()) {
                    selectedPageIndex = scannedPages.lastIndex
                    currentStep = DocScanStep.PAGE_MANAGEMENT
                }
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = when (currentStep) {
                    DocScanStep.CAMERA -> "Smart Document Scanner"
                    DocScanStep.CROP_PERSPECTIVE -> "Adjust Corners & Crop"
                    DocScanStep.ENHANCE_AND_OCR -> "Enhance & Filter"
                    DocScanStep.PAGE_MANAGEMENT -> "Document Pages (${scannedPages.size})"
                },
                navigationIcon = {
                    AppBackButton(onBack = {
                        when (currentStep) {
                            DocScanStep.CROP_PERSPECTIVE -> {
                                currentStep = DocScanStep.CAMERA
                            }
                            DocScanStep.ENHANCE_AND_OCR -> {
                                currentStep = DocScanStep.CROP_PERSPECTIVE
                            }
                            DocScanStep.PAGE_MANAGEMENT -> {
                                if (scannedPages.isEmpty()) onBack() else currentStep = DocScanStep.CAMERA
                            }
                            DocScanStep.CAMERA -> onBack()
                        }
                    })
                },
                actions = {
                    if (currentStep == DocScanStep.CAMERA && scannedPages.isNotEmpty()) {
                        IconButton(onClick = { currentStep = DocScanStep.PAGE_MANAGEMENT }) {
                            Badge(
                                containerColor = BrandBlue,
                                contentColor = Color.White
                            ) {
                                Text("${scannedPages.size}")
                            }
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasCameraPermission) {
                PermissionNoticeCard(
                    title = "Camera Permission Required",
                    explanation = "Utility Hub requires camera access to scan documents, crop pages, and create PDFs.",
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                when (currentStep) {
                    DocScanStep.CAMERA -> {
                        // -------------------------------------------------------------
                        // 1. LIVE CAMERA CAPTURE WITH CORNER FRAME GUIDES & CONTROLS
                        // -------------------------------------------------------------
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(cameraControl) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        cameraControl?.let { ctrl ->
                                            val newZoom = (currentZoomRatio * zoom).coerceIn(minZoomRatio, maxZoomRatio)
                                            currentZoomRatio = newZoom
                                            ctrl.setZoomRatio(newZoom)
                                        }
                                    }
                                }
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    val previewView = PreviewView(ctx).apply {
                                        scaleType = PreviewView.ScaleType.FILL_CENTER
                                    }
                                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                    cameraProviderFuture.addListener({
                                        val cameraProvider = cameraProviderFuture.get()
                                        val preview = Preview.Builder().build().also {
                                            it.setSurfaceProvider(previewView.surfaceProvider)
                                        }

                                        val capture = ImageCapture.Builder()
                                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                            .build()
                                        imageCapture = capture

                                        try {
                                            cameraProvider.unbindAll()
                                            val camera = cameraProvider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview,
                                                capture
                                            )
                                            cameraControl = camera.cameraControl
                                            cameraInfo = camera.cameraInfo
                                            isFlashSupported = camera.cameraInfo.hasFlashUnit()

                                            camera.cameraInfo.zoomState.observe(lifecycleOwner) { zState ->
                                                if (zState != null) {
                                                    minZoomRatio = zState.minZoomRatio
                                                    maxZoomRatio = zState.maxZoomRatio.coerceAtMost(8f)
                                                    currentZoomRatio = zState.zoomRatio
                                                }
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }, ContextCompat.getMainExecutor(ctx))
                                    previewView
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("doc_camera_preview")
                            )

                            // Document Corner Guides Frame Overlay
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = 120.dp, top = 60.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                DocumentFrameWithCorners(
                                    modifier = Modifier
                                        .fillMaxWidth(0.86f)
                                        .fillMaxHeight(0.78f)
                                        .testTag("document_corner_guides")
                                )
                            }

                            // Top Camera Quick Controls (Torch & Zoom Pill)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Torch control
                                if (isFlashSupported) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.6f),
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clickable { isFlashOn = !isFlashOn }
                                            .testTag("doc_camera_torch_toggle")
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                                contentDescription = "Flashlight",
                                                tint = if (isFlashOn) BrandBlue else Color.White,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(44.dp))
                                }

                                // Zoom Slider/Pill
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color.Black.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "${String.format(Locale.US, "%.1f", currentZoomRatio)}x",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(
                                            onClick = {
                                                val nextZoom = if (currentZoomRatio >= 2.0f) 1.0f else 2.0f
                                                currentZoomRatio = nextZoom
                                                cameraControl?.setZoomRatio(nextZoom)
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ZoomIn,
                                                contentDescription = "Zoom Toggle",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Bottom Camera Shutter Bar
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter),
                                color = Color.Black.copy(alpha = 0.75f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = if (scannedPages.isNotEmpty())
                                            "${scannedPages.size} page(s) captured • Ready for next page"
                                        else
                                            "Fit paper inside corner guides",
                                        color = Color.White.copy(alpha = 0.85f),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        // Gallery import
                                        IconButton(
                                            onClick = { galleryPicker.launch("image/*") },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .background(Color.White.copy(alpha = 0.2f), CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PhotoLibrary,
                                                contentDescription = "From Gallery",
                                                tint = Color.White
                                            )
                                        }

                                        // Capture Shutter
                                        Box(
                                            modifier = Modifier
                                                .size(76.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                                .clickable {
                                                    val capture = imageCapture ?: return@clickable
                                                    val photoFile = File(context.cacheDir, "doc_${System.currentTimeMillis()}.jpg")
                                                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                                                    isProcessing = true
                                                    processingMessage = "Capturing document..."
                                                    capture.takePicture(
                                                        outputOptions,
                                                        ContextCompat.getMainExecutor(context),
                                                        object : ImageCapture.OnImageSavedCallback {
                                                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                                                scope.launch(Dispatchers.IO) {
                                                                    var raw = BitmapFactory.decodeFile(photoFile.absolutePath)
                                                                    photoFile.inputStream().use { stream ->
                                                                        if (raw != null) {
                                                                            raw = DocumentProcessor.fixOrientation(raw!!, stream)
                                                                        }
                                                                    }
                                                                    withContext(Dispatchers.Main) {
                                                                        isProcessing = false
                                                                        if (raw != null) {
                                                                            pendingOriginalBitmap = raw
                                                                            detectedQuad = DocumentProcessor.detectDocumentCorners(raw!!)
                                                                            currentStep = DocScanStep.CROP_PERSPECTIVE
                                                                        } else {
                                                                            Toast.makeText(context, "Could not decode image", Toast.LENGTH_SHORT).show()
                                                                        }
                                                                    }
                                                                }
                                                            }

                                                            override fun onError(exception: ImageCaptureException) {
                                                                isProcessing = false
                                                                Toast.makeText(context, "Capture error: ${exception.message}", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    )
                                                }
                                                .padding(6.dp)
                                                .testTag("doc_camera_shutter_btn"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(60.dp)
                                                    .clip(CircleShape)
                                                    .background(BrandBlue)
                                            )
                                        }

                                        // Finish pages / proceed
                                        if (scannedPages.isNotEmpty()) {
                                            IconButton(
                                                onClick = { currentStep = DocScanStep.PAGE_MANAGEMENT },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .background(BrandBlue, CircleShape)
                                                    .testTag("doc_finish_scanning_btn")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Review Pages",
                                                    tint = Color.White
                                                )
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.size(48.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    DocScanStep.CROP_PERSPECTIVE -> {
                        // -------------------------------------------------------------
                        // 2. DOCUMENT CORNERS REVIEW, PERSPECTIVE CROP & ROTATE
                        // -------------------------------------------------------------
                        val rawBmp = pendingOriginalBitmap
                        if (rawBmp != null) {
                            var currentQuad by remember { mutableStateOf(detectedQuad ?: DocumentProcessor.detectDocumentCorners(rawBmp)) }
                            var rotationDegrees by remember { mutableFloatStateOf(0f) }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Review Corners & Perspective",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Drag corner circles to align paper boundaries accurately",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Interactive Corner Dragging Canvas
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color.Black.copy(alpha = 0.05f))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    InteractiveQuadEditor(
                                        bitmap = rawBmp,
                                        quad = currentQuad,
                                        onQuadChanged = { currentQuad = it }
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Rotate Controls
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            pendingOriginalBitmap = DocumentProcessor.rotateBitmap(rawBmp, -90f)
                                            currentQuad = DocumentProcessor.detectDocumentCorners(pendingOriginalBitmap!!)
                                        }
                                    ) {
                                        Icon(Icons.Default.RotateLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Rotate Left")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            pendingOriginalBitmap = DocumentProcessor.rotateBitmap(rawBmp, 90f)
                                            currentQuad = DocumentProcessor.detectDocumentCorners(pendingOriginalBitmap!!)
                                        }
                                    ) {
                                        Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Rotate Right")
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Confirm Crop Action
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { currentStep = DocScanStep.CAMERA },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Retake")
                                    }

                                    PrimaryButton(
                                        text = "Crop & Enhance",
                                        icon = Icons.Default.Crop,
                                        onClick = {
                                            scope.launch {
                                                isProcessing = true
                                                processingMessage = "Applying perspective correction..."
                                                val cropped = withContext(Dispatchers.IO) {
                                                    DocumentProcessor.cropPerspective(pendingOriginalBitmap!!, currentQuad)
                                                }
                                                val enhanced = withContext(Dispatchers.IO) {
                                                    DocumentProcessor.applyFilter(cropped, DocumentFilter.ENHANCE)
                                                }
                                                val ocr = withContext(Dispatchers.IO) {
                                                    DocumentProcessor.extractTextPreview(enhanced)
                                                }
                                                scannedPages.add(
                                                    ScannedPageItem(
                                                        original = pendingOriginalBitmap!!,
                                                        processed = enhanced,
                                                        activeFilter = DocumentFilter.ENHANCE,
                                                        ocrText = ocr
                                                    )
                                                )
                                                selectedPageIndex = scannedPages.lastIndex
                                                isProcessing = false
                                                currentStep = DocScanStep.ENHANCE_AND_OCR
                                            }
                                        },
                                        modifier = Modifier.weight(1.5f),
                                        testTag = "confirm_crop_btn"
                                    )
                                }
                            }
                        }
                    }

                    DocScanStep.ENHANCE_AND_OCR -> {
                        // -------------------------------------------------------------
                        // 3. DOCUMENT ENHANCEMENT, OCR TEXT PREVIEW & COMPARISON
                        // -------------------------------------------------------------
                        if (scannedPages.isNotEmpty() && selectedPageIndex in scannedPages.indices) {
                            val activePage = scannedPages[selectedPageIndex]
                            var selectedFilter by remember { mutableStateOf(activePage.activeFilter) }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Document Enhancement & OCR",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                // Image Preview with Hold to Compare
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val displayBmp = if (showCompareOriginal) activePage.original else activePage.processed
                                    Image(
                                        bitmap = displayBmp.asImageBitmap(),
                                        contentDescription = "Enhanced Document",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp)
                                    )

                                    if (showCompareOriginal) {
                                        Surface(
                                            color = Color.Black.copy(alpha = 0.7f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .align(Alignment.TopCenter)
                                                .padding(top = 12.dp)
                                        ) {
                                            Text(
                                                text = "Showing Original Capture",
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Compare Original Button + OCR Button
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { showCompareOriginal = !showCompareOriginal }
                                    ) {
                                        Icon(
                                            imageVector = if (showCompareOriginal) Icons.Default.VisibilityOff else Icons.Default.Compare,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (showCompareOriginal) "Show Enhanced" else "Compare Original")
                                    }

                                    Button(
                                        onClick = { showOcrDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                                    ) {
                                        Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Extract Text (OCR)")
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Filter Chips
                                Text(
                                    text = "Enhancement Filters",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.Start)
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(DocumentFilter.entries) { filter ->
                                        FilterChip(
                                            selected = selectedFilter == filter,
                                            onClick = {
                                                selectedFilter = filter
                                                activePage.activeFilter = filter
                                                scope.launch {
                                                    isProcessing = true
                                                    processingMessage = "Applying ${filter.label}..."
                                                    val filtered = withContext(Dispatchers.IO) {
                                                        DocumentProcessor.applyFilter(activePage.original, filter)
                                                    }
                                                    activePage.processed = filtered
                                                    isProcessing = false
                                                }
                                            },
                                            label = { Text(filter.label) },
                                            modifier = Modifier.testTag("filter_chip_${filter.name}")
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Navigation Actions: Add More Pages or Finish
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { currentStep = DocScanStep.CAMERA },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Add Page")
                                    }

                                    PrimaryButton(
                                        text = "View All Pages (${scannedPages.size})",
                                        icon = Icons.Default.Collections,
                                        onClick = { currentStep = DocScanStep.PAGE_MANAGEMENT },
                                        modifier = Modifier.weight(1.3f),
                                        testTag = "to_page_management_btn"
                                    )
                                }
                            }
                        }
                    }

                    DocScanStep.PAGE_MANAGEMENT -> {
                        // -------------------------------------------------------------
                        // 4. MULTIPAGE ORDERING, FILENAME, PDF CREATION & BACKUP
                        // -------------------------------------------------------------
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (scannedPages.isNotEmpty() && selectedPageIndex in scannedPages.indices) {
                                val currentBmp = scannedPages[selectedPageIndex].processed

                                // Page Preview with Reorder / Delete bar
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = currentBmp.asImageBitmap(),
                                        contentDescription = "Page ${selectedPageIndex + 1}",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Page Operations (Rotate, Left, Position, Right, Delete)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = {
                                        val rotated = DocumentProcessor.rotateBitmap(scannedPages[selectedPageIndex].processed, 90f)
                                        scannedPages[selectedPageIndex].processed = rotated
                                    }) {
                                        Icon(imageVector = Icons.Default.RotateRight, contentDescription = "Rotate 90°")
                                    }

                                    IconButton(
                                        onClick = {
                                            if (selectedPageIndex > 0) {
                                                val temp = scannedPages[selectedPageIndex]
                                                scannedPages[selectedPageIndex] = scannedPages[selectedPageIndex - 1]
                                                scannedPages[selectedPageIndex - 1] = temp
                                                selectedPageIndex--
                                            }
                                        },
                                        enabled = selectedPageIndex > 0
                                    ) {
                                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Move Left")
                                    }

                                    Text(
                                        text = "Page ${selectedPageIndex + 1} of ${scannedPages.size}",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onBackground
                                    )

                                    IconButton(
                                        onClick = {
                                            if (selectedPageIndex < scannedPages.lastIndex) {
                                                val temp = scannedPages[selectedPageIndex]
                                                scannedPages[selectedPageIndex] = scannedPages[selectedPageIndex + 1]
                                                scannedPages[selectedPageIndex + 1] = temp
                                                selectedPageIndex++
                                            }
                                        },
                                        enabled = selectedPageIndex < scannedPages.lastIndex
                                    ) {
                                        Icon(imageVector = Icons.Default.ArrowForward, contentDescription = "Move Right")
                                    }

                                    IconButton(onClick = {
                                        scannedPages.removeAt(selectedPageIndex)
                                        if (scannedPages.isEmpty()) {
                                            currentStep = DocScanStep.CAMERA
                                        } else {
                                            selectedPageIndex = selectedPageIndex.coerceAtMost(scannedPages.lastIndex)
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Page",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Thumbnails Row
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    itemsIndexed(scannedPages) { idx, pageItem ->
                                        val isSelected = idx == selectedPageIndex
                                        Box(
                                            modifier = Modifier
                                                .size(68.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .border(
                                                    width = if (isSelected) 3.dp else 1.dp,
                                                    color = if (isSelected) BrandBlue else MaterialTheme.colorScheme.outlineVariant,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable { selectedPageIndex = idx }
                                        ) {
                                            Image(
                                                bitmap = pageItem.processed.asImageBitmap(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .size(68.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .clickable { currentStep = DocScanStep.CAMERA },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(imageVector = Icons.Default.Add, contentDescription = "Add Page", tint = BrandBlue)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // PDF Generation & File Save Actions
                                PrimaryButton(
                                    text = "Save as PDF (${scannedPages.size} Pages)",
                                    icon = Icons.Default.PictureAsPdf,
                                    onClick = {
                                        showFilenameDialog = true
                                    },
                                    testTag = "generate_pdf_btn"
                                )

                                if (createdPdfFile != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SecondaryButton(
                                            text = "Share PDF",
                                            icon = Icons.Default.Share,
                                            onClick = {
                                                StorageUtils.shareFile(context, createdPdfFile!!, "application/pdf")
                                            },
                                            modifier = Modifier.weight(1f),
                                            testTag = "share_pdf_btn"
                                        )

                                        SecondaryButton(
                                            text = "Open File",
                                            icon = Icons.Default.OpenInNew,
                                            onClick = {
                                                try {
                                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.fileprovider",
                                                        createdPdfFile!!
                                                    )
                                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, "application/pdf")
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "No PDF viewer installed", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            testTag = "open_pdf_btn"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isProcessing) {
                LoadingState(message = processingMessage)
            }
        }
    }

    // -------------------------------------------------------------
    // DIALOG: OCR Text Recognition Sheet
    // -------------------------------------------------------------
    if (showOcrDialog && scannedPages.isNotEmpty() && selectedPageIndex in scannedPages.indices) {
        val page = scannedPages[selectedPageIndex]
        val textContent = page.ocrText ?: DocumentProcessor.extractTextPreview(page.processed)

        AlertDialog(
            onDismissRequest = { showOcrDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TextFields, contentDescription = null, tint = BrandBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Recognized Document Text", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = textContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Scanned Text", textContent))
                        Toast.makeText(context, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                        showOcrDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Text")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOcrDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // -------------------------------------------------------------
    // DIALOG: Custom PDF Filename & Save
    // -------------------------------------------------------------
    if (showFilenameDialog) {
        val defaultName = remember {
            "Document_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}"
        }
        var fileNameInput by remember { mutableStateOf(customPdfFileName.ifBlank { defaultName }) }

        AlertDialog(
            onDismissRequest = { showFilenameDialog = false },
            title = { Text("Choose PDF Filename", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Specify a name for your compiled PDF document:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = fileNameInput,
                        onValueChange = { fileNameInput = it },
                        singleLine = true,
                        suffix = { Text(".pdf") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Files are saved safely to device storage in your Utility Hub directory.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFilenameDialog = false
                        scope.launch {
                            isProcessing = true
                            processingMessage = "Compiling PDF (${scannedPages.size} pages)..."
                            val cleanName = if (fileNameInput.endsWith(".pdf", ignoreCase = true)) {
                                fileNameInput
                            } else {
                                "$fileNameInput.pdf"
                            }
                            val processedBmps = scannedPages.map { it.processed }
                            val file = withContext(Dispatchers.IO) {
                                PdfUtils.imagesToPdf(context, processedBmps, cleanName)
                            }
                            isProcessing = false
                            if (file != null) {
                                createdPdfFile = file
                                repository.addHistory("doc_scanner", "Created Scanned PDF", "${file.name} (${scannedPages.size} pages)")
                                Toast.makeText(context, "PDF saved successfully: ${file.name}", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Failed to create PDF", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    Text("Save PDF")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFilenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Helper: Live Document Frame Overlay with Corner Marks
// ---------------------------------------------------------------------------
@Composable
private fun DocumentFrameWithCorners(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cornerLen = 32.dp.toPx()
        val strokeW = 4.dp.toPx()
        val cornerColor = Color.White

        // Outer subtle boundary line
        drawRect(
            color = Color.White.copy(alpha = 0.5f),
            topLeft = Offset.Zero,
            size = size,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
        )

        // Top Left Corner
        drawLine(cornerColor, Offset(0f, 0f), Offset(cornerLen, 0f), strokeW)
        drawLine(cornerColor, Offset(0f, 0f), Offset(0f, cornerLen), strokeW)

        // Top Right Corner
        drawLine(cornerColor, Offset(w, 0f), Offset(w - cornerLen, 0f), strokeW)
        drawLine(cornerColor, Offset(w, 0f), Offset(w, cornerLen), strokeW)

        // Bottom Left Corner
        drawLine(cornerColor, Offset(0f, h), Offset(cornerLen, h), strokeW)
        drawLine(cornerColor, Offset(0f, h), Offset(0f, h - cornerLen), strokeW)

        // Bottom Right Corner
        drawLine(cornerColor, Offset(w, h), Offset(w - cornerLen, h), strokeW)
        drawLine(cornerColor, Offset(w, h), Offset(w, h - cornerLen), strokeW)
    }
}

// ---------------------------------------------------------------------------
// Helper: Interactive Corner Dragging Quad Editor
// ---------------------------------------------------------------------------
@Composable
private fun InteractiveQuadEditor(
    bitmap: Bitmap,
    quad: DocumentQuad,
    onQuadChanged: (DocumentQuad) -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(quad, canvasSize) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (canvasSize.width > 0 && canvasSize.height > 0) {
                        val scaleX = bitmap.width.toFloat() / canvasSize.width
                        val scaleY = bitmap.height.toFloat() / canvasSize.height

                        val touchBmpX = change.position.x * scaleX
                        val touchBmpY = change.position.y * scaleY

                        // Find closest corner to touch
                        val dTL = kotlin.math.hypot(touchBmpX - quad.topLeft.x, touchBmpY - quad.topLeft.y)
                        val dTR = kotlin.math.hypot(touchBmpX - quad.topRight.x, touchBmpY - quad.topRight.y)
                        val dBR = kotlin.math.hypot(touchBmpX - quad.bottomRight.x, touchBmpY - quad.bottomRight.y)
                        val dBL = kotlin.math.hypot(touchBmpX - quad.bottomLeft.x, touchBmpY - quad.bottomLeft.y)

                        val minD = minOf(dTL, dTR, dBR, dBL)
                        val newQuad = when (minD) {
                            dTL -> quad.copy(topLeft = PointF(touchBmpX.coerceIn(0f, bitmap.width.toFloat()), touchBmpY.coerceIn(0f, bitmap.height.toFloat())))
                            dTR -> quad.copy(topRight = PointF(touchBmpX.coerceIn(0f, bitmap.width.toFloat()), touchBmpY.coerceIn(0f, bitmap.height.toFloat())))
                            dBR -> quad.copy(bottomRight = PointF(touchBmpX.coerceIn(0f, bitmap.width.toFloat()), touchBmpY.coerceIn(0f, bitmap.height.toFloat())))
                            else -> quad.copy(bottomLeft = PointF(touchBmpX.coerceIn(0f, bitmap.width.toFloat()), touchBmpY.coerceIn(0f, bitmap.height.toFloat())))
                        }
                        onQuadChanged(newQuad)
                    }
                }
            }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            canvasSize = size
            val sx = size.width / bitmap.width.toFloat()
            val sy = size.height / bitmap.height.toFloat()

            val pTL = Offset(quad.topLeft.x * sx, quad.topLeft.y * sy)
            val pTR = Offset(quad.topRight.x * sx, quad.topRight.y * sy)
            val pBR = Offset(quad.bottomRight.x * sx, quad.bottomRight.y * sy)
            val pBL = Offset(quad.bottomLeft.x * sx, quad.bottomLeft.y * sy)

            // Draw bounding polygon edges
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(pTL.x, pTL.y)
                lineTo(pTR.x, pTR.y)
                lineTo(pBR.x, pBR.y)
                lineTo(pBL.x, pBL.y)
                close()
            }
            drawPath(path, color = BrandBlue.copy(alpha = 0.25f))
            drawPath(path, color = BrandBlue, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))

            // Draw 4 corner control handles
            val handleRadius = 14.dp.toPx()
            listOf(pTL, pTR, pBR, pBL).forEach { pt ->
                drawCircle(color = Color.White, radius = handleRadius, center = pt)
                drawCircle(color = BrandBlue, radius = handleRadius - 4.dp.toPx(), center = pt)
            }
        }
    }
}
