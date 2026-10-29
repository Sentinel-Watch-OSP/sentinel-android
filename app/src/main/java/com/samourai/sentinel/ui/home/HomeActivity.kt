package com.samourai.sentinel.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.ShapeAppearanceModel
import com.samourai.sentinel.R
import com.samourai.sentinel.api.APIConfig
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.ui.views.ConnectionIndicatorController
import com.samourai.sentinel.databinding.ActivityHomeBinding
import com.samourai.sentinel.service.WebSocketHandler
import com.samourai.sentinel.service.WebSocketService
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.adapters.CollectionsAdapter
import com.samourai.sentinel.ui.collectionDetails.CollectionDetailsActivity
import com.samourai.sentinel.ui.dojo.DojoConfigureBottomSheet
import com.samourai.sentinel.ui.fragments.AddNewPubKeyBottomSheet
import com.samourai.sentinel.ui.settings.NetworkActivity
import com.samourai.sentinel.ui.settings.SettingsActivity
import com.samourai.sentinel.ui.tools.ToolsActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PermissionResult
import com.samourai.sentinel.ui.utils.permissionResultOf
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.RecyclerViewItemDividerDecorator
import com.samourai.sentinel.ui.utils.SlideInItemAnimator
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.confirm
import com.samourai.sentinel.util.AppUtil
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.TimeOutUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import com.samourai.sentinel.widgets.popUpMenu.popupMenu
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject


class HomeActivity : SentinelActivity() {

    private lateinit var linearLayoutManager: LinearLayoutManager
    private val collectionsAdapter = CollectionsAdapter()
    private val webSocketHandler: WebSocketHandler by inject(WebSocketHandler::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private var connectingDojo = false
    private lateinit var binding: ActivityHomeBinding
    private val model: HomeViewModel by viewModels()
    private var balance = -1L
    private var indicatorController: ConnectionIndicatorController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        val view = binding.root
        window.statusBarColor = ContextCompat.getColor(this, R.color.mpm_black)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.grey_homeActivity)
        setContentView(view)
        setSwipeBackEnable(false)
        setSupportActionBar(binding.toolbarHome)

        title = ""

        binding.toolbarIcon.setOnClickListener {
            showToolOptions(it)
        }

        val model: HomeViewModel by viewModels()
        UtxoMetaUtil.read()

        if (SentinelState.isTorRequired() && SentinelTorManager.getTorState().state == EnumTorState.OFF) {
            SentinelTorManager.start()
            prefsUtil.enableTor = true
        }

