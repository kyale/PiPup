package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class Receiver : BroadcastReceiver() {

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) {
        with(context) {
            val serviceIntent = Intent(this, PipUpService::class.java)
            startForegroundService(serviceIntent)
        }
    }
}
