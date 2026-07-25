package com.example.data.model

import android.net.Uri

data class DiscoveredDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int = 8888,
    val is5GHz: Boolean = false,
    val lastSeenTime: Long = System.currentTimeMillis()
)

data class SelectedItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val relativePath: String = name, // e.g., "Folder/Sub/file.pdf"
    val mimeType: String = "*/*"
)

enum class TransferState {
    IDLE,
    SCANNING,
    CONNECTING,
    TRANSFERRING,
    COMPLETED,
    FAILED
}

data class TransferProgress(
    val state: TransferState = TransferState.IDLE,
    val currentFileName: String = "",
    val isFolder: Boolean = false,
    val currentFileIndex: Int = 0,
    val totalFilesCount: Int = 0,
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
    val currentSpeedMBps: Double = 0.0,
    val estimatedTimeRemainingSec: Long = 0,
    val peerName: String = "",
    val errorMessage: String? = null
)

data class NetworkStatus(
    val isWifiConnected: Boolean = false,
    val ssid: String = "غير متصل",
    val ipAddress: String = "127.0.0.1",
    val is5GHz: Boolean = false,
    val frequencyMHz: Int = 0,
    val isHotspotActive: Boolean = false
)
