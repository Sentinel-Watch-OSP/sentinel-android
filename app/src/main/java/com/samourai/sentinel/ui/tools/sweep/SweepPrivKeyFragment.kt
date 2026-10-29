package com.samourai.sentinel.ui.tools.sweep

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.math.MathUtils
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import com.invertedx.hummingbird.QRScanner
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.hd.HD_Account
import com.samourai.sentinel.core.segwit.P2SH_P2WPKH
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.databinding.ContentCollectionSelectBinding
import com.samourai.sentinel.databinding.ContentPubkeySelectBinding
import com.samourai.sentinel.databinding.ContentSweepPreviewBinding
import com.samourai.sentinel.databinding.FragmentBottomsheetViewPagerBinding
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.adapters.CollectionsAdapter
import com.samourai.sentinel.ui.adapters.PubkeysAdapter
import com.samourai.sentinel.ui.fragments.AddNewPubKeyBottomSheet
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.RecyclerViewItemDividerDecorator
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.ui.views.SuccessfulBottomSheet
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.apiScope
import com.samourai.wallet.SamouraiWalletConst
import com.samourai.wallet.api.backend.beans.UnspentOutput
import com.samourai.wallet.bipFormat.BIP_FORMAT
import com.samourai.wallet.bipFormat.BipFormat
import com.samourai.wallet.bipFormat.BipFormatSupplier
import com.samourai.wallet.segwit.SegwitAddress
import com.samourai.wallet.send.MyTransactionOutPoint
import com.samourai.wallet.send.SendFactoryGeneric
import com.samourai.wallet.send.beans.SweepPreview
import com.samourai.wallet.util.FeeUtil
import com.samourai.wallet.util.PrivKeyReader
import com.samourai.wallet.util.TxUtil
import com.samourai.wallet.util.XPUB
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Transaction
import org.koin.java.KoinJavaComponent
import timber.log.Timber
import java.math.BigInteger
import java.text.DecimalFormat
import kotlin.math.ceil


class SweepPrivKeyFragment(private val privKey: String = "", secure: Boolean = false) : GenericBottomSheet(secure = secure) {

    private val scanPubKeyFragment = ScanPubKeyFragment(privKey)
    private val chooseCollectionFragment = ChooseCollectionFragment()
    private val choosePubkeyFragment = ChoosePubkeyFragment()
    private val previewBottomSheet = PreviewBottomSheet()
    private var pubKeyString = ""
    var privKeyReader: PrivKeyReader? = null
    private var _binding: FragmentBottomsheetViewPagerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBottomsheetViewPagerBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setUpViewPager()

        scanPubKeyFragment.setOnPreviewButtonCall {
            validate(it)
            pubKeyString = it
        }

        previewBottomSheet.setOnChangePubkeyButton {
            previewBottomSheet.dismiss()
            binding.pager.setCurrentItem(2, true)
        }

        chooseCollectionFragment.setOnSelectListener {
            choosePubkeyFragment.setSelectedCollection(it!!)
            previewBottomSheet.setSelectedCollection(it)
            if (!it.isImportFromWallet)
                binding.pager.setCurrentItem(2, true)
            else {
                this.dismiss()
                previewBottomSheet.setSelectedPubkey(it.pubs.get(0))
                previewBottomSheet.show(requireActivity().supportFragmentManager, previewBottomSheet.tag)
            }
        }

        choosePubkeyFragment.setOnSelectListener {
            this.dismiss()
            previewBottomSheet.setSelectedPubkey(it!!)
            previewBottomSheet.show(requireActivity().supportFragmentManager, previewBottomSheet.tag)
        }

