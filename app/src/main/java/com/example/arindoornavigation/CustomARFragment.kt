package com.example.arindoornavigation

import android.net.Uri
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
import com.google.ar.sceneform.assets.RenderableSource
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import java.util.concurrent.CompletableFuture
import kotlin.math.atan2
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
    // x ve z düzlemi üzerinde dönüş (y sabit)
    return Vector3(
        v.x * cos - v.z * sin,
        0f,
        v.x * sin + v.z * cos
    )
}

/**
 * Finish marker için animasyon: Node kendi etrafında daha hızlı döner.
 */
class RotatingFinishNode : Node() {
    override fun onUpdate(frameTime: FrameTime?) {
        super.onUpdate(frameTime)
        val dt = frameTime?.deltaSeconds ?: 0f
        // Saniyede 60 derece dönecek şekilde
        val angle = 60f * dt
        val rotationY = Quaternion.axisAngle(Vector3(0f, 1f, 0f), angle)
        localRotation = Quaternion.multiply(rotationY, localRotation)
    }
}

/**
 * Arrow marker için animasyon: Node, kendi başlangıç pozisyonundan ileriye doğru daha hızlı kayar,
 * ardından geri döner. Aynı zamanda opaklık (alpha) yumuşak geçişle değişir.
 */
open class AnimatedArrowNode(val forwardDirection: Vector3) : Node() {
    private var elapsedTime = 0f
    private var initialPosition: Vector3? = null
    override fun onUpdate(frameTime: FrameTime?) {
        super.onUpdate(frameTime)
        val dt = frameTime?.deltaSeconds ?: 0f
        elapsedTime += dt
        if (initialPosition == null) {
            initialPosition = localPosition
        }
        // Animasyon döngüsünü 3 saniyeye indiriyoruz.
        val cycleDuration = 3f
        val progress = (elapsedTime % cycleDuration) / cycleDuration

        // İleri kayma mesafesini arttırdık: örneğin 0.15 metre
        val maxOffset = 0.15f
        val offsetDistance = when {
            progress < 0.5f -> progress * 2 * maxOffset  // ilk yarıda ileri kayar
            else -> (1f - progress) * 2 * maxOffset       // ikinci yarıda geri döner
        }
        localPosition = initialPosition!! + forwardDirection.scaled(offsetDistance)

        // Opaklık (alpha) yumuşak geçiş:
        // İlk %20: 0 -> 1, Son %20: 1 -> 0, arada tam opak.
        val alpha = when {
            progress < 0.2f -> progress / 0.2f
            progress > 0.8f -> (1f - progress) / 0.2f
            else -> 1f
        }
        // Materyaliniz alpha parametresini destekliyorsa; desteklemiyorsa bu kısmı kaldırabilirsiniz.
        renderable?.material?.setFloat("alpha", alpha)
    }
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
        // Global uncaught exception handler kuruluyor.
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
        }
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
                try {
                    startingAnchor = hitResult.createAnchor()
                    Toast.makeText(requireContext(), "Başlangıç noktası seçildi", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Başlangıç noktası seçildi")
                    // Rota adımlarına göre marker’ları yerleştir.
                    placeRouteMarkersStartingAt(startingAnchor!!)
                } catch (e: Exception) {
                    Log.e(TAG, "Anchor oluşturulurken hata", e)
                }
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
                    Toast.makeText(
                        requireContext(),
                        "Başlangıç noktasını seçmek için ekrana dokunun",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.d(TAG, "Dokunma mesajı gösterildi")
                    tapToastShown = true
                }
            }
        }
    }

    /**
     * placeRouteMarkersStartingAt:
     *
     * Rota adımlarını kullanarak, her segment için marker (konum ve heading) hesaplanır.
     * Her segment için:
     * - Başlangıç ve bitiş noktası arası interpolate edilen marker konumları
     * - Segmentin yönü (heading) saklanır.
     *
     * Dönüş içeren adımlarda (ileri dışındaki) önceki segmentin son marker'ını (yani dönüş öncesi oku)
     * listeden kaldırıyoruz; böylece dönüş anında önceki oku göstermemiş oluyoruz.
     *
     * Daha sonra marker'lar yerleştirilirken, eğer marker finish marker değilse (arrow marker)
     * ilgili segmentin heading'ine göre arrow döndürülür.
     *
     * Finish marker için ise default yön korunur.
     */
    private fun placeRouteMarkersStartingAt(anchor: Anchor) {
        try {
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
            Log.d(TAG, "İlk ileri vektör (kamera yönü): $forward")

            if (routeSteps.isNullOrEmpty()) {
                Log.d(TAG, "Rota adımı yok, marker yerleştirme atlanıyor.")
                return
            }

            // Marker verilerini (konum ve heading) tutacak liste
            val markerData = mutableListOf<Pair<Vector3, Vector3>>() // Pair(position, heading)
            // İlk nokta: başlangıç, heading başlangıçta kamera yönü
            var lastPoint = S
            var currentHeading = forward

            // Yardımcı fonksiyon: iki nokta arasını spacing kadar bölerek interpolate eden fonksiyon
            fun interpolatePoints(start: Vector3, end: Vector3): List<Vector3> {
                val diff = Vector3(end.x - start.x, end.y - start.y, end.z - start.z)
                val dist = diff.length()
                val spacing = 0.4f // Segment içinde marker arası mesafe
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

            // Rota adımlarını segmentlere ayırıp, her segmentin marker konumları ve heading bilgisini saklıyoruz.
            for (step in routeSteps!!) {
                when (step.direction) {
                    "ileri" -> {
                        // "ileri" durumunda yön, mevcut heading korunur.
                    }
                    "geri" -> {
                        // 180° dönüş: mevcut heading tersine çevrilir.
                        currentHeading = currentHeading.scaled(-1f)
                    }
                    "sağ" -> {
                        currentHeading = rotateVector(currentHeading, 90f)
                    }
                    "sol" -> {
                        currentHeading = rotateVector(currentHeading, -90f)
                    }
                    else -> {
                        Log.d(TAG, "Bilinmeyen yön '${step.direction}' atlandı.")
                    }
                }
                val newPoint = lastPoint + currentHeading.scaled(step.distance)
                // Bu segmentin marker konumlarını interpolate ediyoruz.
                val segmentMarkers = interpolatePoints(lastPoint, newPoint)
                // Eğer adım "ileri" değilse, önceki segmentin son marker'ını kaldırıyoruz
                // (yani dönüş öncesi oku göstermiyoruz)
                if (step.direction != "ileri" && markerData.isNotEmpty()) {
                    markerData.removeAt(markerData.lastIndex)
                }
                // Tüm segment marker'larını, mevcut heading ile eşleştirip ekliyoruz.
                markerData.addAll(segmentMarkers.map { it to currentHeading })
                lastPoint = newPoint
            }
            Log.d(TAG, "Hesaplanan marker verileri (konum, heading): $markerData")

            // Önce modelleri yükle
            val smallMarkerFuture = ModelRenderable.builder()
                .setSource(
                    requireContext(),
                    RenderableSource.builder().setSource(
                        requireContext(),
                        Uri.parse("file:///android_asset/arrow.glb"), // arrow.glb dosyanız
                        RenderableSource.SourceType.GLB
                    ).build()
                )
                .build()

            val finishMarkerFuture = ModelRenderable.builder()
                .setSource(
                    requireContext(),
                    RenderableSource.builder().setSource(
                        requireContext(),
                        Uri.parse("file:///android_asset/finish.glb"), // finish.glb dosyanız
                        RenderableSource.SourceType.GLB
                    ).build()
                )
                .build()

            // Her iki model de yüklendikten sonra marker’ları yerleştiriyoruz.
            CompletableFuture.allOf(smallMarkerFuture, finishMarkerFuture)
                .thenAccept {
                    try {
                        val smallMarkerRenderable = smallMarkerFuture.get()
                        val finishMarkerRenderable = finishMarkerFuture.get()

                        // markerData içindeki her marker için:
                        markerData.forEachIndexed { index, (pos, heading) ->
                            val isFinish = (index == markerData.lastIndex)
                            // Arrow marker'lar için y eksenine 0.05 ekleyerek zeminden 5cm yukarıda yerleştiriyoruz.
                            val adjustedPos = if (!isFinish) Vector3(pos.x, pos.y + 0.05f, pos.z) else pos
                            val markerPose = Pose.makeTranslation(adjustedPos.x, adjustedPos.y, adjustedPos.z)
                            val markerAnchor = arSceneView.session?.createAnchor(markerPose)

                            markerAnchor?.let {
                                val anchorNode = AnchorNode(it).apply {
                                    setParent(arSceneView.scene)
                                }

                                if (isFinish) {
                                    // Finish marker için animasyonlu node kullanıyoruz.
                                    val finishNode = RotatingFinishNode().apply {
                                        renderable = finishMarkerRenderable
                                        localScale = Vector3(0.2f, 0.2f, 0.2f)
                                        localRotation = Quaternion.identity()
                                    }
                                    finishNode.setParent(anchorNode)
                                } else {
                                    // Arrow marker için animasyonlu node kullanıyoruz.
                                    // Hesaplanan segment heading'inden açıyı elde ediyoruz (Y ekseni etrafında)
                                    val angleDegrees = Math.toDegrees(atan2(heading.x.toDouble(), heading.z.toDouble())).toFloat()
                                    // Önce arrow'ı yatay hale getirmek için X ekseni etrafında -90° dönüş
                                    val rotationX = Quaternion.axisAngle(Vector3(1f, 0f, 0f), -90f)
                                    // Sonra segment yönüne göre Y ekseni etrafında dönüş
                                    val rotationY = Quaternion.axisAngle(Vector3(0f, 1f, 0f), angleDegrees)
                                    // Dönüş sırası: final = rotationY * rotationX
                                    val finalRotation = Quaternion.multiply(rotationY, rotationX)

                                    // AnimatedArrowNode'a geçiş yönünü (heading) veriyoruz.
                                    val arrowNode = object : AnimatedArrowNode(heading) {}.apply {
                                        renderable = smallMarkerRenderable
                                        localScale = Vector3(0.015f, 0.015f, 0.015f)
                                        localRotation = finalRotation
                                    }
                                    arrowNode.setParent(anchorNode)
                                }
                            } ?: Log.d(TAG, "Anchor oluşturulamadı: $index")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Marker renderable ayarlanırken hata", e)
                    }
                }
                .exceptionally { throwable ->
                    Log.e(TAG, "Model yükleme hatası", throwable)
                    null
                }
        } catch (e: Exception) {
            Log.e(TAG, "placeRouteMarkersStartingAt içinde hata", e)
        }
    }
}
