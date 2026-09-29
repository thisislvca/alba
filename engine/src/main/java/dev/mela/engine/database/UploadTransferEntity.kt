package dev.mela.engine.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "upload_transfers",
    indices = [
        Index(value = ["mediaId"]),
        Index(value = ["accountLabel"]),
        Index(value = ["state"]),
    ],
)
data class UploadTransferEntity(
    @PrimaryKey val transferId: String,
    val mediaId: String,
    val fileName: String,
    val accountLabel: String,
    val sourceRevision: String,
    val localUri: String,
    val stagedPath: String,
    val byteCount: Long,
    val sha256Hex: String,
    val state: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val message: String?,
)