        if (privKey.isNotEmpty()) {
            pubKeyString = privKey
        }
    }


    private fun validate(payload: String) {
        view?.findViewById<CircularProgressIndicator>(R.id.sweepProgress)?.visibility = View.VISIBLE
        when {
            PrivKeyReader(payload.trim(), SentinelState.getNetworkParam()).format != null -> {
                privKeyReader = PrivKeyReader(payload.trim(), SentinelState.getNetworkParam())
                apiScope.launch {
                    withContext(Dispatchers.Default) {
                        findUTXOs()
                        previewBottomSheet.setPrivKeyReader(privKeyReader!!)
                    }
                }
            }
            else -> {
                view?.findViewById<CircularProgressIndicator>(R.id.sweepProgress)?.visibility = View.INVISIBLE
                Toast.makeText(context, "Invalid private key", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun findUTXOs(timelockDerivationIndex: Int = -1) {
        val apiService: ApiService by KoinJavaComponent.inject(ApiService::class.java)
        var foundUTXO = false
        withContext(Dispatchers.IO) {
            val bipFormats: Collection<BipFormat> = getBipFormats(timelockDerivationIndex)
            bipFormats.forEach {
                val address = it.getToAddress(privKeyReader!!.key, privKeyReader!!.params)
                // No runBlocking: this is already inside withContext(Dispatchers.IO)
                // within a suspend function, so it can simply await. runBlocking here
                // pinned the thread and could deadlock.
                val items = apiService.fetchAddressForSweep(address)
                if (items.isNotEmpty()) {
                    previewBottomSheet.setUTXOList(items)
                    previewBottomSheet.setBipFormat(it)
                    binding.pager.setCurrentItem(1, true)
                    foundUTXO = true
                }
            }
            if (!foundUTXO) {
                if (isAdded && activity != null) {
                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            context,
                            "This private key doesn't have any UTXOs",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                dismiss()
            }
        }
    }

    private fun getBipFormats(timelockDerivationIndex: Int = -1) : List<BipFormat> {
        if (timelockDerivationIndex >= 0) {
            return emptyList()
            //return listOf(FidelityBondsTimelockedBipFormat.create(timelockDerivationIndex))
        } else {
            return listOf(
                BIP_FORMAT.LEGACY,
                BIP_FORMAT.SEGWIT_COMPAT,
                BIP_FORMAT.SEGWIT_NATIVE,
                BIP_FORMAT.TAPROOT
            )
        }
    }

    private fun setUpViewPager() {

        val item = arrayListOf<Fragment>()
        item.add(scanPubKeyFragment)
        item.add(chooseCollectionFragment)
        item.add(choosePubkeyFragment)
        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return item.size
            }

            override fun createFragment(position: Int): Fragment {
                return item[position]
            }

        }
        binding.pager.isUserInputEnabled = false
    }

}


class ScanPubKeyFragment(private val privKey: String = "") : Fragment() {

    private lateinit var pasteBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var startSweepBtn: Button
    private lateinit var textPrivKey: TextInputLayout
    private val appContext: Context by KoinJavaComponent.inject(Context::class.java)
    private var onPreviewButtonCall: (scanData: String) -> Unit = {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.scan_private_tool, container, false)
    }

    fun setOnPreviewButtonCall(callback: (scanData: String) -> Unit) {
        this.onPreviewButtonCall = callback
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        pasteBtn = view.findViewById(R.id.pastePrivKeyButton)
        scanBtn = view.findViewById(R.id.scanPrivKey)
        startSweepBtn = view.findViewById(R.id.sweepStartBtn)
        textPrivKey = view.findViewById(R.id.textPrivateKey)

        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        startSweepBtn.alpha = 0.6f

        if (privKey.isNotEmpty() && isPrivateKey(privKey)) {
            startSweepBtn.alpha = 1f
            textPrivKey.editText!!.setText(privKey)
        }

        startSweepBtn.setOnClickListener {
            if (startSweepBtn.alpha == 1f)
                onPreviewButtonCall(textPrivKey.editText!!.text.toString())
        }

        scanBtn.setOnClickListener {
            if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, appContext))
                askCameraPermission()
            else {
                val camera = ScanPrivKeyFragment()
                camera.show(requireFragmentManager(), "scanner_tag")
                camera.setOnScanListener {
                    if (isPrivateKey(it))
                        startSweepBtn.alpha = 1f
                    else
                        startSweepBtn.alpha = 0.6f

                    textPrivKey.editText!!.setText(it)
                    camera.dismiss()
                }
            }
        }

        pasteBtn.setOnClickListener {
            when {
                !clipboard.hasPrimaryClip() -> {
                    Toast.makeText(context, "Private key not found in clipboard", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    try {
                        val item = clipboard.primaryClip?.getItemAt(0)
                        textPrivKey.editText!!.setText(item?.text.toString())
                        if (isPrivateKey(item?.text.toString()))
                            startSweepBtn.alpha = 1f
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error parsing private key", Toast.LENGTH_SHORT).show()
                    }

                }
            }
        }

    }

    fun isPrivateKey(payload: String): Boolean {
        return PrivKeyReader(payload.trim(), SentinelState.getNetworkParam()).format != null
    }

    fun askCameraPermission() {
        MaterialAlertDialogBuilder(appContext)
            .setTitle(resources.getString(R.string.permission_alert_dialog_title_camera))
            .setMessage(resources.getString(R.string.permission_dialog_message_camera))
            .setNegativeButton(resources.getString(R.string.no)) { _, _ ->
                val bottomSheetFragment = AddNewPubKeyBottomSheet()
                bottomSheetFragment.show(requireFragmentManager(), bottomSheetFragment.tag)
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

}

class ChooseCollectionFragment : Fragment() {

    private val repository: CollectionRepository by KoinJavaComponent.inject(CollectionRepository::class.java)
    private val collectionsAdapter = CollectionsAdapter()
    private var onSelect: (PubKeyCollection?) -> Unit = {}

    private var _binding: ContentCollectionSelectBinding? = null
    private val binding get() = _binding!!
    private val HARDENED = 2147483648
    private val POSTMIX_ACC = 2147483646L
    private val PREMIX_ACC = 2147483645L
    private val BADBANK_ACC = 2147483644L


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ContentCollectionSelectBinding.inflate(inflater, container, false)
        binding.textView15.text = "Choose which Collection to receive the sweep to"
        val view = binding.root
        binding.view3.visibility = View.INVISIBLE
        return view
    }

    fun setOnSelectListener(callback: (PubKeyCollection?) -> Unit = {}) {
        this.onSelect = callback
    }


    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        binding.createNewCollection.visibility = View.INVISIBLE
        setUpCollectionSelectList()
        binding.createNewCollection.setOnClickListener {
            this.onSelect(null)
        }
    }

    private fun containsOtherThanWhirlpoolAccounts(collection: PubKeyCollection): Boolean {
        collection.pubs.forEach {
            if (it.type != AddressTypes.ADDRESS) {
                val xpub = XPUB(it.pubKey)
                xpub.decode()
                val account = xpub.child + HARDENED
                if (account != POSTMIX_ACC && account != PREMIX_ACC && account != BADBANK_ACC)
                    return true
            }
            else
                return true
        }
        return false
    }

    private fun setUpCollectionSelectList() {

        repository.collectionsLiveData.observe(viewLifecycleOwner, Observer {
            val collections = it
            val filteredCollections = ArrayList(collections.filter { containsOtherThanWhirlpoolAccounts(it) }.map { it }.toMutableList())
            collectionsAdapter.update(filteredCollections)
        })
        collectionsAdapter.setLayoutType(CollectionsAdapter.Companion.LayoutType.STACKED)
        val linearLayoutManager = LinearLayoutManager(context)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL
        val decorator = RecyclerViewItemDividerDecorator(ContextCompat.getDrawable(requireContext(), R.drawable.divider_grey)!!)
        binding.collectionSelectRecyclerView.apply {
            adapter = collectionsAdapter
            layoutManager = linearLayoutManager
            setHasFixedSize(true)
            addItemDecoration(decorator)
        }

        collectionsAdapter.setOnClickListener {
            this.onSelect(it)
        }
    }


}

