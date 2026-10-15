package com.example.ui.screens.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.UtilityHubApp
import com.example.ui.components.AppBackButton
import com.example.ui.components.PermissionNoticeCard
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.screens.home.ScannerMode
import com.example.ui.theme.BrandBlue
import com.example.utils.DocumentFilter
import com.example.utils.DocumentProcessor
import com.example.utils.DocumentQuad
import com.example.utils.ScannedPageItem
import com.example.utils.ImageUtils
import com.example.utils.PdfUtils
import com.example.utils.QrCodeHelper
import com.example.utils.StorageUtils
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScannerScreen(
    initialMode: String = "QR_BARCODE",
    onBack: () -> Unit,
    onNavigateToResult: (content: String, format: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember { UtilityHubApp.instance.repository }
    val scope = rememberCoroutineScope()

    var currentMode by remember {
        mutableStateOf(
            try {
                ScannerMode.valueOf(initialMode)
            } catch (e: Exception) {
                ScannerMode.QR_BARCODE
            }
        )
    }

    var isFlashOn by remember { mutableStateOf(false) }
    var isFlashSupported by remember { mutableStateOf(false) }
    var currentZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var minZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var maxZoomRatio by remember { mutableFloatStateOf(5.0f) }
    var showModeSheet by remember { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            Toast.makeText(context, "Camera permission needed to scan", Toast.LENGTH_SHORT).show()
        }
    }

    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    var isProcessing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("Processing...") }

    // Multi-page Document scanning
    val documentPages = remember { mutableStateListOf<ScannedPageItem>() }
    var selectedDocPageIndex by remember { mutableIntStateOf(0) }
    var showDocumentReviewSheet by remember { mutableStateOf(false) }
    var generatedPdfFile by remember { mutableStateOf<File?>(null) }
    var customPdfFileName by remember { mutableStateOf("") }
    var showFilenameDialog by remember { mutableStateOf(false) }
    var showOcrDialog by remember { mutableStateOf(false) }
    var showManualCropDialog by remember { mutableStateOf(false) }
    var cropDialogQuad by remember { mutableStateOf<DocumentQuad?>(null) }
    var showCompareOriginal by remember { mutableStateOf(false) }

    // Card capture state
    var capturedCardBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showCardResultDialog by remember { mutableStateOf(false) }

    // Photo capture state
    var capturedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showImageResultDialog by remember { mutableStateOf(false) }

    // Synchronize flash
    LaunchedEffect(isFlashOn, cameraControl) {
        if (isFlashSupported) {
            cameraControl?.enableTorch(isFlashOn)
        }
    }

    // Gallery Picker routing
    val singleImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            when (currentMode) {
                ScannerMode.QR_BARCODE -> {
                    scope.launch {
                        isProcessing = true
                        processingMessage = "Analyzing image for QR & barcodes..."
                        val decoded = withContext(Dispatchers.IO) {
                            try {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    BitmapFactory.decodeStream(stream)?.let { QrCodeHelper.decodeBitmap(it) }
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }
                        isProcessing = false
                        if (decoded != null) {
                            vibrateDevice(context)
                            repository.addHistory("qr_scanner", "QR from Photo", decoded)
                            onNavigateToResult(decoded, "QR_CODE")
                        } else {
                            Toast.makeText(context, "No QR code or barcode found in photo", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                ScannerMode.ID_CARD -> {
                    scope.launch {
                        isProcessing = true
                        val bmp = withContext(Dispatchers.IO) {
                            ImageUtils.decodeSampledBitmapFromUri(context, uri, 1200, 800)
                        }
                        isProcessing = false
                        if (bmp != null) {
                            capturedCardBitmap = bmp
                            showCardResultDialog = true
                        }
                    }
                }
                ScannerMode.IMAGE_SCAN -> {
                    scope.launch {
                        isProcessing = true
                        val bmp = withContext(Dispatchers.IO) {
                            ImageUtils.decodeSampledBitmapFromUri(context, uri, 1600, 1600)
                        }
                        isProcessing = false
                        if (bmp != null) {
                            capturedImageBitmap = bmp
                            showImageResultDialog = true
                        }
                    }
                }
                ScannerMode.DOCUMENT_PDF -> {
                    scope.launch {
                        isProcessing = true
                        processingMessage = "Analyzing document image..."
                        withContext(Dispatchers.IO) {
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
                                    val pageItem = ScannedPageItem(
                                        original = bmp!!,
                                        processed = enhanced,
                                        activeFilter = DocumentFilter.ENHANCE,
                                        ocrText = ocr
                                    )
                                    withContext(Dispatchers.Main) {
                                        documentPages.add(pageItem)
                                        selectedDocPageIndex = documentPages.lastIndex
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        isProcessing = false
                        if (documentPages.isNotEmpty()) {
                            showDocumentReviewSheet = true
                        }
                    }
                }
            }
        }
    }

    val multiImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isProcessing = true
                processingMessage = "Importing ${uris.size} document pages..."
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
                                val pageItem = ScannedPageItem(
                                    original = bmp!!,
                                    processed = enhanced,
                                    activeFilter = DocumentFilter.ENHANCE,
                                    ocrText = ocr
                                )
                                withContext(Dispatchers.Main) {
                                    documentPages.add(pageItem)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                isProcessing = false
                if (documentPages.isNotEmpty()) {
                    selectedDocPageIndex = documentPages.lastIndex
                    showDocumentReviewSheet = true
                }
            }
        }
    }

    fun openGallery() {
        if (currentMode == ScannerMode.DOCUMENT_PDF) {
            multiImagePicker.launch("image/*")
        } else {
            singleImagePicker.launch("image/*")
        }
    }

    fun capturePhoto(onSuccess: (Bitmap) -> Unit) {
        val capture = imageCapture ?: return
        val cacheFile = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(cacheFile).build()

        isProcessing = true
        processingMessage = "Capturing..."
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    scope.launch(Dispatchers.IO) {
                        val bmp = BitmapFactory.decodeFile(cacheFile.absolutePath)
                        withContext(Dispatchers.Main) {
                            isProcessing = false
                            if (bmp != null) {
                                vibrateDevice(context)
                                onSuccess(bmp)
                            } else {
                                Toast.makeText(context, "Could not decode capture", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    isProcessing = false
                    Toast.makeText(context, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    fun captureDocumentPage() {
        val capture = imageCapture ?: return
        val photoFile = File(context.cacheDir, "doc_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        isProcessing = true
        processingMessage = "Capturing & processing page..."
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
                        if (raw != null) {
                            val quad = DocumentProcessor.detectDocumentCorners(raw!!)
                            val cropped = DocumentProcessor.cropPerspective(raw!!, quad)
                            val enhanced = DocumentProcessor.applyFilter(cropped, DocumentFilter.ENHANCE)
                            val ocr = DocumentProcessor.extractTextPreview(enhanced)
                            val pageItem = ScannedPageItem(
                                original = raw!!,
                                processed = enhanced,
                                activeFilter = DocumentFilter.ENHANCE,
                                ocrText = ocr
                            )
                            withContext(Dispatchers.Main) {
                                vibrateDevice(context)
                                documentPages.add(pageItem)
                                selectedDocPageIndex = documentPages.lastIndex
                                isProcessing = false
                                Toast.makeText(context, "Page ${documentPages.size} captured • Ready for next page", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                isProcessing = false
                                Toast.makeText(context, "Could not decode capture", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    isProcessing = false
                    Toast.makeText(context, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    BackHandler {
        if (showDocumentReviewSheet) {
            showDocumentReviewSheet = false
        } else if (showModeSheet) {
            showModeSheet = false
        } else {
            onBack()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasCameraPermission) {
                PermissionNoticeCard(
                    title = "Camera Access Required",
                    explanation = "Utility Hub needs camera permission to scan QR codes, documents, IDs, and images.",
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                // Shared CameraX Stream Engine
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
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            imageCapture = capture

                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            val multiReader = MultiFormatReader().apply {
                                val formats = listOf(
                                    BarcodeFormat.QR_CODE,
                                    BarcodeFormat.EAN_13,
                                    BarcodeFormat.EAN_8,
                                    BarcodeFormat.UPC_A,
                                    BarcodeFormat.UPC_E,
                                    BarcodeFormat.CODE_128,
                                    BarcodeFormat.CODE_39,
                                    BarcodeFormat.ITF,
                                    BarcodeFormat.DATA_MATRIX,
                                    BarcodeFormat.PDF_417,
                                    BarcodeFormat.AZTEC
                                )
                                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to formats))
                            }

                            var hasFoundCode = false
                            val cameraExecutor = Executors.newSingleThreadExecutor()
                            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                if (currentMode == ScannerMode.QR_BARCODE && !hasFoundCode) {
                                    val buffer = imageProxy.planes[0].buffer
                                    val data = ByteArray(buffer.remaining())
                                    buffer.get(data)
                                    val width = imageProxy.width
                                    val height = imageProxy.height

                                    try {
                                        val source = PlanarYUVLuminanceSource(
                                            data, width, height, 0, 0, width, height, false
                                        )
                                        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                                        val result = multiReader.decodeWithState(binaryBitmap)
                                        val text = result.text
                                        if (!text.isNullOrBlank()) {
                                            hasFoundCode = true
                                            scope.launch(Dispatchers.Main) {
                                                vibrateDevice(ctx)
                                                repository.addHistory(
                                                    "qr_scanner",
                                                    "Scanned ${result.barcodeFormat.name}",
                                                    text
                                                )
                                                onNavigateToResult(text, result.barcodeFormat.name)
                                            }
                                        }
                                    } catch (_: NotFoundException) {
                                    } catch (e: Exception) {
                                    } finally {
                                        multiReader.reset()
                                    }
                                }
                                imageProxy.close()
                            }

                            try {
                                cameraProvider.unbindAll()
                                val camera = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    capture,
                                    analysis
                                )
                                cameraControl = camera.cameraControl
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
                        .pointerInput(cameraControl) {
                            detectTransformGestures { _, _, zoom, _ ->
                                cameraControl?.let { ctrl ->
                                    val newZoom = (currentZoomRatio * zoom).coerceIn(minZoomRatio, maxZoomRatio)
                                    currentZoomRatio = newZoom
                                    ctrl.setZoomRatio(newZoom)
                                }
                            }
                        }
                        .testTag("full_camera_preview")
                )

                // Reticle guides
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp, top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (currentMode) {
                        ScannerMode.QR_BARCODE -> {
                            Box(
                                modifier = Modifier
                                    .size(260.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .border(3.dp, BrandBlue, RoundedCornerShape(24.dp))
                                    .testTag("qr_reticle_frame")
                            )
                        }
                        ScannerMode.DOCUMENT_PDF -> {
                            DocumentFrameWithCorners(
                                modifier = Modifier
                                    .fillMaxWidth(0.86f)
                                    .fillMaxHeight(0.76f)
                                    .testTag("doc_reticle_frame")
                            )
                        }
                        ScannerMode.ID_CARD -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .height(210.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .border(3.dp, BrandBlue, RoundedCornerShape(18.dp))
                                    .testTag("id_reticle_frame")
                            )
                        }
                        ScannerMode.IMAGE_SCAN -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.88f)
                                    .fillMaxHeight(0.68f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
                                    .testTag("image_reticle_frame")
                            )
                        }
                    }
                }

                // Instructions Banner Overlay
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 80.dp)
                        .testTag("scanner_instructions")
                ) {
                    val instructionText = if (currentMode == ScannerMode.DOCUMENT_PDF) {
                        if (documentPages.isNotEmpty()) {
                            "${documentPages.size} page(s) scanned • Align next document or tap Done"
                        } else {
                            "Align document within corner guides • Tap shutter to scan"
                        }
                    } else {
                        currentMode.instruction
                    }
                    Text(
                        text = instructionText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                // Scanner Top Bar: [ Back ] ... [ Mode ▼ ] [ Zoom ] [ Flash ] [ Gallery ]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Back button
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Mode Selector Pill
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.65f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clickable { showModeSheet = true }
                            .testTag("scanner_mode_dropdown")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = currentMode.icon,
                                contentDescription = null,
                                tint = BrandBlue,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentMode.title,
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Choose Mode",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Right Actions: Zoom, Flash & Gallery
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Zoom Indicator / Toggle Pill
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.Black.copy(alpha = 0.5f),
                            modifier = Modifier
                                .clickable {
                                    val nextZoom = if (currentZoomRatio >= 1.9f) 1.0f else 2.0f
                                    currentZoomRatio = nextZoom
                                    cameraControl?.setZoomRatio(nextZoom)
                                }
                                .testTag("zoom_toggle_btn")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ZoomIn,
                                    contentDescription = "Zoom",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", currentZoomRatio)}x",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }

                        if (isFlashSupported) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .clickable { isFlashOn = !isFlashOn }
                                    .testTag("toggle_torch_btn"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                    contentDescription = "Toggle Torch",
                                    tint = if (isFlashOn) BrandBlue else Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable { openGallery() }
                                .testTag("gallery_import_btn"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "Import from Gallery",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Bottom Controls Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp, start = 20.dp, end = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentMode == ScannerMode.DOCUMENT_PDF) {
                        if (documentPages.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color.Black.copy(alpha = 0.65f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BrandBlue.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .clickable { showDocumentReviewSheet = true }
                                    .testTag("doc_pages_counter_badge")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    ) {
                                        Image(
                                            bitmap = documentPages.last().processed.asImageBitmap(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "${documentPages.size} pages",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.size(48.dp))
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }

                    // Main Shutter / Action Button
                    if (currentMode != ScannerMode.QR_BARCODE) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable {
                                    when (currentMode) {
                                        ScannerMode.DOCUMENT_PDF -> {
                                            captureDocumentPage()
                                        }
                                        ScannerMode.ID_CARD -> {
                                            capturePhoto { bmp ->
                                                capturedCardBitmap = bmp
                                                showCardResultDialog = true
                                            }
                                        }
                                        ScannerMode.IMAGE_SCAN -> {
                                            capturePhoto { bmp ->
                                                capturedImageBitmap = bmp
                                                showImageResultDialog = true
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                                .testTag("camera_shutter_btn"),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, Color.Black, CircleShape)
                                    .background(BrandBlue)
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.Black.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = "Scanning automatically...",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }

                    if (currentMode == ScannerMode.DOCUMENT_PDF && documentPages.isNotEmpty()) {
                        Button(
                            onClick = { showDocumentReviewSheet = true },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.testTag("doc_done_review_btn")
                        ) {
                            Text("Done (${documentPages.size})")
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }
                }
            }

            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = BrandBlue)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(processingMessage, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    // Mode Selection Animated Bottom Sheet
    if (showModeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showModeSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier.testTag("mode_selection_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "Choose Scanner Mode",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))

                ScannerMode.entries.forEach { mode ->
                    val isSelected = currentMode == mode
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                currentMode = mode
                                showModeSheet = false
                            }
                            .testTag("scanner_mode_option_${mode.name}"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = mode.icon,
                                    contentDescription = mode.title,
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = mode.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Document Review Sheet
    if (showDocumentReviewSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDocumentReviewSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Document Pages (${documentPages.size})",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(14.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                ) {
                    itemsIndexed(documentPages) { index, pageItem ->
                        Box(
                            modifier = Modifier
                                .width(110.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        ) {
                            Image(
                                bitmap = pageItem.processed.asImageBitmap(),
                                contentDescription = "Page ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            IconButton(
                                onClick = { documentPages.removeAt(index) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(28.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                PrimaryButton(
                    text = "Generate PDF Document",
                    icon = Icons.Default.PictureAsPdf,
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            processingMessage = "Creating PDF..."
                            val pdf = withContext(Dispatchers.IO) {
                                PdfUtils.imagesToPdf(context, documentPages.map { it.processed })
                            }
                            isProcessing = false
                            if (pdf != null) {
                                generatedPdfFile = pdf
                                repository.addHistory("doc_scanner", "Scanned Document (${documentPages.size} pages)", pdf.name)
                                showDocumentReviewSheet = false
                                Toast.makeText(context, "PDF saved: ${pdf.name}", Toast.LENGTH_LONG).show()
                                StorageUtils.shareFile(context, pdf, "application/pdf")
                            } else {
                                Toast.makeText(context, "Failed to create PDF", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    }

    // Card Result Dialog
    if (showCardResultDialog && capturedCardBitmap != null) {
        AlertDialog(
            onDismissRequest = { showCardResultDialog = false },
            title = { Text("ID Card Captured", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = capturedCardBitmap!!.asImageBitmap(),
                        contentDescription = "Captured ID Card",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Card edges aligned and captured successfully.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                ImageUtils.compressBitmapToFile(
                                    context,
                                    capturedCardBitmap!!,
                                    quality = 95,
                                    fileName = "id_card_${System.currentTimeMillis()}.jpg"
                                )
                            }
                            showCardResultDialog = false
                            if (file != null) {
                                repository.addHistory("id_card_scanner", "ID Card Captured", file.name)
                                StorageUtils.shareFile(context, file, "image/jpeg")
                            }
                        }
                    }
                ) {
                    Text("Save & Share")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCardResultDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Image Scan Result Dialog
    if (showImageResultDialog && capturedImageBitmap != null) {
        AlertDialog(
            onDismissRequest = { showImageResultDialog = false },
            title = { Text("Photo Captured", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = capturedImageBitmap!!.asImageBitmap(),
                        contentDescription = "Captured Photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                ImageUtils.compressBitmapToFile(
                                    context,
                                    capturedImageBitmap!!,
                                    quality = 90,
                                    fileName = "scanned_photo_${System.currentTimeMillis()}.jpg"
                                )
                            }
                            showImageResultDialog = false
                            if (file != null) {
                                repository.addHistory("image_scanner", "Scanned Photo", file.name)
                                StorageUtils.shareFile(context, file, "image/jpeg")
                            }
                        }
                    }
                ) {
                    Text("Save & Share")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImageResultDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

private fun vibrateDevice(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            @Suppress("DEPRECATION")
            vibrator?.vibrate(50)
        }
    } catch (_: Exception) {}
}
