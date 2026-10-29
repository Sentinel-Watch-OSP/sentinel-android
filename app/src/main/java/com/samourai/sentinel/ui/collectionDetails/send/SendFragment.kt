package com.samourai.sentinel.ui.collectionDetails.send

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewModelScope
import androidx.transition.TransitionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialContainerTransform
import com.samourai.sentinel.R
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.databinding.FragmentSpendBinding
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.broadcast.BroadcastTx
import com.samourai.sentinel.ui.fragments.AddNewPubKeyBottomSheet
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PermissionResult
import com.samourai.sentinel.ui.utils.permissionResultOf
import com.samourai.sentinel.ui.utils.hideKeyboard
import com.samourai.sentinel.ui.views.codeScanner.CameraFragmentBottomSheet
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import com.samourai.wallet.util.FormatsUtilGeneric
import com.samourai.wallet.util.XPUB
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.registry.RegistryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.util.encoders.Hex
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.File
import java.math.BigInteger
import java.text.DecimalFormat
import java.util.*
import kotlin.math.ceil

class SendFragment : Fragment() {

    private var isBTCEditing = false
    private var isFiatEditing = false
    private var amount: Double = 0.0
    private var transactionComposer = TransactionComposer()
    private var rate: ExchangeRateRepository.Rate = ExchangeRateRepository.Rate("", 0)
    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
    private var address: String = ""
    private val feeRepository: FeeRepository by inject(FeeRepository::class.java)
    private var feeLow: Long = 0L
    private var feeMed: Long = 0L
    private var feeHigh: Long = 0L
    var mCollection: PubKeyCollection? = null
    private val decimalFormat = DecimalFormat("##.00")
    private var qrCodeString: String? = null
    private val decimalFormatBTC = DecimalFormat("#")
    private var _fragmentSpendBinding: FragmentSpendBinding? = null
    private val fragmentSpendBinding get() = _fragmentSpendBinding!!
    private var indexPubSelector = -1
    val viewModel: SendViewModel by viewModels()
    private var availableBalance: Long = 0L

