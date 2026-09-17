package com.example.model

enum class AssistantMode {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

data class PermissionStatus(
    val audioRecordGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val phoneCallGranted: Boolean = false,
    val notificationsGranted: Boolean = false
) {
    val allGranted: Boolean
        get() = audioRecordGranted && contactsGranted && phoneCallGranted && notificationsGranted

    val coreGranted: Boolean
        get() = audioRecordGranted
}

data class ZoyaUiState(
    val mode: AssistantMode = AssistantMode.IDLE,
    val statusText: String = "Say \"Zoya\" or tap orb to speak",
    val sassyQuote: String = "I'm all ears, darling. What's on your mind?",
    val isLiveConnected: Boolean = false,
    val isBackgroundServiceRunning: Boolean = false,
    val micAmplitude: Float = 0f,
    val speakerAmplitude: Float = 0f,
    val permissions: PermissionStatus = PermissionStatus(),
    val showPermissionsSheet: Boolean = false,
    val lastActionMessage: String? = null
)
