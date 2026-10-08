package com.example.suda

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.nearby.connection.ConnectionsClient
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private const val REQUEST_BLUETOOTH = 101

        private const val SERVICE_ID = "com.example.suda"
        private const val DEVICE_NAME = "Suda"

        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    private lateinit var connectionsClient: ConnectionsClient

    private lateinit var statusText: TextView
    private lateinit var deviceCountText: TextView

    private val connectedDevices = mutableSetOf<String>()

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(
            endpointId: String,
            payload: Payload
        ) {
            if (payload.type == Payload.Type.BYTES) {

                val bytes = payload.asBytes() ?: return
                val message = String(bytes, Charsets.UTF_8)

                runOnUiThread {
                    playMessageSound()

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
            // File transfer progress can be added here later.
        }
    }

    private val connectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {

            override fun onConnectionInitiated(
                endpointId: String,
                connectionInfo: ConnectionInfo
            ) {
                connectionsClient.acceptConnection(
                    endpointId,
                    payloadCallback
                )
            }

            override fun onConnectionResult(
                endpointId: String,
                result: ConnectionResolution
            ) {
                if (result.status.statusCode ==
                    com.google.android.gms.common.api.Status.RESULT_SUCCESS
                ) {

                    connectedDevices.add(endpointId)

                    runOnUiThread {
                        updateDeviceCount()
                        statusText.text = "Suda connected"
                    }

                } else {

                    connectedDevices.remove(endpointId)

                    runOnUiThread {
                        updateDeviceCount()
                        statusText.text = "Connection failed"
                    }
                }
            }

            override fun onDisconnected(endpointId: String) {

                connectedDevices.remove(endpointId)

                runOnUiThread {
                    updateDeviceCount()
                }
            }
        }

    private val endpointDiscoveryCallback =
        object : EndpointDiscoveryCallback() {

            override fun onEndpointFound(
                endpointId: String,
                info: com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
            ) {

                connectionsClient.requestConnection(
                    DEVICE_NAME,
                    endpointId,
                    connectionLifecycleCallback
                )
                    .addOnSuccessListener {
                        runOnUiThread {
                            statusText.text = "Connecting..."
                        }
                    }
                    .addOnFailureListener {
                        runOnUiThread {
                            statusText.text = "Connection request failed"
                        }
                    }
            }

            override fun onEndpointLost(endpointId: String) {
                // Device is no longer discoverable.
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectionsClient = Nearby.getConnectionsClient(this)

        createInterface()
        requestRequiredPermissions()
    }

    private fun createInterface() {

        val root = LinearLayout(this)

        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(40, 40, 40, 40)

        statusText = TextView(this)

        statusText.text = "Suda ready"
        statusText.textSize = 22f
        statusText.gravity = Gravity.CENTER

        deviceCountText = TextView(this)

        deviceCountText.text = "Connected devices: 0"
        deviceCountText.textSize = 18f
        deviceCountText.gravity = Gravity.CENTER

        val connectButton = Button(this)

        connectButton.text = "Find / Connect Suda Devices"

        connectButton.setOnClickListener {
            checkBluetoothAndStart()
        }

        val sendButton = Button(this)

        sendButton.text = "Send Test Message"

        sendButton.setOnClickListener {
            sendTestMessage()
        }

        val recordButton = Button(this)

        recordButton.text = "Record Voice"

        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        val soundButton = Button(this)

        soundButton.text = "Test Message Sound"

        soundButton.setOnClickListener {
            playMessageSound()
        }

        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            deviceCountText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(connectButton)
        root.addView(sendButton)
        root.addView(recordButton)
        root.addView(soundButton)

        setContentView(root)
    }

    private fun requestRequiredPermissions() {

        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            permissions.add(
                Manifest.permission.BLUETOOTH_SCAN
            )

            permissions.add(
                Manifest.permission.BLUETOOTH_CONNECT
            )

            permissions.add(
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        }

        permissions.add(
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {

            permissions.add(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val required = permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (required.isNotEmpty()) {

            ActivityCompat.requestPermissions(
                this,
                required.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        }
    }

    private fun checkBluetoothAndStart() {

        try {

            val bluetoothAdapter =
                android.bluetooth.BluetoothAdapter.getDefaultAdapter()

            if (bluetoothAdapter != null &&
                !bluetoothAdapter.isEnabled
            ) {

                val intent =
                    Intent(
                        android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE
                    )

                startActivityForResult(
                    intent,
                    REQUEST_BLUETOOTH
                )

                return
            }

        } catch (e: Exception) {
            // Continue with Nearby.
        }

        startNearby()
    }

    private fun startNearby() {

        val advertisingOptions =
            AdvertisingOptions.Builder()
                .setStrategy(STRATEGY)
                .build()

        val discoveryOptions =
            DiscoveryOptions.Builder()
                .setStrategy(STRATEGY)
                .build()

        connectionsClient.startAdvertising(
            DEVICE_NAME,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        )
            .addOnSuccessListener {

                runOnUiThread {
                    statusText.text = "Advertising: Suda ready"
                }
            }
            .addOnFailureListener { error ->

                runOnUiThread {
                    statusText.text =
                        "Advertising failed: ${error.message}"
                }
            }

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        )
            .addOnSuccessListener {

                runOnUiThread {
                    statusText.text = "Searching for Suda devices..."
                }
            }
            .addOnFailureListener { error ->

                runOnUiThread {
                    statusText.text =
                        "Discovery failed: ${error.message}"
                }
            }
    }

    private fun sendTestMessage() {

        if (connectedDevices.isEmpty()) {

            Toast.makeText(
                this,
                "No Suda device connected",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val message =
            "Hello from Suda!"

        val payload =
            Payload.fromBytes(
                message.toByteArray(Charsets.UTF_8)
            )

        for (endpointId in connectedDevices) {

            connectionsClient.sendPayload(
                endpointId,
                payload
            )
                .addOnSuccessListener {

                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Message sent",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .addOnFailureListener { error ->

                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Send failed: ${error.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
        }
    }

    private fun updateDeviceCount() {

        deviceCountText.text =
            "Connected devices: ${connectedDevices.size}"
    }

    private fun playMessageSound() {

        try {

            val audioManager =
                getSystemService(
                    Context.AUDIO_SERVICE
                ) as AudioManager

            audioManager.playSoundEffect(
                AudioManager.FX_KEYPRESS_STANDARD,
                0.8f
            )

        } catch (e: Exception) {
            // Ignore sound errors.
        }
    }

    private fun startRecording() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.RECORD_AUDIO
                ),
                REQUEST_PERMISSIONS
            )

            return
        }

        try {

            val directory =
                getExternalFilesDir(
                    Environment.DIRECTORY_MUSIC
                )

            if (directory != null &&
                !directory.exists()
            ) {
                directory.mkdirs()
            }

            recordingFile =
                File(
                    directory,
                    "suda_voice_${System.currentTimeMillis()}.m4a"
                )

            mediaRecorder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

            mediaRecorder?.apply {

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

            Toast.makeText(
                this,
                "Recording started",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            mediaRecorder?.release()
            mediaRecorder = null

            isRecording = false

            Toast.makeText(
                this,
                "Recording failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopRecording() {

        try {

            mediaRecorder?.stop()

        } catch (e: Exception) {
            // Ignore stop errors.
        }

        mediaRecorder?.release()
        mediaRecorder = null

        isRecording = false

        Toast.makeText(
            this,
            "Voice saved",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {

        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        } catch (e: Exception) {
            // Ignore cleanup errors.
        }

        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            // Ignore.
        }

        mediaRecorder = null

        super.onDestroy()
    }
}
