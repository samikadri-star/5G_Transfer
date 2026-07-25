package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TransferProgress
import com.example.data.model.TransferState
import com.example.ui.theme.SuccessGreen

@Composable
fun TransferProgressCard(
    progress: TransferProgress,
    onReset: () -> Unit,
    formatBytes: (Long) -> String,
    modifier: Modifier = Modifier
) {
    if (progress.state == TransferState.IDLE || progress.state == TransferState.SCANNING) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                when (progress.state) {
                                    TransferState.COMPLETED -> SuccessGreen.copy(alpha = 0.2f)
                                    TransferState.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                                    else -> MaterialTheme.colorScheme.primaryContainer
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (progress.state) {
                                TransferState.COMPLETED -> Icons.Default.CheckCircle
                                TransferState.FAILED -> Icons.Default.Error
                                else -> if (progress.isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile
                            },
                            contentDescription = "حالة النقل",
                            tint = when (progress.state) {
                                TransferState.COMPLETED -> SuccessGreen
                                TransferState.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = when (progress.state) {
                                TransferState.CONNECTING -> "جاري الاتصال..."
                                TransferState.TRANSFERRING -> "جاري النقل بسرعة عالية ⚡"
                                TransferState.COMPLETED -> "اكتمال النقل بنجاح 🎉"
                                TransferState.FAILED -> "فشل النقل"
                                else -> ""
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "جهاز الطرف الآخر: ${progress.peerName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (progress.state == TransferState.TRANSFERRING) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "سرعة النقل",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${String.format("%.1f", progress.currentSpeedMBps)} MB/s",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = progress.currentFileName.ifEmpty { "الملف الحالي..." },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(8.dp))

            val progressPercent = if (progress.totalBytes > 0) {
                (progress.bytesTransferred.toDouble() / progress.totalBytes.toDouble()).toFloat()
            } else 0f

            LinearProgressIndicator(
                progress = { progressPercent.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = if (progress.state == TransferState.COMPLETED) SuccessGreen else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatBytes(progress.bytesTransferred)} / ${formatBytes(progress.totalBytes)} (${(progressPercent * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (progress.estimatedTimeRemainingSec > 0 && progress.state == TransferState.TRANSFERRING) {
                    Text(
                        text = "المتبقي: ${progress.estimatedTimeRemainingSec} ثانية",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (progress.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progress.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (progress.state == TransferState.COMPLETED || progress.state == TransferState.FAILED) {
                Button(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (progress.state == TransferState.COMPLETED) SuccessGreen else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("تم", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
