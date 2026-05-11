package com.bearinmind.equalizer314.audio

import android.media.AudioDeviceInfo

/**
 * Pure helper that maps an [AudioDeviceInfo] to a stable identity key
 * and a human-readable label, centralising every place the rest of the
 * codebase would otherwise have to know about [AudioDeviceInfo.getType]
 * constants.
 *
 * Type buckets follow Wavelet's confirmed `p5/a.java` filters so the
 * binding bucketing matches what users coming from Wavelet expect:
 *  - Bluetooth = {A2DP, BLE_HEADSET, BLE_BROADCAST}; SCO/HFP excluded.
 *  - Wired = {WIRED_HEADSET, WIRED_HEADPHONES, LINE_ANALOG} collapsed.
 *  - USB = {USB_HEADSET, USB_DEVICE, USB_ACCESSORY}, keyed by product
 *    name (USB has no stable address through AudioDeviceInfo).
 *  - Built-in speaker is a singleton.
 *  - Everything else is rejected — `keyOf` returns null and the
 *    routing layer ignores those devices for binding purposes.
 */
object DeviceIdentity {

    private const val TYPE_BLE_HEADSET = 26    // API 31+ constant, hard-coded for compileSdk-agnostic safety
    private const val TYPE_BLE_BROADCAST = 27  // API 33+ constant

    /** Output sinks the binding system tracks. */
    private fun bucket(type: Int): Bucket? = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        TYPE_BLE_HEADSET,
        TYPE_BLE_BROADCAST -> Bucket.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG -> Bucket.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> Bucket.USB
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Bucket.SPEAKER
        else -> null
    }

    private enum class Bucket { BLUETOOTH, WIRED, USB, SPEAKER }

    /** Composes a stable identity key for a tracked output. Returns
     *  null for HFP/SCO, HDMI, cast, telephony, and other outputs we
     *  deliberately don't bind EQ to. */
    fun keyOf(info: AudioDeviceInfo): String? {
        val b = bucket(info.type) ?: return null
        return when (b) {
            Bucket.BLUETOOTH -> {
                val addr = info.address
                if (!addr.isNullOrBlank()) "BT:$addr"
                else "BT-NAME:${info.productName?.toString().orEmpty()}"
            }
            Bucket.WIRED -> "WIRED:"
            Bucket.USB -> "USB:${info.productName?.toString().orEmpty()}"
            Bucket.SPEAKER -> "SPEAKER:"
        }
    }

    /** Friendly label for the UI. Falls back to a type-derived name
     *  when `productName` is empty (built-in / wired never carry one). */
    fun labelOf(info: AudioDeviceInfo): String {
        val product = info.productName?.toString()?.trim()
        if (!product.isNullOrEmpty() && bucket(info.type) !in setOf(Bucket.WIRED, Bucket.SPEAKER)) {
            return product
        }
        return when (bucket(info.type)) {
            Bucket.BLUETOOTH -> product?.takeIf { it.isNotEmpty() } ?: "Bluetooth"
            Bucket.WIRED -> "Wired headphones"
            Bucket.USB -> product?.takeIf { it.isNotEmpty() } ?: "USB audio"
            Bucket.SPEAKER -> "Phone speaker"
            null -> product ?: "Unknown output"
        }
    }

    /** Priority order used when multiple outputs are connected and we
     *  must pick which one the EQ "follows". Higher number wins. */
    fun priority(info: AudioDeviceInfo): Int = when (bucket(info.type)) {
        Bucket.BLUETOOTH -> 4
        Bucket.USB -> 3
        Bucket.WIRED -> 2
        Bucket.SPEAKER -> 1
        null -> 0
    }
}
