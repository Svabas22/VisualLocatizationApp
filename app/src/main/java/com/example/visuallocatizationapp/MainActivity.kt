package com.example.visuallocatizationapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.visuallocatizationapp.network.ApiClient
import com.example.visuallocatizationapp.storage.ZoneStorage
import com.example.visuallocatizationapp.ui.theme.VisualLocatizationAppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VisualLocatizationAppTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Available Zones",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp)
                )
                Divider()
                ZoneListDrawer(
                    onZoneSelected = { scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Visual Localization") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)) {
                CameraRecorderScreen()
            }
        }
    }
}

@Composable
fun CameraRecorderScreen() {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions[Manifest.permission.CAMERA] == true &&
                permissions[Manifest.permission.RECORD_AUDIO] == true
    }

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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "This app needs camera and microphone permissions to record video.",
                    color = Color.White
                )
            }
        }

        videoUri == null -> {
            CameraRecordView(onVideoRecorded = { uri -> videoUri = uri })
        }

        else -> {
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
                val previewView = PreviewView(ctx)
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
                .background(if (isRecording) Color.Red else Color.White, shape = CircleShape)
                .clickable {
                    val capture = videoCapture ?: return@clickable
                    if (!isRecording) {
                        val file = File(
                            context.externalCacheDir,
                            "VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
                        )
                        val outputOptions = FileOutputOptions.Builder(file).build()
                        val pendingRecording = capture.output.prepareRecording(context, outputOptions)
                            .withAudioEnabled()
                        recording = pendingRecording.start(
                            ContextCompat.getMainExecutor(context)
                        ) { recordEvent ->
                            when (recordEvent) {
                                is VideoRecordEvent.Finalize -> {
                                    if (!recordEvent.hasError()) {
                                        onVideoRecorded(Uri.fromFile(file))
                                    }
                                }
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
    val context = LocalContext.current
    var isUploading by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
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
            Text("✕", color = Color.White)
        }

        Button(
            onClick = {
                if (!isUploading) {
                    isUploading = true
                    uploadVideo(context, videoUri) { _, _, _ ->
                        isUploading = false
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
        ) {
            Text(if (isUploading) "Uploading..." else "Send")
        }
    }
}

fun uploadVideo(
    context: Context,
    videoUri: Uri,
    onResult: (Double, Double, Double) -> Unit
) {
    val file = videoUri.toFile()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val requestFile = file.asRequestBody("video/mp4".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("video", file.name, requestFile)
            val response = ApiClient.instance.uploadVideo(body)
            if (response.isSuccessful) {
                val bodyResult = response.body()
                if (bodyResult != null) {
                    withContext(Dispatchers.Main) {
                        onResult(bodyResult.latitude, bodyResult.longitude, bodyResult.confidence)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Upload", "Upload failed", e)
        }
    }
}
