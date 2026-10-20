package com.code2hack.eyeprojector.protocol

import android.app.Activity
import android.app.AlertDialog
import android.annotation.SuppressLint
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.io.Closeable
import java.io.File

/** Area 1 only; host/session actions and pointer settings remain with their owners. */
@SuppressLint("ViewConstructor") // Created in code with Activity callbacks; never inflated from XML.
class PairingPanel(
    private val activity: Activity,
    private val role: PeerRole,
    private val pairedChanged: (Boolean) -> Unit,
    private val beforeForget: (complete: () -> Unit) -> Unit = { it() },
    private val sharedPairing: LanPairing? = null,
) : LinearLayout(activity), Closeable {
    val store = PeerStore(File(activity.filesDir, "session"))
    private val caption = TextView(activity).apply { setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER }
    private val information = TextView(activity).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER }
    val pairButton = Button(activity).apply { text = "Pair with display device"; setOnClickListener { showCodePrompt() } }
    val forgetButton = Button(activity).apply { text = "Forget"; setOnClickListener {
        forgetting = true
        refresh()
        beforeForget {
            forgetting = false
            panelError = null
            try { pairing.forget() } catch (_: Exception) { panelError = "Could not remove pairing. Try Forget again." }
            if (!closed) refresh()
        }
    } }
    private val pairing = sharedPairing ?: LanPairing(store, role, { activity.runOnUiThread { if (!closed) refresh() } })
    private var dialog: AlertDialog? = null
    private var choices: Spinner? = null
    private var displayedPeers = emptyList<DiscoveredPeer>()
    private var lastPaired: Boolean? = null
    private var panelError: String? = null
    var forgetting = false
        private set
    private var closed = false
    private val tick = object : Runnable {
        override fun run() { if (!closed) { refresh(); postDelayed(this, 1000) } }
    }

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK)
        addView(caption); addView(information); addView(pairButton); addView(forgetButton)
        if (sharedPairing == null) pairing.start()
        post(tick)
    }

    fun isPaired(): Boolean = runCatching { store.pairedPeer() != null }.getOrDefault(false)

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (role == PeerRole.GLASSES && child is Button) {
            child.setTextColor(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.WHITE, Color.LTGRAY),
            ))
            child.background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(1, Color.WHITE) }
        }
    }

    private fun refresh() {
        val peer = runCatching { store.pairedPeer() }.getOrNull()
        val paired = peer != null
        pairButton.visibility = if (!paired && role == PeerRole.PHONE) View.VISIBLE else View.GONE
        pairButton.isEnabled = !pairing.pairing
        forgetButton.visibility = if (paired) View.VISIBLE else View.GONE
        forgetButton.isEnabled = !forgetting
        if (paired) caption.text = if (role == PeerRole.PHONE) "Paired with display device" else "Paired with Phone"
        else if (role == PeerRole.GLASSES) {
            val code = pairing.codes.display()
            caption.text = "${code.code}  ·  ${code.remainingSeconds / 60}:${"%02d".format(java.util.Locale.ROOT, code.remainingSeconds % 60)}"
        } else caption.text = "No paired display device"
        val label = if (!paired && role == PeerRole.GLASSES) pairing.identityId?.let { "Display ${it.take(12)}\n" } ?: "" else ""
        val state = when {
            forgetting -> "Stopping projection before Forget. If this waits, restore Shizuku using the Phone setup controls."
            !paired && role == PeerRole.GLASSES && pairing.codes.display().attemptsRemaining == 0 ->
                "Five pairing attempts used. Wait for the code to rotate; the countdown shows when pairing resumes."
            paired && pairing.availablePeers().none { it.identityId == peer?.identityId } ->
                "Paired device unavailable. Open EyeProjector on both devices and check Wi-Fi or the Phone hotspot."
            else -> pairing.status
        }
        information.text = label + (panelError ?: state)
        if (lastPaired != paired) { lastPaired = paired; pairedChanged(paired) }
        updateChoices()
    }

    private fun updateChoices() {
        val spinner = choices ?: return
        val found = pairing.availablePeers()
        val oldId = displayedPeers.getOrNull(spinner.selectedItemPosition)?.identityId
        if (spinner.adapter == null || found.map { it.identityId } != displayedPeers.map { it.identityId }) {
            displayedPeers = found
            spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                if (found.isEmpty()) listOf("Looking for display devices…") else found.map { "Display ${it.identityId.take(12)}" })
            found.indexOfFirst { it.identityId == oldId }.takeIf { it >= 0 }?.let(spinner::setSelection)
        } else displayedPeers = found // retain the latest LAN address
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = found.isNotEmpty() && !pairing.pairing
    }

    private fun showCodePrompt() {
        val input = EditText(activity).apply {
            hint = "Four-digit code shown on Glasses"
            contentDescription = hint
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        choices = Spinner(activity)
        val content = LinearLayout(activity).apply {
            orientation = VERTICAL; setPadding(24, 12, 24, 12)
            addView(TextView(activity).apply { text = "Connect both devices to the same Wi-Fi or the Phone hotspot. Select the display label shown with its code." })
            addView(choices); addView(input)
        }
        displayedPeers = emptyList()
        var attempt: PairingAttempt? = null
        var dismissed = false
        dialog = AlertDialog.Builder(activity).setTitle("Pair with display device").setView(content)
            .setNegativeButton("Cancel", null).setPositiveButton("Pair", null).create().also { prompt ->
                // Negative button, Back, outside touch and teardown all dismiss.
                prompt.setOnDismissListener {
                    dismissed = true
                    pairing.cancelPairing(attempt)
                    if (dialog === prompt) { dialog = null; choices = null }
                    if (!closed) refresh()
                }
                prompt.show()
                prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val code = input.text.toString()
                    if (!code.matches(Regex("[0-9]{4}"))) { input.error = "Enter four digits"; return@setOnClickListener }
                    val candidate = displayedPeers.getOrNull(choices?.selectedItemPosition ?: -1) ?: return@setOnClickListener
                    prompt.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    var submitted: PairingAttempt? = null
                    submitted = pairing.pair(code, candidate) { success -> activity.runOnUiThread {
                        if (!closed && !dismissed && dialog === prompt && submitted != null && attempt === submitted) {
                            if (success) prompt.dismiss() else input.error = "Pairing failed. Check the current code and display label."
                            refresh()
                        }
                    } }
                    attempt = submitted
                }
            }
        updateChoices()
    }

    override fun close() { closed = true; removeCallbacks(tick); dialog?.dismiss(); if (sharedPairing == null) pairing.close() }
}
