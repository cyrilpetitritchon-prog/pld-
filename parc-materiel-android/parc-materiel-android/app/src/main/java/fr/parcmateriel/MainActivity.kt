package fr.parcmateriel

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.webkit.*
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var picker: ValueCallback<Array<Uri>>? = null
    private var camUri: Uri? = null
    private val base = "https://appassets.androidplatform.net/assets/index.html"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true   // IndexedDB : les fiches restent sur le téléphone
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                loader.shouldInterceptRequest(request.url)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                picker?.onReceiveValue(null)
                picker = cb
                val extra = mutableListOf<Intent>()
                camUri = null
                if (p.acceptTypes.any { it.startsWith("image") }) {   // propose l'appareil photo
                    val f = File.createTempFile("photo", ".jpg", cacheDir)
                    camUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fp", f)
                    extra += Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, camUri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(p.createIntent(), "Choisir")
                    .putExtra(Intent.EXTRA_INITIAL_INTENTS, extra.toTypedArray())
                return try { startActivityForResult(chooser, 1); true } catch (e: Exception) { picker = null; false }
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        route(intent)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req != 1) return
        val cb = picker ?: return
        picker = null
        val clip = data?.clipData
        cb.onReceiveValue(when {
            res != RESULT_OK -> null
            data?.data != null -> arrayOf(data.data!!)
            clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
            camUri != null -> arrayOf(camUri!!)
            else -> null
        })
    }

    // Un QR code « parcmateriel://f/<id> » ouvre directement la fiche dans l'application
    private fun route(i: Intent?) {
        val id = i?.data?.takeIf { it.scheme == "parcmateriel" }?.pathSegments?.lastOrNull()
            ?.filter { it.isLetterOrDigit() || it == '-' }
        if (web.url == null) web.loadUrl(if (id != null) "$base#/f/$id" else base)
        else if (id != null) web.evaluateJavascript("location.hash='#/f/$id'", null)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); route(intent) }
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    inner class Bridge {
        @JavascriptInterface
        fun qr(t: String): String {   // matrice du QR code (correction d'erreur H, pour la photo au centre)
            val m = QRCodeWriter().encode(t, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 2))
            return (0 until m.height).joinToString("\n") { y ->
                (0 until m.width).joinToString("") { x -> if (m.get(x, y)) "1" else "0" }
            }
        }

        @JavascriptInterface
        fun decodeQr(dataUrl: String): String = try {   // lit un QR code sur une photo
            val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            QRCodeReader().decode(
                BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, px))),
                mapOf(DecodeHintType.TRY_HARDER to true)).text
        } catch (e: Exception) { "" }

        @JavascriptInterface
        fun print() = runOnUiThread {   // impression Android (ou export PDF)
            (getSystemService(PRINT_SERVICE) as PrintManager).print(
                "Parc matériel", web.createPrintDocumentAdapter("Parc matériel"), PrintAttributes.Builder().build())
        }

        @JavascriptInterface
        fun openPdf(b64: String, name: String) {   // ouvre le PDF dans le lecteur du téléphone (qui sait l'imprimer)
            val f = File(cacheDir, "document.pdf").also { it.writeBytes(Base64.decode(b64, Base64.DEFAULT)) }
            val u = FileProvider.getUriForFile(this@MainActivity, "$packageName.fp", f)
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Aucun lecteur PDF installé ($name)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
