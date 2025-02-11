package com.example.arindoornavigation

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import kotlin.math.cos
import kotlin.math.sin

// Eğer Vector3 için operator overloading tanımlı değilse, aşağıdaki extensionları ekleyin.
operator fun Vector3.plus(other: Vector3): Vector3 =
    Vector3(this.x + other.x, this.y + other.y, this.z + other.z)

operator fun Vector3.minus(other: Vector3): Vector3 =
    Vector3(this.x - other.x, this.y - other.y, this.z - other.z)

/**
 * Belirli bir açıda vektörü döndürmek için yardımcı fonksiyon.
 * Açı, derece cinsindendir. (Pozitif değer saat yönünde, negatif saat tersi)
 */
fun rotateVector(v: Vector3, angleDegrees: Float): Vector3 {
    val angleRad = Math.toRadians(angleDegrees.toDouble())
    val cos = cos(angleRad).toFloat()
    val sin = sin(angleRad).toFloat()
    // x ve z düzlemi üzerinde dönüş
    return Vector3(
        v.x * cos - v.z * sin,
        0f,
        v.x * sin + v.z * cos
    )
}

/**
 * CustomARFragment artık rota adımlarını argüman olarak alabilir.
 */
class CustomARFragment : ArFragment() {

    companion object {
        private const val TAG = "kars"
        private const val ARG_ROUTE_STEPS = "route_steps"

        // Yeni instance oluşturmak için factory method.
        fun newInstance(routeSteps: ArrayList<RouteStep>): CustomARFragment {
            val fragment = CustomARFragment()
            val bundle = Bundle()
            bundle.putSerializable(ARG_ROUTE_STEPS, routeSteps)
            fragment.arguments = bundle
            return fragment
        }
    }

    // Başlangıç noktası seçildikten sonra saklanacak anchor.
    private var startingAnchor: Anchor? = null
    // Zeminin tespit edildiğini ve ekrana dokunulması gerektiğini bildiren toast mesajının yalnızca bir kere gösterilmesini sağlar.
    private var tapToastShown = false

    // Rota adımlarını tutacak değişken.
    private var routeSteps: List<RouteStep>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Argümanlardan rota adımlarını alıyoruz.
        @Suppress("UNCHECKED_CAST")
        routeSteps = arguments?.getSerializable(ARG_ROUTE_STEPS) as? List<RouteStep>
        Log.d(TAG, "Rota adımları alındı: $routeSteps")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Zemini tespit ettikten sonra, kullanıcı ekrana dokunarak başlangıç noktasını seçsin.
        setOnTapArPlaneListener { hitResult, plane, motionEvent ->
            if (startingAnchor == null) {
                startingAnchor = hitResult.createAnchor()
                Toast.makeText(requireContext(), "Başlangıç noktası seçildi", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Başlangıç noktası seçildi")
                // Rota adımlarına göre marker’ları yerleştir.
                placeRouteMarkersStartingAt(startingAnchor!!)
            }
        }
    }

    override fun onUpdate(frameTime: FrameTime?) {
        super.onUpdate(frameTime)
        // Eğer henüz başlangıç noktası seçilmediyse ve uygun bir yatay plane tespit edildiyse, kullanıcıya dokunması gerektiğini bildir.
        if (startingAnchor == null) {
            val planes = arSceneView.session?.getAllTrackables(Plane::class.java)
            if (planes != null && planes.any { it.trackingState == com.google.ar.core.TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }) {
                if (!tapToastShown) {
                    Toast.makeText(requireContext(), "Başlangıç noktasını seçmek için ekrana dokunun", Toast.LENGTH_LONG).show()
                    Log.d(TAG, "Dokunma mesajı gösterildi")
                    tapToastShown = true
                }
            }
        }
    }

