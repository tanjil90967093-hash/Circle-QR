package com.example.ui.screens.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.UtilityHubApp
import com.example.ui.components.PermissionNoticeCard
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.screens.home.ScannerMode
import com.example.ui.theme.BrandBlue
import com.example.utils.DocumentCornerTracker
import com.example.utils.DocumentFilter
import com.example.utils.DocumentProcessor
import com.example.utils.DocumentQuad
import com.example.utils.ImageUtils
import com.example.utils.PdfUtils
import com.example.utils.QrCodeHelper
import com.example.utils.ScannedPageItem
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

enum class CamScannerStep {
    CAMERA,
    CROP_EDITOR,
    ENHANCE_PREVIEW,
    PDF_SESSION_REVIEW
}

enum class CameraAppMode(val label: String, val isDocument: Boolean) {
    SCANNER("Scanner", true),
    PDF("PDF", true),
    QR_BARCODE("QR / Barcode", false),
    ID_CARD("ID Card", false),
    PHOTO("Photo", false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScannerScreen(
    initialMode: String = "DOCUMENT_PDF",
    onBack: () -> Unit,
    onNavigateToResult: (content: String, format: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember { UtilityHubApp.instance.repository }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // Current Workflow Step (Camera -> Crop -> Enhance -> Session)
    var currentStep by remember { mutableStateOf(CamScannerStep.CAMERA) }

    // Active Camera Mode (Scanner, PDF, QR / Barcode, ID Card, Photo)
    var activeCameraMode by remember {
        mutableStateOf(
            when (initialMode) {
                "PDF", "PDF_BATCH" -> CameraAppMode.PDF
                "QR_BARCODE" -> CameraAppMode.QR_BARCODE
                "ID_CARD" -> CameraAppMode.ID_CARD
                "IMAGE_SCAN", "PHOTO" -> CameraAppMode.PHOTO
                else -> CameraAppMode.SCANNER
            }
        )
    }

    var isFlashOn by remember { mutableStateOf(false) }
    var isFlashSupported by remember { mutableStateOf(false) }
    var isHdEnabled by remember { mutableStateOf(true) }
    var currentZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var minZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var maxZoomRatio by remember { mutableFloatStateOf(5.0f) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }

    // Real-time document boundary detection
    val cornerTracker = remember { DocumentCornerTracker() }
    var realtimeQuad by remember { mutableStateOf<DocumentQuad?>(null) }
    var realtimeBufferWidth by remember { mutableIntStateOf(0) }
    var realtimeBufferHeight by remember { mutableIntStateOf(0) }
    var isRealtimeReliable by remember { mutableStateOf(false) }
    var isRealtimeStable by remember { mutableStateOf(false) }

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

    // Document Scanning Pipeline Data
    var pendingRawBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingCropQuad by remember { mutableStateOf<DocumentQuad?>(null) }
    var croppedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var enhancedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeFilter by remember { mutableStateOf(DocumentFilter.MAGIC_COLOR) }
    var showCompareOriginal by remember { mutableStateOf(false) }

    // Multi-page batch session storage
    val documentPages = remember { mutableStateListOf<ScannedPageItem>() }
    var selectedPageIndex by remember { mutableIntStateOf(0) }

    // Document Naming
    var documentName by remember {
        mutableStateOf(
            "CamScanner " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())
        )
    }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf(documentName) }

    // Modals
    var showOcrModal by remember { mutableStateOf(false) }
    var ocrExtractedText by remember { mutableStateOf("") }
    var showSaveSuccessDialog by remember { mutableStateOf(false) }
    var savedPdfFile by remember { mutableStateOf<File?>(null) }

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

    // Helper vibration
    fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(30)
            }
        } catch (_: Exception) {}
    }

    // Gallery Picker for single or multi-images
    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isProcessing = true
                processingMessage = "Importing image(s)..."
                val firstUri = uris.first()
                val importedBmp = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(firstUri)?.use { stream ->
                            val raw = BitmapFactory.decodeStream(stream)
                            context.contentResolver.openInputStream(firstUri)?.use { exifStream ->
                                if (raw != null) DocumentProcessor.fixOrientation(raw, exifStream) else null
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                isProcessing = false

                if (importedBmp != null) {
                    if (activeCameraMode == CameraAppMode.QR_BARCODE) {
                        val decoded = QrCodeHelper.decodeBitmap(importedBmp)
                        if (decoded != null) {
                            vibrate()
                            repository.addHistory("qr_scanner", "QR from Gallery", decoded)
                            onNavigateToResult(decoded, "QR_CODE")
                        } else {
                            Toast.makeText(context, "No QR or barcode found in photo", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Open CamScanner crop editor for imported image
                        pendingRawBitmap = importedBmp
                        pendingCropQuad = DocumentProcessor.detectDocumentCorners(importedBmp)
                        currentStep = CamScannerStep.CROP_EDITOR
                    }
                } else {
                    Toast.makeText(context, "Could not load selected image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Trigger photo capture from CameraX
    fun executeCapture() {
        val capture = imageCapture ?: return
        if (isProcessing) return

        isProcessing = true
        processingMessage = "Capturing..."
        vibrate()

        val photoFile = File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

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
                                when (activeCameraMode) {
                                    CameraAppMode.SCANNER, CameraAppMode.PDF -> {
                                        pendingRawBitmap = raw
                                        pendingCropQuad = DocumentProcessor.detectDocumentCorners(raw!!)
                                        currentStep = CamScannerStep.CROP_EDITOR
                                    }
                                    CameraAppMode.ID_CARD -> {
                                        capturedCardBitmap = raw
                                        showCardResultDialog = true
                                    }
                                    CameraAppMode.PHOTO -> {
                                        capturedImageBitmap = raw
                                        showImageResultDialog = true
                                    }
                                    CameraAppMode.QR_BARCODE -> {}
                                }
                            } else {
                                Toast.makeText(context, "Could not decode captured photo", Toast.LENGTH_SHORT).show()
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

    // Save final document (PDF or image)
    fun finalizeAndSaveDocument() {
        scope.launch {
            isProcessing = true
            processingMessage = "Creating PDF..."

            val pagesToSave = if (documentPages.isNotEmpty()) {
                documentPages.map { it.processed }
            } else if (enhancedBitmap != null) {
                listOf(enhancedBitmap!!)
            } else {
                emptyList()
            }

            if (pagesToSave.isEmpty()) {
                isProcessing = false
                Toast.makeText(context, "No pages to save", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val pdf = withContext(Dispatchers.IO) {
                PdfUtils.imagesToPdf(context, pagesToSave, documentName)
            }

            isProcessing = false
            if (pdf != null) {
                savedPdfFile = pdf
                repository.addHistory(
                    "doc_scanner",
                    "$documentName (${pagesToSave.size} pages)",
                    pdf.name
                )
                showSaveSuccessDialog = true
            } else {
                Toast.makeText(context, "Failed to create PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Handle Back Press across the CamScanner workflow
    BackHandler {
        when (currentStep) {
            CamScannerStep.PDF_SESSION_REVIEW -> {
                currentStep = CamScannerStep.CAMERA
            }
            CamScannerStep.ENHANCE_PREVIEW -> {
                currentStep = CamScannerStep.CROP_EDITOR
            }
            CamScannerStep.CROP_EDITOR -> {
                currentStep = CamScannerStep.CAMERA
            }
            CamScannerStep.CAMERA -> {
                onBack()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = CamScannerDarkBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentStep) {
                // ==========================================
                // 1. CAMERA SCANNER SCREEN (Frame 00:00 - 00:04)
                // ==========================================
                CamScannerStep.CAMERA -> {
                    if (!hasCameraPermission) {
                        PermissionNoticeCard(
                            title = "Camera Access Required",
                            explanation = "Circle Scanner needs camera permission for CamScanner document detection and QR scanning.",
                            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        // Real CameraX Stream Engine
                        key(lensFacing) {
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
                                            .setTargetResolution(android.util.Size(640, 480))
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
                                            try {
                                                if (activeCameraMode == CameraAppMode.QR_BARCODE && !hasFoundCode) {
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
                                                                vibrate()
                                                                repository.addHistory(
                                                                    "qr_scanner",
                                                                    "Scanned ${result.barcodeFormat.name}",
                                                                    text
                                                                )
                                                                onNavigateToResult(text, result.barcodeFormat.name)
                                                            }
                                                        }
                                                    } catch (_: NotFoundException) {
                                                    } catch (_: Exception) {
                                                    } finally {
                                                        multiReader.reset()
                                                    }
                                                } else if (activeCameraMode.isDocument && !isProcessing) {
                                                    val yPlane = imageProxy.planes[0]
                                                    val rawResult = DocumentProcessor.detectDocumentInYuv(
                                                        yBuffer = yPlane.buffer,
                                                        width = imageProxy.width,
                                                        height = imageProxy.height,
                                                        rowStride = yPlane.rowStride,
                                                        pixelStride = yPlane.pixelStride,
                                                        rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                                    )
                                                    val trackedQuad = cornerTracker.track(rawResult)
                                                    val stable = cornerTracker.isStable
                                                    val reliable = rawResult != null && rawResult.isReliable

                                                    scope.launch(Dispatchers.Main) {
                                                        realtimeQuad = trackedQuad
                                                        isRealtimeReliable = reliable
                                                        isRealtimeStable = stable
                                                        if (rawResult != null) {
                                                            realtimeBufferWidth = rawResult.bufferWidth
                                                            realtimeBufferHeight = rawResult.bufferHeight
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            } finally {
                                                imageProxy.close()
                                            }
                                        }

                                        try {
                                            cameraProvider.unbindAll()
                                            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                                            val camera = cameraProvider.bindToLifecycle(
                                                lifecycleOwner,
                                                cameraSelector,
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
                                    .testTag("camscanner_preview")
                            )
                        }

                        // Live document boundary detection overlay in CamScanner Teal
                        if (activeCameraMode.isDocument) {
                            LiveDocumentDetectionOverlay(
                                detectedQuad = realtimeQuad,
                                bufferWidth = realtimeBufferWidth,
                                bufferHeight = realtimeBufferHeight,
                                isReliable = isRealtimeReliable,
                                isStable = isRealtimeStable,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Status guidance badge
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = when {
                                activeCameraMode.isDocument && isRealtimeStable -> CamScannerTeal.copy(alpha = 0.9f)
                                activeCameraMode.isDocument && isRealtimeReliable -> Color(0xFF2563EB).copy(alpha = 0.85f)
                                else -> Color.Black.copy(alpha = 0.65f)
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 70.dp)
                                .testTag("camscanner_instructions")
                        ) {
                            val msg = when {
                                activeCameraMode == CameraAppMode.PDF && documentPages.isNotEmpty() ->
                                    "${documentPages.size} page(s) scanned • Position next page"
                                activeCameraMode.isDocument && isRealtimeStable ->
                                    "Document Steady • Tap to capture"
                                activeCameraMode.isDocument && isRealtimeReliable ->
                                    "Document Detected • Hold steady"
                                activeCameraMode == CameraAppMode.SCANNER ->
                                    "Align document within frame"
                                activeCameraMode == CameraAppMode.PDF ->
                                    "Position page to scan multi-page PDF"
                                activeCameraMode == CameraAppMode.QR_BARCODE ->
                                    "Point camera at QR code or barcode"
                                activeCameraMode == CameraAppMode.ID_CARD ->
                                    "Align ID card within the frame"
                                else -> "Frame photo and tap shutter"
                            }
                            Text(
                                text = msg,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }

                        // Top Camera Toolbar (Back, Flash, HD, Flip)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left: Back arrow button
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .testTag("camera_back_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Right controls: Flash, HD, Camera Flip
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Flash Toggle
                                if (isFlashSupported) {
                                    IconButton(
                                        onClick = { isFlashOn = !isFlashOn },
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.5f))
                                            .testTag("camera_flash_toggle")
                                    ) {
                                        Icon(
                                            imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                            contentDescription = "Flash",
                                            tint = if (isFlashOn) Color(0xFFFBBF24) else Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                // HD Quality Toggle Badge
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isHdEnabled) CamScannerTeal.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { isHdEnabled = !isHdEnabled }
                                ) {
                                    Text(
                                        text = "HD",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }

                                // Flip Camera
                                IconButton(
                                    onClick = {
                                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                            CameraSelector.LENS_FACING_FRONT
                                        else
                                            CameraSelector.LENS_FACING_BACK
                                    },
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.5f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FlipCameraAndroid,
                                        contentDescription = "Flip Camera",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        // Bottom Area: Mode Selector & Shutter Bar (Frames 00:00 - 00:04)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Horizontally arranged mode selector directly above shutter
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp)
                                    .testTag("camscanner_mode_selector"),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                items(CameraAppMode.entries.toTypedArray()) { mode ->
                                    val isSelected = activeCameraMode == mode
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clickable {
                                                if (activeCameraMode != mode) {
                                                    activeCameraMode = mode
                                                    vibrate()
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = mode.label,
                                            color = if (isSelected) CamScannerTealBright else Color.White.copy(alpha = 0.7f),
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .width(18.dp)
                                                    .height(3.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(CamScannerTealBright)
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.height(7.dp))
                                        }
                                    }
                                }
                            }

                            // Shutter Controls Row (Matching CamScanner Video)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left: All Features Grid Icon
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        Toast.makeText(context, "Mode: ${activeCameraMode.label}", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GridView,
                                        contentDescription = "All features",
                                        tint = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "All features",
                                        color = Color.White.copy(alpha = 0.75f),
                                        fontSize = 10.sp
                                    )
                                }

                                // Center: CamScanner Signature Shutter Button
                                Box(
                                    modifier = Modifier
                                        .size(76.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .clickable { executeCapture() }
                                        .testTag("camscanner_shutter_btn"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(CircleShape)
                                            .background(CamScannerTeal),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // Inner white center circle
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                    }
                                }

                                // Right: Import Images / Gallery
                                if (activeCameraMode == CameraAppMode.PDF && documentPages.isNotEmpty()) {
                                    // Multi-page batch indicator button
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable { currentStep = CamScannerStep.PDF_SESSION_REVIEW }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .border(2.dp, CamScannerTealBright, RoundedCornerShape(8.dp))
                                        ) {
                                            Image(
                                                bitmap = documentPages.last().processed.asImageBitmap(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = "View (${documentPages.size})",
                                            color = CamScannerTealBright,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable { galleryPicker.launch("image/*") }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PhotoLibrary,
                                            contentDescription = "Import Images",
                                            tint = Color.White.copy(alpha = 0.85f),
                                            modifier = Modifier.size(26.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Import Images",
                                            color = Color.White.copy(alpha = 0.75f),
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // 2. CROP EDITOR SCREEN (Frame 00:05 - 00:09)
                // ==========================================
                CamScannerStep.CROP_EDITOR -> {
                    val rawBmp = pendingRawBitmap
                    val currentCropQuad = pendingCropQuad ?: (rawBmp?.let { DocumentProcessor.detectDocumentCorners(it) })

                    if (rawBmp != null && currentCropQuad != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(CamScannerDarkBg)
                        ) {
                            // Top Bar: Back arrow and "Crop" title
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { currentStep = CamScannerStep.CAMERA },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back to Camera",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Crop",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }

                            // Center: Full-Screen Interactive Quad Editor
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                InteractiveQuadEditor(
                                    bitmap = rawBmp,
                                    quad = currentCropQuad,
                                    onQuadChanged = { updatedQuad ->
                                        pendingCropQuad = updatedQuad
                                    }
                                )
                            }

                            // Bottom Bar: Left, Right, All, Reset, Next FAB (Matching Frame 00:05)
                            CamScannerCropBottomBar(
                                onRotateLeft = {
                                    val rot = DocumentProcessor.rotateBitmap(rawBmp, 270f)
                                    pendingRawBitmap = rot
                                    pendingCropQuad = DocumentProcessor.detectDocumentCorners(rot)
                                },
                                onRotateRight = {
                                    val rot = DocumentProcessor.rotateBitmap(rawBmp, 90f)
                                    pendingRawBitmap = rot
                                    pendingCropQuad = DocumentProcessor.detectDocumentCorners(rot)
                                },
                                onRetake = {
                                    pendingRawBitmap = null
                                    pendingCropQuad = null
                                    currentStep = CamScannerStep.CAMERA
                                },
                                onSelectAll = {
                                    val w = rawBmp.width.toFloat()
                                    val h = rawBmp.height.toFloat()
                                    val fullQuad = DocumentQuad(
                                        PointF(0f, 0f),
                                        PointF(w, 0f),
                                        PointF(w, h),
                                        PointF(0f, h),
                                        isAutoDetected = false
                                    )
                                    pendingCropQuad = fullQuad
                                },
                                onReset = {
                                    val autoQuad = DocumentProcessor.detectDocumentCorners(rawBmp)
                                    pendingCropQuad = autoQuad
                                },
                                onNext = {
                                    // Execute perspective correction and enhance
                                    scope.launch {
                                        isProcessing = true
                                        processingMessage = "Correcting perspective..."
                                        val quad = pendingCropQuad ?: DocumentProcessor.detectDocumentCorners(rawBmp)
                                        val cropped = withContext(Dispatchers.IO) {
                                            DocumentProcessor.cropPerspective(rawBmp, quad)
                                        }
                                        val enhanced = withContext(Dispatchers.IO) {
                                            DocumentProcessor.applyFilter(cropped, activeFilter)
                                        }
                                        croppedBitmap = cropped
                                        enhancedBitmap = enhanced
                                        isProcessing = false
                                        currentStep = CamScannerStep.ENHANCE_PREVIEW
                                    }
                                }
                            )
                        }
                    }
                }

                // ==========================================
                // 3. ENHANCE PREVIEW SCREEN (Frame 00:10 - 00:17)
                // ==========================================
                CamScannerStep.ENHANCE_PREVIEW -> {
                    val crBmp = croppedBitmap
                    val enhBmp = enhancedBitmap

                    if (crBmp != null && enhBmp != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(CamScannerDarkBg)
                        ) {
                            // Top Bar: Back, Document Title + Edit, Compare Button
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    IconButton(
                                        onClick = { currentStep = CamScannerStep.CROP_EDITOR },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back to Crop",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clickable {
                                                renameInput = documentName
                                                showRenameDialog = true
                                            }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = documentName,
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Rename",
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                // Compare Button (Press & Hold or Click)
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (showCompareOriginal) CamScannerTeal else Color(0xFF262626),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { showCompareOriginal = !showCompareOriginal }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Compare,
                                            contentDescription = "Compare",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Compare",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            // Center: Display Corrected Document
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F0F0F)),
                                contentAlignment = Alignment.Center
                            ) {
                                val displayBmp = if (showCompareOriginal && pendingRawBitmap != null) {
                                    pendingRawBitmap!!
                                } else {
                                    enhBmp
                                }
                                Image(
                                    bitmap = displayBmp.asImageBitmap(),
                                    contentDescription = "Corrected Document Preview",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )

                                if (showCompareOriginal) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color.Black.copy(alpha = 0.75f),
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(top = 12.dp)
                                    ) {
                                        Text(
                                            text = "Original Photo",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            // Bottom Bar: Filter Carousel Strip + Tools (Left, Markup, Extract Text, Recrop, Done)
                            CamScannerEnhanceBottomBar(
                                currentFilter = activeFilter,
                                onFilterSelected = { filter ->
                                    activeFilter = filter
                                    scope.launch {
                                        val updated = withContext(Dispatchers.IO) {
                                            DocumentProcessor.applyFilter(crBmp, filter)
                                        }
                                        enhancedBitmap = updated
                                    }
                                },
                                onRotateLeft = {
                                    val rot = DocumentProcessor.rotateBitmap(crBmp, 270f)
                                    croppedBitmap = rot
                                    scope.launch {
                                        val updated = withContext(Dispatchers.IO) {
                                            DocumentProcessor.applyFilter(rot, activeFilter)
                                        }
                                        enhancedBitmap = updated
                                    }
                                },
                                onMarkup = {
                                    Toast.makeText(context, "Document signature/markup enabled", Toast.LENGTH_SHORT).show()
                                },
                                onExtractText = {
                                    scope.launch {
                                        isProcessing = true
                                        processingMessage = "Extracting text with OCR..."
                                        val text = withContext(Dispatchers.IO) {
                                            DocumentProcessor.extractTextPreview(enhBmp)
                                        }
                                        isProcessing = false
                                        ocrExtractedText = text
                                        showOcrModal = true
                                    }
                                },
                                onRecrop = {
                                    currentStep = CamScannerStep.CROP_EDITOR
                                },
                                onDone = {
                                    // Save page item
                                    val pageItem = ScannedPageItem(
                                        rawOriginal = pendingRawBitmap ?: crBmp,
                                        croppedOriginal = crBmp,
                                        processed = enhBmp,
                                        activeFilter = activeFilter,
                                        quad = pendingCropQuad
                                    )
                                    documentPages.add(pageItem)
                                    selectedPageIndex = documentPages.size - 1
                                    Toast.makeText(context, "Page ${documentPages.size} added to session", Toast.LENGTH_SHORT).show()
                                    currentStep = CamScannerStep.PDF_SESSION_REVIEW
                                }
                            )
                        }
                    }
                }

                // ==========================================
                // 4. PDF SESSION REVIEW (Multi-Page Workflow)
                // Camera -> Capture -> Crop -> Correct Perspective -> Enhance -> Add Page or Finish -> PDF Preview/Save
                // ==========================================
                CamScannerStep.PDF_SESSION_REVIEW -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(CamScannerDarkBg)
                    ) {
                        // Top Bar: Back, Title + Rename, Save PDF Button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                IconButton(
                                    onClick = { currentStep = CamScannerStep.CAMERA },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clickable {
                                            renameInput = documentName
                                            showRenameDialog = true
                                        }
                                        .padding(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "$documentName (${documentPages.size} p.)",
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Rename",
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Button(
                                onClick = { finalizeAndSaveDocument() },
                                colors = ButtonDefaults.buttonColors(containerColor = CamScannerTeal),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save PDF", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Pages Horizontal List with Reordering and Controls
                        LazyRow(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            itemsIndexed(documentPages) { idx, page ->
                                Box(
                                    modifier = Modifier
                                        .width(230.dp)
                                        .fillMaxHeight(0.88f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black)
                                        .border(
                                            2.dp,
                                            if (selectedPageIndex == idx) CamScannerTealBright else Color.White.copy(alpha = 0.2f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable { selectedPageIndex = idx }
                                ) {
                                    Image(
                                        bitmap = page.processed.asImageBitmap(),
                                        contentDescription = "Page ${idx + 1}",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(bottom = 36.dp, top = 36.dp, start = 8.dp, end = 8.dp)
                                    )

                                    // Top Controls on Card: Move Left, Move Right, Delete
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.TopCenter)
                                            .padding(6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Left arrow to reorder page left
                                        if (idx > 0) {
                                            IconButton(
                                                onClick = {
                                                    val temp = documentPages[idx]
                                                    documentPages[idx] = documentPages[idx - 1]
                                                    documentPages[idx - 1] = temp
                                                    selectedPageIndex = idx - 1
                                                },
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.ArrowBack,
                                                    contentDescription = "Move Left",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.size(28.dp))
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            // Right arrow to reorder page right
                                            if (idx < documentPages.size - 1) {
                                                IconButton(
                                                    onClick = {
                                                        val temp = documentPages[idx]
                                                        documentPages[idx] = documentPages[idx + 1]
                                                        documentPages[idx + 1] = temp
                                                        selectedPageIndex = idx + 1
                                                    },
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                                ) {
                                                    Icon(
                                                        Icons.AutoMirrored.Filled.ArrowForward,
                                                        contentDescription = "Move Right",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }

                                            // Delete Page Button
                                            IconButton(
                                                onClick = {
                                                    if (documentPages.size > 1) {
                                                        documentPages.removeAt(idx)
                                                        selectedPageIndex = (selectedPageIndex - 1).coerceAtLeast(0)
                                                    } else {
                                                        Toast.makeText(context, "Cannot delete only page", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .background(Color.Red.copy(alpha = 0.75f), CircleShape)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Delete Page",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Bottom Row: Page Number Badge + Re-crop Action
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomCenter)
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color.Black.copy(alpha = 0.75f)
                                        ) {
                                            Text(
                                                text = "${idx + 1} / ${documentPages.size}",
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = CamScannerTeal.copy(alpha = 0.85f),
                                            modifier = Modifier.clickable {
                                                pendingRawBitmap = page.rawOriginal
                                                pendingCropQuad = page.quad ?: DocumentProcessor.detectDocumentCorners(page.rawOriginal)
                                                currentStep = CamScannerStep.CROP_EDITOR
                                            }
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Icon(Icons.Default.Crop, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Recrop", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Bottom Actions: Add Page (Camera), Add Page (Gallery), Finish & Save PDF
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { currentStep = CamScannerStep.CAMERA },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("+ Camera", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = { galleryPicker.launch("image/*") },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("+ Gallery", fontSize = 12.sp)
                            }

                            Button(
                                onClick = { finalizeAndSaveDocument() },
                                modifier = Modifier.weight(1.3f),
                                colors = ButtonDefaults.buttonColors(containerColor = CamScannerTeal)
                            ) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Finish PDF", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Universal Busy Processing Overlay
            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = CamScannerTealBright)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(processingMessage, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    // Rename Document Dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Document", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Document Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameInput.isNotBlank()) {
                            documentName = renameInput.trim()
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text("Save", color = CamScannerTeal)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // OCR Modal
    if (showOcrModal) {
        AlertDialog(
            onDismissRequest = { showOcrModal = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TextFields, contentDescription = null, tint = CamScannerTeal)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Extracted Text", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = ocrExtractedText.ifBlank { "No clear text recognized in document." },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(ocrExtractedText))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        showOcrModal = false
                    }
                ) {
                    Text("Copy All", color = CamScannerTeal)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOcrModal = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Save PDF Success Dialog
    if (showSaveSuccessDialog && savedPdfFile != null) {
        AlertDialog(
            onDismissRequest = {
                showSaveSuccessDialog = false
                currentStep = CamScannerStep.CAMERA
                documentPages.clear()
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CamScannerTeal)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Document Saved!", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text("PDF created successfully:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        savedPdfFile!!.name,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = CamScannerTeal
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Location: App Storage / pdfs/", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        StorageUtils.shareFile(context, savedPdfFile!!, "application/pdf")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CamScannerTeal)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share PDF")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSaveSuccessDialog = false
                        currentStep = CamScannerStep.CAMERA
                        documentPages.clear()
                    }
                ) {
                    Text("New Scan")
                }
            }
        )
    }

    // Card Result Dialog
    if (showCardResultDialog && capturedCardBitmap != null) {
        AlertDialog(
            onDismissRequest = { showCardResultDialog = false },
            title = { Text("ID Card Captured", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                    ) {
                        Image(
                            bitmap = capturedCardBitmap!!.asImageBitmap(),
                            contentDescription = "Card",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("ID Card recorded in storage", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val bmp = capturedCardBitmap
                        if (bmp != null) {
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    ImageUtils.saveBitmapToGalleryOrApp(context, bmp, "ID_Card_${System.currentTimeMillis()}")
                                }
                                if (saved != null) {
                                    repository.addHistory("card_scanner", "ID Card Scan", saved.name)
                                    Toast.makeText(context, "Saved to gallery", Toast.LENGTH_SHORT).show()
                                }
                                showCardResultDialog = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CamScannerTeal)
                ) {
                    Text("Save to Gallery")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCardResultDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    // Photo Result Dialog
    if (showImageResultDialog && capturedImageBitmap != null) {
        AlertDialog(
            onDismissRequest = { showImageResultDialog = false },
            title = { Text("Photo Captured", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                    ) {
                        Image(
                            bitmap = capturedImageBitmap!!.asImageBitmap(),
                            contentDescription = "Photo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val bmp = capturedImageBitmap
                        if (bmp != null) {
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    ImageUtils.saveBitmapToGalleryOrApp(context, bmp, "Photo_${System.currentTimeMillis()}")
                                }
                                if (file != null) {
                                    repository.addHistory("image_scanner", "Scanned Photo", file.name)
                                    Toast.makeText(context, "Saved to gallery", Toast.LENGTH_SHORT).show()
                                }
                                showImageResultDialog = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CamScannerTeal)
                ) {
                    Text("Save to Gallery")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImageResultDialog = false }) {
                    Text("Done")
                }
            }
        )
    }
}
