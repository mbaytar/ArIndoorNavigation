package com.example.arindoornavigation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
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
     * - İzin verildiyse, AndroidView içinde ARFragment (CustomARFragment) eklenir.
     *   (ARFragment, zemini tespit edip rota marker’larını yerleştirecek.)
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

    /**
     * CustomARFragment:
     * - ArFragment'dan türetilmiştir.
     * - ARCore'un plane detection özelliğini kullanarak yatay (zemin) düzlemi tespit eder.
     * - İlk TRACKING durumundaki horizontal plane bulunduğunda, rota marker’larını yerleştirir.
     * - Rota: 2 m düz, sonra sağa dönüş, 3 m düz, sonra tekrar sağa dönüş, sonra 1 m düz (varış).
     * - Marker’lar, her segment boyunca 20 cm aralıkla yerleştirilecek; varış noktasında büyük kırmızı marker kullanılacak.
     */
    class CustomARFragment : ArFragment() {

        companion object {
            private const val TAG = "kars"
        }

        // Marker’ların yalnızca bir kez yerleştirilmesini sağlamak için bayrak.
        private var markersPlaced = false

        override fun onUpdate(frameTime: FrameTime?) {
            super.onUpdate(frameTime)
            // Tespit edilen tüm düzlemleri (Plane) alıyoruz.
            val planes = arSceneView.session?.getAllTrackables(Plane::class.java)
            if (!markersPlaced && planes != null) {
                for (plane in planes) {
                    if (plane.trackingState == TrackingState.TRACKING &&
                        plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                        Log.d(TAG, "kars: Floor detected. Plane id: ${plane.hashCode()}")
                        placeRouteMarkers(plane)
                        markersPlaced = true
                        break
                    }
                }
            }
        }

        /**
         * placeRouteMarkers:
         * - Seçilen horizontal plane'in merkezini referans alarak rota noktalarını yerleştirir.
         * - Rota noktaları, plane'in lokal koordinat sisteminde tanımlanır.
         */
        private fun placeRouteMarkers(plane: Plane) {
            // Plane'in merkez pose'unu referans olarak alıyoruz.
            val planePose = plane.centerPose
            Log.d(TAG, "kars: Plane center pose: tx=${planePose.tx()}, ty=${planePose.ty()}, tz=${planePose.tz()}")

            // Yeni rota: [ (0,0,0), (0,0,-2), (1,0,-2), (1,0,-5), (2,0,-5), (2,0,-6) ]
            val newRoutePoints = listOf(
                Vector3(0f, 0f, 0f),    // Başlangıç
                Vector3(0f, 0f, -2f),   // 2 m düz
                Vector3(1f, 0f, -2f),   // İlk sağa dönüş: 1 m sağa
                Vector3(1f, 0f, -5f),   // 3 m düz
                Vector3(2f, 0f, -5f),   // İkinci sağa dönüş: 1 m sağa
                Vector3(2f, 0f, -6f)    // 1 m düz (varış)
            )

            // Marker yerleştirme aralığı: 20 cm (0.2 m)
            val spacing = 0.2f

            // Her segment için, iki rota noktası arasındaki mesafeye göre 20 cm aralıkla marker'lar hesaplanır.
            val markers = mutableListOf<Vector3>()
            for (i in 0 until newRoutePoints.size - 1) {
                val start = newRoutePoints[i]
                val end = newRoutePoints[i + 1]
                val segmentVector = Vector3.subtract(end, start)
                val segmentLength = segmentVector.length()
                val count = (segmentLength / spacing).toInt()
                val step = Vector3(
                    segmentVector.x / count,
                    segmentVector.y / count,
                    segmentVector.z / count
                )
                for (j in 0..count) {
                    val markerPoint = Vector3(
                        start.x + step.x * j,
                        start.y + step.y * j,
                        start.z + step.z * j
                    )
                    markers.add(markerPoint)
                }
            }

            // Marker renderable'ları oluşturuluyor:
            // Küçük marker: mavi renkli, yarıçap 0.02 m
            MaterialFactory.makeOpaqueWithColor(requireContext(), com.google.ar.sceneform.rendering.Color(AndroidColor.BLUE))
                .thenAccept { blueMaterial ->
                    val smallMarkerRenderable = ShapeFactory.makeSphere(0.02f, Vector3.zero(), blueMaterial)
                    // Varış marker'ı: kırmızı renkli, yarıçap 0.05 m
                    MaterialFactory.makeOpaqueWithColor(requireContext(), com.google.ar.sceneform.rendering.Color(AndroidColor.RED))
                        .thenAccept { redMaterial ->
                            val finishMarkerRenderable = ShapeFactory.makeSphere(0.05f, Vector3.zero(), redMaterial)
                            // Her marker için:
                            for ((index, point) in markers.withIndex()) {
                                // Her rota noktası için, plane'in pose'una göre dünya pozisyonu hesaplanır.
                                val relativePose = Pose.makeTranslation(point.x, point.y, point.z)
                                val worldPose = planePose.compose(relativePose)
                                // Anchor'ı plane üzerinden oluşturuyoruz.
                                val anchor = plane.createAnchor(worldPose)
                                Log.d(TAG, "kars: Placing marker at world pose: (${worldPose.tx()}, ${worldPose.ty()}, ${worldPose.tz()}) for marker index $index")
                                val anchorNode = AnchorNode(anchor)
                                anchorNode.setParent(arSceneView.scene)
                                val markerNode = com.google.ar.sceneform.Node()
                                markerNode.setParent(anchorNode)
                                // Eğer bu, marker listesinin son noktası ise (varış) kırmızı marker, değilse mavi marker.
                                if (index == markers.size - 1) {
                                    markerNode.renderable = finishMarkerRenderable
                                } else {
                                    markerNode.renderable = smallMarkerRenderable
                                }
                            }
                            Log.d(TAG, "kars: All route markers placed")
                        }
                        .exceptionally { throwable ->
                            Log.e(TAG, "kars: Error creating finish marker renderable", throwable)
                            null
                        }
                }
                .exceptionally { throwable ->
                    Log.e(TAG, "kars: Error creating marker renderable", throwable)
                    null
                }
        }
    }
}
