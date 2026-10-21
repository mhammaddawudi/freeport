package io.github.mhammaddawudi.freeport.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.core.IphoneExporter
import io.github.mhammaddawudi.freeport.ui.IphoneExportUi
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors
import java.io.File

/**
 * Makes a WireGuard config for an iPhone and shows it as a QR code, with a
 * copy and a share button for when the two phones are not side by side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IphoneExportSheet(
    state: IphoneExportUi,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AetherColors.Surface,
        contentColor = AetherColors.Ink,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(AetherColors.HairlineStrong),
            )
        },
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.iphone_title), style = MaterialTheme.typography.headlineSmall, color = AetherColors.Ink)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.iphone_subtitle), style = MaterialTheme.typography.bodyMedium, color = AetherColors.InkMuted)
            Spacer(Modifier.height(18.dp))

            when (state) {
                IphoneExportUi.Idle -> {
                    Note(stringResource(R.string.iphone_same_provider))
                    Spacer(Modifier.height(18.dp))
                    ActionPill(GlyphKind.Bolt, stringResource(R.string.iphone_make), onStart, primary = true)
                }

                is IphoneExportUi.Working -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = AetherColors.Control, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        stringResource(
                            when (state.phase) {
                                IphoneExporter.Phase.Registering -> R.string.iphone_registering
                                IphoneExporter.Phase.Scanning -> R.string.iphone_scanning
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherColors.Ink,
                    )
                }

                is IphoneExportUi.Failed -> {
                    Note(stringResource(R.string.iphone_failed, state.message), AetherColors.Rose)
                    Spacer(Modifier.height(16.dp))
                    ActionPill(GlyphKind.Bolt, stringResource(R.string.iphone_retry), onStart, primary = true)
                }

                is IphoneExportUi.Ready -> {
                    val text = state.config.text
                    val qr = remember(text) { qrBitmap(text) }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Always dark on white: scanners read inverted codes badly.
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = stringResource(R.string.iphone_qr_description),
                            filterQuality = FilterQuality.None,
                            modifier = Modifier
                                .size(260.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(10.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.iphone_endpoint, state.config.endpoint.socket, state.config.endpoint.rttMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.InkMuted,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionPill(
                            GlyphKind.Copy,
                            stringResource(R.string.iphone_copy),
                            onClick = { clipboard.setText(AnnotatedString(text)) },
                            modifier = Modifier.weight(1f),
                        )
                        ActionPill(
                            GlyphKind.Up,
                            stringResource(R.string.iphone_share),
                            onClick = { shareConf(context, text) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Note(stringResource(R.string.iphone_how_to))
                    Spacer(Modifier.height(10.dp))
                    Note(stringResource(R.string.iphone_same_provider))
                    Spacer(Modifier.height(16.dp))
                    ActionPill(GlyphKind.Bolt, stringResource(R.string.iphone_make_another), onStart)
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, accent: Color = AetherColors.Control) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = AetherColors.Ink)
    }
}

@Composable
private fun ActionPill(
    glyph: GlyphKind,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    primary: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    val tint = if (primary) AetherColors.Control else AetherColors.Ink
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (primary) AetherColors.Control.copy(alpha = 0.16f) else AetherColors.Ink.copy(alpha = 0.05f))
            .border(1.dp, if (primary) AetherColors.Control.copy(alpha = 0.45f) else AetherColors.Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Glyph(glyph, tint, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** One pixel per module; the Image scales it up without smoothing. */
private fun qrBitmap(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix.get(index % matrix.width, index / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

/**
 * Shares the config as a file. The WireGuard app on iOS names the tunnel
 * after the file, so the name is kept short and plain.
 */
private fun shareConf(context: Context, text: String) {
    runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "freeport.conf").apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.iphone_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
