package com.samourai.sentinel.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.samourai.sentinel.R
import com.samourai.sentinel.api.APIConfig
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.dojo.DojoConfigureBottomSheet
import com.samourai.sentinel.ui.dojo.DojoCredentialsBottomSheet
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PermissionResult
import com.samourai.sentinel.ui.utils.permissionResultOf
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.confirm
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject

class NetworkActivity : SentinelActivity() {
    
    var torRenewBtn: TextView? = null
    var torConnectionStatus: TextView? = null
    var dojoConnectionStatus: TextView? = null
    var torButton: Button? = null
    var dojoButton: Button? = null
    var dojoDetailsButton: Button? = null
    var torConnectionIcon: ImageView? = null
    var dojoConnectionIcon: ImageView? = null
    var activeColor = 0
    var disabledColor = 0
    var waiting = 0
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);
    private val dojoUtility: DojoUtility by inject(DojoUtility::class.java);
    private val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private val apiService: ApiService by inject(ApiService::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network)
        setSupportActionBar(findViewById(R.id.toolbarCollectionDetails))
        if (supportActionBar != null) {
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        }
        activeColor = ContextCompat.getColor(this, R.color.green_ui_2)
        disabledColor = ContextCompat.getColor(this, R.color.disabledRed)
        waiting = ContextCompat.getColor(this, R.color.warning_yellow)
        torButton = findViewById(R.id.networking_tor_btn)
        torRenewBtn = findViewById(R.id.networking_tor_renew)
        dojoButton = findViewById(R.id.networking_dojo_btn)
        dojoDetailsButton = findViewById(R.id.networking_dojo_details_btn)
        torConnectionIcon = findViewById(R.id.network_tor_status_icon)
        torConnectionStatus = findViewById(R.id.network_tor_status)
        dojoConnectionIcon = findViewById(R.id.network_dojo_status_icon)
        dojoConnectionStatus = findViewById(R.id.network_dojo_status)
        torRenewBtn?.setOnClickListener {
            SentinelTorManager.newIdentity()
            this.showFloatingSnackBar(findViewById(R.id.toolbarCollectionDetails), text = "Tor identity has been renewed")
        }
        setTorConnectionState(SentinelTorManager.getTorState().state)
        SentinelTorManager.getTorStateLiveData().observe(this, {
            setTorConnectionState(it.state)
        })
        dojoDetailsButton?.setOnClickListener {
            showDojoCredentialsBottomSheet()
        }
        dojoButton?.setOnClickListener {
            if (dojoUtility.isDojoEnabled()) {
                confirm(label = "Remove dojo ? ", positiveText = "Remove", negativeText = "Cancel") {
                    if (it)
                        removeDojo()
                }
            } else {
                if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext))
                    this.askCameraPermission()
                else
                    showDojoSetUpBottomSheet()
            }
        }
        torButton!!.setOnClickListener {
            if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
                if (!dojoUtility.isDojoEnabled()) {
                    SentinelTorManager.stop()
                    prefsUtil.enableTor = false
                }
                else
                    this.showFloatingSnackBar(torButton!!.rootView, text = "You wont be able to disable tor if dojo is enabled")
            } else {
                SentinelTorManager.start()
                prefsUtil.enableTor = true
            }

        }
        setDojoStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // NOTE: grantResults can be EMPTY when the dialog is cancelled - never index it directly.
        if (requestCode != Companion.CAMERA_PERMISSION) return
        when (permissionResultOf(grantResults)) {
            PermissionResult.GRANTED -> showDojoSetUpBottomSheet()
            PermissionResult.DENIED -> {
                Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_LONG).show()
                showDojoSetUpBottomSheet()
            }
            PermissionResult.CANCELLED -> Unit
        }
    }

    private fun removeDojo() {
        dojoUtility.clearDojo()
        setDojoStatus()
        if (prefsUtil.apiEndPoint.isNullOrEmpty()) {
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
                            showDojoSetUpBottomSheet()
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
                                    else {
                                        SentinelTorManager.stop()
                                        prefsUtil.enableTor = false
                                    }
                                    if (!prefsUtil.isAPIEndpointEnabled())
                                        removeDojo()
                                    else
                                        importAllXpubs()
                                }
                            )
                            if (prefsUtil.testnet!!) {
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

    private fun importAllXpubs() {
        apiScope.launch {
            val toImport = mutableListOf<Pair<String, String>>()

            repository.pubKeyCollections.forEach { collection ->
                collection.pubs.forEach { pub ->
                    val purpose = "bip${pub.getPurpose()}"
                    toImport.add(pub.pubKey to purpose)
                }
            }

            toImport.forEach { (pubKey, purpose) ->
                try {
                    apiService.importXpub(pubKey, purpose)
                } catch (e: Exception) {
                    Log.d("NetworkActivity", "Error: ${e}")
                }
            }
        }
    }

    fun setDojoStatus() {
        if (dojoUtility.isDojoEnabled()) {
            dojoConnectionStatus?.text = getString(R.string.Enabled)
            dojoButton?.text = getString(R.string.disable_dojo)
            dojoConnectionIcon!!.setColorFilter(activeColor)
            // Only offer pairing details when a payload actually exists.
            dojoDetailsButton?.visibility = View.VISIBLE
        } else {
            dojoConnectionStatus?.text = getString(R.string.disabled)
            dojoButton?.text = getString(R.string.enable)
            dojoConnectionIcon!!.setColorFilter(disabledColor)
            dojoDetailsButton?.visibility = View.GONE
        }
    }

    /**
     * Shows the pairing credentials for the connected Dojo.
     *
     * The sheet sets FLAG_SECURE because the payload contains the node API key.
     */
    private fun showDojoCredentialsBottomSheet() {
        if (!dojoUtility.isDojoEnabled()) {
            this.showFloatingSnackBar(
                findViewById(R.id.toolbarCollectionDetails),
                text = "No Dojo is currently connected"
            )
            return
        }
        val sheet = DojoCredentialsBottomSheet()
        sheet.show(supportFragmentManager, sheet.tag)
    }

    private fun setTorConnectionState(torState: EnumTorState) {
        runOnUiThread {
            when (torState) {

                EnumTorState.ON -> {
                    torButton!!.text = getString(R.string.disable)
                    torButton!!.isEnabled = true
                    torConnectionIcon!!.setColorFilter(activeColor)
                    torConnectionStatus!!.text = getString(R.string.Enabled)
                    torRenewBtn!!.visibility = View.VISIBLE
                }
                EnumTorState.STARTING -> {
                    torRenewBtn!!.visibility = View.INVISIBLE
                    torButton!!.text = getString(R.string.loading)
                    torButton!!.isEnabled = false
                    torConnectionIcon!!.setColorFilter(waiting)
                    torConnectionStatus!!.text = getString(R.string.tor_initializing)
                }
                else -> {
                    torRenewBtn!!.visibility = View.INVISIBLE
                    torButton!!.text = getString(R.string.enable)
                    torButton!!.isEnabled = true
                    torConnectionIcon!!.setColorFilter(disabledColor)
                    torConnectionStatus!!.text = getString(R.string.disabled)
                }
            }
        }
    }

    private fun showDojoSetUpBottomSheet() {
        val dojoConfigureBottomSheet = DojoConfigureBottomSheet()
        dojoConfigureBottomSheet.show(supportFragmentManager, dojoConfigureBottomSheet.tag)
        dojoConfigureBottomSheet.setDojoConfigurationListener(object : DojoConfigureBottomSheet.DojoConfigurationListener {
            override fun onDismiss() {
                //Update dojo status
                setDojoStatus()
                if (!prefsUtil.isAPIEndpointEnabled()) {
                    //removeDojo()
                    Toast.makeText(applicationContext, "No Dojo connected", Toast.LENGTH_SHORT).show()
                } else
                    importAllXpubs()
            }
        })
    }


    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