class ChoosePubkeyFragment : Fragment() {

    private val repository: CollectionRepository by KoinJavaComponent.inject(CollectionRepository::class.java)
    private val pubkeysAdapter = PubkeysAdapter()
    private var onSelect: (PubKeyModel?) -> Unit = {}

    private var _binding: ContentPubkeySelectBinding? = null
    private val binding get() = _binding!!
    private lateinit var selectedCollection: PubKeyCollection
    private val HARDENED = 2147483648
    private val POSTMIX_ACC = 2147483646L
    private val PREMIX_ACC = 2147483645L
    private val BADBANK_ACC = 2147483644L
    private val SWAPS_REFUND = 2147483642L
    private val SWAPS_ASB = 2147483641L


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ContentPubkeySelectBinding.inflate(inflater, container, false)
        val view = binding.root
        binding.view3.visibility = View.INVISIBLE
        return view
    }

    fun setOnSelectListener(callback: (PubKeyModel?) -> Unit = {}) {
        this.onSelect = callback
    }


    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        binding.createNewCollection.visibility = View.INVISIBLE
        binding.createNewCollection.setOnClickListener {
            this.onSelect(null)
        }
    }

    fun setSelectedCollection(collection: PubKeyCollection) {
        this.selectedCollection = collection
        setUpCollectionSelectList()
    }

    private fun isPubAllowedToReceive(pubkey: PubKeyModel): Boolean {
        return if (pubkey.type != AddressTypes.ADDRESS) {
            val xpub = XPUB(pubkey.pubKey)
            xpub.decode()
            val account = xpub.child + HARDENED
            (account != POSTMIX_ACC && account != PREMIX_ACC && account != BADBANK_ACC &&
                    account != SWAPS_ASB && account != SWAPS_REFUND)
        } else
            true
    }
    private fun setUpCollectionSelectList() {

        repository.collectionsLiveData.observe(viewLifecycleOwner) {
            val collectionPubs = repository.findById(selectedCollection.id)!!.pubs
            val filteredPubs = ArrayList(collectionPubs.filter { isPubAllowedToReceive(it) }.map { it }.toMutableList())
            pubkeysAdapter.update(filteredPubs)
        }

        pubkeysAdapter.setLayoutType(PubkeysAdapter.Companion.LayoutType.STACKED)
        val linearLayoutManager = LinearLayoutManager(context)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL
        val decorator = RecyclerViewItemDividerDecorator(ContextCompat.getDrawable(requireContext(), R.drawable.divider_grey)!!)
        binding.collectionSelectRecyclerView.apply {
            adapter = pubkeysAdapter
            layoutManager = linearLayoutManager
            setHasFixedSize(true)
            addItemDecoration(decorator)
        }

        pubkeysAdapter.setOnClickListener {
            this.onSelect(it)
        }
    }


}