    /**
     * placeRouteMarkersStartingAt:
     * - Seçilen başlangıç noktasını (anchor) referans alarak, rota adımlarını kullanır.
     * - İlk adımda, kameranın ileri vektörü (forward) mevcut yön olarak alınır.
     * - Her adımda, yön bilgisine göre mevcut yön güncellenir (örneğin; sağa dönmek için 90° döndürülür)
     * - Güncellenen yön ile belirtilen mesafe kadar ilerlenip yeni nokta hesaplanır.
     * - Her segment arasında 20 cm aralıkla marker noktaları hesaplanır.
     * - Varış noktasında büyük kırmızı, diğer marker'lar için küçük mavi marker kullanılır.
     */
    private fun placeRouteMarkersStartingAt(anchor: Anchor) {
        val startPose = anchor.pose
        val S = Vector3(startPose.tx(), startPose.ty(), startPose.tz())
        Log.d(TAG, "Başlangıç pozisyonu: $S")

        // Kameranın anlık pozunu alıyoruz.
        val camPose = arSceneView.arFrame?.camera?.pose
        if (camPose == null) {
            Log.d(TAG, "Kamera pozisyonu null, rota hesaplanamıyor.")
            return
        }
        // ARCore’da kameranın -z ekseni ileri yönü temsil eder.
        val camZ = camPose.zAxis  // float[3]
        val forward = Vector3(-camZ[0], 0f, -camZ[2]).normalized()
        Log.d(TAG, "İlk ileri vektör: $forward")

        if (routeSteps.isNullOrEmpty()) {
            Log.d(TAG, "Rota adımı yok, marker yerleştirme atlanıyor.")
            return
        }

        // Başlangıç noktasından başlayarak her adım için son noktayı hesaplıyoruz.
        val points = mutableListOf<Vector3>()
        points.add(S)
        var currentPoint = S
        // Mevcut yönü, ilk başta kameranın ileri vektörü olarak belirliyoruz.
        var currentHeading = forward

        for (step in routeSteps!!) {
            // Yön bilgisine göre mevcut yönü güncelleyelim.
            when (step.direction) {
                "ileri" -> {
                    // Hiçbir dönüş yok, mevcut yön korunur.
                }
                "geri" -> {
                    // 180° dönüş (ters yönde ilerleme)
                    currentHeading = currentHeading.scaled(-1f)
                }
                "sağ" -> {
                    // Saat yönünde 90° dönüş (sağa dön)
                    currentHeading = rotateVector(currentHeading, 90f)
                }
                "sol" -> {
                    // Saat tersi 90° dönüş (sola dön)
                    currentHeading = rotateVector(currentHeading, -90f)
                }
                else -> {
                    Log.d(TAG, "Bilinmeyen yön '${step.direction}' atlandı.")
                }
            }
            // Güncellenen yön ile belirtilen mesafe kadar ilerleyelim.
            val offset = currentHeading.scaled(step.distance)
            currentPoint += offset
            points.add(currentPoint)
        }
        Log.d(TAG, "Hesaplanan rota noktaları: $points")

        // Marker aralığı: 20 cm
        val spacing = 0.2f
        fun interpolatePoints(start: Vector3, end: Vector3): List<Vector3> {
            val diff = Vector3(end.x - start.x, end.y - start.y, end.z - start.z)
            val dist = diff.length()
            val count = (dist / spacing).toInt().coerceAtLeast(1)
            val pointsList = mutableListOf<Vector3>()
            for (i in 0..count) {
                val t = i.toFloat() / count.toFloat()
                val interp = Vector3(
                    start.x + t * (end.x - start.x),
                    start.y + t * (end.y - start.y),
                    start.z + t * (end.z - start.z)
                )
                pointsList.add(interp)
            }
            return pointsList
        }

        // Tüm segmentler için marker pozisyonlarını hesapla.
        val markersPositions = mutableListOf<Vector3>()
        for (i in 0 until points.size - 1) {
            markersPositions.addAll(interpolatePoints(points[i], points[i + 1]))
        }

        // Marker renderable'ları oluşturuluyor:
        MaterialFactory.makeOpaqueWithColor(requireContext(), com.google.ar.sceneform.rendering.Color(android.graphics.Color.BLUE))
            .thenAccept { blueMaterial ->
                val smallMarkerRenderable = ShapeFactory.makeSphere(0.01f, Vector3.zero(), blueMaterial)
                MaterialFactory.makeOpaqueWithColor(requireContext(), com.google.ar.sceneform.rendering.Color(android.graphics.Color.RED))
                    .thenAccept { redMaterial ->
                        val finishMarkerRenderable = ShapeFactory.makeSphere(0.05f, Vector3.zero(), redMaterial)
                        for ((index, pos) in markersPositions.withIndex()) {
                            val markerPose = Pose.makeTranslation(pos.x, S.y, pos.z)
                            val markerAnchor = arSceneView.session?.createAnchor(markerPose)
                            if (markerAnchor == null) {
                                Log.d(TAG, "Marker için anchor oluşturulamadı, index: $index")
                                continue
                            }
                            val anchorNode = AnchorNode(markerAnchor)
                            anchorNode.setParent(arSceneView.scene)
                            val markerNode = Node()
                            markerNode.setParent(anchorNode)
                            markerNode.renderable = if (index == markersPositions.size - 1) finishMarkerRenderable else smallMarkerRenderable
                            Log.d(TAG, "Marker yerleştirildi: (${pos.x}, ${S.y}, ${pos.z}), index: $index")
                        }
                        Log.d(TAG, "Tüm rota marker'ları yerleştirildi")
                    }
                    .exceptionally { throwable ->
                        Log.e(TAG, "Varış marker'ı oluşturulurken hata", throwable)
                        null
                    }
            }
            .exceptionally { throwable ->
                Log.e(TAG, "Marker oluşturulurken hata", throwable)
                null
            }
    }
}
