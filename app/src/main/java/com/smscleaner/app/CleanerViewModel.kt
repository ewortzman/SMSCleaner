package com.smscleaner.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CleanerViewModel(application: Application) : AndroidViewModel(application) {

    private val _logText = MutableLiveData("")
    val logText: LiveData<String> = _logText

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    private val _isDryRunComplete = MutableLiveData(false)
    val isDryRunComplete: LiveData<Boolean> = _isDryRunComplete

    private val _isRunButtonEnabled = MutableLiveData(false)
    val isRunButtonEnabled: LiveData<Boolean> = _isRunButtonEnabled

    private val _lastDryRunCount = MutableLiveData(0)
    val lastDryRunCount: LiveData<Int> = _lastDryRunCount

    private val _lastContactCount = MutableLiveData(0)
    val lastContactCount: LiveData<Int> = _lastContactCount

    data class Progress(val done: Int, val total: Int)
    private val _progress = MutableLiveData(Progress(0, 0))
    val progress: LiveData<Progress> = _progress

    private var currentJob: Job? = null
    private val logBuilder = StringBuilder()
    private var cachedScanResults: ScanResults? = null

    fun onSettingsChanged() {
        _isDryRunComplete.value = false
        _isRunButtonEnabled.value = false
        cachedScanResults = null
    }

    fun startDryRun(config: CleanerConfig) {
        if (_isRunning.value == true) return

        val actualConfig = config.copy(dryRun = true)
        logBuilder.clear()
        _logText.value = ""
        _progress.value = Progress(0, 0)
        appendLog("Starting dry run...")

        _isRunning.value = true

        currentJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val contactResolver = ContactResolver(resolver)
                val cleaner = MessageCleaner(
                    resolver, contactResolver, actualConfig,
                    onLog = { line -> appendLogFromBackground(line) },
                    onProgress = { done, total -> _progress.postValue(Progress(done, total)) }
                )
                val count = cleaner.execute()
                cachedScanResults = cleaner.getScanResults()
                val contactCount = countContactMessages(cleaner.getScanResults(), contactResolver)
                appendLogFromBackground("Dry run complete. $count messages would be deleted.")
                if (contactCount > 0) {
                    appendLogFromBackground("Note: $contactCount matched messages involve saved contacts.")
                }

                withContext(Dispatchers.Main) {
                    _isDryRunComplete.value = true
                    _isRunButtonEnabled.value = true
                    _isRunning.value = false
                    _lastDryRunCount.value = count
                    _lastContactCount.value = contactCount
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                _isRunning.postValue(false)
            } catch (e: Exception) {
                appendLogFromBackground("Error: ${e.message}")
                _isRunning.postValue(false)
            }
        }
    }

    fun startClean(config: CleanerConfig) {
        if (_isRunning.value == true) return

        val actualConfig = config.copy(dryRun = false)
        logBuilder.clear()
        _logText.value = ""
        _progress.value = Progress(0, 0)
        appendLog("Starting message deletion...")

        _isRunning.value = true
        _isRunButtonEnabled.value = false

        val scanToUse = cachedScanResults

        currentJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val contactResolver = ContactResolver(resolver)
                val cleaner = MessageCleaner(
                    resolver, contactResolver, actualConfig,
                    onLog = { line -> appendLogFromBackground(line) },
                    onProgress = { done, total -> _progress.postValue(Progress(done, total)) }
                )
                val count = cleaner.execute(previousScan = scanToUse)
                appendLogFromBackground("Deletion complete. $count messages deleted.")

                withContext(Dispatchers.Main) {
                    _isDryRunComplete.value = false
                    _isRunButtonEnabled.value = false
                    _isRunning.value = false
                    cachedScanResults = null
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                _isRunning.postValue(false)
            } catch (e: Exception) {
                appendLogFromBackground("Error: ${e.message}")
                _isRunning.postValue(false)
            }
        }
    }

    fun stop() {
        currentJob?.cancel()
    }

    private fun countContactMessages(scan: ScanResults?, contactResolver: ContactResolver): Int {
        if (scan == null) return 0
        var count = 0
        // SMS
        for ((threadId, ids) in scan.smsThreadMap) {
            val address = scan.smsAddressMap[threadId] ?: continue
            val name = contactResolver.resolve(address)
            if (name != address) count += ids.size
        }
        // MMS (media + group share mmsAddressMap keyed by thread id)
        for ((threadId, ids) in scan.mediaConversations) {
            val address = scan.mmsAddressMap[threadId] ?: continue
            val name = contactResolver.resolve(address)
            if (name != address) count += ids.size
        }
        for ((threadId, ids) in scan.groupConversations) {
            val address = scan.mmsAddressMap[threadId] ?: continue
            val name = contactResolver.resolve(address)
            if (name != address) count += ids.size
        }
        return count
    }

    private fun appendLog(line: String) {
        logBuilder.appendLine(line)
        _logText.postValue(logBuilder.toString())
    }

    private fun appendLogFromBackground(line: String) {
        synchronized(logBuilder) {
            logBuilder.appendLine(line)
            _logText.postValue(logBuilder.toString())
        }
    }
}
