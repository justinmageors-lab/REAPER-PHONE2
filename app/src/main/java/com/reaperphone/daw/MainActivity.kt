package com.reaperphone.daw

import android.Manifest
import android.content.ContentResolver
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private val Bg = Color(0xFF101114)
private val Panel = Color(0xFF1B1D22)
private val Panel2 = Color(0xFF252830)
private val Accent = Color(0xFFE04444)
private val Wave = Color(0xFF55A9E8)
private val Muted = Color(0xFF9BA1AD)

data class AudioTrackItem(val name: String, val file: File, val imported: Boolean)

class MainActivity : ComponentActivity() {
    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private val recording = AtomicBoolean(false)
    private var player: MediaPlayer? = null
    private var currentRecording: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReaperPhoneApp() }
    }

    private fun startRecording(onDone: (File) -> Unit, onError: (String) -> Unit) {
        if (recording.get()) return
        val sampleRate = 44100
        val min = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) { onError("This device does not support the requested recording format."); return }
        val bufferSize = maxOf(min, 4096)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            onError("Could not initialize the microphone.")
            return
        }
        val out = File(filesDir, "REC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.wav")
        currentRecording = out
        recorder = audioRecord
        recording.set(true)
        audioRecord.startRecording()
        recordThread = thread(name = "ReaperPhoneRecorder") {
            try {
                RandomAccessFile(out, "rw").use { raf ->
                    raf.setLength(0)
                    raf.write(ByteArray(44))
                    val buffer = ShortArray(bufferSize / 2)
                    var dataBytes = 0L
                    while (recording.get()) {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val bytes = ByteArray(read * 2)
                            for (i in 0 until read) {
                                val v = buffer[i].toInt()
                                bytes[i * 2] = (v and 0xff).toByte()
                                bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
                            }
                            raf.write(bytes)
                            dataBytes += bytes.size
                        }
                    }
                    writeWavHeader(raf, dataBytes, sampleRate, 1, 16)
                }
                runOnUiThread { onDone(out) }
            } catch (e: Exception) {
                runOnUiThread { onError(e.message ?: "Recording failed.") }
            } finally {
                try { audioRecord.stop() } catch (_: Exception) {}
                audioRecord.release()
                recorder = null
            }
        }
    }

    private fun writeWavHeader(
        raf: RandomAccessFile, dataSize: Long, sampleRate: Int, channels: Int, bits: Int
    ) {
        val byteRate = sampleRate * channels * bits / 8
        raf.seek(0)
        raf.writeBytes("RIFF")
        writeIntLE(raf, (36 + dataSize).toInt())
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        writeIntLE(raf, 16)
        writeShortLE(raf, 1)
        writeShortLE(raf, channels)
        writeIntLE(raf, sampleRate)
        writeIntLE(raf, byteRate)
        writeShortLE(raf, channels * bits / 8)
        writeShortLE(raf, bits)
        raf.writeBytes("data")
        writeIntLE(raf, dataSize.toInt())
    }

    private fun writeIntLE(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xff); raf.write((value shr 8) and 0xff)
        raf.write((value shr 16) and 0xff); raf.write((value shr 24) and 0xff)
    }
    private fun writeShortLE(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xff); raf.write((value shr 8) and 0xff)
    }

    private fun stopRecording() {
        recording.set(false)
    }

    private fun playFile(file: File, onFinished: () -> Unit, onError: (String) -> Unit) {
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback(); onFinished() }
                setOnErrorListener { _, _, _ -> stopPlayback(); onError("Unable to play this audio file."); true }
                prepare()
                start()
            }
        } catch (e: Exception) {
            onError(e.message ?: "Unable to open audio file.")
        }
    }

    private fun stopPlayback() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    override fun onDestroy() {
        recording.set(false)
        stopPlayback()
        super.onDestroy()
    }

    @Composable
    private fun ReaperPhoneApp() {
        val tracks = remember { mutableStateListOf<AudioTrackItem>() }
        var selected by remember { mutableIntStateOf(-1) }
        var isRecording by remember { mutableStateOf(false) }
        var isPlaying by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Ready") }
        var permissionGranted by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        }
        val scope = rememberCoroutineScope()

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            permissionGranted = granted
            if (granted) {
                isRecording = true
                status = "Recording..."
                startRecording(
                    onDone = { file ->
                        tracks.add(AudioTrackItem(file.nameWithoutExtension, file, false))
                        selected = tracks.lastIndex
                        isRecording = false
                        status = "Recording saved"
                    },
                    onError = { msg -> isRecording = false; status = msg }
                )
            } else status = "Microphone permission denied"
        }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) {}
                    val displayName = queryDisplayName(contentResolver, uri) ?: "Imported audio"
                    val ext = displayName.substringAfterLast('.', "audio").lowercase(Locale.US)
                    val dest = File(filesDir, "IMPORT_${System.currentTimeMillis()}_$displayName")
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Could not read selected file")
                    }
                    tracks.add(AudioTrackItem(displayName, dest, true))
                    selected = tracks.lastIndex
                    status = "Imported $displayName"
                }
            }
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Accent, background = Bg, surface = Panel,
                onBackground = Color.White, onSurface = Color.White
            )
        ) {
            Column(
                Modifier.fillMaxSize().background(Bg).padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("REAPER PHONE", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Text("MOBILE AUDIO WORKSTATION  •  BUILD 001", color = Muted, fontSize = 9.sp)
                    }
                    Surface(color = Color(0xFF352023), shape = RoundedCornerShape(8.dp)) {
                        Text("REC", color = Accent, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoTile("PROJECT", "Untitled 01", Modifier.weight(1f))
                    InfoTile("FORMAT", "44.1 kHz / 16-bit", Modifier.weight(1f))
                    InfoTile("TRACKS", tracks.size.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ARRANGEMENT", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(status, color = if (isRecording) Accent else Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (tracks.isEmpty()) {
                        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Wave, modifier = Modifier.size(42.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Your session is empty", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Record a take or import a beat to get started.", color = Muted, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(tracks.indices.toList()) { index ->
                                val track = tracks[index]
                                TrackRow(track, index, selected == index, onClick = { selected = index })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (isRecording) {
                                stopRecording()
                                status = "Finishing recording..."
                            } else if (!isPlaying && selected >= 0) {
                                isPlaying = true
                                status = "Playing ${tracks[selected].name}"
                                playFile(tracks[selected].file, { isPlaying = false; status = "Playback finished" }, { isPlaying = false; status = it })
                            } else if (isPlaying) {
                                stopPlayback(); isPlaying = false; status = "Stopped"
                            } else status = "Select a track to play"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) Color(0xFF6A2428) else Accent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(if (isRecording) Icons.Default.Stop else if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(5.dp))
                        Text(if (isRecording || isPlaying) "STOP" else "PLAY")
                    }
                    OutlinedButton(
                        onClick = {
                            if (isRecording) return@OutlinedButton
                            if (permissionGranted) {
                                isRecording = true; status = "Recording..."
                                startRecording(
                                    onDone = { file -> tracks.add(AudioTrackItem(file.nameWithoutExtension, file, false)); selected = tracks.lastIndex; isRecording = false; status = "Recording saved" },
                                    onError = { msg -> isRecording = false; status = msg }
                                )
                            } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = !isRecording,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                    ) {
                        Icon(Icons.Default.FiberManualRecord, null, tint = Accent)
                        Spacer(Modifier.width(5.dp)); Text("RECORD")
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("audio/*")) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Wave)
                    ) {
                        Icon(Icons.Default.FileOpen, null)
                        Spacer(Modifier.width(4.dp)); Text("IMPORT")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("INPUT: PHONE MIC", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text("USB AUDIO: PLANNED", color = Muted, fontSize = 10.sp)
                }
            }
        }
    }

    @Composable
    private fun InfoTile(label: String, value: String, modifier: Modifier = Modifier) {
        Surface(color = Panel2, shape = RoundedCornerShape(8.dp), modifier = modifier) {
            Column(Modifier.padding(9.dp)) {
                Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    @Composable
    private fun TrackRow(track: AudioTrackItem, index: Int, selected: Boolean, onClick: () -> Unit) {
        Surface(
            color = if (selected) Color(0xFF30343D) else Panel2,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().clickable { onClick() }
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (track.imported) Color(0xFF1D3A50) else Color(0xFF492326), shape = RoundedCornerShape(6.dp)) {
                    Icon(if (track.imported) Icons.Default.AudioFile else Icons.Default.Mic, null,
                        tint = if (track.imported) Wave else Accent, modifier = Modifier.padding(8.dp).size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${index + 1}. ${track.name}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(if (track.imported) "IMPORTED AUDIO" else "RECORDED TAKE", color = Muted, fontSize = 9.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 5.dp)) {
                        repeat(24) { n ->
                            Box(Modifier.width(3.dp).height((5 + ((n * 17 + index * 11) % 15)).dp).background(Wave.copy(alpha = .75f), RoundedCornerShape(2.dp)))
                        }
                    }
                }
                Text(if (selected) "SELECTED" else "TRACK", color = if (selected) Wave else Muted, fontSize = 8.sp)
            }
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: android.net.Uri): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }
}