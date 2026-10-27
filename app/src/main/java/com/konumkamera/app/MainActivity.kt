package com.konumkamera.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.konumkamera.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Konum Kamera — internet gerektirmez.
 * Konum doğrudan telefonun GPS alıcısından (LocationManager) alınır,
 * fotoğrafın üzerine koordinat, rakım, doğruluk ve tarih/saat yazılır,
 * ayrıca konum fotoğrafın EXIF bilgisine de gömülür.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var locationManager: LocationManager

    private var bestLocation: Location? = null
    private var pendingPhotoFile: File? = null
    private var pendingPhotoUri: Uri? = null
    private var lastSavedUri: Uri? = null
    private var listening = false

    private val trLocale = Locale("tr", "TR")

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasLocationPermission()) startLocationUpdates()
            else b.tvLocation.text = getString(R.string.no_permission)
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = pendingPhotoFile
            if (success && file != null && file.length() > 0) {
                processPhoto(file, bestLocation)
            } else {
                toast(getString(R.string.cancelled))
            }
        }

    // Eski Android sürümlerinde tüm metotlar zorunlu olduğu için hepsi tanımlı.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onNewLocation(location)
        override fun onProviderEnabled(provider: String) = updateLocationText()
        override fun onProviderDisabled(provider: String) = updateLocationText()
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        savedInstanceState?.getString("pendingPath")?.let { pendingPhotoFile = File(it) }

        b.btnCapture.setOnClickListener { capture() }
        b.btnShare.setOnClickListener { share() }

        requestNeededPermissions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPhotoFile?.let { outState.putString("pendingPath", it.absolutePath) }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    // ---------- İzinler ----------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    // ---------- Konum (çevrimdışı GPS) ----------

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (listening || !hasLocationPermission()) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }

        // Son bilinen konumdan hızlı başlangıç
        providers.forEach { p ->
            runCatching { locationManager.getLastKnownLocation(p) }.getOrNull()?.let { onNewLocation(it) }
        }
        providers.forEach { p ->
            runCatching {
                locationManager.requestLocationUpdates(p, 1000L, 0f, locationListener, Looper.getMainLooper())
            }
        }
        listening = providers.isNotEmpty()
        updateLocationText()
    }

    private fun stopLocationUpdates() {
        if (!listening) return
        runCatching { locationManager.removeUpdates(locationListener) }
        listening = false
    }

    private fun onNewLocation(loc: Location) {
        val cur = bestLocation
        val ageDiff = if (cur == null) Long.MAX_VALUE else loc.time - cur.time
        val better = cur == null ||
            ageDiff > 30_000 ||                                   // çok daha yeni
            (loc.hasAccuracy() && (!cur.hasAccuracy() || loc.accuracy <= cur.accuracy)) ||
            (ageDiff > 0 && loc.provider == LocationManager.GPS_PROVIDER)
        if (better) {
            bestLocation = loc
            updateLocationText()
        }
    }

    private fun updateLocationText() {
        if (!hasLocationPermission()) {
            b.tvLocation.text = getString(R.string.no_permission); return
        }
        val gpsOn = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)
        val loc = bestLocation
        b.tvLocation.text = when {
            loc != null -> buildLines(loc, Date()).joinToString("\n")
            !gpsOn -> getString(R.string.gps_off)
            else -> getString(R.string.searching_gps)
        }
    }

    private fun buildLines(loc: Location, date: Date): List<String> {
        val lines = mutableListOf<String>()
        lines += String.format(Locale.US, "Enlem:  %.6f°  (%s)", loc.latitude, toDms(loc.latitude, true))
        lines += String.format(Locale.US, "Boylam: %.6f°  (%s)", loc.longitude, toDms(loc.longitude, false))
        val extra = mutableListOf<String>()
        if (loc.hasAltitude()) extra += "Rakım: ${loc.altitude.roundToInt()} m"
        if (loc.hasAccuracy()) extra += "Doğruluk: ±${loc.accuracy.roundToInt()} m"
        if (extra.isNotEmpty()) lines += extra.joinToString("   ")
        lines += SimpleDateFormat("dd.MM.yyyy  HH:mm:ss", trLocale).format(date)
        return lines
    }

    private fun toDms(value: Double, isLat: Boolean): String {
        val dir = if (isLat) (if (value >= 0) "K" else "G") else (if (value >= 0) "D" else "B")
        val a = abs(value)
        val d = a.toInt()
        val mFull = (a - d) * 60
        val m = mFull.toInt()
        val s = (mFull - m) * 60
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", d, m, s, dir)
    }

    // ---------- Fotoğraf ----------

    private fun capture() {
        if (!hasLocationPermission()) { requestNeededPermissions(); return }
        if (bestLocation == null) { toast(getString(R.string.no_location_yet)); return }
        try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "cekim_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            pendingPhotoFile = file
            pendingPhotoUri = uri
            takePictureLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_camera))
        }
    }

    private fun processPhoto(file: File, location: Location?) {
        b.btnCapture.isEnabled = false
        val date = Date()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val bmp = loadRotatedBitmap(file)
                    val lines = if (location != null) buildLines(location, date)
                    else listOf("Konum yok", SimpleDateFormat("dd.MM.yyyy  HH:mm:ss", trLocale).format(date))
                    drawStamp(bmp, lines)
                    val uri = withContext(Dispatchers.IO) { saveToGallery(bmp, location, date) }
                    bmp to uri
                }.getOrNull()
            }
            b.btnCapture.isEnabled = true
            file.delete()
            if (result?.second != null) {
                b.imgPreview.setImageBitmap(result.first)
                b.tvHint.visibility = View.GONE
                lastSavedUri = result.second
                b.btnShare.isEnabled = true
                toast(getString(R.string.saved))
            } else {
                toast(getString(R.string.save_error))
            }
        }
    }

    private fun loadRotatedBitmap(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        val maxSide = 3200
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: error("Görüntü okunamadı")

        val orientation = ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return if (raw.isMutable) raw else raw.copy(Bitmap.Config.ARGB_8888, true)
        val m = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (rotated != raw) raw.recycle()
        return if (rotated.isMutable) rotated else rotated.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun drawStamp(bmp: Bitmap, lines: List<String>) {
        val canvas = Canvas(bmp)
        val shortSide = minOf(bmp.width, bmp.height)
        val textSize = shortSide * 0.035f
        val pad = textSize * 0.8f

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(textSize * 0.12f, 0f, 0f, Color.BLACK)
        }
        val width = (bmp.width - pad * 2).toInt()
        val layout = StaticLayout.Builder
            .obtain(lines.joinToString("\n"), 0, lines.joinToString("\n").length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .build()

        val boxTop = bmp.height - layout.height - pad * 2
        val bg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
        canvas.drawRect(0f, boxTop, bmp.width.toFloat(), bmp.height.toFloat(), bg)

        canvas.save()
        canvas.translate(pad, boxTop + pad)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun saveToGallery(bmp: Bitmap, location: Location?, date: Date): Uri? {
        val name = "KonumKamera_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, date.time)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/KonumKamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            } ?: throw IllegalStateException("Çıkış akışı açılamadı")

            // Konumu EXIF bilgisine de yaz (galeri / harita uygulamaları okuyabilir)
            runCatching {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    ExifInterface(pfd.fileDescriptor).apply {
                        location?.let {
                            setLatLong(it.latitude, it.longitude)
                            if (it.hasAltitude()) setAltitude(it.altitude)
                        }
                        setAttribute(
                            ExifInterface.TAG_DATETIME_ORIGINAL,
                            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(date)
                        )
                        saveAttributes()
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }

    private fun share() {
        val uri = lastSavedUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