class PreviewBottomSheet(private var selectedCollection: PubKeyCollection? = null, private var selectedPubKey: PubKeyModel? = null) : GenericBottomSheet() {
    private var _binding: ContentSweepPreviewBinding? = null
    private val binding get() = _binding!!

    private lateinit var utxoList: MutableList<UnspentOutput>
    private lateinit var bipFormat: BipFormat
    private lateinit var sweepPreview: SweepPreview
    private lateinit var privKeyReader: PrivKeyReader
    private var feeRange: Float? = null

    private var feeLow: Long = 0L
    private var feeMed: Long = 0L
    private var feeHigh: Long = 0L

    private var selectedFee: Long = 1000L

    private var onChangePubkeyButton: () -> Unit = {}

    private val exchangeRateRepository: ExchangeRateRepository by KoinJavaComponent.inject(
        ExchangeRateRepository::class.java
    )
    private val repository: CollectionRepository by KoinJavaComponent.inject(CollectionRepository::class.java)
    private val transactionsRepository: TransactionsRepository by KoinJavaComponent.inject(
        TransactionsRepository::class.java
    )
    private var netWorkJobs: ArrayList<Job?> = arrayListOf()
    val feeRepository: FeeRepository by KoinJavaComponent.inject(FeeRepository::class.java)
    val apiService: ApiService by KoinJavaComponent.inject(ApiService::class.java)


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ContentSweepPreviewBinding.inflate(inflater, container, false)
        val view = binding.root

