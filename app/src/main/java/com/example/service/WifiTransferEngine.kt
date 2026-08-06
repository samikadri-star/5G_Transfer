package com.example.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.example.data.model.DiscoveredDevice
import com.example.data.model.NetworkStatus
import com.example.data.model.SelectedItem
import com.example.data.model.TransferProgress
import com.example.data.model.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class WifiTransferEngine(private val context: Context) {

    companion object {
        const val DEFAULT_PORT = 8888
        const val SERVICE_TYPE = "_flashshare._tcp."
        const val BUFFER_SIZE = 256 * 1024 // 256 KB buffer for max Wi-Fi throughput

        // Protocol Magic Constants
        const val MAGIC_HEADER = 0x4653 // "FS"
        const val TYPE_START_BATCH = 1
        const val TYPE_FILE_HEADER = 2
        const val TYPE_FILE_END = 3
        const val TYPE_BATCH_END = 4
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _networkStatus = MutableStateFlow(NetworkStatus())
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _transferProgress = MutableStateFlow(TransferProgress())
    val transferProgress: StateFlow<TransferProgress> = _transferProgress.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var isReceiverRunning = false
    private var activeSocket: Socket? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    init {
        updateNetworkInfo()
    }

    fun updateNetworkInfo() {
        try {
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: 0
            val ipString = if (ipInt != 0) {
                String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            } else {
                getNetworkIpAddress() ?: "127.0.0.1"
            }

            val freq = wifiInfo?.frequency ?: 0
            val is5G = freq >= 4900

            var ssid = wifiInfo?.ssid?.replace("\"", "") ?: "شبكة محلية"
            if (ssid == "<unknown ssid>" || ssid == "0x") {
                ssid = "نقطة اتصال / واي فاي محلي"
            }

            _networkStatus.value = NetworkStatus(
                isWifiConnected = ipString != "127.0.0.1",
                ssid = ssid,
                ipAddress = ipString,
                is5GHz = is5G,
                frequencyMHz = freq
            )
        } catch (e: Exception) {
            _networkStatus.value = NetworkStatus(
                isWifiConnected = false,
                ssid = "واي فاي مباشر",
                ipAddress = getNetworkIpAddress() ?: "127.0.0.1",
                is5GHz = true
            )
        }
    }

    private fun getNetworkIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    // --- NSD Discovery & Registration ---

    fun startDiscovery() {
        stopDiscovery()
        val currentList = mutableListOf<DiscoveredDevice>()
        _discoveredDevices.value = emptyList()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                _transferProgress.value = _transferProgress.value.copy(state = TransferState.SCANNING)
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_flashshare") || service.serviceName.contains("FlashShare")) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val hostIp = serviceInfo.host?.hostAddress ?: return
                            val device = DiscoveredDevice(
                                id = serviceInfo.serviceName,
                                name = serviceInfo.serviceName.replace("FlashShare - ", ""),
                                ipAddress = hostIp,
                                port = serviceInfo.port
                            )
                            if (!currentList.any { it.ipAddress == hostIp }) {
                                currentList.add(device)
                                _discoveredDevices.value = currentList.toList()
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                currentList.removeAll { it.id == service.serviceName }
                _discoveredDevices.value = currentList.toList()
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        discoveryListener = null
    }

    fun registerService(deviceName: String, port: Int = DEFAULT_PORT) {
        unregisterService()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "FlashShare - $deviceName"
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(arg0: NsdServiceInfo, arg1: Int) {}
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(arg0: NsdServiceInfo, arg1: Int) {}
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        registrationListener = null
    }

    // --- Server (Receiver) Engine ---

    fun startReceiverServer(
        deviceName: String,
        customSaveUri: String?,
        onTransferComplete: (fileName: String, totalBytes: Long, peerName: String) -> Unit
    ) {
        if (isReceiverRunning) return
        isReceiverRunning = true
        registerService(deviceName, DEFAULT_PORT)

        scope.launch {
            try {
                serverSocket = ServerSocket(DEFAULT_PORT)
                _transferProgress.value = TransferProgress(state = TransferState.SCANNING)

                while (isReceiverRunning && serverSocket != null && !serverSocket!!.isClosed) {
                    val socket = serverSocket!!.accept()
                    activeSocket = socket
                    handleIncomingTransfer(socket, customSaveUri, onTransferComplete)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isReceiverRunning = false
            }
        }
    }

    private suspend fun handleIncomingTransfer(
        socket: Socket,
        customSaveUri: String?,
        onTransferComplete: (fileName: String, totalBytes: Long, peerName: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val dataIn = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
        val dataOut = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        try {
            val magic = dataIn.readInt()
            if (magic != MAGIC_HEADER) {
                socket.close()
                return@withContext
            }

            val senderName = dataIn.readUTF()
            dataOut.writeUTF("ACK")
            dataOut.flush()

            val totalFiles = dataIn.readInt()
            val totalBatchBytes = dataIn.readLong()

            _transferProgress.value = TransferProgress(
                state = TransferState.TRANSFERRING,
                totalFilesCount = totalFiles,
                totalBytes = totalBatchBytes,
                peerName = senderName
            )

            var receivedBytesTotal = 0L
            var filesReceived = 0
            val startTime = System.currentTimeMillis()
            var lastSpeedCalcTime = startTime
            var lastSpeedBytes = 0L
            var currentSpeedMBps = 0.0
            var mainItemName = ""

            while (filesReceived < totalFiles) {
                val headerType = dataIn.readByte().toInt()
                if (headerType == TYPE_BATCH_END) break
                if (headerType != TYPE_FILE_HEADER) continue

                val relativePath = dataIn.readUTF()
                val fileSize = dataIn.readLong()
                val isDir = dataIn.readBoolean()

                if (mainItemName.isEmpty()) {
                    mainItemName = relativePath.substringBefore("/")
                }

                _transferProgress.value = _transferProgress.value.copy(
                    currentFileName = relativePath,
                    isFolder = relativePath.contains("/"),
                    currentFileIndex = filesReceived + 1
                )

                if (isDir) {
                    createTargetDirectory(relativePath, customSaveUri)
                    dataOut.writeLong(0L)
                    dataOut.flush()
                } else {
                    val existingSize = getTargetFileExistingSize(relativePath, customSaveUri)
                    val resumeOffset = if (existingSize in 1 until fileSize) existingSize else if (existingSize >= fileSize) fileSize else 0L

                    dataOut.writeLong(resumeOffset)
                    dataOut.flush()

                    if (resumeOffset >= fileSize) {
                        receivedBytesTotal += fileSize
                        _transferProgress.value = _transferProgress.value.copy(
                            bytesTransferred = receivedBytesTotal
                        )
                    } else {
                        receivedBytesTotal += resumeOffset
                        val outputStream = createTargetFileStream(relativePath, customSaveUri, append = resumeOffset > 0)
                        val buffer = ByteArray(BUFFER_SIZE)
                        var remaining = fileSize - resumeOffset

                        while (remaining > 0) {
                            val read = dataIn.read(buffer, 0, Math.min(buffer.size.toLong(), remaining).toInt())
                            if (read == -1) break
                            outputStream.write(buffer, 0, read)
                            remaining -= read
                            receivedBytesTotal += read

                            val now = System.currentTimeMillis()
                            val diff = now - lastSpeedCalcTime
                            if (diff >= 400) {
                                val bytesDiff = receivedBytesTotal - lastSpeedBytes
                                currentSpeedMBps = (bytesDiff.toDouble() / (1024 * 1024)) / (diff.toDouble() / 1000)
                                lastSpeedCalcTime = now
                                lastSpeedBytes = receivedBytesTotal

                                val remainingBytes = totalBatchBytes - receivedBytesTotal
                                val etaSec = if (currentSpeedMBps > 0) (remainingBytes / (currentSpeedMBps * 1024 * 1024)).toLong() else 0

                                _transferProgress.value = _transferProgress.value.copy(
                                    bytesTransferred = receivedBytesTotal,
                                    currentSpeedMBps = currentSpeedMBps,
                                    estimatedTimeRemainingSec = etaSec
                                )
                            }
                        }
                        outputStream.flush()
                        outputStream.close()
                    }
                }

                filesReceived++
            }

            _transferProgress.value = _transferProgress.value.copy(
                state = TransferState.COMPLETED,
                bytesTransferred = totalBatchBytes,
                currentSpeedMBps = 0.0
            )

            onTransferComplete(if (mainItemName.isNotEmpty()) mainItemName else "ملفات مستلمة", totalBatchBytes, senderName)

        } catch (e: Exception) {
            _transferProgress.value = _transferProgress.value.copy(
                state = TransferState.FAILED,
                errorMessage = e.localizedMessage ?: "حدث خطأ أثناء الاتصال بالنقل"
            )
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    fun stopReceiverServer() {
        isReceiverRunning = false
        unregisterService()
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Directory & Storage Creation Logic ---

    private fun getDownloadsDir(): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FlashShare")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun createTargetDirectory(relativePath: String, customSaveUri: String?) {
        if (customSaveUri != null) {
            try {
                val treeUri = Uri.parse(customSaveUri)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                if (rootDoc != null) {
                    val parts = relativePath.split("/")
                    var currentDoc: DocumentFile = rootDoc
                    for (part in parts) {
                        if (part.isNotEmpty()) {
                            val existing = currentDoc.findFile(part)
                            val created = existing ?: currentDoc.createDirectory(part)
                            if (created != null) {
                                currentDoc = created
                            }
                        }
                    }
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback to local Downloads/FlashShare folder
        val targetDir = File(getDownloadsDir(), relativePath)
        if (!targetDir.exists()) targetDir.mkdirs()
    }

    private fun getTargetFileExistingSize(relativePath: String, customSaveUri: String?): Long {
        if (customSaveUri != null) {
            try {
                val treeUri = Uri.parse(customSaveUri)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                if (rootDoc != null) {
                    val parts = relativePath.split("/")
                    var currentDoc: DocumentFile = rootDoc
                    for (i in 0 until parts.size - 1) {
                        val part = parts[i]
                        if (part.isNotEmpty()) {
                            val existing = currentDoc.findFile(part) ?: break
                            currentDoc = existing
                        }
                    }
                    val fileName = parts.last()
                    val existingFile = currentDoc.findFile(fileName)
                    if (existingFile != null && existingFile.exists()) {
                        return existingFile.length()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val targetFile = File(getDownloadsDir(), relativePath)
        return if (targetFile.exists()) targetFile.length() else 0L
    }

    private fun createTargetFileStream(relativePath: String, customSaveUri: String?, append: Boolean = false): OutputStream {
        if (customSaveUri != null) {
            try {
                val treeUri = Uri.parse(customSaveUri)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                if (rootDoc != null) {
                    val parts = relativePath.split("/")
                    var currentDoc: DocumentFile = rootDoc
                    for (i in 0 until parts.size - 1) {
                        val part = parts[i]
                        if (part.isNotEmpty()) {
                            val existing = currentDoc.findFile(part)
                            val created = existing ?: currentDoc.createDirectory(part)
                            if (created != null) {
                                currentDoc = created
                            }
                        }
                    }
                    val fileName = parts.last()
                    val existingFile = currentDoc.findFile(fileName)
                    val targetDoc = if (existingFile != null) {
                        if (!append) {
                            existingFile.delete()
                            currentDoc.createFile("*/*", fileName)
                        } else {
                            existingFile
                        }
                    } else {
                        currentDoc.createFile("*/*", fileName)
                    }

                    if (targetDoc != null) {
                        val mode = if (append) "wa" else "w"
                        val pfd = context.contentResolver.openOutputStream(targetDoc.uri, mode)
                        if (pfd != null) return BufferedOutputStream(pfd, BUFFER_SIZE)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback file output stream
        val targetFile = File(getDownloadsDir(), relativePath)
        targetFile.parentFile?.mkdirs()
        if (!append && targetFile.exists()) {
            targetFile.delete()
        }
        return BufferedOutputStream(FileOutputStream(targetFile, append), BUFFER_SIZE)
    }

    // --- Sender Engine ---

    fun sendItemsToDevice(
        targetIp: String,
        targetPort: Int,
        senderDeviceName: String,
        selectedItems: List<SelectedItem>,
        onTransferComplete: (fileName: String, totalBytes: Long, peerName: String) -> Unit
    ) {
        scope.launch {
            _transferProgress.value = TransferProgress(
                state = TransferState.CONNECTING,
                peerName = targetIp
            )

            val flattenedFiles = flattenItems(selectedItems)
            val totalBytes = flattenedFiles.sumOf { it.size }
            val totalCount = flattenedFiles.size

            try {
                val socket = Socket(targetIp, targetPort)
                activeSocket = socket
                socket.tcpNoDelay = true
                socket.sendBufferSize = BUFFER_SIZE

                val dataOut = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))
                val dataIn = DataInputStream(BufferedInputStream(socket.getInputStream()))

                dataOut.writeInt(MAGIC_HEADER)
                dataOut.writeUTF(senderDeviceName)
                dataOut.flush()

                val ack = dataIn.readUTF()
                if (ack != "ACK") {
                    throw Exception("لم يتم قبول الاتصال من الجهاز المستلم")
                }

                dataOut.writeInt(totalCount)
                dataOut.writeLong(totalBytes)
                dataOut.flush()

                _transferProgress.value = TransferProgress(
                    state = TransferState.TRANSFERRING,
                    totalFilesCount = totalCount,
                    totalBytes = totalBytes,
                    peerName = targetIp
                )

                var bytesSentTotal = 0L
                val startTime = System.currentTimeMillis()
                var lastSpeedCalcTime = startTime
                var lastSpeedBytes = 0L
                var currentSpeedMBps = 0.0

                for ((index, item) in flattenedFiles.withIndex()) {
                    _transferProgress.value = _transferProgress.value.copy(
                        currentFileName = item.relativePath,
                        isFolder = item.relativePath.contains("/"),
                        currentFileIndex = index + 1
                    )

                    dataOut.writeByte(TYPE_FILE_HEADER)
                    dataOut.writeUTF(item.relativePath)
                    dataOut.writeLong(item.size)
                    dataOut.writeBoolean(item.isDirectory)
                    dataOut.flush()

                    val resumeOffset = dataIn.readLong()

                    if (!item.isDirectory) {
                        if (resumeOffset >= item.size) {
                            bytesSentTotal += item.size
                            _transferProgress.value = _transferProgress.value.copy(
                                bytesTransferred = bytesSentTotal
                            )
                        } else {
                            bytesSentTotal += resumeOffset
                            val inputStream = context.contentResolver.openInputStream(item.uri)
                                ?: throw Exception("تعذر فتح الملف: ${item.name}")
                            val bufferedIn = BufferedInputStream(inputStream, BUFFER_SIZE)

                            if (resumeOffset > 0) {
                                var skipped = 0L
                                while (skipped < resumeOffset) {
                                    val s = bufferedIn.skip(resumeOffset - skipped)
                                    if (s <= 0) break
                                    skipped += s
                                }
                            }

                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int

                            while (bufferedIn.read(buffer).also { read = it } != -1) {
                                dataOut.write(buffer, 0, read)
                                bytesSentTotal += read

                                val now = System.currentTimeMillis()
                                val diff = now - lastSpeedCalcTime
                                if (diff >= 400) {
                                    val bytesDiff = bytesSentTotal - lastSpeedBytes
                                    currentSpeedMBps = (bytesDiff.toDouble() / (1024 * 1024)) / (diff.toDouble() / 1000)
                                    lastSpeedCalcTime = now
                                    lastSpeedBytes = bytesSentTotal

                                    val remainingBytes = totalBytes - bytesSentTotal
                                    val etaSec = if (currentSpeedMBps > 0) (remainingBytes / (currentSpeedMBps * 1024 * 1024)).toLong() else 0

                                    _transferProgress.value = _transferProgress.value.copy(
                                        bytesTransferred = bytesSentTotal,
                                        currentSpeedMBps = currentSpeedMBps,
                                        estimatedTimeRemainingSec = etaSec
                                    )
                                }
                            }
                            bufferedIn.close()
                            dataOut.flush()
                        }
                    }
                }

                dataOut.writeByte(TYPE_BATCH_END)
                dataOut.flush()

                _transferProgress.value = _transferProgress.value.copy(
                    state = TransferState.COMPLETED,
                    bytesTransferred = totalBytes,
                    currentSpeedMBps = 0.0
                )

                val mainName = selectedItems.firstOrNull()?.name ?: "ملفات"
                onTransferComplete(mainName, totalBytes, targetIp)

                socket.close()

            } catch (e: Exception) {
                _transferProgress.value = _transferProgress.value.copy(
                    state = TransferState.FAILED,
                    errorMessage = e.localizedMessage ?: "تعذر إرسال الملفات للجهاز المستلم"
                )
            }
        }
    }

    private fun flattenItems(items: List<SelectedItem>): List<SelectedItem> {
        val result = mutableListOf<SelectedItem>()
        for (item in items) {
            if (item.isDirectory) {
                val doc = DocumentFile.fromTreeUri(context, item.uri)
                if (doc != null) {
                    traverseDocumentFolder(doc, item.name, result)
                }
            } else {
                result.add(item)
            }
        }
        return result
    }

    private fun traverseDocumentFolder(docFolder: DocumentFile, currentRelativePath: String, result: MutableList<SelectedItem>) {
        val files = docFolder.listFiles()
        if (files.isEmpty()) {
            result.add(
                SelectedItem(
                    uri = docFolder.uri,
                    name = docFolder.name ?: "مجلد",
                    size = 0,
                    isDirectory = true,
                    relativePath = currentRelativePath
                )
            )
            return
        }

        for (file in files) {
            val subPath = "$currentRelativePath/${file.name ?: "file"}"
            if (file.isDirectory) {
                traverseDocumentFolder(file, subPath, result)
            } else {
                result.add(
                    SelectedItem(
                        uri = file.uri,
                        name = file.name ?: "file",
                        size = file.length(),
                        isDirectory = false,
                        relativePath = subPath
                    )
                )
            }
        }
    }

    fun resetTransferState() {
        _transferProgress.value = TransferProgress(state = TransferState.IDLE)
    }
}
