package com.example.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.example.data.model.DiscoveredDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WifiP2pEngine(private val context: Context) : WifiP2pManager.PeerListListener, WifiP2pManager.ConnectionInfoListener {

    companion object {
        private const val TAG = "WifiP2pEngine"
    }

    private val p2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val _isP2pEnabled = MutableStateFlow(false)
    val isP2pEnabled: StateFlow<Boolean> = _isP2pEnabled.asStateFlow()

    private val _p2pPeers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val p2pPeers: StateFlow<List<WifiP2pDevice>> = _p2pPeers.asStateFlow()

    private val _p2pDiscoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val p2pDiscoveredDevices: StateFlow<List<DiscoveredDevice>> = _p2pDiscoveredDevices.asStateFlow()

    private val _p2pConnectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val p2pConnectionInfo: StateFlow<WifiP2pInfo?> = _p2pConnectionInfo.asStateFlow()

    private val _p2pStatusText = MutableStateFlow("واي فاي مباشر P2P جاهز")
    val p2pStatusText: StateFlow<String> = _p2pStatusText.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var isReceiverRegistered = false
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _isP2pEnabled.value = enabled
                    _p2pStatusText.value = if (enabled) "واي فاي مباشر (P2P) مفعّل" else "واي فاي مباشر (P2P) غير مفعل"
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (p2pManager != null && channel != null) {
                        try {
                            p2pManager.requestPeers(channel, this@WifiP2pEngine)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException requesting peers", e)
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (p2pManager == null || channel == null) return
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    if (networkInfo?.isConnected == true) {
                        p2pManager.requestConnectionInfo(channel, this@WifiP2pEngine)
                        _isConnected.value = true
                    } else {
                        _p2pConnectionInfo.value = null
                        _isConnected.value = false
                        _p2pStatusText.value = "غير متصل بـ Wi-Fi Direct"
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    // Local device info changed
                }
            }
        }
    }

    fun initP2p() {
        if (p2pManager == null) {
            _p2pStatusText.value = "جهازك لا يدعم Wi-Fi Direct"
            return
        }

        if (channel == null) {
            channel = p2pManager.initialize(context, Looper.getMainLooper(), null)
        }

        registerReceiver()
    }

    private fun registerReceiver() {
        if (!isReceiverRegistered) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(p2pReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(p2pReceiver, intentFilter)
                }
                isReceiverRegistered = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register receiver", e)
            }
        }
    }

    fun unregisterReceiver() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(p2pReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister receiver", e)
            } finally {
                isReceiverRegistered = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(onSuccess: (() -> Unit)? = null, onFailure: ((String) -> Unit)? = null) {
        initP2p()
        if (p2pManager == null || channel == null) {
            onFailure?.invoke("خدمة Wi-Fi Direct غير متاحة")
            return
        }

        try {
            p2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _p2pStatusText.value = "جاري البحث عن أجهزة Wi-Fi Direct قريبة..."
                    onSuccess?.invoke()
                }

                override fun onFailure(reasonCode: Int) {
                    val msg = when (reasonCode) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct غير مدعوم"
                        WifiP2pManager.BUSY -> "النظام مشغول، يرجى المحاولة لاحقاً"
                        WifiP2pManager.ERROR -> "حدث خطأ أثناء البحث"
                        else -> "فشل بدء البحث ($reasonCode)"
                    }
                    _p2pStatusText.value = msg
                    onFailure?.invoke(msg)
                }
            })
        } catch (e: SecurityException) {
            _p2pStatusText.value = "تطلب إذن الموقع أو الأجهزة القريبة"
            onFailure?.invoke("يتطلب الإذن")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(
        device: WifiP2pDevice,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        if (p2pManager == null || channel == null) return

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        _p2pStatusText.value = "جاري الاتصال المباشر بـ ${device.deviceName}..."

        try {
            p2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _p2pStatusText.value = "تم إرسال طلب الاتصال بـ ${device.deviceName}"
                    onSuccess?.invoke()
                }

                override fun onFailure(reasonCode: Int) {
                    val msg = "فشل الاتصال بـ ${device.deviceName} ($reasonCode)"
                    _p2pStatusText.value = msg
                    onFailure?.invoke(msg)
                }
            })
        } catch (e: SecurityException) {
            _p2pStatusText.value = "خطأ في الصلاحيات للاتصال بـ Wi-Fi Direct"
            onFailure?.invoke("خطأ صلاحيات")
        }
    }

    fun disconnectP2p(onComplete: (() -> Unit)? = null) {
        if (p2pManager != null && channel != null) {
            p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _isConnected.value = false
                    _p2pConnectionInfo.value = null
                    _p2pStatusText.value = "تم قطع الاتصال المباشر"
                    onComplete?.invoke()
                }

                override fun onFailure(reasonCode: Int) {
                    _isConnected.value = false
                    _p2pConnectionInfo.value = null
                    _p2pStatusText.value = "تم إنهاء الجلسة"
                    onComplete?.invoke()
                }
            })
        } else {
            onComplete?.invoke()
        }
    }

    override fun onPeersAvailable(peers: WifiP2pDeviceList?) {
        val deviceList = peers?.deviceList?.toList() ?: emptyList()
        _p2pPeers.value = deviceList

        // Map P2P devices to DiscoveredDevice format so they can be shown in UI seamlessly
        val mappedList = deviceList.map { device ->
            DiscoveredDevice(
                id = "P2P_${device.deviceAddress}",
                name = device.deviceName.ifBlank { "جهاز Wi-Fi Direct (${device.deviceAddress.takeLast(5)})" },
                ipAddress = "192.168.49.1", // Default P2P Group Owner IP or connection target
                port = WifiTransferEngine.DEFAULT_PORT
            )
        }
        _p2pDiscoveredDevices.value = mappedList

        if (deviceList.isNotEmpty()) {
            _p2pStatusText.value = "تم العثور على ${deviceList.size} جهاز Wi-Fi Direct"
        }
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo?) {
        _p2pConnectionInfo.value = info
        if (info != null && info.groupFormed) {
            val hostAddress = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
            _isConnected.value = true
            _p2pStatusText.value = if (info.isGroupOwner) {
                "متصل كـ Group Owner على IP: $hostAddress"
            } else {
                "متصل بـ Wi-Fi Direct (IP: $hostAddress)"
            }
        }
    }
}
