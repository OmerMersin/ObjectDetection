package com.example.objectdetection

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.graphics.Paint
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.objectdetection.ui.theme.ObjectDetectionTheme
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
// Alias imports to avoid conflicts
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide the status bar and navigation bar
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN          // Hides the status bar
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION  // Hides the navigation bar
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY // Hides both and prevents UI from showing
                )
        setContent {
            ObjectDetectionTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    RTSPVideoPlayer(
                        videoUrl = "rtsp://192.168.10.78:8554/mystream",  // Update with your RTSP stream URL
                        onObjectSelected = { x, y, actionType ->
                            sendCoordinatesToServer(x, y, actionType)
                        }
                    )
                }
            }
        }
    }

    private fun sendCoordinatesToServer(x: Float, y: Float, actionType: String) {
        val client = OkHttpClient()
        val json = JSONObject().apply {
            put("x", x)
            put("y", y)
            put("action", actionType)
        }
        val requestBody = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("http://192.168.10.78:5000/select_object")  // Update with your endpoint
            .post(requestBody)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    println("Coordinates sent successfully: $x, $y")
                    println("Response: ${response.body?.string()}")
                } else {
                    println("Failed to send coordinates: ${response.code} ${response.message}")
                }
            } catch (e: Exception) {
                println("Error sending coordinates: ${e.message}")
            }
        }
    }
}

