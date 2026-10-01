package com.kjwindham.audiocool.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** The phone's own microphone, and any headset or USB microphone plugged in or paired. */
object Mics {
    private val headsetTypes = buildList {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        add(AudioDeviceInfo.TYPE_USB_DEVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    }

    private fun inputs(context: Context): List<AudioDeviceInfo> =
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    fun phone(context: Context): AudioDeviceInfo? = inputs(context).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    /**
     * A plugged-in microphone, preferring a wired one, or with [bluetooth] a paired one too. (A
     * Bluetooth headset's mic is fine for your own voice but too poor to record a room with.)
     */
    fun external(context: Context, bluetooth: Boolean = true): AudioDeviceInfo? =
        inputs(context).filter { it.type in headsetTypes && (bluetooth || !isBluetooth(it)) }.minByOrNull { headsetTypes.indexOf(it.type) }

    fun isBluetooth(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    fun label(device: AudioDeviceInfo?): String = when {
        device == null -> "mic"
        device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone mic"
        isBluetooth(device) -> "Bluetooth mic"
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE -> "USB mic"
        else -> "headset mic"
    }
}
