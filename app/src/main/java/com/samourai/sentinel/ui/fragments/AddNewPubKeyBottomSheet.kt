package com.samourai.sentinel.ui.fragments

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.invertedx.hummingbird.QRScanner
import com.samourai.sentinel.R
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.databinding.ContentChooseAddressTypeBinding
import com.samourai.sentinel.databinding.ContentCollectionSelectBinding
import com.samourai.sentinel.databinding.FragmentBottomsheetViewPagerBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.adapters.CollectionsAdapter
import com.samourai.sentinel.ui.collectionEdit.CollectionEditActivity
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.tools.sweep.SweepPrivKeyFragment
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.RecyclerViewItemDividerDecorator
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.ui.views.alertWithInput
import com.samourai.sentinel.util.ExportImportUtil
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.wallet.util.PrivKeyReader
import com.samourai.wallet.util.XPUB
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.PathComponent
import com.sparrowwallet.hummingbird.registry.RegistryType
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.crypto.ChildNumber
import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import org.koin.java.KoinJavaComponent
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets


class AddNewPubKeyBottomSheet(private val pubKey: String = "", private val secure: Boolean = false) : GenericBottomSheet(secure = secure) {

    private val scanPubKeyFragment = ScanPubKeyFragment()
    private var newPubKeyListener: ((pubKey: PubKeyModel?) -> Unit)? = null
    private val selectAddressTypeFragment = SelectAddressTypeFragment()
    private val chooseCollectionFragment = ChooseCollectionFragment()
    private var pubKeyString = ""
    private var pubKeyModel: PubKeyModel? = null
    private var selectedPubKeyCollection: PubKeyCollection? = null
    private val collectionRepository: CollectionRepository by KoinJavaComponent.inject(
        CollectionRepository::class.java
    )
    private var _binding: FragmentBottomsheetViewPagerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentBottomsheetViewPagerBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    fun setSelectedCollection(pubKeyCollection: PubKeyCollection) {
        selectedPubKeyCollection = pubKeyCollection
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        setUpViewPager()

        scanPubKeyFragment.setOnScanListener {
            validate(it)
            pubKeyString = it
        }

        selectAddressTypeFragment.setOnSelectListener {
            validateXPUB(it)
        }

        chooseCollectionFragment.setOnSelectListener {
            startActivity(Intent(context, CollectionEditActivity::class.java).apply {
                this.putExtra("pubKey", pubKeyModel)
                if (it != null) {
                    this.putExtra("editIndex", collectionRepository.findById(it.id)?.pubs?.size!!)
                    this.putExtra("collection", it.id)
                }
            })
            this.dismiss()
        }

        if (pubKey.isNotEmpty()) {
            validate(pubKey)
            pubKeyString = pubKey
        }
    }

