package com.smscleaner.app

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TestActivity : AppCompatActivity() {

    private lateinit var etSmsCount: TextInputEditText
    private lateinit var etMmsMediaCount: TextInputEditText
    private lateinit var etMmsGroupCount: TextInputEditText
    private lateinit var btnTestStartDate: MaterialButton
    private lateinit var btnTestEndDate: MaterialButton
    private lateinit var etConversationCount: TextInputEditText
    private lateinit var etGroupConversationCount: TextInputEditText
    private lateinit var btnGenerate: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var testProgressBar: LinearProgressIndicator
    private lateinit var tvTestLog: MaterialTextView
    private lateinit var scrollTestLog: ScrollView
    private lateinit var btnBack: MaterialButton

    private var startDateMs: Long? = null
    private var endDateMs: Long? = null
    private var generateJob: Job? = null
    private val logBuilder = StringBuilder()

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        bindViews()

        val cal = Calendar.getInstance()
        endDateMs = cal.timeInMillis
        btnTestEndDate.text = "End: ${dateFormat.format(Date(endDateMs!!))}"

        cal.add(Calendar.YEAR, -1)
        startDateMs = cal.timeInMillis
        btnTestStartDate.text = "Start: ${dateFormat.format(Date(startDateMs!!))}"

        setupListeners()

        @Suppress("ClickableViewAccessibility")
        scrollTestLog.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
    }

    private fun bindViews() {
        etSmsCount = findViewById(R.id.etSmsCount)
        etMmsMediaCount = findViewById(R.id.etMmsMediaCount)
        etMmsGroupCount = findViewById(R.id.etMmsGroupCount)
        btnTestStartDate = findViewById(R.id.btnTestStartDate)
        btnTestEndDate = findViewById(R.id.btnTestEndDate)
        etConversationCount = findViewById(R.id.etConversationCount)
        etGroupConversationCount = findViewById(R.id.etGroupConversationCount)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnCancel = findViewById(R.id.btnCancel)
        testProgressBar = findViewById(R.id.testProgressBar)
        tvTestLog = findViewById(R.id.tvTestLog)
        scrollTestLog = findViewById(R.id.scrollTestLog)
        btnBack = findViewById(R.id.btnBack)
    }

    private fun setupListeners() {
        btnTestStartDate.setOnClickListener { showDatePicker(isStart = true) }
        btnTestEndDate.setOnClickListener { showDatePicker(isStart = false) }
        btnBack.setOnClickListener { finish() }
        btnCancel.setOnClickListener {
            generateJob?.cancel()
            appendLog("Cancelled.")
        }

        btnGenerate.setOnClickListener {
            val smsCount = etSmsCount.text.toString().toIntOrNull() ?: 0
            val mmsMediaCount = etMmsMediaCount.text.toString().toIntOrNull() ?: 0
            val mmsGroupCount = etMmsGroupCount.text.toString().toIntOrNull() ?: 0
            val conversationCount = etConversationCount.text.toString().toIntOrNull() ?: 5
            val groupConversationCount = etGroupConversationCount.text.toString().toIntOrNull() ?: 3

            if (smsCount == 0 && mmsMediaCount == 0 && mmsGroupCount == 0) {
                Toast.makeText(this, "Enter at least one message count", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (startDateMs == null || endDateMs == null) {
                Toast.makeText(this, "Both dates required for generation", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            startGeneration(smsCount, mmsMediaCount, mmsGroupCount, conversationCount, groupConversationCount)
        }
    }

    private fun startGeneration(smsCount: Int, mmsMediaCount: Int, mmsGroupCount: Int, conversationCount: Int, groupConversationCount: Int) {
        logBuilder.clear()
        tvTestLog.text = ""
        btnGenerate.isEnabled = false
        btnCancel.isEnabled = true
        testProgressBar.visibility = View.VISIBLE

        val generator = TestMessageGenerator(contentResolver, this)

        generateJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                generator.generate(
                    smsCount = smsCount,
                    mmsMediaCount = mmsMediaCount,
                    mmsGroupCount = mmsGroupCount,
                    conversationCount = conversationCount.coerceAtLeast(1),
                    groupConversationCount = groupConversationCount.coerceAtLeast(1),
                    startDateMs = startDateMs!!,
                    endDateMs = endDateMs!!
                ) { line ->
                    appendLog(line)
                }
                appendLog("Generation complete!")
            } catch (_: CancellationException) {
                // handled by cancel button click
            } catch (e: Exception) {
                appendLog("Error: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                btnGenerate.isEnabled = true
                btnCancel.isEnabled = false
                testProgressBar.visibility = View.GONE
            }
        }
    }

    private fun appendLog(line: String) {
        synchronized(logBuilder) {
            logBuilder.appendLine(line)
            runOnUiThread {
                tvTestLog.text = logBuilder.toString()
                scrollTestLog.post { scrollTestLog.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun showDatePicker(isStart: Boolean) {
        val title = if (isStart) getString(R.string.start_date) else getString(R.string.end_date)
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(title)
            .apply {
                val current = if (isStart) startDateMs else endDateMs
                if (current != null) setSelection(current)
            }
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            if (isStart) {
                startDateMs = selection
                btnTestStartDate.text = "Start: ${dateFormat.format(Date(selection))}"
            } else {
                endDateMs = selection + (24 * 60 * 60 * 1000 - 1)
                btnTestEndDate.text = "End: ${dateFormat.format(Date(selection))}"
            }
        }

        picker.show(supportFragmentManager, "date_picker")
    }

    override fun onDestroy() {
        super.onDestroy()
        generateJob?.cancel()
    }
}
