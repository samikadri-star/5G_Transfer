package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfer_records")
data class TransferRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val isFolder: Boolean,
    val fileSize: Long,
    val transferType: String, // "SENT" or "RECEIVED"
    val peerDeviceName: String,
    val peerIp: String,
    val speedMBps: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String, // "SUCCESS", "FAILED", "CANCELLED"
    val localUriOrPath: String? = null
)
