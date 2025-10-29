package com.example.visuallocatizationapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.visuallocatizationapp.ui.theme.VisualLocatizationAppTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VisualLocatizationAppTheme {
                CameraRecorderScreen()
            }
        }
    }
}

@Composable
fun CameraRecorderScreen() {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(false) }

    // Launcher to request both permissions at once
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions[Manifest.permission.CAMERA] == true &&
                permissions[Manifest.permission.RECORD_AUDIO] == true
    }

    // Check current permission state
    LaunchedEffect(Unit) {
        val cameraGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        hasPermissions = cameraGranted && audioGranted

        if (!hasPermissions) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    var videoUri by remember { mutableStateOf<Uri?>(null) }

    when {
        !hasPermissions -> {
            // Permission not granted yet
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "This app needs camera and microphone permissions to record video.",
                    color = Color.White
                )
            }
        }

        videoUri == null -> {
            // Show camera preview
            CameraRecordView(onVideoRecorded = { uri -> videoUri = uri })
        }

        else -> {
            // Show preview of recorded video
            VideoPreviewScreen(
                videoUri = videoUri!!,
                onDiscard = { videoUri = null },
                onSend = {
                    Log.d("Uploader", "Pretending to send video: $videoUri")
                    videoUri = null
                }
            )
        }
    }
}



@SuppressLint("MissingPermission")
@Composable
fun CameraRecordView(onVideoRecorded: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var recording: Recording? by remember { mutableStateOf(null) }
    var isRecording by remember { mutableStateOf(false) }
    var videoCapture: VideoCapture<Recorder>? by remember { mutableStateOf(null) }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = androidx.camera.core.Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val recorder = Recorder.Builder().build()
                    val newVideoCapture = VideoCapture.withOutput(recorder)
                    videoCapture = newVideoCapture

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            newVideoCapture
                        )
                        Log.d("CameraRecordView", "Camera successfully bound to lifecycle")
                    } catch (exc: Exception) {
                        Log.e("CameraRecordView", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp)
                .size(80.dp)
                .background(
                    if (isRecording) Color.Red else Color.White,
                    shape = MaterialTheme.shapes.medium
                )
                .clickable {
                    val capture = videoCapture
                    if (capture == null) {
                        Log.e("CameraRecordView", "VideoCapture not ready yet")
                        return@clickable
                    }

                    if (!isRecording) {
                        val file = File(
                            context.externalCacheDir,
                            "VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
                        )
                        val outputOptions = FileOutputOptions.Builder(file).build()

                        val pendingRecording = if (
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            capture.output.prepareRecording(context, outputOptions)
                                .withAudioEnabled()
                        } else {
                            capture.output.prepareRecording(context, outputOptions)
                        }

                        recording = pendingRecording.start(
                            ContextCompat.getMainExecutor(context)
                        ) { recordEvent ->
                            when (recordEvent) {
                                is VideoRecordEvent.Finalize -> {
                                    if (!recordEvent.hasError()) {
                                        onVideoRecorded(Uri.fromFile(file))
                                    } else {
                                        Log.e(
                                            "CameraRecordView",
                                            "Recording error: ${recordEvent.error}"
                                        )
                                    }
                                }
                                else -> {}
                            }
                        }
                        isRecording = true
                    } else {
                        recording?.stop()
                        recording = null
                        isRecording = false
                    }
                }
        )
    }
}


@Composable
fun VideoPreviewScreen(
    videoUri: Uri,
    onDiscard: () -> Unit,
    onSend: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Play the recorded video
        AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    setVideoURI(videoUri)
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        start()
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                .clickable { onDiscard() },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = Color.White, style = MaterialTheme.typography.titleLarge)
        }

        Button(
            onClick = onSend,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
        ) {
            Text("Send")
        }
    }
}
