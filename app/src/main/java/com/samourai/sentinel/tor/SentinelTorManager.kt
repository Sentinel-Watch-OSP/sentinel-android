package com.samourai.sentinel.tor

import android.app.Application
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import org.json.JSONException
import org.json.JSONObject
import org.koin.java.KoinJavaComponent
import java.net.Proxy

object SentinelTorManager {

    private const val TAG = "SamouraiTorManager"
    private val prefsUtil: PrefsUtil by KoinJavaComponent.inject(PrefsUtil::class.java)
    private val dojoUtility: DojoUtility by KoinJavaComponent.inject(DojoUtility::class.java)


    private var torKmpManager: TorKmpManager? = null
        get() = field

    private var appContext: Application? = null

    fun setUp(application: Application) {
        appContext = application
        torKmpManager = TorKmpManager(application)
    }

    fun getTorStateLiveData(): MutableLiveData<TorState> {
        return torKmpManager!!.torStateLiveData
    }

    fun getTorState(): TorState {
        return torKmpManager!!.torState
    }

    fun isRequired(): Boolean {
        return dojoUtility.isDojoEnabled() || prefsUtil.enableTor!!
    }

    fun isConnected(): Boolean {
        return torKmpManager?.isConnected() ?: false
    }

    fun isStarting(): Boolean {
        return torKmpManager?.isStarting() ?: false
    }

    fun stop() {
        torKmpManager?.stop()
    }

    fun start() {
        torKmpManager?.start()
    }

    fun getProxy(): Proxy? {
        return torKmpManager?.proxy;
    }

    @JvmStatic
    fun newIdentity() {
        torKmpManager?.newIdentity(appContext!!);
    }

    fun toJSON(): JSONObject {

        val jsonPayload = JSONObject();

        try {
            jsonPayload.put("active", (isRequired()));
        } catch (ex: JSONException) {
            Log.d(TAG, "JSONException issue on toJSON:" + ex.message)
        } catch (ex: ClassCastException) {
            Log.d(TAG, "ClassCastException issue on toJSON:" + ex.message)
        }

        return jsonPayload
    }

    fun fromJSON(jsonPayload: JSONObject) {
        try {
            if (jsonPayload.has("active")) {
                prefsUtil.enableTor = jsonPayload.getBoolean("active");
            }
        } catch (ex: JSONException) {
            Log.d(TAG, "JSONException issue on fromJSON:" + ex.message)
        }
    }
}
