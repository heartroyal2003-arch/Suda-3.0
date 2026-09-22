package com.example.suda

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
 private lateinit var status: TextView
 private var recorder: MediaRecorder? = null
 private var audioFile: String? = null
 private val serviceId = "com.example.suda.offline"
 private val strategy = Strategy.P2P_CLUSTER
 private val connected = mutableSetOf<String>()
 override fun onCreate(b: Bundle?) { super.onCreate(b); setContentView(R.layout.activity_main); status=findViewById(R.id.status); findViewById<Button>(R.id.startButton).setOnClickListener{startNearby()}; findViewById<Button>(R.id.recordButton).setOnClickListener{toggleRecord()}; findViewById<Button>(R.id.soundButton).setOnClickListener{playMessageSound()}; requestPermissions() }
 private fun requestPermissions(){ val p=mutableListOf(Manifest.permission.RECORD_AUDIO); if(android.os.Build.VERSION.SDK_INT>=31){p+=Manifest.permission.BLUETOOTH_SCAN;p+=Manifest.permission.BLUETOOTH_CONNECT;p+=Manifest.permission.BLUETOOTH_ADVERTISE}else p+=Manifest.permission.ACCESS_FINE_LOCATION; ActivityCompat.requestPermissions(this,p.toTypedArray(),10) }
 private fun startNearby(){ status.text="Searching nearby Suda devices…"; Nearby.getConnectionsClient(this).startDiscovery(serviceId,discovery,DiscoveryOptions.Builder().setStrategy(strategy).build()).addOnFailureListener{status.text="Discovery failed"}; Nearby.getConnectionsClient(this).startAdvertising("Suda",serviceId,lifecycle,AdvertisingOptions.Builder().setStrategy(strategy).build()).addOnFailureListener{status.text="Advertising failed"} }
 private val discovery=object:EndpointDiscoveryCallback(){ override fun onEndpointFound(id:String,info:DiscoveredEndpointInfo){status.text="Found: ${info.endpointName}";Nearby.getConnectionsClient(this@MainActivity).requestConnection("Suda",id,lifecycle)}; override fun onEndpointLost(id:String){} }
 private val lifecycle=object:ConnectionLifecycleCallback(){ override fun onConnectionInitiated(id:String,info:ConnectionInfo){Nearby.getConnectionsClient(this@MainActivity).acceptConnection(id,payloads)}; override fun onConnectionResult(id:String,r:ConnectionResolution){if(r.status.isSuccess){connected+=id;status.text="Connected • ${connected.size} device(s)"}else status.text="Connection failed"}; override fun onDisconnected(id:String){connected-=id;status.text="Connected • ${connected.size} device(s)"} }
 private val payloads=object:PayloadCallback(){ override fun onPayloadReceived(id:String,p:Payload){if(p.type==Payload.Type.BYTES){playMessageSound();Toast.makeText(this@MainActivity,"💬 "+String(p.asBytes()?:ByteArray(0)),Toast.LENGTH_SHORT).show()}};override fun onPayloadTransferUpdate(id:String,u:PayloadTransferUpdate){} }
 private fun playMessageSound(){try{(getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD,0.8f)}catch(_:Exception){}}
 private fun toggleRecord(){if(recorder==null){val d=getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!;if(!d.exists())d.mkdirs();audioFile=File(d,"SudaVoice_${SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())}.m4a").absolutePath;recorder=MediaRecorder(this).apply{setAudioSource(MediaRecorder.AudioSource.MIC);setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);setAudioEncoder(MediaRecorder.AudioEncoder.AAC);setOutputFile(audioFile);prepare();start()};findViewById<Button>(R.id.recordButton).text="⏹ Stop recording";Toast.makeText(this,"🎙 Recording…",Toast.LENGTH_SHORT).show()}else{recorder?.stop();recorder?.release();recorder=null;findViewById<Button>(R.id.recordButton).text="🎙 Record voice";Toast.makeText(this,"Voice saved locally",Toast.LENGTH_SHORT).show()}}
}
