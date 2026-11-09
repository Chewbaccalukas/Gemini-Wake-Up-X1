package com.example.data.model

/**
 * Synchronization state for local Room entities participating in the
 * hybrid Room + Cloud Firestore sync pipeline (`Chat`, `Message`, `WakeWord`).
 */
enum class SyncState {
    PENDING,
    SYNCED,
    DIRTY,
    FAILED;

    companion object {
        fun fromStatus(status: String?): SyncState = when (status?.trim()?.uppercase()) {
            SYNCED.name -> SYNCED
            DIRTY.name -> DIRTY
            FAILED.name -> FAILED
            else -> PENDING
        }
    }
}

object SyncStatus {
    const val PENDING = "PENDING"
    const val SYNCED = "SYNCED"
    const val DIRTY = "DIRTY"
    const val FAILED = "FAILED"
}
