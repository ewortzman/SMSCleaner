package com.smscleaner.app.fragment

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.smscleaner.app.R
import com.smscleaner.app.TestMessageGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TestMessagesFragment : Fragment(R.layout.fragment_test_messages) {

    private var startDateMs: Long? = null
    private var endDateMs: Long? = null
    private var generateJob: Job? = null
    private val logBuilder = StringBuilder()

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val timestampFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etSmsCount = view.findViewById<TextInputEditText>(R.id.etSmsCount)
        val etMmsMediaCount = view.findViewById<TextInputEditText>(R.id.etMmsMediaCount)
        val etMmsGroupCount = view.findViewById<TextInputEditText>(R.id.etMmsGroupCount)
        val btnTestStartDate = view.findViewById<MaterialButton>(R.id.btnTestStartDate)
        val btnTestEndDate = view.findViewById<MaterialButton>(R.id.btnTestEndDate)
        val etConversationCount = view.findViewById<TextInputEditText>(R.id.etConversationCount)
        val etGroupConversationCount = view.findViewById<TextInputEditText>(R.id.etGroupConversationCount)
        val btnGenerate = view.findViewById<MaterialButton>(R.id.btnGenerate)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val testProgressBar = view.findViewById<LinearProgressIndicator>(R.id.testProgressBar)
        val tvTestLog = view.findViewById<MaterialTextView>(R.id.tvTestLog)
        val scrollTestLog = view.findViewById<ScrollView>(R.id.scrollTestLog)

        // Default dates
        val cal = Calendar.getInstance()
        endDateMs = cal.timeInMillis
        btnTestEndDate.text = "End: ${dateFormat.format(Date(endDateMs!!))}"
        cal.add(Calendar.YEAR, -1)
        startDateMs = cal.timeInMillis
        btnTestStartDate.text = "Start: ${dateFormat.format(Date(startDateMs!!))}"

        fun showDatePicker(isStart: Boolean) {
            val title = if (isStart) getString(R.string.start_date) else getString(R.string.end_date)
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(title)
                .apply {
                    val current = if (isStart) startDateMs else endDateMs
                    if (current != null) setSelection(current)
                }.build()
            picker.addOnPositiveButtonClickListener { selection ->
                if (isStart) {
                    startDateMs = selection
                    btnTestStartDate.text = "Start: ${dateFormat.format(Date(selection))}"
                } else {
                    endDateMs = selection + (24 * 60 * 60 * 1000 - 1)
                    btnTestEndDate.text = "End: ${dateFormat.format(Date(selection))}"
                }
            }
            picker.show(childFragmentManager, "date_picker")
        }

        btnTestStartDate.setOnClickListener { showDatePicker(true) }
        btnTestEndDate.setOnClickListener { showDatePicker(false) }
        btnCancel.setOnClickListener {
            generateJob?.cancel()
            appendLog(tvTestLog, scrollTestLog, "Cancelled.")
        }

        fun appendLog(line: String) = appendLog(tvTestLog, scrollTestLog, line)

        btnGenerate.setOnClickListener {
            val smsCount = etSmsCount.text.toString().toIntOrNull() ?: 0
            val mmsMediaCount = etMmsMediaCount.text.toString().toIntOrNull() ?: 0
            val mmsGroupCount = etMmsGroupCount.text.toString().toIntOrNull() ?: 0
            val conversationCount = etConversationCount.text.toString().toIntOrNull() ?: 5
            val groupConversationCount = etGroupConversationCount.text.toString().toIntOrNull() ?: 3

            if (smsCount == 0 && mmsMediaCount == 0 && mmsGroupCount == 0) {
                Toast.makeText(requireContext(), "Enter at least one message count", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (startDateMs == null || endDateMs == null) {
                Toast.makeText(requireContext(), "Both dates required for generation", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            logBuilder.clear()
            tvTestLog.text = ""
            btnGenerate.isEnabled = false
            btnCancel.isEnabled = true
            testProgressBar.visibility = View.VISIBLE

            val generator = TestMessageGenerator(requireContext().contentResolver, requireContext())

            generateJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    generator.generate(
                        smsCount = smsCount, mmsMediaCount = mmsMediaCount, mmsGroupCount = mmsGroupCount,
                        conversationCount = conversationCount.coerceAtLeast(1),
                        groupConversationCount = groupConversationCount.coerceAtLeast(1),
                        startDateMs = startDateMs!!, endDateMs = endDateMs!!
                    ) { line -> appendLog(line) }
                    appendLog("Generation complete!")
                } catch (_: CancellationException) {
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

        @Suppress("ClickableViewAccessibility")
        scrollTestLog.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
    }

    private fun appendLog(tvLog: MaterialTextView, scrollLog: ScrollView, line: String) {
        synchronized(logBuilder) {
            logBuilder.appendLine("[${timestampFmt.format(Date())}] $line")
            activity?.runOnUiThread {
                tvLog.text = logBuilder.toString()
                scrollLog.post { scrollLog.scrollTo(0, tvLog.bottom) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        generateJob?.cancel()
    }
}
