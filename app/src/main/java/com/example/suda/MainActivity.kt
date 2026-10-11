
package com.example.suda

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private const val REQUEST_BLUETOOTH = 101
        private const val SERVICE_ID = "com.example.suda"
        private const val DEVICE_NAME = "Suda"
    }

    private lateinit var client: ConnectionsClient
    private lateinit var statusText: TextView
    private lateinit var deviceCountText: TextView

    private val connectedDevices = mutableSetOf<String>()
    private val strategy = Strategy.P2P_CLUSTER

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val message =
                    payload.asBytes()?.toString(Charsets.UTF_8) ?: return

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
            // Transfer progress callback.
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(
            endpointId: String,
            connectionInfo: ConnectionInfo
        ) {
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { error ->
                    runOnUiThread {
                        statusText.text =
                            "Accept failed: ${error.message}"
                    }
                }
        }

        override fun onConnectionResult(
            endpointId: String,
            result: ConnectionResolution
        ) {
            // FIX: statusCode (Int) ko Status object se compare nahi karna.
            if (result.status.isSuccess) {
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
            runOnUiThread { updateDeviceCount() }
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String,
            info: DiscoveredEndpointInfo
        ) {
            client.requestConnection(
                DEVICE_NAME,
                endpointId,
                lifecycleCallback
            ).addOnFailureListener { error ->
                runOnUiThread {
                    statusText.text =
                        "Connection request failed: ${error.message}"
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Device no longer visible.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = Nearby.getConnectionsClient(this)
        createInterface()
        requestRequiredPermissions()
    }

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        statusText = TextView(this).apply {
            text = "Suda ready"
            textSize = 22f
            gravity = Gravity.CENTER
        }

        deviceCountText = TextView(this).apply {
            text = "Connected devices: 0"
            textSize = 18f
            gravity = Gravity.CENTER
        }

        root.addView(statusText)
        root.addView(deviceCountText)

        root.addView(Button(this).apply {
            text = "Find / Connect Suda Devices"
            setOnClickListener { checkBluetoothAndStart() }
        })

        root.addView(Button(this).apply {
            text = "Send Test Message"
            setOnClickListener { sendTestMessage() }
        })

        root.addView(Button(this).apply {
            text = "Start Voice Recording"
            setOnClickListener {
                if (isRecording) {
                    stopRecording()
                    text = "Start Voice Recording"
                } else {
                    startRecording()
                    if (isRecording) text = "Stop Voice Recording"
                }
            }
        })

        root.addView(Button(this).apply {
            text = "Test Message Sound"
            setOnClickListener { playMessageSound() }
        })

        setContentView(root)
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        permissions.add(Manifest.permission.RECORD_AUDIO)

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        }
    }

    private fun checkBluetoothAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestRequiredPermissions()
            return
        }

        try {
            val adapter =
                android.bluetooth.BluetoothAdapter.getDefaultAdapter()

            if (adapter != null && !adapter.isEnabled) {
                startActivityForResult(
                    Intent(
                        android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE
                    ),
                    REQUEST_BLUETOOTH
                )
                return
            }
        } catch (_: Exception) {
            // Nearby will report transport errors if any.
        }

        startNearby()
    }

    @Deprecated("Use Activity Result APIs in a future update")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_BLUETOOTH &&
            resultCode == RESULT_OK
        ) {
            startNearby()
        }
    }

    private fun startNearby() {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()

        client.startAdvertising(
            DEVICE_NAME,
            SERVICE_ID,
            lifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            runOnUiThread {
                statusText.text = "Advertising: ready"
            }
        }.addOnFailureListener { error ->
            runOnUiThread {
                statusText.text =
                    "Advertising failed: ${error.message}"
            }
        }

        client.startDiscovery(
            SERVICE_ID,
            discoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            runOnUiThread {
                statusText.text = "Searching for Suda devices..."
            }
        }.addOnFailureListener { error ->
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

        connectedDevices.toList().forEach { endpointId ->
            client.sendPayload(
                endpointId,
                Payload.fromBytes(
                    "Hello from Suda!".toByteArray(Charsets.UTF_8)
                )
            ).addOnFailureListener { error ->
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
            val audio =
                getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.playSoundEffect(
                AudioManager.FX_KEYPRESS_STANDARD,
                0.8f
            )
        } catch (_: Exception) {
            // Ignore sound errors.
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestRequiredPermissions()
            return
        }

        try {
            val directory =
                getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: filesDir

            if (!directory.exists()) directory.mkdirs()

            recordingFile = File(
                directory,
                "suda_voice_${System.currentTimeMillis()}.m4a"
            )

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(recordingFile!!.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            Toast.makeText(
                this,
                "Recording started",
                Toast.LENGTH_SHORT
            ).show()
        } catch (error: Exception) {
            try {
                recorder?.release()
            } catch (_: Exception) {
            }

            recorder = null
            isRecording = false

            Toast.makeText(
                this,
                "Recording failed: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopRecording() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Ignore very short recording errors.
        } finally {
            recorder?.release()
            recorder = null
            isRecording = false
        }

        Toast.makeText(
            this,
            "Voice saved: ${recordingFile?.name ?: "unknown"}",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {
        try {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        } catch (_: Exception) {
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        recorder = null
        super.onDestroy()
    }
}
