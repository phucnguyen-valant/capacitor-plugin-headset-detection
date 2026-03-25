package com.saninnsalas.capacitor.headsetdetection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.saninnsalas.capacitor.headsetdetection.models.HeadsetDevice
import com.saninnsalas.capacitor.headsetdetection.models.HeadsetPluginResponse

@CapacitorPlugin(name = "HeadsetDetectionPlugin")
public class HeadsetDetectionPlugin : Plugin() {
    private val TAG = "HeadsetDetectionPlugin"

    private var started = false
    private var initialCallbackProcessed = false

    private val headphoneDeviceTypes = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            // AudioDeviceInfo.TYPE_USB_HEADSET Requires ApiLevel 26
    )

    private var connectedHeadset: HeadsetDevice? = null

    @PluginMethod
    fun start(_call: PluginCall) {
        if(!started) {
            Log.i(TAG, "Starting plugin.")
            val audioManager = context.getSystemService(AudioManager::class.java)
            audioManager.registerAudioDeviceCallback(deviceCallBack, null)
            context.registerReceiver(wiredHeadsetReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
            started = true
        } else {
            Log.i(TAG, "start() was called when the plugin was already started. Updating headphones.")
            updateHeadphonesConnected()
        }
    }

    private fun updateHeadphonesConnected() {
        val isConnected = connectedHeadset != null
        val response = HeadsetPluginResponse(isConnected, connectedHeadset)
        val jsObject = response.toJSObject();
        Log.i(TAG, "response $jsObject")
        notifyListeners("ConnectedHeadphones", jsObject)
    }

    private val deviceCallBack: AudioDeviceCallback = object: AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if(!initialCallbackProcessed) {
                initialCallbackProcessed = true
                val audioManager = context.getSystemService(AudioManager::class.java)
                val currentHeadphone = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .filter { isHeadphoneDevice(it) && !isWiredDevice(it) }
                    .firstOrNull()
                connectedHeadset = currentHeadphone?.let {
                    HeadsetDevice(it.id, it.type, it.productName.toString())
                }
                return
            }
            if(addedDevices == null) return
            val headphones = addedDevices.filter { isHeadphoneDevice(it) && !isWiredDevice(it) }
            if(headphones.isEmpty()) return
            headphones.forEach {
                Log.d(TAG, "Headphones addedDevices ${it.productName}, type: ${it.type}")
                connectedHeadset = HeadsetDevice(it.id, it.type, it.productName.toString())
            }
            updateHeadphonesConnected()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if(removedDevices == null) return
            val headphones = removedDevices.filter { isHeadphoneDevice(it) && !isWiredDevice(it) }
            if(headphones.isEmpty()) return
            headphones.forEach {
                Log.d(TAG, "Headphones removedDevices ${it.productName}, type: ${it.type}")
                connectedHeadset = null
            }
            updateHeadphonesConnected()
        }
    }

    // Handles wired headset plug/unplug via the legacy broadcast system, which is more
    // reliable than AudioDeviceCallback for analog jacks on most Android OEM devices.
    private val wiredHeadsetReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if(intent.action != Intent.ACTION_HEADSET_PLUG) return
            val state = intent.getIntExtra("state", -1)
            Log.d(TAG, "ACTION_HEADSET_PLUG state=$state")
            when(state) {
                1 -> {
                    val audioManager = context.getSystemService(AudioManager::class.java)
                    val wiredDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { isWiredDevice(it) }
                    connectedHeadset = wiredDevice?.let {
                        HeadsetDevice(it.id, it.type, it.productName.toString())
                    } ?: HeadsetDevice(
                        0,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        intent.getStringExtra("name") ?: "Wired Headset"
                    )
                    updateHeadphonesConnected()
                }
                0 -> {
                    if(connectedHeadset != null && isWiredTypeCode(connectedHeadset!!.typeCode)) {
                        connectedHeadset = null
                        updateHeadphonesConnected()
                    }
                }
            }
        }
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        val audioManager = context.getSystemService(AudioManager::class.java)
        audioManager.unregisterAudioDeviceCallback(deviceCallBack)
        try {
            context.unregisterReceiver(wiredHeadsetReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "wiredHeadsetReceiver was not registered")
        }
    }

    private fun isHeadphoneDevice(device: AudioDeviceInfo): Boolean {
        // Filter idea from https://github.com/google/talkback/blob/92eb6dd4461e53fc904052b7fbe9b77ddfbf930a/utils/src/main/java/HeadphoneStateMonitor.java#L125
        return device.isSink && headphoneDeviceTypes.contains(device.type)
    }

    private fun isWiredDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            || device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            || device.type == AudioDeviceInfo.TYPE_AUX_LINE
    }

    private fun isWiredTypeCode(typeCode: Int): Boolean {
        return typeCode == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            || typeCode == AudioDeviceInfo.TYPE_WIRED_HEADSET
            || typeCode == AudioDeviceInfo.TYPE_AUX_LINE
    }
}
