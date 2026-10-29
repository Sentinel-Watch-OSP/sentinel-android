package com.samourai.sentinel.ui.dojo

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.client.android.Contents
import com.google.zxing.client.android.encode.QRCodeEncoder
import com.samourai.sentinel.R
import com.samourai.sentinel.ui.views.GenericBottomSheet
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

/**
 * Displays the pairing credentials for the currently connected Dojo.
 *
 * Shows a QR of the full pairing payload (so another device can be paired) plus
 * the individual fields as selectable text.
 *
 * The payload embeds the node's API key, so the sheet is created with
 * [GenericBottomSheet]'s `secure` flag to set FLAG_SECURE, matching how the app
 * already treats public keys and transaction details.
 */
class DojoCredentialsBottomSheet(
    secure: Boolean = true
) : GenericBottomSheet(secure = secure) {

    private val dojoUtility: DojoUtility by inject(DojoUtility::class.java)

    override fun getTheme(): Int = R.style.AppTheme_BottomSheet_Theme

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dojo_credentials_bottomsheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.dojoCredentialsToolbar)
        val qrImage = view.findViewById<ShapeableImageView>(R.id.dojoPairingQr)
        val urlValue = view.findViewById<TextView>(R.id.dojoUrlValue)
        val apiKeyValue = view.findViewById<TextView>(R.id.dojoApiKeyValue)
        val typeValue = view.findViewById<TextView>(R.id.dojoTypeValue)
        val versionValue = view.findViewById<TextView>(R.id.dojoVersionValue)
        val payloadValue = view.findViewById<TextView>(R.id.dojoPayloadValue)
        val copyPayloadButton = view.findViewById<MaterialButton>(R.id.dojoCopyPayloadButton)
        val copyApiKeyButton = view.findViewById<MaterialButton>(R.id.dojoCopyApiKeyButton)

        dialog?.window?.navigationBarColor =
            ContextCompat.getColor(requireContext(), R.color.grey_homeActivity)

        toolbar.setNavigationOnClickListener { dismiss() }

        val rawPayload = dojoUtility.exportDojoPayload()
        if (rawPayload.isNullOrBlank()) {
            // Should not happen (the entry point is hidden when no Dojo is paired),
            // but fail visibly rather than showing a screen of empty fields.
            Toast.makeText(
                requireContext(),
                "No Dojo pairing details available",
                Toast.LENGTH_SHORT
            ).show()
            dismiss()
            return
        }

        val pairing = dojoUtility.getPairing()
        urlValue.text = pairing?.url.orPlaceholder()
        apiKeyValue.text = pairing?.apikey.orPlaceholder()
        typeValue.text = pairing?.type.orPlaceholder()
        versionValue.text = pairing?.version.orPlaceholder()

        // Pretty-print so the payload is readable rather than one long line.
        payloadValue.text = prettyPrint(rawPayload)

        renderQr(qrImage, rawPayload)

        copyPayloadButton.setOnClickListener {
            copyToClipboard("Dojo pairing payload", rawPayload)
        }

        copyApiKeyButton.setOnClickListener {
            val key = pairing?.apikey
            if (key.isNullOrBlank()) {
                Toast.makeText(requireContext(), "No API key found", Toast.LENGTH_SHORT).show()
            } else {
                copyToClipboard("Dojo API key", key)
            }
        }
    }

    private fun String?.orPlaceholder(): String =
        if (this.isNullOrBlank()) "—" else this

    private fun prettyPrint(raw: String): String = try {
        JSONObject(raw).toString(2)
    } catch (e: Exception) {
        Timber.e(e, "Could not pretty-print Dojo payload")
        raw
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun renderQr(target: ShapeableImageView, payload: String) {
        val radius = resources.getDimension(R.dimen.spacing_large)
        var bitmap: Bitmap? = null
        try {
            bitmap = QRCodeEncoder(
                payload,
                null,
                Contents.Type.TEXT,
                BarcodeFormat.QR_CODE.toString(),
                500
            ).encodeAsBitmap()
        } catch (e: WriterException) {
            Timber.e(e, "Could not encode Dojo pairing QR")
        }

        if (bitmap == null) {
            target.visibility = View.GONE
            Toast.makeText(
                requireContext(),
                "Could not render pairing QR code",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        target.shapeAppearanceModel = target.shapeAppearanceModel
            .toBuilder()
            .setAllCornerSizes(radius)
            .build()
        target.setImageBitmap(bitmap)
    }
}
