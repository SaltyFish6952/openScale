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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Handler for the original Bong Fit BLE scale.
 *
 * Protocol highlights (reverse engineered from the retired Bong app and
 * verified with a real Bong Fit scale):
 * - Advertised name: bongfit
 * - Service: 6e400001-b5a3-f393-e0a9-e50e24dcca1e
 * - Write:   6e400002-b5a3-f393-e0a9-e50e24dcca1e (host -> scale)
 * - Notify:  6e400003-b5a3-f393-e0a9-e50e24dcca1e (scale -> host)
 *
 * Host -> scale commands used here:
 * - 41 00 00 06 [userId:3 BE] 00 [gender] [age] [height]
 *     Sends the active user profile. gender: 0 = male, 1 = female.
 * - 41 00 01 01
 *     Acknowledges a completed measurement frame.
 * - 42 00 00 01 [fromTs:4 BE] [toTs:4 BE] [userId:3 BE]
 *     Requests stored history records.
 *
 * Known but not implemented yet:
 * - 40 00 00 05 [parentUserId:3 BE] 03 [subId] [weightRaw:2 BE]
 *     Writes back the selected user/latest weight in the original app flow.
 *
 * Scale -> host measurement frames are 20 bytes and start with 41 00 00.
 * Only status 1 is published; other status values are live/in-progress frames.
 *
 * Scale -> host history frames are also 20 bytes. The original parser uses
 * bytes 0-3 as a big-endian Unix timestamp in seconds, then reuses the same
 * measurement fields at offsets 4-19. Because historical Bong user ids may not
 * match openScale user ids, imported records are assigned to the active user.
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
    private var historyRequestInProgress = false
    private var historyImported = 0
    private var historyNewestTimestampSec = 0L
    private var historyRequestedEndSec = 0L
    private var historyTimeoutJob: Job? = null

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
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.HISTORY_READ
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        historyRequestInProgress = false
        historyImported = 0
        historyNewestTimestampSec = 0L
        historyRequestedEndSec = 0L
        historyTimeoutJob?.cancel()
        historyTimeoutJob = null

        setNotifyOn(service, notifyCharacteristic)
        val profile = buildUserProfileCommand(user)
        logI("Bong Fit write user profile ${profile.toHexPreview(24)} userId=${user.id}")
        writeTo(service, writeCharacteristic, profile, withResponse = true)
        requestHistory(user)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onDisconnected() {
        logD("Bong Fit onDisconnected: imported=$historyImported historyActive=$historyRequestInProgress")
        historyTimeoutJob?.cancel()
        historyTimeoutJob = null
        historyRequestInProgress = false
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != notifyCharacteristic) {
            return
        }

        logI("Bong Fit notify len=${data.size} ${data.toHexPreview(32)} ascii='${data.toAsciiPreview(32)}'")

        if (isHistoryControlFrame(data)) {
            logI("Bong Fit history control frame ascii='${data.toAsciiPreview(8)}'")
            finishHistoryRequest(if (isEndMarker(data)) "end marker" else "success marker")
            return
        }

        if (isBatteryFrame(data)) {
            logI("Bong Fit battery frame ignored ${data.toHexPreview(16)}")
            return
        }

        val frame = parseMeasurementFrame(data) ?: run {
            logD("Ignoring Bong Fit notification ${data.toHexPreview(24)}")
            return
        }

        if (frame.isHistory) {
            val ts = frame.timestampSec ?: 0L
            logI(
                "Bong Fit history frame ts=$ts user=${frame.scaleUserId} sub=${frame.scaleSubId} " +
                    "kg=${frame.weightKg} fat=${frame.fat} water=${frame.water} " +
                    "muscle=${frame.muscle} bone=${frame.bone} bmr=${frame.bmr} vf=${frame.visceralFat}"
            )
            publish(frame.toScaleMeasurement(user.id))
            historyImported += 1
            if (ts > historyNewestTimestampSec) {
                historyNewestTimestampSec = ts
            }
            return
        }

        if (frame.status != STATUS_COMPLETE) {
            if (frame.weightKg > 0.0f) {
                logD("Bong Fit measuring kg=${frame.weightKg} user=${frame.scaleUserId} sub=${frame.scaleSubId}")
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

        logI(
            "Bong Fit final frame user=${frame.scaleUserId} sub=${frame.scaleSubId} " +
                "kg=${frame.weightKg} fat=${frame.fat} water=${frame.water} " +
                "muscle=${frame.muscle} bone=${frame.bone} bmr=${frame.bmr} vf=${frame.visceralFat}"
        )
        publish(frame.toScaleMeasurement(user.id))
        requestDisconnect()
    }

    private fun buildUserProfileCommand(user: ScaleUser): ByteArray {
        val userId = user.id and 0xFFFFFF
        val age = user.age.coerceIn(0, 255)
        val height = user.bodyHeight.toInt().coerceIn(0, 255)
        val gender = if (user.gender.isMale()) 0x00 else 0x01

        // User profile command layout:
        //   0-3:   41 00 00 06
        //   4-6:   app user id, truncated to 24-bit big-endian
        //   7:     reserved, always 0
        //   8:     gender, 0 = male, 1 = female
        //   9:     age in years
        //   10:    height in cm
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

    private fun requestHistory(user: ScaleUser) {
        val nowSec = System.currentTimeMillis() / 1000L
        val lastSyncSec = lastHistorySyncSec(user.id)
        val startSec = when {
            lastSyncSec > 0L && lastSyncSec < nowSec -> lastSyncSec + 1L
            else -> (nowSec - HISTORY_LOOKBACK_SEC).coerceAtLeast(0L)
        }

        if (startSec >= nowSec) {
            logD("Bong Fit history request skipped: start=$startSec now=$nowSec lastSync=$lastSyncSec")
            return
        }

        val request = buildHistoryRequestCommand(user.id, startSec, nowSec)
        historyRequestInProgress = true
        historyImported = 0
        historyNewestTimestampSec = 0L
        historyRequestedEndSec = nowSec
        logI(
            "Bong Fit write history request ${request.toHexPreview(32)} " +
                "userId=${user.id} start=$startSec end=$nowSec lastSync=$lastSyncSec"
        )
        writeTo(service, writeCharacteristic, request, withResponse = true)
        armHistoryTimeout()
    }

    private fun buildHistoryRequestCommand(appUserId: Int, startSec: Long, endSec: Long): ByteArray {
        val userId = appUserId and 0xFFFFFF
        return byteArrayOf(
            0x42,
            0x00,
            0x00,
            0x01,
            (startSec shr 24).toByte(),
            (startSec shr 16).toByte(),
            (startSec shr 8).toByte(),
            startSec.toByte(),
            (endSec shr 24).toByte(),
            (endSec shr 16).toByte(),
            (endSec shr 8).toByte(),
            endSec.toByte(),
            (userId shr 16).toByte(),
            (userId shr 8).toByte(),
            userId.toByte()
        )
    }

    private fun armHistoryTimeout() {
        historyTimeoutJob?.cancel()
        historyTimeoutJob = scope.launch {
            delay(HISTORY_TIMEOUT_MS)
            finishHistoryRequest("timeout")
        }
    }

    private fun finishHistoryRequest(reason: String) {
        if (!historyRequestInProgress) {
            return
        }
        historyRequestInProgress = false
        historyTimeoutJob?.cancel()
        historyTimeoutJob = null
        val syncTimestampSec = when {
            historyNewestTimestampSec > 0L -> historyNewestTimestampSec
            reason != "timeout" -> historyRequestedEndSec
            else -> 0L
        }
        if (syncTimestampSec > 0L) {
            markHistorySynced(currentAppUser().id, syncTimestampSec)
        }
        logI(
            "Bong Fit history finished reason=$reason imported=$historyImported newest=${historyNewestTimestampSec}"
        )
        historyRequestedEndSec = 0L
    }

    private fun lastHistorySyncSec(appUserId: Int): Long =
        settingsGetInt(historySyncKey(appUserId), 0).toLong()

    private fun markHistorySynced(appUserId: Int, timestampSec: Long) {
        if (timestampSec <= 0L) {
            return
        }
        val clamped = timestampSec.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        settingsPutInt(historySyncKey(appUserId), clamped)
        logI("Bong Fit history sync saved userId=$appUserId ts=$timestampSec")
    }

    private fun historySyncKey(appUserId: Int): String =
        "bongfit/last_history_sync/$appUserId"

    private fun parseMeasurementFrame(data: ByteArray): BongFitFrame? {
        if (data.size != 20) {
            return null
        }

        return if (isLiveMeasurementFrame(data)) {
            parseBodyFields(
                data = data,
                status = u8(data, 3),
                timestampSec = null,
                isHistory = false
            )
        } else {
            val timestampSec = u32be(data, 0)
            if (!isPlausibleHistoryTimestamp(timestampSec)) {
                null
            } else {
                parseBodyFields(
                    data = data,
                    status = STATUS_HISTORY,
                    timestampSec = timestampSec,
                    isHistory = true
                )
            }
        }
    }

    private fun parseBodyFields(
        data: ByteArray,
        status: Int,
        timestampSec: Long?,
        isHistory: Boolean
    ): BongFitFrame {
        // Measurement frame layout:
        //   0-2:   41 00 00 for live frames
        //   0-3:   Unix timestamp seconds for history frames
        //   3:     live status, 0 = measuring, 1 = complete
        //   4-6:   Bong app user id, 24-bit big-endian
        //   7:     Bong Fit sub/user slot id
        //   8-9:   weight, kg * 100, big-endian
        //   10:    body fat integer part, percent
        //   11:    water integer part, percent
        //   12:    high nibble = body fat tenth, low nibble = water tenth
        //   13:    muscle integer part, percent
        //   14:    bone mass integer part, kg
        //   15:    high nibble = muscle tenth, low nibble = bone mass tenth
        //   16-17: BMR, kcal, big-endian
        //   18:    visceral fat * 10
        //   19:    body age, parsed for completeness but not stored by openScale
        return BongFitFrame(
            status = status,
            timestampSec = timestampSec,
            isHistory = isHistory,
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

    private fun isLiveMeasurementFrame(data: ByteArray): Boolean =
        data.size == 20 && u8(data, 0) == 0x41 && u8(data, 1) == 0x00 && u8(data, 2) == 0x00

    private fun isPlausibleHistoryTimestamp(timestampSec: Long): Boolean {
        val nowSec = System.currentTimeMillis() / 1000L
        return timestampSec in HISTORY_MIN_TIMESTAMP_SEC..(nowSec + HISTORY_CLOCK_SKEW_SEC)
    }

    private fun sendResultAck() {
        // Original app sends this after accepting a complete frame.
        logD("Bong Fit write result ack")
        writeTo(
            service,
            writeCharacteristic,
            byteArrayOf(0x41, 0x00, 0x01, 0x01),
            withResponse = true
        )
    }

    private fun isHistoryControlFrame(data: ByteArray): Boolean =
        isEndMarker(data) || data.contentEquals(ASCII_SUCCESS)

    private fun isEndMarker(data: ByteArray): Boolean =
        data.contentEquals(ASCII_END)

    private fun isBatteryFrame(data: ByteArray): Boolean {
        if (data.size != 4 || u8(data, 0) != 0x02 || u8(data, 1) != 0x02) {
            return false
        }
        val charging = u8(data, 2) == 1
        val percent = u8(data, 3)
        logI("Bong Fit battery percent=$percent charging=$charging")
        return true
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

    private fun u32be(data: ByteArray, offset: Int): Long =
        ((u8(data, offset).toLong() shl 24) or
            (u8(data, offset + 1).toLong() shl 16) or
            (u8(data, offset + 2).toLong() shl 8) or
            u8(data, offset + 3).toLong()) and 0xFFFF_FFFFL

    private fun highNibble(data: ByteArray, index: Int): Int =
        (u8(data, index) and 0xF0) shr 4

    private fun lowNibble(data: ByteArray, index: Int): Int =
        u8(data, index) and 0x0F

    private data class BongFitFrame(
        val status: Int,
        val timestampSec: Long?,
        val isHistory: Boolean,
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
                dateTime = if (isHistory && timestampSec != null && timestampSec > 0L) {
                    Date(timestampSec * 1000L)
                } else {
                    Date()
                }
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
        const val STATUS_HISTORY = 0x70
        const val HISTORY_LOOKBACK_SEC = 30L * 24L * 60L * 60L
        const val HISTORY_TIMEOUT_MS = 12_000L
        const val HISTORY_MIN_TIMESTAMP_SEC = 1_577_836_800L
        const val HISTORY_CLOCK_SKEW_SEC = 2 * 24L * 60L * 60L
        val ASCII_END = byteArrayOf(0x65, 0x6E, 0x64)
        val ASCII_SUCCESS = byteArrayOf(0x73, 0x75, 0x63, 0x63, 0x65, 0x73, 0x73)
    }
}
