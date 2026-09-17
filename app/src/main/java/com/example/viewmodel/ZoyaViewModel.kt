package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.AssistantMode
import com.example.model.PermissionStatus
import com.example.model.ZoyaUiState
import com.example.service.BackgroundAudioService
import com.example.tools.ToolExecutionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ZoyaViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    private val _uiState = MutableStateFlow(ZoyaUiState())
    val uiState: StateFlow<ZoyaUiState> = _uiState.asStateFlow()

    private var backgroundService: BackgroundAudioService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? BackgroundAudioService.LocalBinder
            backgroundService = binder?.getService()
            isServiceBound = true
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            backgroundService = null
            isServiceBound = false
        }
    }

    init {
        checkPermissions()
        bindBackgroundService()

        // Observe background service running state
        viewModelScope.launch {
            BackgroundAudioService.isRunning.collect { running ->
                _uiState.update { it.copy(isBackgroundServiceRunning = running) }
            }
        }
    }

    fun checkPermissions() {
        val audioGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val contactsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val phoneGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val perms = PermissionStatus(
            audioRecordGranted = audioGranted,
            contactsGranted = contactsGranted,
            phoneCallGranted = phoneGranted,
            notificationsGranted = notificationsGranted
        )

        _uiState.update {
            it.copy(
                permissions = perms,
                showPermissionsSheet = !perms.coreGranted
            )
        }
    }

    fun onPermissionsResult() {
        checkPermissions()
        if (_uiState.value.permissions.coreGranted) {
            _uiState.update { it.copy(showPermissionsSheet = false) }
            startBackgroundService()
        }
    }

    fun dismissPermissionsSheet() {
        _uiState.update { it.copy(showPermissionsSheet = false) }
    }

    fun showPermissionsSheet() {
        _uiState.update { it.copy(showPermissionsSheet = true) }
    }

    private fun bindBackgroundService() {
        val intent = Intent(context, BackgroundAudioService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeServiceState() {
        val service = backgroundService ?: return
        val liveManager = service.liveSessionManager

        viewModelScope.launch {
            liveManager.mode.collect { mode ->
                _uiState.update { state ->
                    val status = when (mode) {
                        AssistantMode.IDLE -> if (state.isBackgroundServiceRunning) "Listening for \"Zoya\"" else "Tap orb or say \"Zoya\""
                        AssistantMode.LISTENING -> "Listening to you..."
                        AssistantMode.THINKING -> "Thinking..."
                        AssistantMode.SPEAKING -> "Zoya speaking..."
                    }
                    state.copy(mode = mode, statusText = status)
                }
            }
        }

        viewModelScope.launch {
            liveManager.micAmplitude.collect { amp ->
                _uiState.update { it.copy(micAmplitude = amp) }
            }
        }

        viewModelScope.launch {
            liveManager.speakerAmplitude.collect { amp ->
                _uiState.update { it.copy(speakerAmplitude = amp) }
            }
        }

        viewModelScope.launch {
            liveManager.lastSassyComment.collect { sassy ->
                _uiState.update { it.copy(sassyQuote = sassy) }
            }
        }

        viewModelScope.launch {
            liveManager.isLiveSessionActive.collect { active ->
                _uiState.update { it.copy(isLiveConnected = active) }
            }
        }
    }

    fun toggleOrbTap() {
        val service = backgroundService
        if (service == null) {
            // Start service if not bound
            startBackgroundService()
            return
        }

        val liveManager = service.liveSessionManager
        when (_uiState.value.mode) {
            AssistantMode.SPEAKING -> {
                // User wants to interrupt Zoya
                liveManager.handleInterruption()
            }
            AssistantMode.LISTENING -> {
                // Return to idle
                liveManager.stopLiveSession()
            }
            AssistantMode.IDLE, AssistantMode.THINKING -> {
                // Start live listening session
                liveManager.startLiveSession()
            }
        }
    }

    fun toggleBackgroundService() {
        if (_uiState.value.isBackgroundServiceRunning) {
            stopBackgroundService()
        } else {
            startBackgroundService()
        }
    }

    fun startBackgroundService() {
        val intent = Intent(context, BackgroundAudioService::class.java).apply {
            action = BackgroundAudioService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        bindBackgroundService()
    }

    fun stopBackgroundService() {
        val intent = Intent(context, BackgroundAudioService::class.java).apply {
            action = BackgroundAudioService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun executeQuickVoiceCommand(command: String) {
        val service = backgroundService
        if (service != null) {
            service.liveSessionManager.processVoiceCommand(command)
        } else {
            startBackgroundService()
            _uiState.update { it.copy(sassyQuote = "Starting up Zoya's systems for you, sweetheart...") }
        }
    }

    override fun onCleared() {
        if (isServiceBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isServiceBound = false
        }
        super.onCleared()
    }
}
