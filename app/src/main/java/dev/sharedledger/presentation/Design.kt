package dev.sharedledger.presentation

import android.os.Build
import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharedledger.domain.ThemeMode

val Forest = Color(0xFF153F33)
val Mint = Color(0xFFBCEFC5)
val PaleMint = Color(0xFFE2F2DF)
val Coral = Color(0xFFA94338)
val PaleCoral = Color(0xFFFBE8DF)

private val Light = lightColorScheme(
    primary = Color(0xFF286348), onPrimary = Color.White, primaryContainer = PaleMint, onPrimaryContainer = Forest,
    secondary = Color(0xFF506758), secondaryContainer = Color(0xFFE4EEE3),
    background = Color(0xFFF5F7F1), onBackground = Color(0xFF192A22),
    surface = Color(0xFFFEFFFA), onSurface = Color(0xFF192A22), surfaceVariant = Color(0xFFE8EEE7),
    onSurfaceVariant = Color(0xFF51645A), outline = Color(0xFF77887D), outlineVariant = Color(0xFFD6E2D7),
    surfaceContainer = Color(0xFFEDF2EA), surfaceContainerHigh = Color(0xFFE9EFE6), error = Coral, errorContainer = PaleCoral
)
private val Dark = darkColorScheme(
    primary = Mint, onPrimary = Forest, primaryContainer = Color(0xFF254F3D), onPrimaryContainer = Mint,
    secondary = Color(0xFFB0CEB8), secondaryContainer = Color(0xFF31463C),
    background = Color(0xFF121D17), onBackground = Color(0xFFE6EFE5), surface = Color(0xFF1A2820),
    onSurface = Color(0xFFE6EFE5), surfaceVariant = Color(0xFF2C3B32), onSurfaceVariant = Color(0xFFBDCEC1),
    outline = Color(0xFF8B9F90), outlineVariant = Color(0xFF3B5043), surfaceContainer = Color(0xFF213127),
    error = Color(0xFFFFB4A8), errorContainer = Color(0xFF5A2C27)
)
private val Type = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-1.5).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = (-.6).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 25.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
)

@Composable
fun LedgerTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) Dark else Light
    val view = LocalView.current
    SideEffect {
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = Type, content = content)
}

fun iconFor(key: String): ImageVector = when (key) {
    "basket" -> Icons.Outlined.ShoppingBasket
    "dining" -> Icons.Outlined.Restaurant
    "coffee" -> Icons.Outlined.Coffee
    "delivery" -> Icons.Outlined.DeliveryDining
    "home" -> Icons.Outlined.Cottage
    "bill" -> Icons.Outlined.ReceiptLong
    "wifi" -> Icons.Outlined.Wifi
    "phone" -> Icons.Outlined.Smartphone
    "train" -> Icons.Outlined.Train
    "car" -> Icons.Outlined.DirectionsCar
    "fuel" -> Icons.Outlined.LocalGasStation
    "parking" -> Icons.Outlined.LocalParking
    "health" -> Icons.Outlined.MedicalServices
    "pharmacy" -> Icons.Outlined.Medication
    "sports" -> Icons.Outlined.FitnessCenter
    "clothes" -> Icons.Outlined.Checkroom
    "beauty" -> Icons.Outlined.Spa
    "sofa" -> Icons.Outlined.Chair
    "devices" -> Icons.Outlined.Devices
    "film" -> Icons.Outlined.Movie
    "book" -> Icons.Outlined.MenuBook
    "travel" -> Icons.Outlined.Flight
    "gift" -> Icons.Outlined.CardGiftcard
    "pets" -> Icons.Outlined.Pets
    "child" -> Icons.Outlined.ChildCare
    "shield" -> Icons.Outlined.VerifiedUser
    "heart" -> Icons.Outlined.FavoriteBorder
    "work" -> Icons.Outlined.WorkOutline
    "chart" -> Icons.Outlined.TrendingUp
    "bank" -> Icons.Outlined.AccountBalance
    "card" -> Icons.Outlined.CreditCard
    "transfer" -> Icons.Outlined.SwapHoriz
    "wallet" -> Icons.Outlined.AccountBalanceWallet
    else -> Icons.Outlined.MoreHoriz
}

@Composable
fun CategoryMark(icon: String, color: Long, modifier: Modifier = Modifier) {
    val tint = Color(color)
    Box(modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
        Icon(iconFor(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun EmptyMessage(title: String, description: String, icon: ImageVector = Icons.Outlined.ReceiptLong) {
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
