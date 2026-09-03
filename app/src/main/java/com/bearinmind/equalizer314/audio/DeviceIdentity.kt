package com.bearinmind.equalizer314.audio

import android.media.AudioDeviceInfo

/** Maps an [AudioDeviceInfo] to a stable identity key + label; SCO/HFP, HDMI, cast etc. are untracked. */
object DeviceIdentity {

    // API 28-33 constants, hard-coded for compileSdk-agnostic safety
    private const val TYPE_HEARING_AID = 23
    private const val TYPE_BUILTIN_SPEAKER_SAFE = 24
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_BLE_SPEAKER = 27
    private const val TYPE_BLE_BROADCAST = 30

    /** Output sinks the binding system tracks. */
    private fun bucket(type: Int): Bucket? = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        TYPE_BLE_HEADSET,
        TYPE_BLE_SPEAKER,
        TYPE_BLE_BROADCAST,
        TYPE_HEARING_AID -> Bucket.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE -> Bucket.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> Bucket.USB
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        TYPE_BUILTIN_SPEAKER_SAFE -> Bucket.SPEAKER
        else -> null
    }

    /** Product name with junk rejected (Wavelet's rules) and Poweramp's "USB-Audio - " prefix stripped. */
    private fun cleanProductName(raw: CharSequence?): String? {
        var s = raw?.toString()?.trim()
        if (s.isNullOrEmpty()) return null
        if (s == android.os.Build.MODEL || s == "boot_headset" || s == "h2w") return null
        if (s.startsWith("USB-Audio - ") && s.length > 12) s = s.substring(12)
        return s
    }

    /** Canonical name for alias matching — TWS " L"/" R" bud names merge to one identity. */
    fun aliasName(label: String?): String? {
        val s = cleanProductName(label)
            ?.replace(" L ", " ")?.replace(" R ", " ")
            ?.removeSuffix(" L")?.removeSuffix(" R")?.trim()
        return if (s.isNullOrEmpty()) null else s
    }

    private enum class Bucket { BLUETOOTH, WIRED, USB, SPEAKER }

    /** Stable identity key for a tracked output; null for outputs we don't bind. */
    fun keyOf(info: AudioDeviceInfo): String? {
        val b = bucket(info.type) ?: return null
        return when (b) {
            Bucket.BLUETOOTH -> {
                val addr = info.address
                if (!addr.isNullOrBlank()) "BT:$addr"
                else "BT-NAME:${cleanProductName(info.productName) ?: info.productName?.toString().orEmpty()}"
            }
            Bucket.WIRED -> "WIRED:"
            Bucket.USB -> "USB:${cleanProductName(info.productName) ?: info.productName?.toString().orEmpty()}"
            Bucket.SPEAKER -> "SPEAKER:"
        }
    }

    /** Friendly UI label; type-derived fallback when productName is empty or junk. */
    fun labelOf(info: AudioDeviceInfo): String {
        val product = cleanProductName(info.productName)
        if (product != null && bucket(info.type) !in setOf(Bucket.WIRED, Bucket.SPEAKER)) {
            return product
        }
        return when (bucket(info.type)) {
            Bucket.BLUETOOTH -> product ?: "Bluetooth"
            Bucket.WIRED -> "Wired headphones"
            Bucket.USB -> product ?: "USB audio"
            Bucket.SPEAKER -> "Phone speaker"
            null -> product ?: "Unknown output"
        }
    }

    /** Pick priority when multiple outputs are connected — higher wins. */
    fun priority(info: AudioDeviceInfo): Int = when (bucket(info.type)) {
        Bucket.BLUETOOTH -> 4
        Bucket.USB -> 3
        Bucket.WIRED -> 2
        Bucket.SPEAKER -> 1
        null -> 0
    }

    /** Second-line display for a stored key — BT shows the MAC, others the connection type. */
    fun displayKey(key: String): String = when {
        key.startsWith("BT:") -> key.removePrefix("BT:")
        key.startsWith("BT-NAME:") -> "Bluetooth"
        key.startsWith("USB:") -> "USB"
        key.startsWith("WIRED:") -> "Wired"
        key.startsWith("SPEAKER:") -> "Speaker"
        else -> key.trimEnd(':')
    }
}
