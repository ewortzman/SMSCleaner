package com.smscleaner.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Minimal MMS receiver stub required for default SMS app eligibility.
        // MMS download and storage requires carrier-specific implementation.
        // This receiver acknowledges receipt so the system doesn't retry.
    }
}
