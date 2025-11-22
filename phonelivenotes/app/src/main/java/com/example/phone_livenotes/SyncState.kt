package com.example.phone_livenotes

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

object SyncState {
    val notes = mutableStateListOf<NotePayload>()
    val projects = mutableStateListOf<ProjectPayload>()
    val connectionStatus = mutableStateOf("Disconnected")
    val debugLog = mutableStateListOf<String>()
}
