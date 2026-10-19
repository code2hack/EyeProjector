package com.code2hack.eyeprojector.directhost

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.code2hack.eyeprojector.protocol.Tls
import com.code2hack.eyeprojector.protocol.Wire
import java.io.DataOutputStream
import java.io.File

class HostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = getString(R.string.host_ready)
            textSize = 20f; setTextColor(Color.WHITE)
        }
        val stop = Button(this).apply {
            text = getString(R.string.host_stop); contentDescription = "Stop projection and disconnect all peers"
            setOnClickListener {
                isEnabled = false
                Thread {
                    val stopped = runCatching {
                        Tls.connect(File(filesDir, "session")).use { socket ->
                            Wire.writeRequest(DataOutputStream(socket.outputStream), Wire.STOP_HOST)
                            // A local UI success requires observed endpoint closure, not merely a send.
                            require(socket.inputStream.read() == -1)
                        }
                    }.isSuccess
                    runOnUiThread { status.text = if (stopped) getString(R.string.host_stopped) else getString(R.string.host_stop_unknown); isEnabled = true }
                }.start()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(24, 24, 24, 24)
            addView(status); addView(stop)
        })
    }
}
