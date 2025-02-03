package com.example.arindoornavigation

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.arindoornavigation.ui.theme.ArIndoorNavigationTheme
import com.google.ar.core.Pose
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.FrameTime

class MainActivity : AppCompatActivity() { // AppCompatActivity kullanılarak supportFragmentManager erişilebilir hale gelir.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArIndoorNavigationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ARNavigationScreen()
                }
            }
        }
    }

    /**
     * ARNavigationScreen composable'ı, kamera iznini kontrol eder.
     * İzin verildiyse AR fragment’ını içeren bir AndroidView oluşturur,
     * aksi halde hata mesajı gösterir.
     */
    @Composable
    fun ARNavigationScreen() {
        val context = LocalContext.current
        var hasCameraPermission by remember { mutableStateOf(false) }

        // Kamera iznini istemek için launcher oluşturuyoruz.
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasCameraPermission = granted
        }

        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                hasCameraPermission = true
            }
        }

        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        id = View.generateViewId()
                        // Burada ctx'nin bir AppCompatActivity olduğundan emin oluyoruz.
                        (ctx as AppCompatActivity).supportFragmentManager.beginTransaction()
                            .replace(id, CustomARFragment())
                            .commit()
                    }
                }
            )
        } else {
            Text(text = "Kamera izni gerekli!")
        }
    }

    /**
     * CustomARFragment: ArFragment'dan türetilmiş olup,
     * ARCore sahnesine yön oklarını yerleştirir.
     */
    class CustomARFragment : ArFragment() {
        private var arrowsPlaced = false

        override fun onUpdate(frameTime: FrameTime?) {
            super.onUpdate(frameTime)
            if (!arrowsPlaced) {
                placeArrows()
                arrowsPlaced = true
            }
        }

        /**
         * placeArrows(): ARCore kamerasının pozisyonuna göre hesaplanmış
         * noktalara (örneğin; başlangıç, dönüş, varış) ok modelini yerleştirir.
         */
        private fun placeArrows() {
            val frame = arSceneView.arFrame ?: return
            val cameraPose = frame.camera.pose

            // Manuel olarak belirlenen yön noktaları (kamera referansına göre).
            val arrowPositions = listOf(
                floatArrayOf(0f, 0f, -1f),  // 1 metre önde
                floatArrayOf(1f, 0f, -2f),  // Sağa dönüş
                floatArrayOf(1f, 0f, -3f)   // Varış noktasına doğru
            )

            ModelRenderable.builder()
                .setSource(requireContext(), Uri.parse("arrow.sfb"))
                .build()
                .thenAccept { renderable ->
                    arrowPositions.forEach { pos ->
                        val translationPose = Pose.makeTranslation(pos[0], pos[1], pos[2])
                        val worldPose = cameraPose.compose(translationPose)
                        val anchor = arSceneView.session?.createAnchor(worldPose)
                        if (anchor != null) {
                            val anchorNode = AnchorNode(anchor)
                            anchorNode.setParent(arSceneView.scene)
                            Node().apply {
                                this.renderable = renderable
                                setParent(anchorNode)
                                localRotation = Quaternion.axisAngle(Vector3(0f, 1f, 0f), 0f)
                            }
                        }
                    }
                }
                .exceptionally { throwable ->
                    throwable.printStackTrace()
                    null
                }
        }

        override fun getSessionConfiguration(session: com.google.ar.core.Session?): com.google.ar.core.Config {
            val config = super.getSessionConfiguration(session)
            return config
        }
    }
}