    private val HARDENED = 2147483648
    private val PREMIX_ACC = 2147483645L

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        _fragmentSpendBinding = FragmentSpendBinding.inflate(inflater, container, false)
        val view = fragmentSpendBinding.root
        return view
    }

    override fun onResume() {
        if (indexPubSelector != -1 && indexPubSelector != 0)
            setBalance(mCollection!!.pubs[indexPubSelector-1])
        else
            setBalance(mCollection!!.pubs[findFirstNonAddressPubkey()-1])
        super.onResume()
    }

    fun setIndexPubSelector(index: Int) {
        indexPubSelector = index
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        decimalFormatBTC.minimumIntegerDigits = 1
        decimalFormatBTC.minimumFractionDigits = 8
        decimalFormatBTC.maximumFractionDigits = 8

        exchangeRateRepository.getRateLive().observe(this.viewLifecycleOwner) {
            rate = it
            DecimalFormat.getNumberInstance().currency = Currency.getInstance(rate.currency)
        }

        fragmentSpendBinding.fragmentComposeTx.totalBTC.setOnClickListener {
            viewModel.setAmount(availableBalance.div(1e8))
            setBtcEdit(decimalFormatBTC.format(availableBalance.div(1e8)).toString())
            val fiatRate = availableBalance.div(1e8).times(rate.rate)
            setFiatEdit(DecimalFormat.getNumberInstance().format(fiatRate))
        }

        fragmentSpendBinding.fragmentComposeTx.textView12.setOnClickListener {
            viewModel.setAmount(availableBalance.div(1e8))
            setBtcEdit(decimalFormatBTC.format(availableBalance.div(1e8)).toString())
            val fiatRate = availableBalance.div(1e8).times(rate.rate)
            setFiatEdit(DecimalFormat.getNumberInstance().format(fiatRate))
        }

        watchAddressAndAmount()

        fragmentSpendBinding.toEditText.setEndIconOnClickListener {
            val string = AndroidUtil.getClipBoardString(requireContext())
            fragmentSpendBinding.btcAddress.setText(string)
        }

        fragmentSpendBinding.composeBtn.setOnClickListener {
            containerTransform(fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView, fragmentSpendBinding.composeBtn)
        }

        setBalance(mCollection!!.pubs[findFirstNonAddressPubkey()-1])

        if (isAdded) {
            setPubKeySelector()
            if (indexPubSelector != -1) {
                setDropDownPub(indexPubSelector)
            }
        }


        setUpFee()

        fragmentSpendBinding.fragmentBroadcastTx.broadCastTransactionBtn.setOnClickListener {
            startActivity(Intent(context, BroadcastTx::class.java))
        }

        viewModel.psbtLive.observe(viewLifecycleOwner) {
            generateQRCode(it)
            qrCodeString = it
            fragmentSpendBinding.fragmentBroadcastTx.psbtText.text = it
            fragmentSpendBinding.fragmentBroadcastTx.psbtText.movementMethod = ScrollingMovementMethod()
        }

        viewModel.validSpend.observe(viewLifecycleOwner) {
            fragmentSpendBinding.composeBtn.isEnabled = it
            fragmentSpendBinding.composeBtn.alpha = if (it) 1f else 0.6f
        }

        fragmentSpendBinding.fragmentBroadcastTx.psbtCopyBtn.setOnClickListener {
            val psbt = viewModel.psbtLive.value
            if (psbt?.length == 0) {
                return@setOnClickListener
            }
            val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
            val clipData = ClipData
                    .newPlainText("PSBT", psbt)
            if (cm != null) {
                cm.setPrimaryClip(clipData)
                Toast.makeText(context, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT)
                        .show()
            }
        }

        viewModel.minerFee.observe(viewLifecycleOwner) {
            fragmentSpendBinding.fragmentComposeTx.feeSelector.totalMinerFee.text = it
        }

        fragmentSpendBinding.fragmentBroadcastTx.psbtShareBtn.setOnClickListener { view1 ->
            val popup = PopupMenu(requireContext(), view1)
            popup.menuInflater.inflate(R.menu.send_share_menu, popup.menu)
            popup.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menu_share_as_text -> {
                        val psbtString = viewModel.psbtLive.value
                        val sendIntent: Intent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, psbtString)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, null)
                        startActivity(shareIntent)
                    }
                    R.id.menu_save_as_psbt -> {
                        sharePSBTFile()
                    }
                    R.id.menu_share_psbt -> {
                        val psbtBytes: ByteArray? = viewModel.getPsbtBytes()
                        val psbtFile = File(requireContext().cacheDir, "PSBT_transaction.psbt")
                        psbtFile.writeBytes(psbtBytes!!)

                        val uri = FileProvider.getUriForFile(
                            requireContext(),
                            "${requireContext().packageName}.provider",
                            psbtFile
                        )

                        val sendIntent: Intent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_STREAM, uri)
                            type = "application/octet-stream"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                        val shareIntent = Intent.createChooser(sendIntent, null)
                        startActivity(shareIntent)
                    }
                    R.id.menu_save_as_text_file -> {
                        saveTxtFile()
                    }
                }
                true
            }
            popup.show()
        }

        fragmentSpendBinding.composeBtn.setOnClickListener {
            it.hideKeyboard()
            if (viewModel.makeTx()) {
                requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.v3_accent)
                containerTransform(fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView, fragmentSpendBinding.composeBtn)
            }
        }

        setUpToolbar()
    }

    private fun setUpToolbar() {
        fragmentSpendBinding.fragmentBroadcastTx.unsignedTxToolbar.setNavigationOnClickListener {
            containerTransform(fragmentSpendBinding.composeBtn, fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView)
        }
        fragmentSpendBinding.sendAppBar.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mpm_black))
        (activity as SentinelActivity).window.navigationBarColor = ContextCompat.getColor(requireContext(), R.color.grey_homeActivity)
        fragmentSpendBinding.sendAppBar.setNavigationOnClickListener {
            requireActivity().onBackPressed()
        }

        fragmentSpendBinding.sendAppBar.setOnMenuItemClickListener { menu ->
            if (menu.itemId == R.id.action_scan_qr) {
                if (!AndroidUtil.isPermissionGranted(
                        Manifest.permission.CAMERA,
                        requireContext()
                    )
                ) {
                    this.askCameraPermission()
                } else {
                    val camera = CameraFragmentBottomSheet()
                    camera.setQrCodeScanLisenter {
                        if (FormatsUtilGeneric.getInstance().isBitcoinUri(it)) {
                            fragmentSpendBinding.btcAddress.setText(FormatsUtilGeneric.getInstance().getBitcoinAddress(it))
                            val satsAmount = java.lang.Double.valueOf(FormatsUtilGeneric.getInstance().getBitcoinAmount(it))
                            fragmentSpendBinding.btcEditText.setText(MonetaryUtil.getInstance().getBTCDecimalFormat(satsAmount.toLong()))
                        } else if (it.length < 100) {
                            fragmentSpendBinding.btcAddress.setText(it)
                        }
                        camera.dismiss()
                    }
                    camera.show(parentFragmentManager, camera.tag)
                }
            }
            true
        }
    }

    protected fun askCameraPermission() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(resources.getString(R.string.permission_alert_dialog_title_camera))
            .setMessage(resources.getString(R.string.permission_dialog_message_camera))
            .setNegativeButton(resources.getString(R.string.no)) { _, _ ->
                val bottomSheetFragment = AddNewPubKeyBottomSheet()
                bottomSheetFragment.show(childFragmentManager, bottomSheetFragment.tag)
            }
            .setPositiveButton(resources.getString(R.string.yes)) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA),
                        SentinelActivity.CAMERA_PERMISSION
                    )
                }
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // NOTE: grantResults can be EMPTY when the dialog is cancelled - never index it directly.
        if (requestCode != SentinelActivity.CAMERA_PERMISSION) return
        when (permissionResultOf(grantResults)) {
            PermissionResult.GRANTED -> showAddressScanner()
            PermissionResult.DENIED -> {
                Toast.makeText(context, "Permission denied", Toast.LENGTH_LONG).show()
                showAddressScanner()
            }
            PermissionResult.CANCELLED -> Unit
        }
    }

    private fun showAddressScanner() {
        // Guard against the fragment being detached while the permission dialog was up.
        if (!isAdded) return
        val camera = CameraFragmentBottomSheet()
        camera.setQrCodeScanLisenter {
            if (it.length < 100) {
                fragmentSpendBinding.btcAddress.setText(it)
            }
            camera.dismiss()
        }
        camera.show(parentFragmentManager, camera.tag)
    }


    fun getPsbtBytes() {
        viewModel.getPsbtBytes()
    }

    private fun sharePSBTFile() {
        val psbt = viewModel.getPsbtBytes() ?: return
        val fileUUID = UUID.randomUUID()
        val qrFile = "${requireContext().cacheDir.path}${File.separator}${fileUUID}.psbt"

        val file = File(qrFile)
        if (file.exists()) {
            file.delete()
        }
        file.writeBytes(psbt)
        file.setReadable(true, false)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "**/**"
            putExtra(Intent.EXTRA_TITLE, "${fileUUID}.psbt")
        }
        intent.putExtra("psbtContent", psbt)

        requireActivity().startActivityForResult(intent, 1)
    }

    private fun saveTxtFile() {
        val psbt = viewModel.psbtLive.value ?: return
        val fileUUID = UUID.randomUUID()
        val txtFile = "${requireContext().cacheDir.path}${File.separator}${fileUUID}.txt"

        val file = File(txtFile)
        if (file.exists()) {
            file.delete()
        }
        file.writeText(psbt)

        file.setReadable(true, false)

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain" // Set the MIME type for text files
            putExtra(Intent.EXTRA_TITLE, "${fileUUID}.txt")
        }

        intent.putExtra("txtContent", psbt)
        requireActivity().startActivityForResult(intent, 2)
    }

    private fun containerTransform(enter: View, leaving: View) {
        val transform = MaterialContainerTransform()
        transform.scrimColor = Color.TRANSPARENT
        transform.drawingViewId = R.id.sendRootLayout

        val transition: MaterialContainerTransform = transform
        transition.startView = leaving
        transition.duration = 400
        transition.endView = enter

        transition.addTarget(enter)
        TransitionManager.beginDelayedTransition(view as ViewGroup, transition)
        enter.visibility = VISIBLE
        leaving.visibility = GONE
        if (leaving.id == fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView.id) {
            requireActivity().window.statusBarColor =
                    ContextCompat.getColor(requireContext(), android.R.color.transparent)
        } else {
            requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.v3_accent)
        }
    }

    private fun watchAddressAndAmount() {
        fragmentSpendBinding.fiatEditTextLayout.hint = exchangeRateRepository.getRateLive().value?.currency
        fragmentSpendBinding.fiatEditText.addTextChangedListener { onFiatValueChange(it.toString()) }
        fragmentSpendBinding.btcEditText.addTextChangedListener { onBtcValueChanged(it.toString()) }
        fragmentSpendBinding.btcAddress.addTextChangedListener {
            if (it.toString().isNotEmpty()) {
                address = it.toString().trim()
                if (FormatsUtil.isValidBitcoinAddress(address)) {
                    viewModel.setDestinationAddress(address)
                } else {
                    Toast.makeText(requireContext(), "Invalid address", Toast.LENGTH_SHORT).show()
                    fragmentSpendBinding.composeBtn.alpha = 0.6f
                    fragmentSpendBinding.composeBtn.isEnabled = false
                }
            } else {
                viewModel.setDestinationAddress("")
            }
        }
    }

    private fun onBtcValueChanged(btcString: String) {
        if (isBTCEditing) {
            return
        }
        try {
            if (btcString.isEmpty()) {
                setBtcEdit("")
                setFiatEdit("")
            }
            if (btcString.split(".").size > 1 && btcString.split(".")[1].length > 8) {
                setBtcEdit(btcString.dropLast(btcString.split(".")[1].length-8))
                return
            }
            val btc: Double = btcString.toDouble()
            amount = btc
            if (btc > 21000000.0) {
                setFiatEdit("")
                setBtcEdit("")
                return
            }
            if (amount <= 0) {
                fragmentSpendBinding.composeBtn.alpha = 0.6f
                fragmentSpendBinding.composeBtn.isEnabled = false
                return
            }
            val fiatRate = btc.times(rate.rate)
            setFiatEdit(DecimalFormat.getNumberInstance().format(fiatRate))
            viewModel.setAmount(amount)
            print("Setting amount: " + amount)
        } catch (Ex: Exception) {

        }
    }

    private fun onFiatValueChange(fiatString: String) {
        val ungroupedFiat = fiatString.replace((DecimalFormat.getInstance() as DecimalFormat).decimalFormatSymbols.groupingSeparator.toString(), "")
        if (isFiatEditing) {
            return
        }
        if (ungroupedFiat.contains(".") && ungroupedFiat.split(".")[1].length > 2) {
            setFiatEdit(ungroupedFiat.dropLast(1))
            return
        }
        try {
            if (ungroupedFiat.isEmpty()) {
                setBtcEdit("")
                setFiatEdit("")
                fragmentSpendBinding.composeBtn.alpha = 0.6f
                fragmentSpendBinding.composeBtn.isEnabled = false
            }
            val fiat: Double = ungroupedFiat.toDouble()
            val btcRate = (1 / rate.rate.toFloat()) * fiat.toFloat()
            if (btcRate > 21000000.0) {
                setBtcEdit("")
                setFiatEdit("")
                return
            }

            if (ungroupedFiat.get(ungroupedFiat.lastIndex).equals('.'))
                setFiatEdit(DecimalFormat.getNumberInstance().format(fiat) + ".")
            else
                setFiatEdit(DecimalFormat.getNumberInstance().format(fiat))

            setBtcEdit(MonetaryUtil.getInstance().formatToBtc((btcRate * 1e8).toLong()))
            val btc: Double = MonetaryUtil.getInstance().formatToBtc((btcRate * 1e8).toLong()).toDouble()
            amount = btc
            viewModel.setAmount(amount)
            print("Setting amount: " + amount)
        } catch (Ex: Exception) {

        }
    }

    private fun setBtcEdit(value: String) {
        isBTCEditing = true
        fragmentSpendBinding.btcEditText.setText(value)
        fragmentSpendBinding.btcEditText.text?.length?.let { fragmentSpendBinding.btcEditText.setSelection(it) }
        isBTCEditing = false
    }

    private fun setFiatEdit(value: String) {
        isFiatEditing = true
        fragmentSpendBinding.fiatEditText.setText(value)
        fragmentSpendBinding.fiatEditText.text?.length?.let { fragmentSpendBinding.fiatEditText.setSelection(it) }
        isFiatEditing = false
    }


    fun onBackPressed(): Boolean {
        if (fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView.visibility == VISIBLE) {
            containerTransform(fragmentSpendBinding.composeBtn, fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView)
            return false
        }
        Timber.i("onBackPressed: ")
        return true
    }

    fun setCollection(mCollection: PubKeyCollection) {
        this.mCollection = mCollection
    }

    fun setDropDownPub(index: Int) {
        decimalFormatBTC.minimumIntegerDigits = 1
        decimalFormatBTC.minimumFractionDigits = 8
        decimalFormatBTC.maximumFractionDigits = 8

        if (mCollection == null || index <= 0 || !isAdded) {
            indexPubSelector = index
            return
        }

        val newIndex = if (mCollection!!.pubs[index-1].type != AddressTypes.ADDRESS) index else findFirstNonAddressPubkey()

        mCollection?.let { pubKeySelector ->
            val items = pubKeySelector.pubs.filter {
                isPubkeyAddressOrPremix(it)
            }.map { it.label }.toMutableList()

            val adapter: ArrayAdapter<String> = ArrayAdapter(
                requireContext(),
                R.layout.dropdown_menu_popup_item, items
            )
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.inputType = InputType.TYPE_NULL
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.threshold = items.size
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.setAdapter(adapter)
            val selectPubKeyModel = pubKeySelector.pubs[newIndex-1]
            viewModel.setPublicKey(selectPubKeyModel, viewLifecycleOwner, mCollection!!)
            if (mCollection!!.isImportFromWallet && selectPubKeyModel.label.equals("Deposit")) {
                transactionComposer.setPubKey(listOf(
                    getPubKeyModelByLabel("Deposit BIP84"),
                    getPubKeyModelByLabel("Deposit BIP49"),
                    getPubKeyModelByLabel("Deposit BIP44")
                ))
            } else {
                transactionComposer.setPubKey(listOf(selectPubKeyModel))
            }
            setBalance(mCollection!!.pubs[newIndex-1])
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.setText(mCollection!!.pubs.get(newIndex-1).label, false)

            view?.isEnabled = true
            view?.alpha = 1f
            fragmentSpendBinding.composeBtn.isEnabled = true
            }
    }

    private fun isPubkeyAddressOrPremix(it: PubKeyModel): Boolean {
        //Also has to filter if collection is imported via Samourai Wallet pairing menu

        if (mCollection!!.isImportFromWallet) {
            if (it.label.equals("Deposit BIP49") || it.label.equals("Deposit BIP44"))
                return false
            if (it.label.equals("Deposit BIP84"))
                it.label = "Deposit"
        }

        if (it.type == AddressTypes.ADDRESS)
            return false

        val xpub = XPUB(it.pubKey)
        xpub.decode()
        val account = xpub.child + HARDENED
        if (account == PREMIX_ACC)
            return false

        return true
    }

    private fun findFirstNonAddressPubkey(): Int {
        //Pubkey must also NOT be Premix
        mCollection!!.pubs.forEach {
            if (it.type != AddressTypes.ADDRESS) {
                val xpub = XPUB(it.pubKey)
                xpub.decode()
                val account = xpub.child + HARDENED
                if (account != PREMIX_ACC)
                    return mCollection!!.pubs.indexOf(it)+1
            }
        }
        return 1
    }

    private fun setPubKeySelector() {
        decimalFormatBTC.minimumIntegerDigits = 1
        decimalFormatBTC.minimumFractionDigits = 8
        decimalFormatBTC.maximumFractionDigits = 8

        if (mCollection == null) {
            return
        }
        mCollection?.let { pubKeySelector ->
            val items = pubKeySelector.pubs.filter { isPubkeyAddressOrPremix(it) }.map { it.label }.toMutableList()

            val adapter: ArrayAdapter<String> = ArrayAdapter(
                    requireContext(),
                    R.layout.dropdown_menu_popup_item, items
            )
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.inputType = InputType.TYPE_NULL
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.threshold = items.size
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.setAdapter(adapter)
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.onItemClickListener = AdapterView.OnItemClickListener { _, _, index, _ ->
                val selectPubKeyModel = getPubKeyModelByLabel(items[index])
                viewModel.setPublicKey(selectPubKeyModel, viewLifecycleOwner, mCollection!!)

                if (mCollection!!.isImportFromWallet && selectPubKeyModel.label.equals("Deposit")) {
                    transactionComposer.setPubKey(listOf(
                        getPubKeyModelByLabel("Deposit BIP84"),
                        getPubKeyModelByLabel("Deposit BIP49"),
                        getPubKeyModelByLabel("Deposit BIP44")
                    ))
                } else {
                    transactionComposer.setPubKey(listOf(selectPubKeyModel))
                }
                setBalance(getPubKeyModelByLabel(items[index]))

                view?.isEnabled = true
                view?.alpha = 1f
                fragmentSpendBinding.composeBtn.isEnabled = true
            }
            if (items.size == 0) {
                return
            }
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.setText(items.first(), false)
            viewModel.setPublicKey(getPubKeyModelByLabel(items[0]), viewLifecycleOwner,
                mCollection!!
            )
        }
    }

    private fun getPubKeyModelByLabel(label: String): PubKeyModel {
        mCollection?.pubs?.forEach {
            if (it.label == label)
                return it
        }
        return mCollection!!.pubs[0]
    }

    private fun setBalance(pub: PubKeyModel) {

        if (mCollection!!.isImportFromWallet && pub.label == "Deposit") {
            var blockedUtxoBalanceSum = 0L
            val totalBalance =
                getPubKeyModelByLabel("Deposit BIP84").balance + getPubKeyModelByLabel("Deposit BIP44").balance + getPubKeyModelByLabel("Deposit BIP49").balance
            val depositPubs = listOf(
                getPubKeyModelByLabel("Deposit BIP84").pubKey,
                getPubKeyModelByLabel("Deposit BIP49").pubKey,
                getPubKeyModelByLabel("Deposit BIP44").pubKey
            )
            depositPubs.forEach {
                val blockedUtxos = UtxoMetaUtil.getBlockedAssociatedWithPubKey(it)
                blockedUtxos.forEach { utxo ->
                    blockedUtxoBalanceSum += utxo.amount
                }
            }
            val finalBalance = totalBalance - blockedUtxoBalanceSum
            availableBalance = finalBalance
            fragmentSpendBinding.fragmentComposeTx.totalBTC.text =
                decimalFormatBTC.format(finalBalance.div(1e8)).toString() + " BTC"
        } else {
            var blockedUtxoBalanceSum = 0L
            val blockedUtxos = UtxoMetaUtil.getBlockedAssociatedWithPubKey(pub.pubKey)
            blockedUtxos.forEach { utxo ->
                blockedUtxoBalanceSum += utxo.amount
            }
            val balance = pub.balance - blockedUtxoBalanceSum
            availableBalance = balance
            fragmentSpendBinding.fragmentComposeTx.totalBTC.text =
                decimalFormatBTC.format(balance.div(1e8)).toString() + " BTC"
        }
    }

    private fun setUpFee() {
        val multiplier = 10000
//        FEE_TYPE = PrefsUtil.getInstance(this).getValue(PrefsUtil.CURRENT_FEE_TYPE, SendActivity.FEE_NORMAL)
        feeLow = feeRepository.getLowFee().defaultPerKB.toLong() / 1000L
        feeMed = feeRepository.getNormalFee().defaultPerKB.toLong() / 1000L
        feeHigh = feeRepository.getHighFee().defaultPerKB.toLong() / 1000L

        val high = feeHigh / 2 + feeHigh
        val feeHighSliderValue = (high * multiplier)
        val feeMedSliderValue = (feeMed * multiplier)
        val valueTo = (feeHighSliderValue - multiplier).toFloat()
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.stepSize = 1F
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueTo = if (valueTo <= 0) feeHighSliderValue.toFloat() else valueTo
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueTo = if (valueTo <= 0) feeHighSliderValue.toFloat() else valueTo
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueFrom = 1F
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.setLabelFormatter { i: Float ->
            val value = (i + multiplier) / multiplier
            val formatted = "${decimalFormat.format(value)} sat/vB"
            fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRate.text = formatted
            formatted
        }
        if (feeLow == feeMed && feeMed == feeHigh) {
            feeLow = (feeMed.toDouble() * 0.85).toLong()
            feeHigh = (feeMed.toDouble() * 1.15).toLong()
            val loSf = SuggestedFee()
            loSf.defaultPerKB = BigInteger.valueOf(feeLow * 1000L)
            feeRepository.setLowFee(loSf)
            val hiSf = SuggestedFee()
            hiSf.defaultPerKB = BigInteger.valueOf(feeHigh * 1000L)
            feeRepository.setHighFee(hiSf)
        } else if (feeLow == feeMed || feeMed == feeMed) {
            feeMed = (feeLow + feeHigh) / 2L
            val miSf = SuggestedFee()
            miSf.defaultPerKB = BigInteger.valueOf(feeHigh * 1000L)
            feeRepository.setNormalFee(miSf)
        }
        if (feeLow < 1L) {
            feeLow = 1L
            val loSf = SuggestedFee()
            loSf.defaultPerKB = BigInteger.valueOf(feeLow * 1000L)
            feeRepository.setLowFee(loSf)
        }
        if (feeMed < 1L) {
            feeMed = 1L
            val miSf = SuggestedFee()
            miSf.defaultPerKB = BigInteger.valueOf(feeMed * 1000L)
            feeRepository.setNormalFee(miSf)
        }
        if (feeHigh < 1L) {
            feeHigh = 1L
            val hiSf = SuggestedFee()
            hiSf.defaultPerKB = BigInteger.valueOf(feeHigh * 1000L)
            feeRepository.setHighFee(hiSf)
        }
        fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRateLayman.text = getString(R.string.normal)
        feeRepository.sanitizeFee()
        fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRate.text = ("$feeMed sat/vB")
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.value = (feeMedSliderValue - multiplier + 1).toFloat()
        setFeeLabels()
        viewModel.setFee(((fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.value + multiplier) / multiplier)*1000)
        var nbBlocks = 6
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.addOnChangeListener { slider, sliderVal, fromUser ->
            val value = (sliderVal + multiplier) / multiplier
            var pct = 0F
            if (value <= feeLow) {
                pct = feeLow / value
                nbBlocks = ceil(pct * 24.0).toInt()
            } else if (value >= feeHigh.toFloat()) {
                pct = feeHigh / value
                nbBlocks = ceil(pct * 2.0).toInt()
                if (nbBlocks < 1) {
                    nbBlocks = 1
                }
            } else {
                pct = feeMed / value
                nbBlocks = ceil(pct * 6.0).toInt()
            }
            fragmentSpendBinding.fragmentComposeTx.feeSelector.estBlockTime.text = "$nbBlocks blocks"
            if (nbBlocks > 50) {
                fragmentSpendBinding.fragmentComposeTx.feeSelector.estBlockTime.text = "50+ blocks"
            }
            setFeeLabels()
            viewModel.setFee(value * 1000)
        }
        fragmentSpendBinding.fragmentComposeTx.feeSelector.estBlockTime.text = "$nbBlocks blocks"
    }

    private fun setFeeLabels() {
        if (fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueTo <= 0) {
            return
        }
        val sliderValue: Float = fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.value / fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueTo
        val sliderInPercentage = sliderValue * 100
        if (sliderInPercentage < 33) {
            fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRateLayman.setText(R.string.low)
        } else if (sliderInPercentage > 33 && sliderInPercentage < 66) {
            fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRateLayman.setText(R.string.normal)
        } else if (sliderInPercentage > 66) {
            fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRateLayman.setText(R.string.urgent)
        }
    }

    private fun generateQRCode(uri: String) {
        viewModel.viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                fragmentSpendBinding.fragmentBroadcastTx.psbtQRCode.maxFragmentLength = 40
                fragmentSpendBinding.fragmentBroadcastTx.psbtQRCode.fps = 5
                fragmentSpendBinding.fragmentBroadcastTx.psbtQRCode.setContent(UR.fromBytes(RegistryType.CRYPTO_PSBT.type,Hex.decode(uri)));
            }
        }
    }
}
