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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.arindoornavigation.ui.theme.ArIndoorNavigationTheme

// Basit rota adım modeli; Serializable olarak işaretleyerek fragment’ler arası aktarımı kolaylaştırıyoruz.
data class RouteStep(val direction: String, val distance: Float) : java.io.Serializable

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
                Surface(modifier = Modifier.fillMaxSize()) {
                    ARNavigationScreen()
                }
            }
        }
        Log.d(TAG, "kars: onCreate finished")
    }

    /**
     * ARNavigationScreen:
     * - Kamera iznini kontrol eder.
     * - İzin verildiyse, kullanıcıya önce rota planlama UI’si sunulur.
     * - Rota oluşturulduktan sonra "Navigasyona Başla" butonuna basınca, AR view (CustomARFragment) gösterilir.
     */
    @Composable
    fun ARNavigationScreen() {
        val context = LocalContext.current
        var hasCameraPermission by remember { mutableStateOf(false) }
        var navigationStarted by remember { mutableStateOf(false) }
        // Kullanıcının planladığı rota adımlarını saklamak için liste.
        var routeSteps by remember { mutableStateOf(listOf<RouteStep>()) }

        Log.d(TAG, "kars: ARNavigationScreen started")

        // Kamera izni istemek için launcher.
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

        if (!hasCameraPermission) {
            Log.d(TAG, "kars: Camera permission not available, showing error message")
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(text = "Kamera izni gerekli!")
            }
        } else {
            // Eğer navigasyona henüz başlanmadıysa, rota planlama UI’sini göster.
            if (!navigationStarted) {
                RoutePlanningUI(
                    routeSteps = routeSteps,
                    onRouteStepsChange = { routeSteps = it },
                    onStartNavigation = {
                        if (routeSteps.isNotEmpty()) {
                            navigationStarted = true
                        } else {
                            Toast.makeText(context, "Lütfen en az bir rota adımı ekleyin", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            } else {
                // AR view’ü göster; CustomARFragment'e rota adımlarını argüman olarak geçiyoruz.
                Log.d(TAG, "kars: Displaying AR content with route: $routeSteps")
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                id = View.generateViewId()
                                (ctx as AppCompatActivity).supportFragmentManager.beginTransaction()
                                    .replace(id, CustomARFragment.newInstance(ArrayList(routeSteps)))
                                    .commit()
                                Log.d(TAG, "kars: AR fragment transaction committed")
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * RoutePlanningUI:
     * - Kullanıcıya dinamik rota oluşturması için UI sunar.
     * - Kullanıcı “yön” ve “mesafe” bilgilerini girer, adım ekler.
     * - Eklenen adımlar liste halinde gösterilir.
     * - Alt kısımda "Navigasyona Başla" butonu bulunur.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun RoutePlanningUI(
        routeSteps: List<RouteStep>,
        onRouteStepsChange: (List<RouteStep>) -> Unit,
        onStartNavigation: () -> Unit
    ) {
        // Türkçe yön seçenekleri listesi
        val directions = listOf("ileri", "geri", "sağ", "sol")
        var expanded by remember { mutableStateOf(false) }
        // Seçili yönün varsayılan değeri
        var selectedDirection by remember { mutableStateOf(directions[0]) }
        var distanceInput by remember { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("Rota Oluştur", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))

            // Yön seçimi için dropdown menu
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedDirection.replaceFirstChar { it.uppercase() },
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Yön") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor() // Material 3 API'sine uygun menü konumlandırması
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    directions.forEach { direction ->
                        DropdownMenuItem(
                            text = { Text(direction.replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                selectedDirection = direction
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Mesafe girişi için TextField
            OutlinedTextField(
                value = distanceInput,
                onValueChange = { distanceInput = it },
                label = { Text("Mesafe (metre)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val distance = distanceInput.toFloatOrNull()
                    if (distance != null) {
                        // Yeni adımı listeye ekle.
                        val newStep = RouteStep(selectedDirection, distance)
                        onRouteStepsChange(routeSteps + newStep)
                        // Mesafe girişini sıfırla (seçili yön aynı kalır)
                        distanceInput = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Adım Ekle")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Eklenen Adımlar:", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(routeSteps) { index, step ->
                    Text("${index + 1}. Yön: ${step.direction.replaceFirstChar { it.uppercase() }}, Mesafe: ${step.distance} m")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onStartNavigation,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Navigasyona Başla")
            }
        }
    }


}
