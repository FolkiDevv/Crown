package io.nekohasekai.sagernet.simple

import android.content.Context
import io.nekohasekai.sagernet.R
import java.text.NumberFormat

/**
 * Traffic in binary units (1 GB = 1024³ bytes), the way subscription panels and the classic interface count it;
 * Android's Formatter uses decimal units since 8.0, which shows a larger number than the provider does.
 */
fun trafficSize(context: Context, bytes: Long): String {
    val units = listOf(
        1L shl 40 to R.string.simple_size_tb,
        1L shl 30 to R.string.simple_size_gb,
        1L shl 20 to R.string.simple_size_mb,
    )
    val (unit, label) = units.firstOrNull { bytes >= it.first } ?: units.last()
    val value = bytes.toDouble() / unit
    val format = NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
        maximumFractionDigits = if (value < 100) 1 else 0
        minimumFractionDigits = 0
    }
    return context.getString(label, format.format(value))
}
