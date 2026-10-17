package com.example.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.example.UtilityHubApp
import com.example.data.local.entity.HistoryEntity
import com.example.domain.model.ToolCategory
import com.example.domain.model.ToolRegistry
import com.example.utils.PdfUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.ui.navigation.Screen
import com.example.ui.theme.BrandBlue
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkTextPrimary
import com.example.ui.theme.DarkTextSecondary
import com.example.ui.theme.DarkBlue
import com.example.ui.theme.ReadingBackground
import com.example.ui.theme.ReadingBorder
import com.example.ui.theme.ReadingSurface
import com.example.ui.theme.ReadingTextPrimary
import com.example.ui.theme.ReadingTextSecondary
import com.example.ui.theme.ReadingBlue
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onNavigateToScanner: () -> Unit,
    onNavigateToCategory: (String) -> Unit,
    onNavigateToTool: (String) -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToPro: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit
) {
    val repository = remember { UtilityHubApp.instance.repository }
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }

    val favoriteIds by repository.getFavoriteToolIds().collectAsState(initial = emptyList())
    val historyItems by repository.getAllHistory().collectAsState(initial = emptyList())
    val recentTools by repository.getRecentTools().collectAsState(initial = emptyList())

    val scanHistoryItems = remember(historyItems) {
        historyItems.filter { item ->
            val id = item.toolId.lowercase()
            val title = item.title.lowercase()
            (id == "doc_scanner" || id == "qr_scanner" || id == "barcode_scanner" ||
             id.contains("pdf") || id.contains("scan") || id.contains("image") || id.contains("img_") ||
             title.contains("scanned") || title.contains("pdf") || title.contains("photo") || title.contains("scan") || title.contains("document")) &&
            !title.contains("clicked") && !title.contains("opened") && !title.contains("viewed") && !title.contains("visited")
        }
    }

    val searchResults = remember(searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            emptyList()
        } else {
            ToolRegistry.allTools.filter { tool ->
                tool.name.contains(query, ignoreCase = true) ||
                        tool.description.contains(query, ignoreCase = true) ||
                        tool.category.title.contains(query, ignoreCase = true) ||
                        tool.keywords.any { it.contains(query, ignoreCase = true) }
            }
        }
    }

    val currentThemeMode by repository.themeMode.collectAsState()
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (currentThemeMode.uppercase()) {
        "DARK" -> true
        "SYSTEM", "SYSTEM DEFAULT" -> isSystemDark
        else -> false
    }
    val isReading = currentThemeMode.uppercase() == "READING"

    val screenBg = when {
        isDark -> DarkBackground
        isReading -> ReadingBackground
        else -> Color.White
    }
    val cardBg = when {
        isDark -> DarkSurface
        isReading -> ReadingSurface
        else -> Color.White
    }
    val primaryTextColor = when {
        isDark -> DarkTextPrimary
        isReading -> ReadingTextPrimary
        else -> Color(0xFF0F172A)
    }
    val secondaryTextColor = when {
        isDark -> DarkTextSecondary
        isReading -> ReadingTextSecondary
        else -> Color(0xFF64748B)
    }
    val cardBorder = when {
        isDark -> DarkBorder
        isReading -> ReadingBorder
        else -> Color(0xFFE2E8F0)
    }
    val subtleCardBg = when {
        isDark -> Color(0xFF1E293B)
        isReading -> Color(0xFFF3EDE3)
        else -> Color(0xFFF8FAFC)
    }
    val searchBarBg = when {
        isDark -> Color(0xFF1E293B)
        isReading -> Color(0xFFF1EAE0)
        else -> Color(0xFFF1F5F9)
    }
    val searchBarBorder = when {
        isDark -> DarkBorder
        isReading -> ReadingBorder
        else -> Color(0xFFE2E8F0).copy(alpha = 0.8f)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBg)
    ) {
            // 1. Header (Top Bar matching reference)
            HomeHeader(
                isDark = isDark,
                isReading = isReading,
                primaryTextColor = primaryTextColor,
                secondaryTextColor = secondaryTextColor,
                cardBorder = cardBorder,
                onNavigateToPro = onNavigateToPro,
                onNavigateToSettings = onNavigateToSettings
            )

            // 2. Search Bar
            HomeSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                searchBarBg = searchBarBg,
                searchBarBorder = searchBarBorder,
                primaryTextColor = primaryTextColor,
                secondaryTextColor = secondaryTextColor
            )

            if (searchQuery.isNotBlank()) {
                // Live Search Results
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("home_search_results"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(searchResults, key = { it.id }) { tool ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    scope.launch { repository.recordToolUsage(tool.id) }
                                    onNavigateToTool(tool.route)
                                }
                                .testTag("home_result_${tool.id}"),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = subtleCardBg),
                            border = BorderStroke(1.dp, cardBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(tool.category.accentColor.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = tool.icon,
                                        contentDescription = null,
                                        tint = tool.category.accentColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tool.name,
                                        style = TextStyle(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = primaryTextColor
                                        )
                                    )
                                    Text(
                                        text = "${tool.category.title} · ${tool.description}",
                                        style = TextStyle(
                                            fontSize = 12.sp,
                                            color = secondaryTextColor
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF64748B) else Color(0xFFCBD5E1),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // Exact Reference Home Screen Content
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("home_dashboard_scroll"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // 3. Scan Anything Hero Card
                    item {
                        ScanAnythingHeroCard(
                            isDark = isDark,
                            isReading = isReading,
                            primaryTextColor = primaryTextColor,
                            secondaryTextColor = secondaryTextColor,
                            onNavigateToScanner = onNavigateToScanner
                        )
                    }

                    // 4. Quick Categories
                    item {
                        Column {
                            SectionHeaderRow(
                                title = "Quick Categories",
                                primaryTextColor = primaryTextColor,
                                isDark = isDark,
                                onSeeAllClick = onNavigateToTools
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // PDF
                                CategoryCompactCard(
                                    title = "PDF",
                                    badgeColor = Color(0xFFEF4444),
                                    backgroundColor = if (isDark) Color(0xFFEF4444).copy(alpha = 0.15f) else Color(0xFFFEF2F2),
                                    borderColor = if (isDark) Color(0xFFEF4444).copy(alpha = 0.35f) else Color(0xFFFEE2E2),
                                    iconType = CategoryIconType.PDF_BADGE,
                                    primaryTextColor = primaryTextColor,
                                    onClick = { onNavigateToCategory("pdf") },
                                    modifier = Modifier.weight(1f)
                                )
                                // QR & Barcode
                                CategoryCompactCard(
                                    title = "QR & Barcode",
                                    badgeColor = Color(0xFF2563EB),
                                    backgroundColor = if (isDark) Color(0xFF2563EB).copy(alpha = 0.15f) else Color(0xFFEFF6FF),
                                    borderColor = if (isDark) Color(0xFF2563EB).copy(alpha = 0.35f) else Color(0xFFDBEAFE),
                                    iconType = CategoryIconType.QR,
                                    primaryTextColor = primaryTextColor,
                                    onClick = { onNavigateToCategory("scanner") },
                                    modifier = Modifier.weight(1f)
                                )
                                // Image Tools
                                CategoryCompactCard(
                                    title = "Image Tools",
                                    badgeColor = Color(0xFF16A34A),
                                    backgroundColor = if (isDark) Color(0xFF16A34A).copy(alpha = 0.15f) else Color(0xFFF0FDF4),
                                    borderColor = if (isDark) Color(0xFF16A34A).copy(alpha = 0.35f) else Color(0xFFDCFCE7),
                                    iconType = CategoryIconType.IMAGE,
                                    primaryTextColor = primaryTextColor,
                                    onClick = { onNavigateToCategory("image") },
                                    modifier = Modifier.weight(1f)
                                )
                                // Files
                                CategoryCompactCard(
                                    title = "Files",
                                    badgeColor = Color(0xFF9333EA),
                                    backgroundColor = if (isDark) Color(0xFF9333EA).copy(alpha = 0.15f) else Color(0xFFFAF5FF),
                                    borderColor = if (isDark) Color(0xFF9333EA).copy(alpha = 0.35f) else Color(0xFFF3E8FF),
                                    iconType = CategoryIconType.FILES,
                                    primaryTextColor = primaryTextColor,
                                    onClick = { onNavigateToCategory("files") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // 5. Scan History
                    item {
                        Column {
                            SectionHeaderRow(
                                title = "Scan History",
                                primaryTextColor = primaryTextColor,
                                isDark = isDark,
                                onSeeAllClick = onNavigateToHistory
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            if (scanHistoryItems.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val displayItems = scanHistoryItems.take(4)
                                    displayItems.forEach { item ->
                                        ScanHistoryRow(
                                            item = item,
                                            cardBg = cardBg,
                                            cardBorder = cardBorder,
                                            primaryTextColor = primaryTextColor,
                                            secondaryTextColor = secondaryTextColor,
                                            isDark = isDark,
                                            onClick = onNavigateToHistory
                                        )
                                    }
                                }
                            } else {
                                EmptyStateCard(
                                    message = "No scan history yet. Your scanned documents, photos, and codes will appear here.",
                                    subtleCardBg = subtleCardBg,
                                    cardBorder = cardBorder,
                                    secondaryTextColor = secondaryTextColor
                                )
                            }
                        }
                    }

                    // 6. Favorite Tools
                    item {
                        Column {
                            SectionHeaderRow(
                                title = "Favorite Tools",
                                primaryTextColor = primaryTextColor,
                                isDark = isDark,
                                onSeeAllClick = onNavigateToTools
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Favorite 1: QR Scanner
                                FavoriteCompactCard(
                                    title = "QR Scanner",
                                    badgeColor = Color(0xFF2563EB),
                                    backgroundColor = if (isDark) Color(0xFF2563EB).copy(alpha = 0.15f) else Color(0xFFEFF6FF),
                                    borderColor = if (isDark) Color(0xFF2563EB).copy(alpha = 0.35f) else Color(0xFFDBEAFE),
                                    primaryTextColor = primaryTextColor,
                                    icon = Icons.Default.QrCodeScanner,
                                    onClick = {
                                        scope.launch { repository.recordToolUsage("tool_qr_scanner") }
                                        onNavigateToTool(Screen.QrScanner.route)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                // Favorite 2: PDF Tools
                                FavoriteCompactCard(
                                    title = "PDF Tools",
                                    badgeColor = Color(0xFFEF4444),
                                    backgroundColor = if (isDark) Color(0xFFEF4444).copy(alpha = 0.15f) else Color(0xFFFEF2F2),
                                    borderColor = if (isDark) Color(0xFFEF4444).copy(alpha = 0.35f) else Color(0xFFFEE2E2),
                                    primaryTextColor = primaryTextColor,
                                    icon = Icons.Default.PictureAsPdf,
                                    onClick = {
                                        scope.launch { repository.recordToolUsage("tool_pdf_tools") }
                                        onNavigateToTool(Screen.PdfTools.createRoute(0))
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                // Favorite 3: Image Compressor
                                FavoriteCompactCard(
                                    title = "Image\nCompressor",
                                    badgeColor = Color(0xFF16A34A),
                                    backgroundColor = if (isDark) Color(0xFF16A34A).copy(alpha = 0.15f) else Color(0xFFF0FDF4),
                                    borderColor = if (isDark) Color(0xFF16A34A).copy(alpha = 0.35f) else Color(0xFFDCFCE7),
                                    primaryTextColor = primaryTextColor,
                                    icon = Icons.Default.Image,
                                    onClick = {
                                        scope.launch { repository.recordToolUsage("tool_image_tools") }
                                        onNavigateToTool(Screen.ImageTools.createRoute(0))
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                // Favorite 4: File Manager
                                FavoriteCompactCard(
                                    title = "File Manager",
                                    badgeColor = Color(0xFF9333EA),
                                    backgroundColor = if (isDark) Color(0xFF9333EA).copy(alpha = 0.15f) else Color(0xFFFAF5FF),
                                    borderColor = if (isDark) Color(0xFF9333EA).copy(alpha = 0.35f) else Color(0xFFF3E8FF),
                                    primaryTextColor = primaryTextColor,
                                    icon = Icons.Default.Folder,
                                    onClick = {
                                        scope.launch { repository.recordToolUsage("tool_file_manager") }
                                        onNavigateToTool(Screen.FileManager.route)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // 7. Continue Where You Left Off
                    item {
                        val lastToolId = recentTools.firstOrNull()?.toolId ?: "tool_qr_scanner"
                        val lastTool = ToolRegistry.getById(lastToolId)
                        val lastRoute = lastTool?.route ?: Screen.Scanner.createRoute("QR_BARCODE")

                        val continueBg = when {
                            isDark -> Color(0xFF1E293B)
                            isReading -> ReadingSurface
                            else -> Color(0xFFF0F6FF)
                        }
                        val continueBorder = when {
                            isDark -> Color(0xFF3B82F6).copy(alpha = 0.35f)
                            isReading -> ReadingBorder
                            else -> Color(0xFFDBEAFE)
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = continueBg,
                            border = BorderStroke(1.dp, continueBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    scope.launch { repository.recordToolUsage(lastToolId) }
                                    onNavigateToTool(lastRoute)
                                }
                                .testTag("continue_last_operation_card")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(BrandBlue),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCodeScanner,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Continue Where You Left Off",
                                        style = TextStyle(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = primaryTextColor
                                        )
                                    )
                                    Text(
                                        text = "Resume your last operation",
                                        style = TextStyle(
                                            fontSize = 12.sp,
                                            color = secondaryTextColor
                                        )
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 1. Header (Logo, Utility Hub, Pro button, Settings button)
// ---------------------------------------------------------------------------
@Composable
private fun HomeHeader(
    isDark: Boolean,
    isReading: Boolean,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    cardBorder: Color,
    onNavigateToPro: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Logo (Blue rounded square with 4 white rounded clover lobes)
        UtilityHubLogo()

        Spacer(modifier = Modifier.width(10.dp))

        // Title and Subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Circle Scanner",
                style = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryTextColor
                )
            )
            Text(
                text = "Smart Document & Tools",
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = secondaryTextColor
                )
            )
        }

        // Pale-blue Pro pill button
        val proBg = when {
            isDark -> Color(0xFF2563EB).copy(alpha = 0.2f)
            isReading -> Color(0xFFE8DFCD)
            else -> Color(0xFFEFF6FF)
        }
        val proBorder = BorderStroke(0.8.dp, when {
            isDark -> Color(0xFF3B82F6).copy(alpha = 0.4f)
            isReading -> ReadingBorder
            else -> Color(0xFFDBEAFE)
        })
        val proTint = when {
            isDark -> Color(0xFF93C5FD)
            isReading -> ReadingBlue
            else -> Color(0xFF2563EB)
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = proBg,
            border = proBorder,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .clickable { onNavigateToPro() }
                .testTag("home_pro_pill_action")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WorkspacePremium,
                    contentDescription = "Pro",
                    tint = proTint,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Pro",
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = proTint
                    )
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Circular Settings Icon
        val settingsBg = when {
            isDark -> Color(0xFF1E293B)
            isReading -> ReadingSurface
            else -> Color(0xFFF8FAFC)
        }
        val settingsIconTint = if (isDark) Color(0xFFE2E8F0) else Color(0xFF334155)

        Surface(
            shape = CircleShape,
            color = settingsBg,
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable { onNavigateToSettings() }
                .testTag("home_settings_action")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = settingsIconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 2. Modern 4-Lobe Clover Logo
// ---------------------------------------------------------------------------
@Composable
private fun UtilityHubLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BrandBlue),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val petalSize = size.width * 0.42f
            val cornerRadius = CornerRadius(petalSize * 0.42f, petalSize * 0.42f)
            val offset = size.width * 0.05f

            // Top-left lobe
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(offset, offset),
                size = Size(petalSize, petalSize),
                cornerRadius = cornerRadius
            )
            // Top-right lobe
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(size.width - petalSize - offset, offset),
                size = Size(petalSize, petalSize),
                cornerRadius = cornerRadius
            )
            // Bottom-left lobe
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(offset, size.height - petalSize - offset),
                size = Size(petalSize, petalSize),
                cornerRadius = cornerRadius
            )
            // Bottom-right lobe
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(size.width - petalSize - offset, size.height - petalSize - offset),
                size = Size(petalSize, petalSize),
                cornerRadius = cornerRadius
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 3. Search Bar
// ---------------------------------------------------------------------------
@Composable
private fun HomeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    searchBarBg: Color,
    searchBarBorder: Color,
    primaryTextColor: Color,
    secondaryTextColor: Color
) {
    Surface(
        shape = CircleShape,
        color = searchBarBg,
        border = BorderStroke(1.dp, searchBarBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(42.dp)
            .testTag("home_search_input")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = secondaryTextColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search tools and features...",
                        style = TextStyle(
                            fontSize = 13.5.sp,
                            color = secondaryTextColor
                        )
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 13.5.sp,
                        color = primaryTextColor,
                        fontWeight = FontWeight.Normal
                    ),
                    cursorBrush = SolidColor(BrandBlue),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = secondaryTextColor,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 4. Scan Anything Hero Card
// ---------------------------------------------------------------------------
@Composable
private fun ScanAnythingHeroCard(
    isDark: Boolean,
    isReading: Boolean,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    onNavigateToScanner: () -> Unit
) {
    val heroBg = when {
        isDark -> Color(0xFF1E293B)
        isReading -> ReadingSurface
        else -> Color(0xFFEFF6FF)
    }
    val heroBorder = BorderStroke(1.dp, when {
        isDark -> Color(0xFF3B82F6).copy(alpha = 0.35f)
        isReading -> ReadingBorder
        else -> Color(0xFFDBEAFE)
    })

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = heroBg,
        border = heroBorder,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .testTag("scan_anything_hero_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column (Text + Button)
            Column(
                modifier = Modifier.weight(1.25f)
            ) {
                Text(
                    text = "Scan Anything",
                    style = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryTextColor
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "QR codes, barcodes or documents",
                    style = TextStyle(
                        fontSize = 12.5.sp,
                        color = secondaryTextColor
                    )
                )
                Spacer(modifier = Modifier.height(14.dp))

                // Pill Button "Start Scanning"
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = BrandBlue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { onNavigateToScanner() }
                        .testTag("start_scanning_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Start Scanning",
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Right Illustration (Circular Blob + Scan Frame Illustration)
            Box(
                modifier = Modifier
                    .weight(0.95f)
                    .height(105.dp),
                contentAlignment = Alignment.Center
            ) {
                // Background soft light-blue circular blob
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0xFF2563EB).copy(alpha = 0.25f) else Color(0xFFDBEAFE).copy(alpha = 0.55f))
                )

                // Large scan frame corners + horizontal laser
                Canvas(modifier = Modifier.size(72.dp)) {
                    val strokeW = 3.5.dp.toPx()
                    val bracketLen = 16.dp.toPx()
                    val cornerR = 7.dp.toPx()
                    val blueColor = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)

                    // Top-Left corner bracket
                    drawLine(blueColor, Offset(0f, bracketLen), Offset(0f, cornerR), strokeW, StrokeCap.Round)
                    drawLine(blueColor, Offset(cornerR, 0f), Offset(bracketLen, 0f), strokeW, StrokeCap.Round)
                    drawArc(
                        color = blueColor,
                        startAngle = 180f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(0f, 0f),
                        size = Size(cornerR * 2, cornerR * 2),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )

                    // Top-Right corner bracket
                    val w = size.width
                    drawLine(blueColor, Offset(w - bracketLen, 0f), Offset(w - cornerR, 0f), strokeW, StrokeCap.Round)
                    drawLine(blueColor, Offset(w, cornerR), Offset(w, bracketLen), strokeW, StrokeCap.Round)
                    drawArc(
                        color = blueColor,
                        startAngle = 270f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(w - cornerR * 2, 0f),
                        size = Size(cornerR * 2, cornerR * 2),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )

                    // Bottom-Left corner bracket
                    val h = size.height
                    drawLine(blueColor, Offset(0f, h - bracketLen), Offset(0f, h - cornerR), strokeW, StrokeCap.Round)
                    drawLine(blueColor, Offset(cornerR, h), Offset(bracketLen, h), strokeW, StrokeCap.Round)
                    drawArc(
                        color = blueColor,
                        startAngle = 90f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(0f, h - cornerR * 2),
                        size = Size(cornerR * 2, cornerR * 2),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )

                    // Bottom-Right corner bracket
                    drawLine(blueColor, Offset(w - bracketLen, h), Offset(w - cornerR, h), strokeW, StrokeCap.Round)
                    drawLine(blueColor, Offset(w, h - bracketLen), Offset(w, h - cornerR), strokeW, StrokeCap.Round)
                    drawArc(
                        color = blueColor,
                        startAngle = 0f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(w - cornerR * 2, h - cornerR * 2),
                        size = Size(cornerR * 2, cornerR * 2),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )

                    // Center horizontal scan beam
                    val beamMargin = 10.dp.toPx()
                    drawLine(
                        color = Color(0xFF3B82F6).copy(alpha = 0.55f),
                        start = Offset(beamMargin, h / 2f),
                        end = Offset(w - beamMargin, h / 2f),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 5. Section Header Row ("Title" and "See All >")
// ---------------------------------------------------------------------------
@Composable
private fun SectionHeaderRow(
    title: String,
    primaryTextColor: Color = Color(0xFF0F172A),
    isDark: Boolean = false,
    onSeeAllClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = primaryTextColor
            )
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onSeeAllClick() }
                .padding(vertical = 4.dp, horizontal = 4.dp)
        ) {
            Text(
                text = "See All",
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)
                )
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "See All",
                tint = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 6. Category Compact Card (Quick Categories: PDF, QR & Barcode, Image, Files)
// ---------------------------------------------------------------------------
enum class CategoryIconType {
    PDF_BADGE,
    QR,
    IMAGE,
    FILES
}

@Composable
private fun CategoryCompactCard(
    title: String,
    badgeColor: Color,
    backgroundColor: Color,
    borderColor: Color,
    iconType: CategoryIconType,
    primaryTextColor: Color = Color(0xFF1E293B),
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = BorderStroke(0.6.dp, borderColor),
        modifier = modifier
            .height(68.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (iconType) {
                CategoryIconType.PDF_BADGE -> {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "PDF",
                            style = TextStyle(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        )
                    }
                }
                CategoryIconType.QR -> {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = title,
                        tint = badgeColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                CategoryIconType.IMAGE -> {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = title,
                        tint = badgeColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                CategoryIconType.FILES -> {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = title,
                        tint = badgeColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = title,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryTextColor,
                    textAlign = TextAlign.Center
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 7. Scan History Row (Real thumbnails, scan type chips, document names & dates)
// ---------------------------------------------------------------------------
@Composable
private fun ScanHistoryRow(
    item: HistoryEntity,
    cardBg: Color = Color.White,
    cardBorder: Color = Color(0xFFF1F5F9),
    primaryTextColor: Color = Color(0xFF1E293B),
    secondaryTextColor: Color = Color(0xFF64748B),
    isDark: Boolean = false,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val relativeTime = remember(item.timestamp) {
        formatRelativeTime(item.timestamp)
    }

    val (scanTypeLabel, typeColor, typeIcon) = remember(item.toolId, item.title) {
        val id = item.toolId.lowercase()
        val title = item.title.lowercase()
        when {
            id.contains("doc") || title.contains("document") -> Triple("Document", Color(0xFF0F766E), Icons.Default.Description)
            id.contains("pdf") || title.contains("pdf") -> Triple("PDF", Color(0xFFEF4444), Icons.Default.PictureAsPdf)
            id.contains("qr") || title.contains("qr") -> Triple("QR Code", Color(0xFF2563EB), Icons.Default.QrCode2)
            id.contains("barcode") || title.contains("barcode") -> Triple("Barcode", Color(0xFF7C3AED), Icons.Default.QrCodeScanner)
            id.contains("card") || title.contains("card") -> Triple("ID Card", Color(0xFFD97706), Icons.Default.Badge)
            id.contains("image") || id.contains("photo") || title.contains("photo") -> Triple("Image", Color(0xFF16A34A), Icons.Default.Image)
            else -> Triple("Scan", Color(0xFF0284C7), Icons.Default.DocumentScanner)
        }
    }

    var thumbnailBitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id, item.details) {
        withContext(Dispatchers.IO) {
            try {
                // 1. Look for PDF file in pdfs folder
                val pdfDir = File(context.filesDir, "pdfs")
                val candidatePdf = if (item.details.endsWith(".pdf", ignoreCase = true)) {
                    File(pdfDir, item.details)
                } else null

                if (candidatePdf != null && candidatePdf.exists()) {
                    val uri = Uri.fromFile(candidatePdf)
                    val bmps = PdfUtils.pdfToBitmaps(context, uri, 1)
                    if (bmps.isNotEmpty()) {
                        thumbnailBitmap = bmps.first()
                        return@withContext
                    }
                }

                // 2. Look for Image in images or exported_media
                val imgDir = File(context.filesDir, "images")
                val mediaDir = File(context.filesDir, "exported_media")
                val possibleImg = listOf(
                    File(imgDir, item.details),
                    File(mediaDir, item.details),
                    File(item.details)
                ).firstOrNull { it.exists() && it.isFile }

                if (possibleImg != null) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bmp = BitmapFactory.decodeFile(possibleImg.absolutePath, opts)
                    if (bmp != null) {
                        thumbnailBitmap = bmp
                    }
                }
            } catch (e: Exception) {
                // Fallback to type icon badge
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag("scan_history_item_${item.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Thumbnail or Category Badge
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(typeColor.copy(alpha = if (thumbnailBitmap != null) 0.05f else 0.12f))
                    .border(1.dp, typeColor.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap!!.asImageBitmap(),
                        contentDescription = "Scan Preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (scanTypeLabel == "PDF") {
                    Text(
                        text = "PDF",
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = typeColor
                        )
                    )
                } else {
                    Icon(
                        imageVector = typeIcon,
                        contentDescription = scanTypeLabel,
                        tint = typeColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Title, Type Tag & Details
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.title,
                        style = TextStyle(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryTextColor
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // Type Badge Chip
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = typeColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = scanTypeLabel,
                            color = typeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = item.details.ifBlank { "Scan completed" },
                        style = TextStyle(
                            fontSize = 11.5.sp,
                            color = secondaryTextColor
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = relativeTime,
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (isDark) Color(0xFF475569) else Color(0xFFCBD5E1),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 8. Favorite Compact Card (QR Scanner, PDF Tools, Image Compressor, File Manager)
// ---------------------------------------------------------------------------
@Composable
private fun FavoriteCompactCard(
    title: String,
    badgeColor: Color,
    backgroundColor: Color,
    borderColor: Color,
    primaryTextColor: Color = Color(0xFF1E293B),
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        border = BorderStroke(0.7.dp, borderColor),
        modifier = modifier
            .height(86.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(badgeColor),
                contentAlignment = Alignment.Center
            ) {
                if (title.contains("PDF", ignoreCase = true)) {
                    Text(
                        text = "PDF",
                        style = TextStyle(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = title,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryTextColor,
                    textAlign = TextAlign.Center,
                    lineHeight = 13.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 9. Empty State Card
// ---------------------------------------------------------------------------
@Composable
private fun EmptyStateCard(
    message: String,
    subtleCardBg: Color = Color(0xFFF8FAFC),
    cardBorder: Color = Color(0xFFE2E8F0),
    secondaryTextColor: Color = Color(0xFF64748B)
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = subtleCardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.HistoryToggleOff,
                contentDescription = null,
                tint = secondaryTextColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = TextStyle(
                    fontSize = 12.sp,
                    color = secondaryTextColor
                )
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Helper: Relative Time Formatter
// ---------------------------------------------------------------------------
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}
