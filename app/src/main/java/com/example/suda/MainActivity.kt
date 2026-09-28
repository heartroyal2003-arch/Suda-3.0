package com.example.suda

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.nearby.connection.Payload
import java.io.File

class MainActivity : Activity() {

    private val serviceId = "com.example.suda"
    private val strategy = Strategy.P2P_CLUSTER

    private val connectedDevices = mutableMapOf<String, String>()

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false

    private lateinit var statusText: TextView
    private lateinit var messageInput: EditText

    private val connectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {

            override fun onConnectionInitiated(
                endpointId: String,
                connectionInfo: ConnectionInfo
            ) {
                Nearby.getConnectionsClient(this@MainActivity)
                    .acceptConnection(endpointId, payloadCallback)
                    .addOnSuccessListener {
                        statusText.text = "Connecting: ${connectionInfo.endpointName}"
                    }
            }

            override fun onConnectionResult(
                endpointId: String,
                result: ConnectionResolution
            ) {
                if (result.status.isSuccess) {
                    connectedDevices[endpointId] = endpointId

                    statusText.text =
                        "Connected devices: ${connectedDevices.size}"

                    Toast.makeText(
                        this@MainActivity,
                        "Suda device connected",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    connectedDevices.remove(endpointId)

                    statusText.text =
                        "Connected devices: ${connectedDevices.size}"
                }
            }

            override fun onDisconnected(endpointId: String) {
                connectedDevices.remove(endpointId)

                statusText.text =
                    "Connected devices: ${connectedDevices.size}"
            }
        }

    private val payloadCallback =
        object : PayloadCallback() {

            override fun onPayloadReceived(
                endpointId: String,
                payload: Payload
            ) {
                if (payload.type == Payload.Type.BYTES) {

                    val data = payload.asBytes()
                    val message =
                        data?.toString(Charsets.UTF_8) ?: return

                    playMessageSound()

                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Message: $message",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            override fun onPayloadTransferUpdate(
                endpointId: String,
                update: PayloadTransferUpdate
            ) {
                // File/voice transfer progress can be handled here.
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createInterface()
        requestSudaPermissions()
    }

    private fun createInterface() {

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 40, 32, 32)

        val title = TextView(this)
        title.text = "Suda"
        title.textSize = 30f

        statusText = TextView(this)
        statusText.text = "Suda ready"
        statusText.textSize = 18f

        val connectButton = Button(this)
        connectButton.text = "Find / Connect Suda Devices"
        connectButton.setOnClickListener {
            startSudaConnection()
        }

        messageInput = EditText(this)
        messageInput.hint = "Type message"

        val sendButton = Button(this)
        sendButton.text = "Send Message"
        sendButton.setOnClickListener {
            sendMessage()
        }

        val voiceButton = Button(this)
        voiceButton.text = "Record Voice"
        voiceButton.setOnClickListener {
            toggleRecording(voiceButton)
        }

        val soundButton = Button(this)
        soundButton.text = "Test Message Sound"
        soundButton.setOnClickListener {
            playMessageSound()
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(connectButton)
        root.addView(messageInput)
        root.addView(sendButton)
        root.addView(voiceButton)
        root.addView(soundButton)

        setContentView(root)
    }

    private fun requestSudaPermissions() {

        val permissions = mutableListOf<String>()

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        permissions.add(Manifest.permission.RECORD_AUDIO)

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                needed.toTypedArray(),
                1001
            )
        }
    }

    private fun startSudaConnection() {

        if (!hasRequiredPermissions()) {
            requestSudaPermissions()
            return
        }

        val client = Nearby.getConnectionsClient(this)

        val advertisingOptions =
            AdvertisingOptions.Builder(strategy).build()

        client.startAdvertising(
            "Suda",
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {

            statusText.text =
                "Suda is discoverable"

        }.addOnFailureListener { error ->

            statusText.text =
                "Advertising error: ${error.message}"
        }

        val discoveryOptions =
            DiscoveryOptions.Builder(strategy).build()

        client.startDiscovery(
            serviceId,
            object : EndpointDiscoveryCallback() {

                override fun onEndpointFound(
                    endpointId: String,
                    info: DiscoveredEndpointInfo
                ) {

                    client.requestConnection(
                        "Suda",
                        endpointId,
                        connectionLifecycleCallback
                    )
                }

                override fun onEndpointLost(endpointId: String) {
                    // Device disappeared.
                }
            },
            discoveryOptions
        ).addOnFailureListener { error ->

            statusText.text =
                "Discovery error: ${error.message}"
        }

        enableBluetooth()
    }

    private fun sendMessage() {

        val text = messageInput.text.toString().trim()

        if (text.isEmpty()) {
            return
        }

        if (connectedDevices.isEmpty()) {
            Toast.makeText(
                this,
                "No Suda device connected",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val payload =
            Payload.fromBytes(text.toByteArray(Charsets.UTF_8))

        for (endpointId in connectedDevices.keys) {

            Nearby.getConnectionsClient(this)
                .sendPayload(endpointId, payload)
        }

        messageInput.text.clear()

        Toast.makeText(
            this,
            "Message sent",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun playMessageSound() {

        val audioManager =
            getSystemService(Context.AUDIO_SERVICE) as AudioManager

        audioManager.playSoundEffect(
            AudioManager.FX_KEYPRESS_STANDARD,
            0.8f
        )
    }

    private fun toggleRecording(button: Button) {

        if (isRecording) {

            stopRecording()

            button.text = "Record Voice"

            Toast.makeText(
                this,
                "Voice recording saved",
                Toast.LENGTH_SHORT
            ).show()

        } else {

            startRecording()

            button.text = "Stop Recording"
        }
    }

    private fun startRecording() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestSudaPermissions()
            return
        }

        try {

            recordingFile =
                File(
                    cacheDir,
                    "suda_voice_${System.currentTimeMillis()}.m4a"
                )

            recorder = MediaRecorder(this).apply {

                setAudioSource(
                    MediaRecorder.AudioSource.MIC
                )

                setOutputFormat(
                    MediaRecorder.OutputFormat.MPEG_4
                )

                setAudioEncoder(
                    MediaRecorder.AudioEncoder.AAC
                )

                setOutputFile(
                    recordingFile!!.absolutePath
                )

                prepare()
                start()
            }

            isRecording = true

        } catch (e: Exception) {

            recorder?.release()
            recorder = null

            Toast.makeText(
                this,
                "Recording failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopRecording() {

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        recorder?.release()
        recorder = null
        isRecording = false
    }

    private fun enableBluetooth() {

        if (android.os.Build.VERSION.SDK_INT >= 31) {

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val adapter =
            BluetoothAdapter.getDefaultAdapter()

        if (adapter != null && !adapter.isEnabled) {

            try {

                val intent =
                    Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

                startActivityForResult(intent, 2001)

            } catch (_: Exception) {
                // User can enable Bluetooth manually.
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {

        if (android.os.Build.VERSION.SDK_INT >= 31) {

            return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        } else {

            return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        Nearby.getConnectionsClient(this)
            .stopAllEndpoints()
            .addOnCompleteListener {
                super.onDestroy()
            }
    }
}
