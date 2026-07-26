package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.TransferRecord
import com.example.data.model.DiscoveredDevice
import com.example.data.model.NetworkStatus
import com.example.data.model.SelectedItem
import com.example.data.model.TransferProgress
import com.example.data.model.TransferState
import com.example.data.repository.TransferRepository
import com.example.service.NotificationHelper
import com.example.service.WifiP2pEngine
import com.example.service.WifiTransferEngine
import android.net.wifi.p2p.WifiP2pDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class MainTab {
    SEND,
    RECEIVE,
    HISTORY,
    SETTINGS
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext
    val repository = TransferRepository(context)
    val transferEngine = WifiTransferEngine(context)
    val wifiP2pEngine = WifiP2pEngine(context)
    private val notificationHelper = NotificationHelper(context)

    private val _currentTab = MutableStateFlow(MainTab.SEND)
    val currentTab: StateFlow<MainTab> = _currentTab.asStateFlow()

    private val _selectedItems = MutableStateFlow<List<SelectedItem>>(emptyList())
    val selectedItems: StateFlow<List<SelectedItem>> = _selectedItems.asStateFlow()

    val networkStatus: StateFlow<NetworkStatus> = transferEngine.networkStatus
    val nsdDiscoveredDevices: StateFlow<List<DiscoveredDevice>> = transferEngine.discoveredDevices
    val p2pDiscoveredDevices: StateFlow<List<DiscoveredDevice>> = wifiP2pEngine.p2pDiscoveredDevices
    val p2pStatusText: StateFlow<String> = wifiP2pEngine.p2pStatusText
    val p2pPeers: StateFlow<List<WifiP2pDevice>> = wifiP2pEngine.p2pPeers
    val isP2pConnected: StateFlow<Boolean> = wifiP2pEngine.isConnected

    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = transferEngine.discoveredDevices
    val transferProgress: StateFlow<TransferProgress> = transferEngine.transferProgress

    val historyRecords = repository.allHistoryRecords
    val totalSentBytes = repository.totalSentBytes
    val totalReceivedBytes = repository.totalReceivedBytes
    val customSaveUri = repository.customSaveUri
    val deviceName = repository.deviceName
    val autoAccept = repository.autoAccept

    init {
        wifiP2pEngine.initP2p()

        viewModelScope.launch {
            transferProgress.collectLatest { progress ->
                if (progress.state == TransferState.TRANSFERRING) {
                    val percent = if (progress.totalBytes > 0) {
                        ((progress.bytesTransferred.toDouble() / progress.totalBytes) * 100).toInt()
                    } else 0
                    notificationHelper.showTransferProgressNotification(
                        progress.currentFileName.ifEmpty { "ملفات" },
                        percent,
                        progress.currentSpeedMBps
                    )
                } else if (progress.state == TransferState.COMPLETED) {
                    notificationHelper.cancelProgressNotification()
                }
            }
        }
    }

    fun selectTab(tab: MainTab) {
        _currentTab.value = tab
        if (tab == MainTab.RECEIVE) {
            startReceivingServer()
            wifiP2pEngine.startDiscovery()
        } else if (tab == MainTab.SEND) {
            startDeviceDiscovery()
        } else {
            transferEngine.stopDiscovery()
        }
    }

    fun startDeviceDiscovery() {
        transferEngine.startDiscovery()
        wifiP2pEngine.startDiscovery()
    }

    fun connectToP2pDevice(device: WifiP2pDevice) {
        wifiP2pEngine.connectToDevice(device)
    }

    fun disconnectP2p() {
        wifiP2pEngine.disconnectP2p()
    }

    fun startReceivingServer() {
        transferEngine.startReceiverServer(
            deviceName = repository.deviceName.value,
            customSaveUri = repository.customSaveUri.value,
            onTransferComplete = { fileName, totalBytes, peerName ->
                saveTransferRecordToDb(
                    fileName = fileName,
                    isFolder = fileName.contains("/"),
                    fileSize = totalBytes,
                    transferType = "RECEIVED",
                    peerName = peerName,
                    status = "SUCCESS"
                )
                notificationHelper.showTransferSuccessNotification(
                    fileName = fileName,
                    peerName = peerName,
                    totalBytesFormatted = formatBytes(totalBytes)
                )
            }
        )
    }

    fun sendSelectedItemsTo(device: DiscoveredDevice) {
        if (_selectedItems.value.isEmpty()) return

        transferEngine.sendItemsToDevice(
            targetIp = device.ipAddress,
            targetPort = device.port,
            senderDeviceName = repository.deviceName.value,
            selectedItems = _selectedItems.value,
            onTransferComplete = { fileName, totalBytes, peerName ->
                saveTransferRecordToDb(
                    fileName = fileName,
                    isFolder = fileName.contains("/"),
                    fileSize = totalBytes,
                    transferType = "SENT",
                    peerName = peerName,
                    status = "SUCCESS"
                )
                notificationHelper.showTransferSuccessNotification(
                    fileName = fileName,
                    peerName = peerName,
                    totalBytesFormatted = formatBytes(totalBytes)
                )
            }
        )
    }

    fun addSelectedFileUris(uris: List<Uri>) {
        viewModelScope.launch {
            val newList = _selectedItems.value.toMutableList()
            for (uri in uris) {
                val item = createSelectedItemFromUri(uri)
                if (item != null && !newList.any { it.uri == uri }) {
                    newList.add(item)
                }
            }
            _selectedItems.value = newList
        }
    }

    fun addSelectedFolderUri(treeUri: Uri) {
        viewModelScope.launch {
            try {
                val doc = DocumentFile.fromTreeUri(context, treeUri)
                if (doc != null && doc.isDirectory) {
                    val folderName = doc.name ?: "مجلد مستندات"
                    val folderItem = SelectedItem(
                        uri = treeUri,
                        name = folderName,
                        size = calculateDirectorySize(doc),
                        isDirectory = true,
                        relativePath = folderName
                    )
                    val newList = _selectedItems.value.toMutableList()
                    newList.add(folderItem)
                    _selectedItems.value = newList
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun calculateDirectorySize(docDir: DocumentFile): Long {
        var size = 0L
        for (file in docDir.listFiles()) {
            size += if (file.isDirectory) calculateDirectorySize(file) else file.length()
        }
        return size
    }

    private fun createSelectedItemFromUri(uri: Uri): SelectedItem? {
        var fileName = "ملف غير معروف"
        var fileSize = 0L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }

        val mimeType = context.contentResolver.getType(uri) ?: "*/*"

        return SelectedItem(
            uri = uri,
            name = fileName,
            size = fileSize,
            isDirectory = false,
            relativePath = fileName,
            mimeType = mimeType
        )
    }

    fun removeItemAt(index: Int) {
        val list = _selectedItems.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _selectedItems.value = list
        }
    }

    fun clearSelectedItems() {
        _selectedItems.value = emptyList()
    }

    fun setCustomSaveDirectory(uri: Uri) {
        try {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            repository.setCustomSaveUri(uri.toString())
        } catch (e: Exception) {
            repository.setCustomSaveUri(uri.toString())
        }
    }

    fun resetCustomSaveDirectory() {
        repository.setCustomSaveUri(null)
    }

    fun updateDeviceName(name: String) {
        repository.setDeviceName(name)
    }

    fun toggleAutoAccept(enabled: Boolean) {
        repository.setAutoAccept(enabled)
    }

    fun resetTransfer() {
        transferEngine.resetTransferState()
    }

    private fun saveTransferRecordToDb(
        fileName: String,
        isFolder: Boolean,
        fileSize: Long,
        transferType: String,
        peerName: String,
        status: String
    ) {
        viewModelScope.launch {
            val record = TransferRecord(
                fileName = fileName,
                isFolder = isFolder,
                fileSize = fileSize,
                transferType = transferType,
                peerDeviceName = peerName,
                peerIp = transferProgress.value.peerName,
                speedMBps = transferProgress.value.currentSpeedMBps,
                status = status
            )
            repository.addRecord(record)
        }
    }

    fun deleteHistoryRecord(id: Long) {
        viewModelScope.launch {
            repository.deleteRecord(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
