package com.nexconnect.app

data class BmsTelemetry(
    val timestampMillis: Long = System.currentTimeMillis(),
    val packVoltageV: Double? = null,
    val currentA: Double? = null,
    val socPercent: Double? = null,
    val ratedCapacityAh: Double? = null,
    val remainingCapacityOverrideAh: Double? = null,
    val cellVoltagesV: List<Double> = emptyList(),
    val temperaturesC: List<Int> = emptyList(),
    val cycleCount: Int? = null,
    val chargeMosEnabled: Boolean? = null,
    val dischargeMosEnabled: Boolean? = null,
    val balanceEnabled: Boolean? = null,
    val operatingMode: String? = null,
    val alarmCode: Long? = null,
    val protocol: String = "Unknown",
    val firmwareVersion: String? = null
) {
    val powerW: Double? get() = if (packVoltageV != null && currentA != null) kotlin.math.abs(packVoltageV * currentA) else null
    val powerKw: Double? get() = powerW?.div(1000.0)
    val remainingCapacityAh: Double? get() = remainingCapacityOverrideAh ?: if (socPercent != null && ratedCapacityAh != null) ratedCapacityAh * socPercent / 100.0 else null
}

/** Read-only JBD/Jiabaida software-board protocol V4 BLE decoder (commands 03/04/05). */
class JbdV4BleDecoder {
    private val pending = ArrayList<Int>()
    private var last: BmsTelemetry? = null

    @Synchronized fun reset() { pending.clear(); last = null }

    @Synchronized fun accept(chunk: ByteArray): BmsTelemetry? {
        chunk.forEach { pending.add(it.toInt() and 0xFF) }
        var result: BmsTelemetry? = null
        while (true) {
            val start = pending.indexOf(0xDD)
            if (start < 0) { pending.clear(); break }
            if (start > 0) repeat(start) { pending.removeAt(0) }
            if (pending.size < 7) break
            val length = pending[3]
            val frameSize = 7 + length
            if (pending.size < frameSize) break
            val frame = pending.take(frameSize)
            repeat(frameSize) { pending.removeAt(0) }
            if (frame.last() != 0x77 || frame[2] != 0x00) continue
            val data = frame.subList(4, 4 + length)
            val checksum = ((frame[4 + length] shl 8) or frame[5 + length])
            val expected = (0x10000 - length - data.sum()) and 0xFFFF
            if (checksum != expected) continue
            val old = last
            fun word(index: Int) = ((data[index] and 0xFF) shl 8) or (data[index + 1] and 0xFF)
            when (frame[1]) {
                0x03 -> if (data.size >= 23) {
                    val currentRaw = word(2)
                    val current = (if (currentRaw and 0x8000 != 0) currentRaw - 0x10000 else currentRaw) / 100.0
                    val remainingAh = word(4) / 100.0
                    val ratedAh = word(6) / 100.0
                    val cycle = word(8)
                    val protection = word(16).toLong()
                    val soc = data[19].coerceIn(0, 100).toDouble()
                    val fet = data[20]
                    val tempCount = data.getOrNull(22)?.coerceAtMost(16) ?: 0
                    val temperatures = (0 until tempCount).mapNotNull { index ->
                        val offset = 23 + index * 2
                        if (offset + 1 < data.size) ((word(offset) - 2731) / 10).takeIf { it in -50..150 } else null
                    }
                    result = BmsTelemetry(
                        packVoltageV = word(0) / 100.0,
                        currentA = current,
                        socPercent = soc,
                        ratedCapacityAh = ratedAh.takeIf { it > 0.0 },
                        remainingCapacityOverrideAh = remainingAh,
                        cycleCount = cycle,
                        temperaturesC = temperatures,
                        chargeMosEnabled = fet and 0x01 != 0,
                        dischargeMosEnabled = fet and 0x02 != 0,
                        operatingMode = when { current > 0.1 -> "Charging"; current < -0.1 -> "Discharging"; else -> "Idle" },
                        alarmCode = protection,
                        protocol = "JBD V4 BLE",
                        firmwareVersion = "Software %X.%X".format((data[18] shr 4) and 0x0F, data[18] and 0x0F)
                    )
                }
                0x04 -> if (data.size >= 2) {
                    val count = (data.size / 2).coerceAtMost(48)
                    val cells = (0 until count).map { word(it * 2) / 1000.0 }.filter { it in 1.0..6.0 }
                    result = old?.copy(cellVoltagesV = cells, protocol = "JBD V4 BLE")
                }
                0x05 -> if (data.isNotEmpty()) result = old?.copy(
                    protocol = "JBD V4 BLE",
                    firmwareVersion = "Hardware ${data.joinToString("") { "%02X".format(it) }}"
                )
            }
            if (result != null) last = result
        }
        return result
    }

