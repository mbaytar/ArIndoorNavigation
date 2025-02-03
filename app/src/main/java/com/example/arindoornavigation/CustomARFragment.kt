package com.example.arindoornavigation

import android.util.Log
import android.view.View
import android.widget.Toast
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory

// Eğer Vector3 için operator overloading tanımlı değilse, aşağıdaki extensionları ekleyin.
operator fun Vector3.plus(other: Vector3): Vector3 =
    Vector3(this.x + other.x, this.y + other.y, this.z + other.z)

operator fun Vector3.minus(other: Vector3): Vector3 =
    Vector3(this.x - other.x, this.y - other.y, this.z - other.z)

class CustomARFragment : ArFragment() {

    companion object {
        private const val TAG = "kars"
    }

    // Başlangıç noktası seçildikten sonra saklanacak anchor.
    private var startingAnchor: Anchor? = null
    // Zeminin tespit edildiğini ve ekrana dokunulması gerektiğini bildiren toast mesajının yalnızca bir kere gösterilmesini sağlar.
    private var tapToastShown = false

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Zemini tespit ettikten sonra, kullanıcı ekrana dokunarak başlangıç noktasını seçsin.
        setOnTapArPlaneListener { hitResult, plane, motionEvent ->
            if (startingAnchor == null) {
                startingAnchor = hitResult.createAnchor()
                Toast.makeText(requireContext(), "Başlangıç noktası seçildi", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "kars: Starting point selected")
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
                    Log.d(TAG, "kars: Tap toast shown")
                    tapToastShown = true
                }
            }
        }
    }

    /**
     * placeRouteMarkersStartingAt:
     * - Seçilen başlangıç noktasını (anchor) referans alarak rota oluşturur.
     * - Kameranın yönünden (yatay bileşeni 0 alınarak) forward ve right vektörleri hesaplanır.
     * - Rota:
     *      Segment 1: P1 = S + forward * 2   (2 m düz)
     *      Segment 2: P2 = P1 + right * 3     (3 m sağa)
     *      Segment 3: P3 = P2 - forward * 2   (2 m düz, yani ikinci sağa dönüş uygulanmış olur)
     * - Her segment arasında 20 cm aralıkla marker noktaları hesaplanır.
     * - Varış noktasında büyük kırmızı, diğer marker'lar için küçük mavi marker kullanılır.
     */
    private fun placeRouteMarkersStartingAt(anchor: Anchor) {
        val startPose = anchor.pose
        val S = Vector3(startPose.tx(), startPose.ty(), startPose.tz())
        Log.d(TAG, "kars: Starting pose: $S")

        // Alınan anlık kamera pose'undan forward (ileri) vektörünü hesaplıyoruz.
        val camPose = arSceneView.arFrame?.camera?.pose
        if (camPose == null) {
            Log.d(TAG, "kars: Camera pose is null, cannot compute route.")
            return
        }
        // ARCore’da kameranın -z ekseni ileri yönü temsil eder.
        val camZ = camPose.zAxis  // float[3]
        val forward = Vector3(-camZ[0], 0f, -camZ[2]).normalized()
        // Sağ yönü elde etmek için, right = Vector3(-forward.z, 0, forward.x)
        val right = Vector3(-forward.z, 0f, forward.x).normalized()

        Log.d(TAG, "kars: forward vector: $forward, right vector: $right")

        // Rota hesaplaması:
        // Segment 1: P1 = S + forward * 2
        val P1 = S + forward.scaled(2f)
        // Segment 2: P2 = P1 + right * 3
        val P2 = P1 + right.scaled(3f)
        // Segment 3: P3 = P2 - forward * 2  (İkinci sağa dönüş için: ikinci dönüş yönü, -forward)
        val P3 = P2 - forward.scaled(2f)

        Log.d(TAG, "kars: Route points: S=$S, P1=$P1, P2=$P2, P3=$P3")

        // Aradaki marker aralığı: 20 cm
        val spacing = 0.2f

        // İki nokta arasında lineer interpolasyon yaparak marker noktalarını döndüren fonksiyon.
        fun interpolatePoints(start: Vector3, end: Vector3): List<Vector3> {
            val diff = Vector3.subtract(end, start)
            val dist = diff.length()
            val count = (dist / spacing).toInt()
            val points = mutableListOf<Vector3>()
            for (i in 0..count) {
                val t = i.toFloat() / count.toFloat()
                val interp = Vector3(
                    start.x + t * (end.x - start.x),
                    start.y + t * (end.y - start.y),
                    start.z + t * (end.z - start.z)
                )
                points.add(interp)
            }
            return points
        }

        // Segmentler için marker noktalarını oluşturuyoruz.
        val markersPositions = mutableListOf<Vector3>()
        markersPositions.addAll(interpolatePoints(S, P1))
        markersPositions.addAll(interpolatePoints(P1, P2))
        markersPositions.addAll(interpolatePoints(P2, P3))

        // Oluşturulacak marker renderable'larını hazırlıyoruz:
        // Küçük marker: mavi renkli, yarıçap 0.01 m
        MaterialFactory.makeOpaqueWithColor(requireContext(), com.google.ar.sceneform.rendering.Color(android.graphics.Color.BLUE))
            .thenAccept { blueMaterial ->
                val smallMarkerRenderable = ShapeFactory.makeSphere(0.01f, Vector3.zero(), blueMaterial)
                // Varış marker'ı: kırmızı renkli, yarıçap 0.05 m
                MaterialFactory.makeOpaqueWithColor(requireContext(), com.google.ar.sceneform.rendering.Color(android.graphics.Color.RED))
                    .thenAccept { redMaterial ->
                        val finishMarkerRenderable = ShapeFactory.makeSphere(0.05f, Vector3.zero(), redMaterial)
                        // Her marker için:
                        for ((index, pos) in markersPositions.withIndex()) {
                            // Marker dünya pozisyonu: y değerini başlangıç noktasının y değeri olarak alıyoruz.
                            val markerPose = Pose.makeTranslation(pos.x, S.y, pos.z)
                            val markerAnchor = arSceneView.session?.createAnchor(markerPose)
                            if (markerAnchor == null) {
                                Log.d(TAG, "kars: Failed to create anchor for marker index $index")
                                continue
                            }
                            val anchorNode = AnchorNode(markerAnchor)
                            anchorNode.setParent(arSceneView.scene)
                            val markerNode = com.google.ar.sceneform.Node()
                            markerNode.setParent(anchorNode)
                            // Son marker (varış) için kırmızı renderable, diğerleri için mavi.
                            if (index == markersPositions.size - 1) {
                                markerNode.renderable = finishMarkerRenderable
                            } else {
                                markerNode.renderable = smallMarkerRenderable
                            }
                            Log.d(TAG, "kars: Placed marker at (${pos.x}, ${S.y}, ${pos.z}) for index $index")
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
