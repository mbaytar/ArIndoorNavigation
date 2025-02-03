package com.example.arindoornavigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.arindoornavigation.ui.theme.ArIndoorNavigationTheme
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.ar.core.Anchor
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "kars"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "kars: onCreate started")
        enableEdgeToEdge()
        setContent {
            ArIndoorNavigationTheme {
                Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    ARNavigationScreen()
                }
            }
        }
        Log.d(TAG, "kars: onCreate finished")
    }

    /**
     * ARNavigationScreen:
     * - Kamera iznini kontrol eder.
     * - İzin verildiyse, AndroidView içinde CustomARFragment eklenir.
     * - İzin yoksa hata mesajı gösterilir.
     */
    @Composable
    fun ARNavigationScreen() {
        val context = androidx.compose.ui.platform.LocalContext.current
        var hasCameraPermission by remember { mutableStateOf(false) }
        Log.d(TAG, "kars: ARNavigationScreen started")

        // Kamera izni istemek için launcher
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            Log.d(TAG, "kars: Permission result: $granted")
            hasCameraPermission = granted
        }

        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "kars: Camera permission not granted. Requesting permission.")
                permissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                Log.d(TAG, "kars: Camera permission already granted.")
                hasCameraPermission = true
            }
        }

        if (hasCameraPermission) {
            Log.d(TAG, "kars: Displaying AR content")
            Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            id = View.generateViewId()
                            // CustomARFragment ekleniyor.
                            (ctx as AppCompatActivity).supportFragmentManager.beginTransaction()
                                .replace(id, CustomARFragment())
                                .commit()
                            Log.d(TAG, "kars: AR fragment transaction committed")
                        }
                    }
                )
            }
        } else {
            Log.d(TAG, "kars: Camera permission not available, showing error message")
            Text(text = "Kamera izni gerekli!")
        }
    }
}
