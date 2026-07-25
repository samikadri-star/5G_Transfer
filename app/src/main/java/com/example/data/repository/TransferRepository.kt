package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.example.data.db.AppDatabase
import com.example.data.db.TransferRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TransferRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val dao = db.transferDao()
    private val prefs: SharedPreferences = context.getSharedPreferences("flash_share_prefs", Context.MODE_PRIVATE)

    private val _customSaveUri = MutableStateFlow<String?>(prefs.getString("custom_save_uri", null))
    val customSaveUri: StateFlow<String?> = _customSaveUri.asStateFlow()

    private val _deviceName = MutableStateFlow(
        prefs.getString("device_name", null) ?: "هاتف_${android.os.Build.MODEL.replace(" ", "_")}"
    )
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _autoAccept = MutableStateFlow(prefs.getBoolean("auto_accept", true))
    val autoAccept: StateFlow<Boolean> = _autoAccept.asStateFlow()

    val allHistoryRecords: Flow<List<TransferRecord>> = dao.getAllRecords()
    val totalSentBytes: Flow<Long?> = dao.getTotalSentBytes()
    val totalReceivedBytes: Flow<Long?> = dao.getTotalReceivedBytes()

    fun setCustomSaveUri(uriString: String?) {
        prefs.edit().putString("custom_save_uri", uriString).apply()
        _customSaveUri.value = uriString
    }

    fun setDeviceName(name: String) {
        prefs.edit().putString("device_name", name).apply()
        _deviceName.value = name
    }

    fun setAutoAccept(enabled: Boolean) {
        prefs.edit().putBoolean("auto_accept", enabled).apply()
        _autoAccept.value = enabled
    }

    suspend fun addRecord(record: TransferRecord): Long {
        return dao.insertRecord(record)
    }

    suspend fun deleteRecord(id: Long) {
        dao.deleteRecord(id)
    }

    suspend fun clearHistory() {
        dao.clearAll()
    }
}