    private fun validateXPUB(addressTypes: AddressTypes) {

        pubKeyModel = if ((addressTypes == AddressTypes.BIP49 || addressTypes == AddressTypes.BIP84) && (pubKeyString.startsWith("xpub") || pubKeyString.startsWith("tpub"))) {
            val xpub = XPUB(pubKeyString)
            xpub.decode()
            pubKeyString =
                if (addressTypes == AddressTypes.BIP49 && pubKeyString.startsWith("tpub"))
                    XPUB.makeXPUB(XPUB.MAGIC_UPUB, xpub.depth, xpub.fingerprint, xpub.child, xpub.chain, xpub.getPubkey())
                else if (addressTypes == AddressTypes.BIP49 && pubKeyString.startsWith("xpub"))
                    XPUB.makeXPUB(XPUB.MAGIC_YPUB, xpub.depth, xpub.fingerprint, xpub.child, xpub.chain, xpub.getPubkey())
                else if (addressTypes == AddressTypes.BIP84 && pubKeyString.startsWith("tpub"))
                    XPUB.makeXPUB(XPUB.MAGIC_VPUB, xpub.depth, xpub.fingerprint, xpub.child, xpub.chain, xpub.getPubkey())
                else if (addressTypes == AddressTypes.BIP84 && pubKeyString.startsWith("xpub"))
                    XPUB.makeXPUB(XPUB.MAGIC_ZPUB, xpub.depth, xpub.fingerprint, xpub.child, xpub.chain, xpub.getPubkey())
                else
                    pubKeyString
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = addressTypes, fingerPrint = scanPubKeyFragment.getFingerprint())
        } else if (pubKeyString.startsWith("ypub") || pubKeyString.startsWith("upub")) {
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = addressTypes, fingerPrint = scanPubKeyFragment.getFingerprint())
        } else if (pubKeyString.startsWith("zpub") || pubKeyString.startsWith("vpub")) {
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = addressTypes, fingerPrint = scanPubKeyFragment.getFingerprint())
        } else {
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = AddressTypes.BIP44, fingerPrint = scanPubKeyFragment.getFingerprint())
        }

        if (newPubKeyListener != null) {
            this.newPubKeyListener?.let { it(pubKeyModel) }
            this.dismiss()
            newPubKeyListener = null
            return
        }
        if (selectedPubKeyCollection != null) {
            startActivity(Intent(context, CollectionEditActivity::class.java).apply {
                this.putExtra("pubKey", pubKeyModel)
                this.putExtra("collection", selectedPubKeyCollection?.id)
            })
            this.dismiss()
        } else {
            binding.pager.setCurrentItem(2, true)
        }

    }

    private fun validate(code: String) {
        val payload = FormatsUtil.extractPublicKey(code)
        val type = FormatsUtil.getPubKeyType(payload)
        if (FormatsUtil.isValidBitcoinAddress(payload.trim()) || FormatsUtil.isValidXpub(payload)) {
            if (isPublicKeyTesnet(payload) && !SentinelState.isTestNet()) {
                if (context != null)
                    Toast.makeText(context, "Can't track Testnet public keys in Mainnet", Toast.LENGTH_LONG).show()
                this.dismiss()
                return
            }
            if (!isPublicKeyTesnet(payload) && SentinelState.isTestNet()) {
                if (context != null)
                    Toast.makeText(context, "Can't track Mainnet public keys in Testnet", Toast.LENGTH_LONG).show()
                this.dismiss()
                return
            }
        }
        when {
            PrivKeyReader(payload.trim(), SentinelState.getNetworkParam()).format != null -> {
                if (isAdded && activity != null) {
                    this.dismiss()
                    val bottomSheetFragment = SweepPrivKeyFragment(payload.trim(), secure)
                    bottomSheetFragment.show(requireActivity().supportFragmentManager, bottomSheetFragment.tag)
                }
            }

            FormatsUtil.isValidBitcoinAddress(payload.trim()) -> {
                if (newPubKeyListener != null) {
                    val pubKey = PubKeyModel(pubKey = payload, type = AddressTypes.ADDRESS, label = "Untitled", fingerPrint = scanPubKeyFragment.getFingerprint())
                    newPubKeyListener?.let { it(pubKey) }
                    this.dismiss()
                    newPubKeyListener = null
                    return
                } else {
                    pubKeyModel = PubKeyModel(pubKey = payload, type = AddressTypes.ADDRESS, label = "Untitled", fingerPrint = scanPubKeyFragment.getFingerprint())
                    // Skip type selection screen since payload is an address
                    binding.pager.setCurrentItem(2, true)
                }
            }
            FormatsUtil.isValidXpub(code) -> {
                //show option to choose xpub type
                if (type == AddressTypes.BIP84 || type == AddressTypes.BIP49) {
                    pubKeyString = code
                    validateXPUB(type)
                }
                else {
                    binding.pager.setCurrentItem(1, true)
                    binding.pager.post {
                        selectAddressTypeFragment.setType(type)
                    }
                }
            }
            (JSONObject(payload.trim()).has("external") &&  JSONObject(payload.trim()).has("payload")) -> {
                if (isAdded && activity != null) {
                    this.dismiss()
                    val bottomSheetFragment = WalletPairingFragment(payload.trim(), secure)
                    bottomSheetFragment.show(requireActivity().supportFragmentManager, bottomSheetFragment.tag)
                }
            }
            else -> {
                Toast.makeText(context, "Invalid public key or payload", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isPublicKeyTesnet(payload: String): Boolean {
        if (payload.lowercase().startsWith("tb") || payload.lowercase().startsWith("2") || payload.lowercase().startsWith("m") || payload.lowercase().startsWith("n"))
            return true
        if (payload.lowercase().startsWith("tpub") || payload.lowercase().startsWith("upub") || payload.lowercase().startsWith("vpub"))
            return true
        return false
    }

    private fun setUpViewPager() {
        val item = arrayListOf<Fragment>()
        item.add(scanPubKeyFragment)
        item.add(selectAddressTypeFragment)
        item.add(chooseCollectionFragment)
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

    fun setPubKeyListener(listener: (pubKey: PubKeyModel?) -> Unit) {
        newPubKeyListener = listener
    }
}


class ScanPubKeyFragment : Fragment() {

    private lateinit var  mCodeScanner: QRScanner;
    private val appContext: Context by KoinJavaComponent.inject(Context::class.java)
    private var onScan: (scanData: String) -> Unit = {}
    private var fingerprintHex: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.content_scan_layout, container, false)
    }

    fun setOnScanListener(callback: (scanData: String) -> Unit) {
        this.onScan = callback
    }

    fun getFingerprint(): String? {
        return fingerprintHex
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.pastePubKey).text = "Paste"
        mCodeScanner = view.findViewById(R.id.scannerViewXpub);
        mCodeScanner.setLifeCycleOwner(this)

        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        mCodeScanner.setQRDecodeListener {
            lifecycleScope.launch {
                mCodeScanner.stopScanner()
                onScan(it)
            }
        }

        mCodeScanner.setURDecodeListener { result ->
            mCodeScanner.stopScanner()
            result.fold(
                onSuccess = {
                    val xpub = getXpubFromUR(it)
                    if (xpub != null) {
                        onScan(xpub)
                    }
                    else {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Error decoding public key")
                            .setPositiveButton("Ok") { dialog, which ->
                                dialog.dismiss()
                            }.show()
                        mCodeScanner.stopScanner()
                    }
                },
                onFailure = {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Error decoding UR")
                        .setMessage("Exception: ${it.message}")
                        .setPositiveButton("Ok") { dialog, which ->
                            dialog.dismiss()
                        }.show()
                    mCodeScanner.stopScanner()
                }
            )
        }

        view.findViewById<Button>(R.id.pastePubKey).setOnClickListener {
            when {
                !clipboard.hasPrimaryClip() -> {
                    Toast.makeText(context, "PubKey not found in clipboard", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    try {
                        val item = clipboard.primaryClip?.getItemAt(0)
                        onScan(item?.text.toString())
                    } catch (e: Exception) {
                        Toast.makeText(context, "PubKey not found in clipboard", Toast.LENGTH_SHORT).show()
                    }

                }
            }
        }

    }

    private fun getXpubFromUR(it: URDecoder.Result): String? {
        if (it.ur.registryType == RegistryType.CRYPTO_ACCOUNT) {
            val cryptoAccount = it.ur.decodeFromRegistry() as CryptoAccount
            for (outputDescriptor in cryptoAccount.outputDescriptors) {
                fingerprintHex = Hex.toHexString(cryptoAccount.masterFingerprint).lowercase()
                val cryptoHDKey = outputDescriptor.hdKey
                var lastChild = ChildNumber.ZERO
                var depth = 1
                var parentFingerprint = ByteArray(4)
                var version: Int
                if (cryptoHDKey.origin != null) {
                    if (cryptoHDKey.origin.components.isNotEmpty()) {
                        val lastComponent: PathComponent =
                            cryptoHDKey.origin.components[cryptoHDKey.origin.components.size - 1]
                        lastChild = ChildNumber(lastComponent.index, lastComponent.isHardened)
                        depth = cryptoHDKey.origin.depth
                    }
                    if (cryptoHDKey.origin.sourceFingerprint != null) {
                        parentFingerprint = cryptoHDKey.parentFingerprint
                    }
                }
                val parentFingerprintInt = ByteBuffer.wrap(parentFingerprint).int

                if (cryptoHDKey.origin.path.substring(0..1) == "44")
                    version = if (SentinelState.isTestNet())  XPUB.MAGIC_TPUB else XPUB.MAGIC_XPUB
                else if (cryptoHDKey.origin.path.substring(0..1) == "49")
                    version = if (SentinelState.isTestNet())  XPUB.MAGIC_UPUB else XPUB.MAGIC_YPUB
                else
                    version = if (SentinelState.isTestNet())  XPUB.MAGIC_VPUB else XPUB.MAGIC_ZPUB

                val xpub = XPUB.makeXPUB(
                    version,
                    depth.toByte(),
                    parentFingerprintInt,
                    lastChild.i,
                    cryptoHDKey.chainCode,
                    cryptoHDKey.key
                )
                return xpub
            }
        }
        else if (it.ur.registryType == RegistryType.BYTES) {
            val urBytes = it.ur.decodeFromRegistry()
            val decoder = StandardCharsets.UTF_8.newDecoder()
            val buf = ByteBuffer.wrap(urBytes as ByteArray)
            val charBuffer = decoder.decode(buf)
            val xpubsJson = JSONObject(charBuffer.toString())
            if (xpubsJson.has("bip84")) {
                try {
                    fingerprintHex = xpubsJson.getString("xfp").lowercase()
                } catch (e: Exception) {}
                return xpubsJson.getJSONObject("bip84").getString("_pub")
            }
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        if (AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, appContext)) {
            mCodeScanner?.startScanner()
        }
    }

    override fun onPause() {
        mCodeScanner?.stopScanner()
        super.onPause()
    }

}

class SelectAddressTypeFragment : Fragment() {
    private var onSelect: (type: AddressTypes) -> Unit = {}
    var addressType: AddressTypes = AddressTypes.BIP44

    private var _binding: ContentChooseAddressTypeBinding? = null
    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = ContentChooseAddressTypeBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    fun setOnSelectListener(callback: (type: AddressTypes) -> Unit = {}) {
        this.onSelect = callback
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        if (SentinelState.isTestNet()) {
            binding.buttonBIP44.text = getString(R.string.address_starting_mn_bip44)
            binding.buttonBIP49.text = getString(R.string.address_number2_bip49)
            binding.buttonBIP84.text = getString(R.string.address_starting_tb_bip84)
        }

        binding.radioGroup.setOnCheckedChangeListener { radioGroup, i ->
            when (i) {
                0 -> {
                    addressType = AddressTypes.BIP44
                }
                1 -> {
                    addressType = AddressTypes.BIP49
                }
                2 -> {
                    addressType = AddressTypes.BIP84
                }
            }
        }
        binding.nextBtn.setOnClickListener {
            onSelect(addressType)
        }

        binding.buttonBIP84.setOnClickListener(View.OnClickListener {
            addressType = AddressTypes.BIP84
        })
        binding.buttonBIP49.setOnClickListener(View.OnClickListener {
            addressType = AddressTypes.BIP49
        })
        binding.buttonBIP44.setOnClickListener(View.OnClickListener {
            addressType = AddressTypes.BIP44
        })

        when (addressType) {
            AddressTypes.BIP44 -> {
                binding.buttonBIP44.isChecked = true
            }
            AddressTypes.BIP49 -> {
                binding.buttonBIP49.isChecked = true
            }
            AddressTypes.BIP84 -> {
                binding.buttonBIP84.isChecked = true
            }
            AddressTypes.ADDRESS -> {
                //No-op
            }
        }
    }

    fun setType(type: AddressTypes?) {
        if (type != null) {
            addressType = type

        }
    }

}

class ChooseCollectionFragment : Fragment() {

    private val repository: CollectionRepository by KoinJavaComponent.inject(CollectionRepository::class.java)
    private val collectionsAdapter = CollectionsAdapter()
    private var onSelect: (PubKeyCollection?) -> Unit = {}

    private var _binding: ContentCollectionSelectBinding? = null
    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = ContentCollectionSelectBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    fun setOnSelectListener(callback: (PubKeyCollection?) -> Unit = {}) {
        this.onSelect = callback
    }


    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setUpCollectionSelectList()
        binding.createNewCollection.setOnClickListener {
            this.onSelect(null)
        }
    }

    private fun setUpCollectionSelectList() {
        repository.collectionsLiveData.observe(viewLifecycleOwner, Observer {
            val iterator = it.iterator()

            while (iterator.hasNext()) {
                val collection = iterator.next()
                if (collection.isImportFromWallet)
                    iterator.remove()
            }

            collectionsAdapter.update(it)
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