        binding.sweepPreviewCircularProgress.visibility = View.GONE
        binding.feeSelector.totalMinerFeeGroup.visibility = View.INVISIBLE

        preparePreview()

        setUpFee()

        if (selectedCollection!!.isImportFromWallet) {
            binding.changePubkey.setOnClickListener {
                onChangePubkeyButton()
            }
        }
        else {
            binding.changePubkey.visibility = View.GONE
        }

        binding.sweepBtn.setOnClickListener {
            val hexTx = broadcastTx()
            var response: String? = null
            apiScope.launch {
                try {
                    response = apiService.broadcast(hexTx!!)
                } catch (e: Exception) {
                    Log.d("SweepPrivateKey", "Error broadcasting tx: " + e)
                }

                requireActivity().runOnUiThread {
                    view.findViewById<ConstraintLayout>(R.id.sweepPreviewTitleLayout).visibility =
                        View.GONE
                    view.findViewById<ConstraintLayout>(R.id.sweepPreviewCircularProgress).visibility =
                        View.VISIBLE
                    view.findViewById<NestedScrollView>(R.id.sweepPreveiewScrollView).visibility =
                        View.GONE
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (response == null) {
                            Toast.makeText(
                                context,
                                "Error broadcasting transaction",
                                Toast.LENGTH_SHORT
                            ).show()
                            dismiss()
                        } else if (response != "TX_ID_NOT_FOUND") {
                            if (isAdded && activity != null) {
                                /*
                                try {
                                    exchangeRateRepository.fetch()
                                    repository.pubKeyCollections.forEach {

                                        val job = apiScope.launch {
                                            try {
                                                transactionsRepository.fetchFromServer(it)
                                            } catch (e: Exception) {
                                                Timber.e(e)
                                                throw CancellationException(e.message)
                                            }
                                        }
                                        netWorkJobs.add(job)
                                    }
                                    val job = apiScope.launch {
                                        try {
                                            feeRepository.getDynamicFees()
                                        } catch (e: Exception) {
                                            Timber.e(e)
                                            throw CancellationException(e.message)
                                        }
                                    }
                                    netWorkJobs.add(job)

                                    if (netWorkJobs.isNotEmpty()) {
                                        //Save last sync time to prefs
                                        netWorkJobs[netWorkJobs.lastIndex]?.let {
                                            it.invokeOnCompletion { error ->
                                                if (error != null) {
                                                    Timber.e(error)
                                                    return@invokeOnCompletion
                                                }
                                            }
                                        }
                                    }
                                } catch (ex: Exception) {
                                    Timber.e(ex)
                                }
                                 */
                                dismiss()
                                val bottomSheet = SuccessfulBottomSheet(
                                    "Sweep Successful",
                                    response!!,
                                    onViewReady = {
                                        it.view
                                    },
                                )
                                bottomSheet.show(
                                    requireActivity().supportFragmentManager,
                                    bottomSheet.tag
                                )
                            }
                        } else {
                            Toast.makeText(
                                context,
                                "Error broadcasting transaction",
                                Toast.LENGTH_SHORT
                            ).show()
                            dismiss()
                        }
                    }, 4000)
                }
            }
        }

        return view
    }

    fun setOnChangePubkeyButton(callback: () -> Unit) {
        this.onChangePubkeyButton = callback
    }

    fun broadcastTx(): String? {
        val feeRepository: FeeRepository by KoinJavaComponent.inject(FeeRepository::class.java)
        var hexTx: String? = null

        feeLow = 1000L
        feeMed = feeRepository.getNormalFee().defaultPerKB.toLong()
        feeHigh = feeRepository.getHighFee().defaultPerKB.toLong()
        if (feeHigh == 1000L && feeLow == 1000L) {
            feeHigh = 3000L
        }
        var transaction: Transaction? = null
        try {
            val receiveAddress = getAddress(selectedPubKey!!)
            val rbfOptin = false //PrefsUtil.getInstance(context).getValue(PrefsUtil.RBF_OPT_IN, false)
            val blockHeight = SentinelState.blockHeight?.height ?: -1L
            val totalValue = UnspentOutput.sumValue(utxoList)
            val address: String? = bipFormat.getToAddress(privKeyReader.key, privKeyReader.params)
            var feePerKb = MathUtils.lerp(feeLow.toFloat(), feeHigh.toFloat(), feeRange ?: 0f).coerceAtLeast(1f)
            var fee: Long = computeFee(bipFormat, utxoList, selectedFee.div(1000.0).toLong())
            var amount = totalValue - fee
            //Check if the amount too low for a tx or miner fee is high
            if (amount == 0L || fee > totalValue || amount <= SamouraiWalletConst.bDust.toLong()) {
                //check if the tx is possible with 1sat/b rate
                feeRange = 0.1f
                feePerKb = MathUtils.lerp(feeLow.toFloat(), feeHigh.toFloat(), 0.0f).coerceAtLeast(1f)
                fee = computeFee(bipFormat, utxoList, feePerKb.div(1000.0).toLong())
                amount = totalValue - fee
            }
            sweepPreview = SweepPreview(amount, address, bipFormat, fee, utxoList, privKeyReader.key, privKeyReader.params)
            val params = sweepPreview.params
            val receivers: MutableMap<String, Long> = LinkedHashMap()
            receivers[receiveAddress] = sweepPreview.amount
            val outpoints: MutableCollection<MyTransactionOutPoint> = mutableListOf()
            sweepPreview.utxos
                .map { unspentOutput: UnspentOutput -> unspentOutput.computeOutpoint(params) }.toCollection(outpoints)
            val bipFormatSupplier: BipFormatSupplier = getBipFormatSupplier(bipFormat)
            val tr = createTransaction(receivers, outpoints, bipFormatSupplier, rbfOptin, blockHeight)
            transaction = TransactionForSweepHelper.signTransactionForSweep(tr, sweepPreview.privKey, params, bipFormatSupplier)
            try {
                if (transaction != null) {
                    hexTx = TxUtil.getInstance().getTxHex(transaction)
                }
            } catch (e: Exception) {
                throw  CancellationException("pushTx : ${e.message}")
            }
        } catch (e: Exception) {
            println( "issue on making transaction : " + e.message + ":: " + e)
        }
        return hexTx
    }
    private fun createTransaction(
        receivers: MutableMap<String, Long>,
        outpoints: MutableCollection<MyTransactionOutPoint>,
        bipFormatSupplier: BipFormatSupplier,
        rbfOptin: Boolean,
        blockHeight: Long
    ): Transaction? {
        return SendFactoryGeneric.getInstance()
            .makeTransaction(receivers, outpoints, bipFormatSupplier, rbfOptin, SentinelState.getNetworkParam(), blockHeight)
        /*
        if (FidelityBondsTimelockedBipFormat.ID.equals(bipFormat?.value?.id)) {
            return TransactionForSweepHelper.makeTimelockTransaction(receivers, outpoints,
                bipFormat!!.value as FidelityBondsTimelockedBipFormat?, params)
        } else {
            return SendFactoryGeneric.getInstance()
                .makeTransaction(receivers, outpoints, bipFormatSupplier, rbfOptin, SentinelState.getNetworkParam(), blockHeight)
        }
         */
    }

    fun getAddress(pubKey: PubKeyModel): String {
        val accountIndex = pubKey.account_index

        return when (pubKey.type) {
            AddressTypes.BIP44 -> {
                val account = HD_Account(SentinelState.getNetworkParam(), pubKey.pubKey, "", 0)
                account.getChain(0).addrIdx = accountIndex
                val hdAddress = account.getChain(0).getAddressAt(accountIndex)
                hdAddress.addressString
            }
            AddressTypes.BIP49 -> {
                val account = HD_Account(SentinelState.getNetworkParam(), pubKey.pubKey, "", 0)
                account.getChain(0).addrIdx = accountIndex
                val address = account.getChain(0).getAddressAt(accountIndex)
                val ecKey = address.ecKey
                val p2shP2wpkH = P2SH_P2WPKH(ecKey.pubKey, SentinelState.getNetworkParam())
                p2shP2wpkH.addressAsString
            }
            AddressTypes.BIP84 -> {
                val account = HD_Account(SentinelState.getNetworkParam(), pubKey.pubKey, "", 0)
                account.getChain(0).addrIdx = accountIndex
                val address = account.getChain(0).getAddressAt(accountIndex)
                val ecKey = address.ecKey
                val segwitAddress = SegwitAddress(ecKey.pubKey, SentinelState.getNetworkParam())
                segwitAddress.bech32AsString
            }
            AddressTypes.ADDRESS -> {
                pubKey.pubKey
            }
            else -> ""
        }

    }

    fun setSelectedPubkey(pubkey: PubKeyModel) {
        this.selectedPubKey = pubkey
    }

    override fun onResume() {
        super.onResume()
        preparePreview()
    }

    fun setPrivKeyReader(privKeyReader: PrivKeyReader) {
        this.privKeyReader = privKeyReader
    }

    fun setUTXOList(utxoList: MutableList<UnspentOutput>) {
        this.utxoList = utxoList
    }

    fun setBipFormat(bipFormat: BipFormat) {
        this.bipFormat = bipFormat
    }

    private fun preparePreview() {
        binding.receiveAddress.text = getAddress(selectedPubKey!!)
        binding.collectionAndPubkey.text = "${selectedCollection!!.collectionLabel}, ${selectedPubKey!!.label}"
        binding.fromAddress.text = this.utxoList.get(0).addr
        binding.amount.text = "${MonetaryUtil.getInstance().getBTCDecimalFormat(UnspentOutput.sumValue(utxoList))} BTC"
    }

    private fun setUpFee() {
        val decimalFormat = DecimalFormat("##.00")
        val multiplier = 10000
//        FEE_TYPE = PrefsUtil.getInstance(this).getValue(PrefsUtil.CURRENT_FEE_TYPE, SendActivity.FEE_NORMAL)
        feeLow = feeRepository.getLowFee().defaultPerKB.toLong() / 1000L
        feeMed = feeRepository.getNormalFee().defaultPerKB.toLong() / 1000L
        feeHigh = feeRepository.getHighFee().defaultPerKB.toLong() / 1000L

        val high = feeHigh / 2 + feeHigh
        val feeHighSliderValue = (high * multiplier)
        val feeMedSliderValue = (feeMed * multiplier)
        val valueTo = (feeHighSliderValue - multiplier).toFloat()
        binding.feeSelector.feeSlider.stepSize = 1F
        binding.feeSelector.feeSlider.valueTo = if (valueTo <= 0) feeHighSliderValue.toFloat() else valueTo
        binding.feeSelector.feeSlider.valueTo = if (valueTo <= 0) feeHighSliderValue.toFloat() else valueTo
        binding.feeSelector.feeSlider.valueFrom = 1F
        binding.feeSelector.feeSlider.setLabelFormatter { i: Float ->
            val value = (i + multiplier) / multiplier
            val formatted = "${decimalFormat.format(value)} sat/vB"
            binding.feeSelector.selectedFeeRate.text = formatted
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
        binding.feeSelector.selectedFeeRateLayman.text = getString(R.string.normal)
        feeRepository.sanitizeFee()
        binding.feeSelector.selectedFeeRate.text = ("$feeMed sat/vB")
        binding.feeSelector.feeSlider.value = (feeMedSliderValue - multiplier + 1).toFloat()
        setFeeLabels()
        selectedFee =
            (((binding.feeSelector.feeSlider.value + multiplier) / multiplier)*1000).toLong()
        var nbBlocks = 6
        binding.feeSelector.feeSlider.addOnChangeListener { slider, sliderVal, fromUser ->
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

            //binding.feeSelector.estBlockTime.text = "$nbBlocks blocks"
            if (nbBlocks > 50) {
                binding.feeSelector.estBlockTime.text = "50+ blocks"
            }
            setFeeLabels()
            selectedFee = (value * 1000).toLong()
            binding.totalMinerFee.text =
                "${computeFee(bipFormat, utxoList, selectedFee.div(1000.0).toLong())} sats"
            feeRange = sliderVal
            view?.findViewById<ConstraintLayout>(R.id.sweepPreviewCircularProgress)?.visibility = View.GONE
        }
        binding.feeSelector.estBlockTime.text = "$nbBlocks blocks"
        binding.totalMinerFee.text =
            "${computeFee(bipFormat, utxoList, selectedFee.div(1000.0).toLong())} sats"
    }

    private fun setFeeLabels() {
        if (binding.feeSelector.feeSlider.valueTo <= 0) {
            return
        }
        val sliderValue: Float = binding.feeSelector.feeSlider.value / binding.feeSelector.feeSlider.valueTo
        val decimalFormat = DecimalFormat("##.00")
        val feeValue = decimalFormat.format(binding.feeSelector.feeSlider.value.plus(10000).div(10000))
        binding.feeSelector.selectedFeeRate.text = "${feeValue} sat/vB"
        val sliderInPercentage = sliderValue * 100
        if (sliderInPercentage < 33) {
            binding.feeSelector.selectedFeeRateLayman.setText(R.string.low)
        } else if (sliderInPercentage > 33 && sliderInPercentage < 66) {
            binding.feeSelector.selectedFeeRateLayman.setText(R.string.normal)
        } else if (sliderInPercentage > 66) {
            binding.feeSelector.selectedFeeRateLayman.setText(R.string.urgent)
        }
    }

    private fun computeFee(bipFormat: BipFormat, unspentOutputs: Collection<UnspentOutput?>, feePerB: Long): Long {
        var inputsP2PKH = 0
        var inputsP2WPKH = 0
        var inputsP2SH_P2WPKH = 0
        if (bipFormat === BIP_FORMAT.SEGWIT_COMPAT) {
            inputsP2SH_P2WPKH = unspentOutputs.size
        } else if (bipFormat === BIP_FORMAT.SEGWIT_NATIVE) {
            inputsP2WPKH = unspentOutputs.size
        } else if (bipFormat === BIP_FORMAT.TAPROOT) {
            inputsP2WPKH = unspentOutputs.size
        } else {
            inputsP2PKH = unspentOutputs.size
        }

        return FeeUtil.getInstance().estimatedFeeSegwit(inputsP2PKH, inputsP2SH_P2WPKH, inputsP2WPKH, 1, 0, feePerB)
    }

    private fun getBipFormatSupplier(bipFormat: BipFormat?): BipFormatSupplier {
        /*
        if (FidelityBondsTimelockedBipFormat.ID.equals(bipFormat?.id)) {
            return FidelityBondsTimelockedBipFormatSupplier.create(bipFormat as FidelityBondsTimelockedBipFormat?);
        }
         */
        return BIP_FORMAT.PROVIDER
    }

    fun setSelectedCollection(collection: PubKeyCollection) {
        this.selectedCollection = collection
    }
}


class ScanPrivKeyFragment : BottomSheetDialogFragment() {

    private lateinit var  mCodeScanner: QRScanner;
    private val appContext: Context by KoinJavaComponent.inject(Context::class.java)
    private var onScan: (scanData: String) -> Unit = {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.content_scan_layout, container, false)
    }

    fun setOnScanListener(callback: (scanData: String) -> Unit) {
        this.onScan = callback
    }


    @SuppressLint("RestrictedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.pastePubKey).alpha = 0f
        mCodeScanner = view.findViewById(R.id.scannerViewXpub);
        mCodeScanner.setLifeCycleOwner(this)

        mCodeScanner.setQRDecodeListener {
            lifecycleScope.launch {
                onScan(it)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, appContext)) {
            mCodeScanner.startScanner()
        }
    }

    override fun onPause() {
        mCodeScanner.stopScanner()
        super.onPause()
    }

}