    fun request(command: Int): ByteArray {
        val checksum = (0x10000 - command) and 0xFFFF
        return byteArrayOf(0xDD.toByte(), 0xA5.toByte(), command.toByte(), 0x00, (checksum shr 8).toByte(), checksum.toByte(), 0x77)
    }
}

/** Decoder for DALY H/K/M/S BLE's D2 Modbus register block (0x0000..0x003D). */
class DalyModbusBleDecoder {
    private val pending = ArrayList<Int>()
    private var cells = emptyList<Double>()
    private var temperatures = emptyList<Int>()

    @Synchronized fun reset() { pending.clear(); cells = emptyList(); temperatures = emptyList() }

    @Synchronized fun accept(chunk: ByteArray): BmsTelemetry? {
        chunk.forEach { pending.add(it.toInt() and 0xFF) }
        while (pending.size >= 5) {
            val start = pending.indexOf(0xD2)
            if (start < 0) { pending.clear(); return null }
            repeat(start) { pending.removeAt(0) }
            if (pending.size < 3) return null
            val function = pending[1]
            val frameSize = when (function) {
                0x03 -> 3 + pending[2] + 2
                0x06 -> 8
                else -> if (function and 0x80 != 0) 5 else { pending.removeAt(0); continue }
            }
            if (pending.size < frameSize) return null
            val frame = pending.take(frameSize).toIntArray()
            val crc = modbusCrc(frame, frameSize - 2)
            val received = frame[frameSize - 2] or (frame[frameSize - 1] shl 8)
            repeat(frameSize) { pending.removeAt(0) }
            if (crc != received || function != 0x03 || frame[2] < 124) continue
            val data = frame.copyOfRange(3, 3 + frame[2])
            fun word(index: Int) = ((data[index].toLong() and 0xFF) shl 8) or (data[index + 1].toLong() and 0xFF)
            val cellCount = word(98).toInt().coerceIn(0, 48)
            val tempCount = word(100).toInt().coerceIn(0, 16)
            cells = (0 until cellCount).mapNotNull { i -> word(i * 2).toInt().takeIf { it in 1000..6000 }?.div(1000.0) }
            temperatures = (0 until tempCount).map { i -> (word(64 + i * 2).toInt() - 40).coerceIn(-50, 150) }
            val current = (word(82) - 30000L) / 10.0
            return BmsTelemetry(
                packVoltageV = word(80) / 10.0,
                currentA = current,
                socPercent = (word(84) / 10.0).coerceIn(0.0, 100.0),
                remainingCapacityOverrideAh = word(96) / 10.0,
                cellVoltagesV = cells,
                temperaturesC = temperatures,
                cycleCount = word(102).toInt(),
                chargeMosEnabled = word(106) != 0L,
                dischargeMosEnabled = word(108) != 0L,
                operatingMode = when { current > 0.1 -> "Charging"; current < -0.1 -> "Discharging"; else -> "Idle" },
                alarmCode = (116..122 step 2).fold(0L) { acc, index -> (acc shl 16) or word(index) },
                protocol = "DALY D2 Modbus BLE"
            )
        }
        return null
    }

    fun requestReadBlock(): ByteArray {
        val body = intArrayOf(0xD2, 0x03, 0x00, 0x00, 0x00, 0x3E)
        val crc = modbusCrc(body, body.size)
        return (body + intArrayOf(crc and 0xFF, (crc shr 8) and 0xFF)).map { it.toByte() }.toByteArray()
    }

