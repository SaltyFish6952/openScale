/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Handler for the original Bong Fit BLE scale.
 *
 * Reverse engineered from the retired Bong app:
 *  - Advertised name: bongfit
 *  - Service: 6e400001-b5a3-f393-e0a9-e50e24dcca1e
 *  - Write:   6e400002-b5a3-f393-e0a9-e50e24dcca1e
 *  - Notify:  6e400003-b5a3-f393-e0a9-e50e24dcca1e
 *
 * Live/final measurement frames are 20 bytes and start with 41 00 00.
 */
class BongFitHandler : ScaleDeviceHandler() {

    private val service: UUID =
        UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca1e")
    private val writeCharacteristic: UUID =
        UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca1e")
    private val notifyCharacteristic: UUID =
        UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca1e")

    private var lastPublishedFrame: ByteArray? = null
    private var lastPublishMs: Long = 0L

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.US).trim()
        if (name != "bongfit" && name != "bong fit") {
            return null
        }

        val caps = setOf(
            DeviceCapability.USER_SYNC,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.HISTORY_READ
        )

        return DeviceSupport(
            displayName = "Bong Fit",
            capabilities = caps,
            implemented = setOf(
                DeviceCapability.USER_SYNC,
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        setNotifyOn(service, notifyCharacteristic)
        writeTo(service, writeCharacteristic, buildUserProfileCommand(user), withResponse = true)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != notifyCharacteristic) {
            return
        }

        val frame = parseMeasurementFrame(data) ?: run {
            logD("Ignoring Bong Fit notification ${data.toHexPreview(24)}")
            return
        }

        if (frame.status != STATUS_COMPLETE) {
            if (frame.weightKg > 0.0f) {
                logD("Bong Fit measuring kg=${frame.weightKg}")
            }
            return
        }

        sendResultAck()

        if (frame.weightKg <= 0.0f) {
            logD("Ignoring complete Bong Fit frame with empty weight")
            return
        }

        if (isDuplicateFinalFrame(data)) {
            logD("Ignoring duplicate Bong Fit final frame")
            requestDisconnect()
            return
        }

        publish(frame.toScaleMeasurement(user.id))
        requestDisconnect()
    }

    private fun buildUserProfileCommand(user: ScaleUser): ByteArray {
        val userId = user.id and 0xFFFFFF
        val age = user.age.coerceIn(0, 255)
        val height = user.bodyHeight.toInt().coerceIn(0, 255)
        val gender = if (user.gender.isMale()) 0x00 else 0x01

        return byteArrayOf(
            0x41,
            0x00,
            0x00,
            0x06,
            (userId shr 16).toByte(),
            (userId shr 8).toByte(),
            userId.toByte(),
            0x00,
            gender.toByte(),
            age.toByte(),
            height.toByte()
        )
    }

    private fun parseMeasurementFrame(data: ByteArray): BongFitFrame? {
        if (data.size != 20) {
            return null
        }

        if (u8(data, 0) != 0x41 || u8(data, 1) != 0x00 || u8(data, 2) != 0x00) {
            return null
        }

        return BongFitFrame(
            status = u8(data, 3),
            scaleUserId = u24be(data, 4),
            scaleSubId = u8(data, 7),
            weightKg = u16be(data, 8) / 100.0f,
            fat = u8(data, 10) + highNibble(data, 12) / 10.0f,
            water = u8(data, 11) + lowNibble(data, 12) / 10.0f,
            muscle = u8(data, 13) + highNibble(data, 15) / 10.0f,
            bone = u8(data, 14) + lowNibble(data, 15) / 10.0f,
            bmr = u16be(data, 16).toFloat(),
            visceralFat = u8(data, 18) / 10.0f,
            bodyAge = u8(data, 19)
        )
    }

    private fun sendResultAck() {
        writeTo(
            service,
            writeCharacteristic,
            byteArrayOf(0x41, 0x00, 0x01, 0x01),
            withResponse = true
        )
    }

    private fun isDuplicateFinalFrame(data: ByteArray): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastPublishedFrame
        if (previous != null && previous.contentEquals(data) && now - lastPublishMs < 6000L) {
            return true
        }

        lastPublishedFrame = data.copyOf()
        lastPublishMs = now
        return false
    }

    private fun u8(data: ByteArray, index: Int): Int =
        data[index].toInt() and 0xFF

    private fun u16be(data: ByteArray, offset: Int): Int =
        (u8(data, offset) shl 8) or u8(data, offset + 1)

    private fun u24be(data: ByteArray, offset: Int): Int =
        (u8(data, offset) shl 16) or (u8(data, offset + 1) shl 8) or u8(data, offset + 2)

    private fun highNibble(data: ByteArray, index: Int): Int =
        (u8(data, index) and 0xF0) shr 4

    private fun lowNibble(data: ByteArray, index: Int): Int =
        u8(data, index) and 0x0F

    private data class BongFitFrame(
        val status: Int,
        val scaleUserId: Int,
        val scaleSubId: Int,
        val weightKg: Float,
        val fat: Float,
        val water: Float,
        val muscle: Float,
        val bone: Float,
        val bmr: Float,
        val visceralFat: Float,
        val bodyAge: Int
    ) {
        fun toScaleMeasurement(appUserId: Int): ScaleMeasurement =
            ScaleMeasurement().apply {
                userId = appUserId
                dateTime = Date()
                weight = weightKg
                fat = this@BongFitFrame.fat
                water = this@BongFitFrame.water
                muscle = this@BongFitFrame.muscle
                bone = this@BongFitFrame.bone
                bmr = this@BongFitFrame.bmr
                visceralFat = this@BongFitFrame.visceralFat
            }
    }

    private companion object {
        const val STATUS_COMPLETE = 1
    }
}