        if (
            !AndroidUtil.isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS, applicationContext)
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && prefsUtil.firstRun == true
        )
            this.askNotificationPermission()

        setUp()

        setUpCollectionList()

        model.getCollections().observe(this, {
            if (it.isNotEmpty())
                binding.welcomeMessage.visibility = View.GONE
            else
                binding.welcomeMessage.visibility = View.VISIBLE

            collectionsAdapter.update(it)
        })

        model.getBalance().observe(this) {
            updateBalance(it)
            balance = it
        }

        binding.exchangeRateTxt.visibility = if (prefsUtil.fiatDisabled == true) View.INVISIBLE else View.VISIBLE

        model.getFiatBalance().observe(this, { updateFiat(it) })

        binding.fab.setBackgroundResource(R.drawable.background_gradient);

        binding.fab.setOnClickListener {
            connectingDojo = false
            if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext)) {
                this.askCameraPermission()
            } else {
                if (AppUtil.getInstance(applicationContext).isOfflineMode
                    ||  SentinelTorManager.getTorState().state == EnumTorState.STARTING)
                    Toast.makeText(this, "No data connection. Please wait, then try again", Toast.LENGTH_LONG).show()
                else
                    showPubKeyBottomSheet()
            }
        }

        // Drive all loading UI from the explicit SyncState.
        model.syncState().observe(this) { renderSyncState(it) }

        // NOTE: the toolbar indicator is wired up in setNetWorkMenu(), once the
        // menu's action view actually exists.

        // A corrupt payload must never look like "you have no wallets".
        model.payloadReadFailure().observe(this) { failure ->
            if (failure != null) {
                binding.welcomeMessage.visibility = View.GONE
                MaterialAlertDialogBuilder(this)
                    .setTitle("Could not load collections")
                    .setMessage(
                        "Your saved collections could not be read. They have NOT been " +
                            "deleted. Restart the app and re-enter your PIN. If this " +
                            "persists, restore from your backup."
                    )
                    .setPositiveButton(resources.getString(R.string.ok), null)
                    .show()
            }
        }

        binding.syncRetryButton.setOnClickListener {
            requestRefresh()
        }

        if (intent != null) {
            if (intent.hasExtra("forceRefresh") && intent.getBooleanExtra("forceRefresh", true)) {
                model.fetchBalance()
            }
        }

        binding.swipeRefreshCollection.setOnRefreshListener {
            // The banner is now the source of loading feedback; the pull spinner
            // only acknowledges the gesture.
            binding.swipeRefreshCollection.isRefreshing = false
            requestRefresh()
        }

        fetch(model)

        if (SentinelState.isTorRequired()) {
            SentinelTorManager.getTorStateLiveData().observe(this, {
                if (it.state == EnumTorState.ON)
                    WebSocketService.start(applicationContext)
            })
        } else {
            WebSocketService.start(applicationContext)
        }
    }

    /**
     * Renders the sync banner from the explicit [SyncState].
     *
     * Cached collections stay visible at all times; this only annotates them.
     */
    private fun renderSyncState(state: SyncState) {
        val banner = binding.syncStatusBanner
        val text = binding.syncStatusText
        val progress = binding.syncStatusProgress
        val retry = binding.syncRetryButton

        when (state) {
            is SyncState.Idle -> {
                banner.visibility = View.GONE
            }

            is SyncState.WaitingForTor -> {
                banner.visibility = View.VISIBLE
                progress.visibility = View.VISIBLE
                retry.visibility = View.GONE
                text.text = if (state.progress in 1..99) {
                    "Connecting via Tor\u2026 ${state.progress}%"
                } else {
                    "Connecting via Tor\u2026"
                }
            }

            is SyncState.Syncing -> {
                banner.visibility = View.VISIBLE
                progress.visibility = View.VISIBLE
                retry.visibility = View.GONE
                val base = if (state.total > 1) {
                    "Syncing ${state.done} of ${state.total} collections\u2026"
                } else {
                    "Syncing\u2026"
                }
                // Explain the delay rather than failing: Tor can be genuinely slow.
                text.text = if (state.slow) {
                    "$base still working over Tor, this can take a while"
                } else {
                    base
                }
            }

            is SyncState.Success -> {
                progress.visibility = View.GONE
                retry.visibility = View.GONE
                text.text = "Updated just now"
                banner.visibility = View.VISIBLE
                // Briefly confirm, then get out of the way.
                banner.postDelayed({
                    if (model.syncState().value is SyncState.Success) {
                        banner.visibility = View.GONE
                    }
                }, 2000)
            }

            is SyncState.Failed -> {
                banner.visibility = View.VISIBLE
                progress.visibility = View.GONE
                text.text = state.reason
                retry.visibility = if (state.retryable) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Single entry point for user-initiated refresh, from either the pull gesture
     * or the banner's Retry button.
     */
    private fun requestRefresh() {
        if (SentinelState.isTorRequired()) {
            when (SentinelTorManager.getTorState().state) {
                EnumTorState.ON -> model.fetchBalance()
                EnumTorState.OFF -> {
                    SentinelTorManager.start()
                    prefsUtil.enableTor = true
                }
                // STARTING / STOPPING: the banner already communicates progress.
                else -> Unit
            }
        } else {
            model.fetchBalance()
        }
    }

    private fun showToolOptions(it: View) {
        val toolWindowSize = applicationContext.resources.displayMetrics.density * 220;
        val popupMenu = popupMenu {
            fixedContentWidthInPx = toolWindowSize.toInt()
            style = R.style.Theme_Samourai_Widget_MPM_Menu_Dark
            section {
                item {
                    label = "Sentinel"
                    iconDrawable = ContextCompat.getDrawable(applicationContext, R.drawable.icon_innergradient)
                    iconSize = 34
                    labelColor = ContextCompat.getColor(applicationContext, R.color.white)
                    disableTint = true
                    iconShapeAppearanceModel = ShapeAppearanceModel().toBuilder()
                        .setAllCornerSizes(resources.getDimension(R.dimen.qr_image_corner_radius))
                        .build()
                    isTitle = true
                }
                item {
                    label = "\tTools"
                    icon = R.drawable.ic_tools
                    iconSize = 18
                    hasNestedItems
                    callback = {
                        val intent = Intent(this@HomeActivity, ToolsActivity::class.java)
                        startActivity(intent)
                    }
                }

            }
            section {
                item {
                    label = "\tSettings"
                    icon = R.drawable.ic_cog
                    iconSize = 18
                    callback = {
                        TimeOutUtil.getInstance().updatePin()
                        val intent = Intent(this@HomeActivity, SettingsActivity::class.java)
                        startActivity(intent)
                    }
                }
                item {
                    label = "\tExit"
                    iconSize = 18
                    iconColor = ContextCompat.getColor(this@HomeActivity, R.color.mpm_red)
                    labelColor = ContextCompat.getColor(this@HomeActivity, R.color.mpm_red)
                    icon = R.drawable.ic_baseline_power_settings_new_24
                    callback = {
                        this@HomeActivity.onBackPressed()
                    }
                }
            }
        }
        popupMenu.show(this@HomeActivity, it)
    }
    private fun fetch(model: HomeViewModel) {
        if (!SentinelState.isRecentlySynced()) {
            if (SentinelState.isTorRequired() && SentinelTorManager.getTorState().state == EnumTorState.ON) {
                model.fetchBalance()
            } else {
                SentinelTorManager.getTorStateLiveData().observe(this, {
                    if (it.state == EnumTorState.ON) {
                        lifecycleScope.launch {
                            model.fetchBalance()
                        }
                    }
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefsUtil.streetMode == true) {
            binding.homeBalanceBtc.text = "********"
            binding.exchangeRateTxt.text = "********"
        }
        if (balance != -1L)
            updateBalance(balance)

        binding.exchangeRateTxt.visibility = if (prefsUtil.fiatDisabled == true) View.INVISIBLE else View.VISIBLE
    }

    private fun setUp() {
        if (prefsUtil.firstRun == true && AppUtil.getInstance(applicationContext).isSideLoaded) {
            this.confirm(label = "Choose network",
                    positiveText = "Mainnet",
                    negativeText = "Testnet",
                    isCancelable = false,
                    onConfirm = { confirm ->
                        prefsUtil.firstRun = false
                        if (!confirm) {
                            prefsUtil.testnet = true
                        }
                        showServerConfig()
                    })
        } else {
            showServerConfig()
        }
    }

    private fun showServerConfig() {
        if (prefsUtil.apiEndPoint.isNullOrEmpty()) {
            connectingDojo = true
            if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext)) {
                this.askCameraPermission()
            } else {
                showDojoSetUpBottomSheet()
            }
            /*
            this.confirm(label = "Choose server",
                    positiveText = "Connect to Dojo",
                    //negativeText = "Connect to Samourai’s server",
                    isCancelable = false,
                    onConfirm = { confirm ->
                        if (confirm) {
                            connectingDojo = true
                            if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext)) {
                                this.askCameraPermission()
                            } else {
                                showDojoSetUpBottomSheet()
                            }
                        } else {
                            this.confirm(label = "Connect through Tor?",
                                message = "",
                                positiveText = "Yes",
                                negativeText = "No",
                                onConfirm = { confirmed ->
                                    if (confirmed) {
                                        SentinelTorManager.start()
                                        prefsUtil.enableTor = true
                                    }
                                }
                            )
                            if (prefsUtil.testnet == true) {
                                prefsUtil.apiEndPoint = APIConfig.SAMOURAI_API_TESTNET
                                prefsUtil.apiEndPointTor = APIConfig.SAMOURAI_API_TOR_TESTNET
                            } else {
                                prefsUtil.apiEndPoint = APIConfig.SAMOURAI_API
                                prefsUtil.apiEndPointTor = APIConfig.SAMOURAI_API_TOR
                            }
                        }
                    })
             */
        }
    }

    private fun showDojoSetUpBottomSheet() {
        val dojoConfigureBottomSheet = DojoConfigureBottomSheet()
        dojoConfigureBottomSheet.show(supportFragmentManager, dojoConfigureBottomSheet.tag)
        dojoConfigureBottomSheet.setDojoConfigurationListener(object : DojoConfigureBottomSheet.DojoConfigurationListener {
            override fun onDismiss() {
                if (!prefsUtil.isAPIEndpointEnabled()) {
                    //showServerConfig()
                    Toast.makeText(applicationContext, "No Dojo connected", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun updateBalance(it: Long) {
        if (prefsUtil.streetMode == true)
            binding.homeBalanceBtc.text = "********"
        else
            binding.homeBalanceBtc.text = "${MonetaryUtil.getInstance().getBTCDecimalFormat(it)} BTC"
    }

    private fun updateFiat(it: String) {
        if (prefsUtil.streetMode == true)
            binding.exchangeRateTxt.text = "********"
        else
            binding.exchangeRateTxt.text = it
    }

    private fun showPubKeyBottomSheet() {
        val bottomSheetFragment = AddNewPubKeyBottomSheet(secure = prefsUtil.displaySecure == true)
        bottomSheetFragment.show(supportFragmentManager, bottomSheetFragment.tag)
    }


    private fun setUpCollectionList() {

        collectionsAdapter.setOnClickListener {
            startActivity(Intent(applicationContext, CollectionDetailsActivity::class.java).apply {
                putExtra("collection", it.id)
            })
        }

        linearLayoutManager = LinearLayoutManager(this)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL
        val decorator = RecyclerViewItemDividerDecorator(ContextCompat.getDrawable(applicationContext, R.drawable.divider_tx)!!)
        binding.collectionRecyclerView.apply {
            adapter = collectionsAdapter
            layoutManager = linearLayoutManager
            itemAnimator = SlideInItemAnimator(slideFromEdge = Gravity.TOP)
            setHasFixedSize(true)
            addItemDecoration(decorator)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!SentinelState.checkedClipBoard) {
            SentinelState.checkedClipBoard = true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // NOTE: grantResults can be EMPTY when the dialog is cancelled - never index it directly.
        when (requestCode) {
            Companion.CAMERA_PERMISSION -> {
                when (permissionResultOf(grantResults)) {
                    PermissionResult.GRANTED -> Unit
                    PermissionResult.DENIED ->
                        Toast.makeText(this, "Permission denied", Toast.LENGTH_LONG).show()
                    // User dismissed the dialog; take no action at all.
                    PermissionResult.CANCELLED -> return
                }
                if (connectingDojo) showDojoSetUpBottomSheet() else showPubKeyBottomSheet()
            }

            Companion.NOTIF_PERMISSION -> {
                when (permissionResultOf(grantResults)) {
                    PermissionResult.GRANTED ->
                        Toast.makeText(this, "Notification permissions granted.", Toast.LENGTH_SHORT).show()
                    PermissionResult.DENIED ->
                        Toast.makeText(this, "Notification permissions denied.", Toast.LENGTH_SHORT).show()
                    PermissionResult.CANCELLED -> Unit
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.home_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onBackPressed() {
        MaterialAlertDialogBuilder(this)
                .setTitle(resources.getString(R.string.confirm_exit))
                .setMessage(resources.getString(R.string.ask_you_sure_exit))
                .setNegativeButton(resources.getString(R.string.no)) { _, _ ->
                }
                .setPositiveButton(resources.getString(R.string.yes)) { _, _ ->
                    SentinelTorManager.stop()
                    super.onBackPressed()
                }
                .show()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.let { setNetWorkMenu(it) }
        return super.onPrepareOptionsMenu(menu)
    }

    private fun setNetWorkMenu(menu: Menu) {
        val alertMenuItem: MenuItem = menu.findItem(R.id.activity_home_menu_network)
        val rootView = alertMenuItem.actionView ?: return
        val statusCircle = rootView.findViewById<View>(R.id.home_menu_network_shape) as FrameLayout

        // Traffic-light: green = Tor connected AND synced, flashing yellow =
        // connecting/syncing, red = failed. Driven by the ViewModel so Tor state
        // and sync state are considered together.
        indicatorController?.dispose()
        indicatorController = ConnectionIndicatorController(statusCircle).also { controller ->
            model.connectionIndicator().observe(this) { controller.render(it) }
        }

        rootView.setOnClickListener {
            startActivity(Intent(this, NetworkActivity::class.java))
        }
    }

    override fun onDestroy() {
        indicatorController?.dispose()
        indicatorController = null
        super.onDestroy()
    }

    fun connectSocket() {
        try {
            webSocketHandler.connect()
        } catch (ex: Exception) {
        }
    }
}