    private fun modbusCrc(values: IntArray, length: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor values[i]
            repeat(8) { crc = if (crc and 1 != 0) (crc shr 1) xor 0xA001 else crc shr 1 }
        }
        return crc and 0xFFFF
    }
}

/** Read-only decoder for the public DALY A5/13-byte BLE telemetry frame family. */
class DalyA5Decoder {
    private val pending = ArrayList<Int>()
    private var voltage: Double? = null
    private var current: Double? = null
    private var soc: Double? = null
    private var capacityAh: Double? = null
    private var cycles: Int? = null
    private var chargeMos: Boolean? = null
    private var dischargeMos: Boolean? = null
    private var mode: String? = null
    private val cells = sortedMapOf<Int, Double>()
    private val temperatures = sortedMapOf<Int, Int>()

    @Synchronized
    fun reset() {
        pending.clear()
        voltage = null; current = null; soc = null; capacityAh = null; cycles = null
        chargeMos = null; dischargeMos = null; mode = null
        cells.clear(); temperatures.clear()
    }

    @Synchronized
    fun accept(chunk: ByteArray): BmsTelemetry? {
        chunk.forEach { pending.add(it.toInt() and 0xFF) }
        var changed = false
        while (true) {
            val start = pending.indexOf(0xA5)
            if (start < 0) { pending.clear(); break }
            if (start > 0) repeat(start) { pending.removeAt(0) }
            if (pending.size < 13) break
            val frame = pending.take(13).toIntArray()
            val checksum = frame.take(12).sum() and 0xFF
            if (checksum != frame[12] || frame[3] != 0x08) {
                pending.removeAt(0)
                continue
            }
            repeat(13) { pending.removeAt(0) }
            val p = frame.copyOfRange(4, 12)
            val word = { offset: Int -> ((p[offset] shl 8) or p[offset + 1]) }
            when (frame[2]) {
                0x90 -> {
                    voltage = word(0) / 10.0
                    current = (word(4) - 30000) / 10.0
                    soc = (word(6) / 10.0).coerceIn(0.0, 100.0)
                    changed = true
                }
                0x93 -> {
                    mode = when (p[0]) { 0 -> "Idle"; 1 -> "Charging"; else -> "Discharging" }
                    chargeMos = p[1] != 0
                    dischargeMos = p[2] != 0
                    val mAh = ((p[4].toLong() and 0xFF) shl 24) or ((p[5].toLong() and 0xFF) shl 16) or ((p[6].toLong() and 0xFF) shl 8) or (p[7].toLong() and 0xFF)
                    if (mAh in 1..10_000_000) capacityAh = mAh / 1000.0
                    changed = true
                }
                0x94 -> {
                    cycles = word(5)
                    changed = true
                }
                0x95 -> {
                    val block = p[0]
                    for (i in 0..2) {
                        val mV = word(1 + i * 2)
                        if (mV in 1000..6000) cells[(block - 1) * 3 + i + 1] = mV / 1000.0
                    }
                    changed = true
                }
                0x96 -> {
                    val block = p[0]
                    for (i in 0..6) {
                        val value = p[i + 1] - 40
                        if (value in -50..150) temperatures[(block - 1) * 7 + i + 1] = value
                    }
                    changed = true
                }
            }
        }
        if (!changed || voltage == null) return null
        return BmsTelemetry(
            packVoltageV = voltage,
            currentA = current,
            socPercent = soc,
            ratedCapacityAh = capacityAh,
            cellVoltagesV = cells.values.toList(),
            temperaturesC = temperatures.values.toList(),
            cycleCount = cycles,
            chargeMosEnabled = chargeMos,
            dischargeMosEnabled = dischargeMos,
            operatingMode = mode,
            protocol = "DALY A5 (unverified on this firmware)"
        )
    }

    fun request(command: Int): ByteArray {
        val frame = ByteArray(13)
        frame[0] = 0xA5.toByte()
        frame[1] = 0x80.toByte()
        frame[2] = command.toByte()
        frame[3] = 0x08
        frame[12] = (frame.take(12).sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        return frame
    }
}
