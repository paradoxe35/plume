package me.pngwasi.plume.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The platform font at deliberate weights. Headings run tight and heavy, body stays roomy — the
 * app shows a lot of user text, and the contrast is what keeps settings screens from reading flat.
 */
private val default = Typography()

val PlumeTypography = Typography(
    displaySmall = default.displaySmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = default.headlineMedium.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = default.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = default.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = default.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = default.bodyMedium.copy(lineHeight = 21.sp),
    labelLarge = default.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = default.labelSmall.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    ),
)

/** Monospace for prompt editors, where whitespace and placeholders need to be legible. */
val PromptEditorStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)