@Composable
fun RTSPVideoPlayer(videoUrl: String, onObjectSelected: (Float, Float, String) -> Unit) {
    val context = LocalContext.current

    // State variables for touch events and floating window
    var startX by remember { mutableStateOf(0f) }
    var startY by remember { mutableStateOf(0f) }
    var currentX by remember { mutableStateOf(0f) }
    var currentY by remember { mutableStateOf(0f) }
    var isDrawing by remember { mutableStateOf(false) }
    var showFloatingWindow by remember { mutableStateOf(false) }
    var selectedX by remember { mutableStateOf(0f) }
    var selectedY by remember { mutableStateOf(0f) }
    var selectedActionType by remember { mutableStateOf<String?>(null) }

    // Player state
    val player = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1500,   // Minimum buffer before playback starts (in ms)
                5000,   // Maximum buffer size (in ms)
                500,    // Buffer size to keep playback running smoothly (in ms)
                250     // Buffer size to resume playback after a stall (in ms)
            )
            .build()

        SimpleExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
            }
    }


    var retryCount by remember { mutableStateOf(0) }
    var playerError by remember { mutableStateOf<PlaybackException?>(null) }

    // Manage player lifecycle
    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    // Set up the media item
    LaunchedEffect(videoUrl) {
        val mediaItem = MediaItem.Builder()
            .setUri(videoUrl)
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
    }

    // Add player listener for errors and playback state changes
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playerError = error
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    // Reset retry count when playback is ready
                    retryCount = 0
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    // Handle retries on player errors
    LaunchedEffect(playerError) {
        if (playerError != null) {
            retryCount++
            val delayTime = (minOf(retryCount, 10) * 2000L) // Cap delay at 20 seconds
            println("Player error: ${playerError?.message}, retrying in $delayTime ms")
            player.playWhenReady = false
            delay(delayTime)
            player.seekToDefaultPosition()
            player.prepare()
            player.playWhenReady = true
            playerError = null // Reset error
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // RTSP Video Player using ExoPlayer
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    this.resizeMode = com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    useController = false  // Disable the default controls
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Transparent overlay for touch events and rectangle drawing
        AndroidView(
            factory = { ctx ->
                object : View(ctx) {
                    override fun onDraw(canvas: Canvas) {
                        super.onDraw(canvas)
                        if (isDrawing) {
                            val fillPaint = Paint().apply {
                                color = AndroidColor.argb(128, 0, 255, 0) // Semi-transparent green
                                style = Paint.Style.FILL
                                isAntiAlias = true
                            }
                            val strokePaint = Paint().apply {
                                color = AndroidColor.argb(128, 0, 255, 0) // Semi-transparent green
                                style = Paint.Style.STROKE
                                strokeWidth = 10f
                                isAntiAlias = true
                            }
                            val left = min(startX, currentX)
                            val top = min(startY, currentY)
                            val right = max(startX, currentX)
                            val bottom = max(startY, currentY)

                            val cornerRadius = 30f // Adjust the corner radius

                            // Draw a rounded rectangle with filled background
                            canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, fillPaint)

                            // Draw the arc on the four corners
                            canvas.drawArc(
                                left - cornerRadius, top - cornerRadius,
                                left + cornerRadius, top + cornerRadius,
                                180f, 90f, false, strokePaint
                            )
                            canvas.drawArc(
                                right - cornerRadius, top - cornerRadius,
                                right + cornerRadius, top + cornerRadius,
                                270f, 90f, false, strokePaint
                            )
                            canvas.drawArc(
                                left - cornerRadius, bottom - cornerRadius,
                                left + cornerRadius, bottom + cornerRadius,
                                90f, 90f, false, strokePaint
                            )
                            canvas.drawArc(
                                right - cornerRadius, bottom - cornerRadius,
                                right + cornerRadius, bottom + cornerRadius,
                                0f, 90f, false, strokePaint
                            )
                        }
                    }
                }.apply {
                    setOnTouchListener { view, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                startX = event.x
                                startY = event.y
                                currentX = startX
                                currentY = startY
                                isDrawing = true
                                invalidate()
                            }
                            MotionEvent.ACTION_MOVE -> {
                                currentX = event.x
                                currentY = event.y
                                invalidate()
                            }
                            MotionEvent.ACTION_UP -> {
                                isDrawing = false
                                val centerX = (startX + currentX) / 2
                                val centerY = (startY + currentY) / 2

                                // Map screen coordinates to video coordinates
                                val viewWidth = view.width.toFloat()
                                val viewHeight = view.height.toFloat()
                                val videoWidth = 640f
                                val videoHeight = 480f

                                val videoX = (centerX / viewWidth) * videoWidth
                                val videoY = (centerY / viewHeight) * videoHeight

                                selectedX = videoX
                                selectedY = videoY

                                showFloatingWindow = true
                                invalidate()
                            }
                        }
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Floating window with buttons
        if (showFloatingWindow) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor.Transparent)
                    .clickable(
                        onClick = {
                            if (selectedActionType != null) {
                                // Abort the action
                                selectedActionType = null
                            } else {
                                // Hide the floating window
                                showFloatingWindow = false
                            }
                        },
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .clickable(
                            onClick = {},
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        )
                        .background(ComposeColor.Black.copy(alpha = 0.7f))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (selectedActionType == null) {
                        // Show the action buttons
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            val buttonWidth = 150.dp // Set desired width for all buttons

                            ActionButton(
                                icon = Icons.Default.DirectionsRun,
                                text = "Active Track",
                                width = buttonWidth,
                                onClick = {
                                    selectedActionType = "active_track"
                                }
                            )
                            ActionButton(
                                icon = Icons.Default.Highlight,
                                text = "Spotlight",
                                width = buttonWidth,
                                onClick = {
                                    selectedActionType = "spotlight"
                                }
                            )
                            ActionButton(
                                icon = Icons.Default.Place,
                                text = "ROI",
                                width = buttonWidth,
                                onClick = {
                                    selectedActionType = "roi"
                                }
                            )
                        }
                    } else {
                        // Show the "Go" button and confirmation message
                        Text(
                            text = "Proceed with ${selectedActionType!!.replace('_', ' ').capitalize()}?",
                            color = ComposeColor.White,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        Button(
                            onClick = {
                                // Send action to server
                                onObjectSelected(selectedX, selectedY, selectedActionType!!)
                                // Reset states
                                selectedActionType = null
                                showFloatingWindow = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.Gray),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(text = "Go", color = ComposeColor.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActionButton(icon: ImageVector, text: String, onClick: () -> Unit, width: Dp) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.Gray),
        shape = RoundedCornerShape(4.dp), // Set the desired corner radius here
        modifier = Modifier.width(width) // Set fixed width for each button
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = text, tint = androidx.compose.ui.graphics.Color.White)
            Text(text = text, color = androidx.compose.ui.graphics.Color.White)
        }
    }
}