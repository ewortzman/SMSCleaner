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
        appendLog("Starting dry run...")

        _isRunning.value = true

        currentJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val contactResolver = ContactResolver(resolver)
                val cleaner = MessageCleaner(resolver, contactResolver, actualConfig) { line ->
                    appendLogFromBackground(line)
                }
                val count = cleaner.execute()
                cachedScanResults = cleaner.getScanResults()
                appendLogFromBackground("Dry run complete. $count messages would be deleted.")

                withContext(Dispatchers.Main) {
                    _isDryRunComplete.value = true
                    _isRunButtonEnabled.value = true
                    _isRunning.value = false
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
        appendLog("Starting message deletion...")

        _isRunning.value = true
        _isRunButtonEnabled.value = false

        val scanToUse = cachedScanResults

        currentJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val contactResolver = ContactResolver(resolver)
                val cleaner = MessageCleaner(resolver, contactResolver, actualConfig) { line ->
                    appendLogFromBackground(line)
                }
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
