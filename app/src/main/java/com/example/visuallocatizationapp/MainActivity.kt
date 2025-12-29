package com.example.visuallocatizationapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.visuallocatizationapp.ui.theme.VisualLocatizationAppTheme
import com.example.visuallocatizationapp.model.LoadedModel
import com.example.visuallocatizationapp.model.ModelLoader
import com.example.visuallocatizationapp.model.OnnxLocalizationModel
import com.example.visuallocatizationapp.model.PredictionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
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
    var selectedZone by remember { mutableStateOf<Zone?>(null) }

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
                    selectedZoneId = selectedZone?.id,
                    onZoneSelected = { zone ->
                        selectedZone = zone
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Visual Localization" +
                                    (selectedZone?.let { " (${it.name})" } ?: "")
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                CameraRecorderScreen(selectedZone)
            }
        }
    }
}

@Composable
fun CameraRecorderScreen(selectedZone: Zone?) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions[Manifest.permission.CAMERA] == true
    }

    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var extractedFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }

    val pickVideoLauncher = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val localUri = copyUriToCache(context, uri)
                val frames = extractFramesFromVideo(context, localUri, frameCount = 30)
                withContext(Dispatchers.Main) {
                    videoUri = localUri
                    extractedFrames = frames
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val cameraGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        hasPermissions = cameraGranted

        if (!hasPermissions) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    when {
        !hasPermissions -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "This app needs camera permissions to record video.",
                    color = Color.White
                )
            }
        }

        videoUri == null -> {
            Box(Modifier.fillMaxSize()) {
                CameraRecordView(onVideoRecorded = { uri ->
                    videoUri = uri
                    CoroutineScope(Dispatchers.IO).launch {
                        val frames = extractFramesFromVideo(context, uri, frameCount = 30)
                        withContext(Dispatchers.Main) {
                            extractedFrames = frames
                        }
                        deleteVideoFile(uri)
                    }
                })

                Button(
                    onClick = { pickVideoLauncher.launch("video/*") },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Text("Upload video")
                }
            }
        }

        extractedFrames.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                Text(
                    text = "Extracting frames...",
                    color = Color.White,
                    modifier = Modifier.padding(top = 80.dp)
                )
            }
        }

        else -> {
            FramePlaybackScreen(
                frames = extractedFrames,
                videoUri = videoUri!!,
                selectedZone = selectedZone,
                onDiscard = {
                    deleteVideoFile(videoUri!!)
                    extractedFrames.forEach { it.recycle() }
                    videoUri = null
                    extractedFrames = emptyList()
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
fun FramePlaybackScreen(
    frames: List<Bitmap>,
    videoUri: Uri,
    selectedZone: Zone?,
    onDiscard: () -> Unit
) {
    val context = LocalContext.current
    var currentFrameIndex by remember { mutableStateOf(0) }
    var isProcessing by remember { mutableStateOf(false) }
    var locationData by remember { mutableStateOf<Triple<Double, Double, Double>?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var loadedModel by remember { mutableStateOf<LoadedModel?>(null) }
    var modelStatus by remember { mutableStateOf("Model not loaded") }

    LaunchedEffect(frames) {
        if (frames.isNotEmpty()) {
            while (isActive) {
                delay(100)
                currentFrameIndex = (currentFrameIndex + 1) % frames.size
            }
        }
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(2000)
            statusMessage = null
        }
    }

    LaunchedEffect(selectedZone) {
        if (selectedZone != null) {
            val lm = ModelLoader.load(context, selectedZone)
            loadedModel = lm
            modelStatus = lm?.let { "Model: ${it.info.id} v${it.info.version} (${it.info.engine})" }
                ?: "Model not available for this zone"
        } else {
            loadedModel = null
            modelStatus = "No zone selected"
        }
    }

    locationData?.let { (lat, lon, _) ->
        if (selectedZone != null) {
            MapOfflineScreen(
                zone = selectedZone,
                latitude = lat,
                longitude = lon,
                onBack = { locationData = null }
            )
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (frames.isNotEmpty()) {
            Image(
                bitmap = frames[currentFrameIndex].asImageBitmap(),
                contentDescription = "Frame $currentFrameIndex",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        LazyRow(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(100.dp)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(8.dp)
        ) {
            items(frames) { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Thumbnail",
                    modifier = Modifier
                        .size(80.dp)
                        .padding(4.dp),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                .clickable { onDiscard() },
            contentAlignment = Alignment.Center
        ) {
            Text("X", color = Color.White)
        }

        val canSend = selectedZone != null && !isProcessing

        Button(
            onClick = {
                if (!isProcessing) {
                    if (selectedZone == null) {
                        statusMessage = "Please select a zone first."
                        return@Button
                    }

                    isProcessing = true
                    CoroutineScope(Dispatchers.Main).launch {
                        val result: PredictionResult = if (loadedModel != null) {
                            val keyFrames = frames.take(8)
                            OnnxLocalizationModel(loadedModel!!).predict(keyFrames, selectedZone)
                        } else {
                            // Fallback to legacy fake coords
                            PredictionResult(54.903, 23.959, 0.9)
                        }

                        Log.d(
                            "Localization",
                            "Raw prediction: lat=${result.latitude}, lon=${result.longitude}, conf=${result.confidence}; " +
                                    "zone=${selectedZone.name}, bounds=[${selectedZone.bounds.minLat},${selectedZone.bounds.maxLat}]x[${selectedZone.bounds.minLon},${selectedZone.bounds.maxLon}]"
                        )

                        if (selectedZone.contains(result.latitude, result.longitude)) {
                            Log.d("Localization", "Predicted coords: ${result.latitude}, ${result.longitude} in zone ${selectedZone.name}")
                            locationData = Triple(result.latitude, result.longitude, result.confidence)
                        } else {
                            statusMessage = "Prediction outside selected zone."
                        }

                        isProcessing = false
                    }
                }
            },
            enabled = canSend,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Text(if (isProcessing) "Processing..." else "Send")
        }

        Text(
            text = "Frame ${currentFrameIndex + 1}/${frames.size}" +
                    (selectedZone?.let { "\nZone: ${it.name}" } ?: "\nNo zone selected"),
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Text(
            text = modelStatus,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
                .background(Color.Black.copy(alpha = 0.6f), shape = CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )

        statusMessage?.let { msg ->
            Text(
                text = msg,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp)
                    .background(Color.Black.copy(alpha = 0.7f), shape = CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

fun extractFramesFromVideo(
    context: Context,
    videoUri: Uri,
    frameCount: Int = 12,
    maxSize: Int = 320
): List<Bitmap> {
    val retriever = MediaMetadataRetriever()
    val frames = mutableListOf<Bitmap>()
    try {
        retriever.setDataSource(context, videoUri)
        val duration =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: 0L
        val interval = if (frameCount > 0) duration / frameCount else duration

        for (i in 0 until frameCount) {
            val timeUs = (i * interval) * 1000
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let {
                val scaled = if (it.width > maxSize || it.height > maxSize) {
                    val scale = minOf(maxSize.toFloat() / it.width, maxSize.toFloat() / it.height)
                    Bitmap.createScaledBitmap(
                        it,
                        (it.width * scale).toInt(),
                        (it.height * scale).toInt(),
                        true
                    ).also { scaledBmp -> it.recycle() }
                } else {
                    it
                }
                frames.add(scaled)
            }
        }
    } catch (e: Exception) {
        Log.e("FrameExtractor", "Failed to extract frames", e)
    } finally {
        retriever.release()
    }
    return frames
}

fun deleteVideoFile(videoUri: Uri) {
    if (videoUri.scheme == "file") {
        val path = videoUri.path ?: return
        runCatching { File(path).delete() }
    }
}

fun copyUriToCache(context: Context, uri: Uri): Uri {
    val input: InputStream = context.contentResolver.openInputStream(uri) ?: return uri
    val outFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}.mp4")
    input.use { inp ->
        outFile.outputStream().use { out ->
            inp.copyTo(out)
        }
    }
    return Uri.fromFile(outFile)
}
