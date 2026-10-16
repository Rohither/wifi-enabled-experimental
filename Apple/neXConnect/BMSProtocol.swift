import Foundation

struct BMSTelemetry: Codable {
    var timestamp = Date()
    var voltage: Double?
    var current: Double?
    var soc: Double?
    var remainingAh: Double?
    var ratedAh: Double?
    var cells: [Double] = []
    var temperatures: [Int] = []
    var cycles: Int?
    var chargeMOS: Bool?
    var dischargeMOS: Bool?
    var mode: String = "—"
    var protocolName: String = "Unknown"
    var powerKW: Double? { guard let voltage, let current else { return nil }; return abs(voltage * current) / 1000 }
}

struct BatterySample: Codable, Identifiable {
    var id: Int64 { Int64(timestamp.timeIntervalSince1970) }
    let timestamp: Date
    let voltage: Double
    let current: Double
    let soc: Double
    let mode: String
}

enum BatteryHistoryStore {
    private static let key = "nexconnect.history.v1"
    static func load() -> [BatterySample] {
        guard let data = UserDefaults.standard.data(forKey: key), let points = try? JSONDecoder().decode([BatterySample].self, from: data) else { return [] }
        return points.sorted { $0.timestamp < $1.timestamp }
    }
    static func append(_ telemetry: BMSTelemetry) {
        guard let voltage = telemetry.voltage, let current = telemetry.current, let soc = telemetry.soc else { return }
        var points = load()
        let point = BatterySample(timestamp: telemetry.timestamp, voltage: voltage, current: current, soc: soc, mode: telemetry.mode)
        let minute = Int64(point.timestamp.timeIntervalSince1970 / 60)
        if let last = points.last, Int64(last.timestamp.timeIntervalSince1970 / 60) == minute { points[points.count - 1] = point }
        else { points.append(point) }
        let cutoff = Date().addingTimeInterval(-366 * 24 * 60 * 60)
        points = points.filter { $0.timestamp >= cutoff }
        if points.count > 20_000 { points.removeFirst(points.count - 20_000) }
        if let data = try? JSONEncoder().encode(points) { UserDefaults.standard.set(data, forKey: key) }
    }
    static func clear() { UserDefaults.standard.removeObject(forKey: key) }
}

final class DalyD2Decoder {
    private var bytes: [UInt8] = []
    func reset() { bytes.removeAll() }
    func request() -> Data { var frame: [UInt8] = [0xD2, 0x03, 0x00, 0x00, 0x00, 0x3E]; let crc = Self.crc(frame); frame += [UInt8(crc & 0xFF), UInt8(crc >> 8)]; return Data(frame) }
    func accept(_ data: Data) -> BMSTelemetry? {
        bytes += data
        while bytes.count >= 5 {
            guard let start = bytes.firstIndex(of: 0xD2) else { bytes.removeAll(); return nil }
            if start > 0 { bytes.removeFirst(start) }
            guard bytes.count >= 3 else { return nil }
            let function = bytes[1]
            let size = function == 0x03 ? 3 + Int(bytes[2]) + 2 : (function == 0x06 ? 8 : ((function & 0x80) != 0 ? 5 : 0))
            guard size > 0 else { bytes.removeFirst(); continue }
            guard bytes.count >= size else { return nil }
            let frame = Array(bytes.prefix(size)); bytes.removeFirst(size)
            let expected = Self.crc(Array(frame.dropLast(2)))
            let received = Int(frame[size - 2]) | (Int(frame[size - 1]) << 8)
            guard expected == received, function == 0x03, frame[2] >= 124 else { continue }
            let payload = Array(frame[3..<(3 + Int(frame[2]))])
            func word(_ offset: Int) -> Int { (Int(payload[offset]) << 8) | Int(payload[offset + 1]) }
            let cellCount = min(max(word(98), 0), 48), temperatureCount = min(max(word(100), 0), 16)
            let cells = (0..<cellCount).compactMap { i -> Double? in let v = word(i * 2); return (1000...6000).contains(v) ? Double(v) / 1000 : nil }
            let temps = (0..<temperatureCount).map { min(max(word(64 + $0 * 2) - 40, -50), 150) }
            let current = Double(word(82) - 30000) / 10
            return BMSTelemetry(timestamp: Date(), voltage: Double(word(80)) / 10, current: current, soc: min(max(Double(word(84)) / 10, 0), 100), remainingAh: Double(word(96)) / 10, ratedAh: nil, cells: cells, temperatures: temps, cycles: word(102), chargeMOS: word(106) != 0, dischargeMOS: word(108) != 0, mode: current > 0.1 ? "Charging" : (current < -0.1 ? "Discharging" : "Idle"), protocolName: "DALY D2 Modbus BLE")
        }
        return nil
    }
    private static func crc(_ data: [UInt8]) -> Int {
        var crc = 0xFFFF
        for byte in data { crc ^= Int(byte); for _ in 0..<8 { crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1 } }
        return crc & 0xFFFF
    }
}

final class DalyA5Decoder {
    private var bytes: [UInt8] = []
    private var voltage: Double?, current: Double?, soc: Double?, capacity: Double?
    private var cycles: Int?, chargeMOS: Bool?, dischargeMOS: Bool?, mode = "—"
    private var cells: [Int: Double] = [:], temperatures: [Int: Int] = [:]
    func reset() { bytes.removeAll(); voltage = nil; current = nil; soc = nil; capacity = nil; cycles = nil; chargeMOS = nil; dischargeMOS = nil; mode = "—"; cells.removeAll(); temperatures.removeAll() }
    func request(_ command: UInt8) -> Data { var frame = [UInt8](repeating: 0, count: 13); frame[0] = 0xA5; frame[1] = 0x80; frame[2] = command; frame[3] = 0x08; frame[12] = UInt8(frame.prefix(12).reduce(0) { ($0 + Int($1)) & 0xFF }); return Data(frame) }
    func accept(_ data: Data) -> BMSTelemetry? {
        bytes += data
        var changed = false
        while true {
            guard let start = bytes.firstIndex(of: 0xA5) else { bytes.removeAll(); break }
            if start > 0 { bytes.removeFirst(start) }
            guard bytes.count >= 13 else { break }
            let frame = Array(bytes.prefix(13))
            guard (frame.prefix(12).reduce(0) { ($0 + Int($1)) & 0xFF }) == Int(frame[12]), frame[3] == 0x08 else { bytes.removeFirst(); continue }
            bytes.removeFirst(13); let p = Array(frame[4..<12])
            func word(_ offset: Int) -> Int { (Int(p[offset]) << 8) | Int(p[offset + 1]) }
            switch frame[2] {
            case 0x90: voltage = Double(word(0)) / 10; current = Double(word(4) - 30000) / 10; soc = min(max(Double(word(6)) / 10, 0), 100); changed = true
            case 0x93:
                mode = p[0] == 0 ? "Idle" : (p[0] == 1 ? "Charging" : "Discharging"); chargeMOS = p[1] != 0; dischargeMOS = p[2] != 0
                let mAh = (UInt32(p[4]) << 24) | (UInt32(p[5]) << 16) | (UInt32(p[6]) << 8) | UInt32(p[7]); if (1...10_000_000).contains(mAh) { capacity = Double(mAh) / 1000 }; changed = true
            case 0x94: cycles = word(5); changed = true
            case 0x95:
                let block = Int(p[0]); for i in 0...2 { let mv = word(1 + i * 2); if (1000...6000).contains(mv) { cells[(block - 1) * 3 + i + 1] = Double(mv) / 1000 } }; changed = true
            case 0x96:
                let block = Int(p[0]); for i in 0...6 { let value = Int(p[i + 1]) - 40; if (-50...150).contains(value) { temperatures[(block - 1) * 7 + i + 1] = value } }; changed = true
            default: break
            }
        }
        guard changed, let voltage else { return nil }
        return BMSTelemetry(timestamp: Date(), voltage: voltage, current: current, soc: soc, remainingAh: nil, ratedAh: capacity, cells: cells.keys.sorted().compactMap { cells[$0] }, temperatures: temperatures.keys.sorted().compactMap { temperatures[$0] }, cycles: cycles, chargeMOS: chargeMOS, dischargeMOS: dischargeMOS, mode: mode, protocolName: "DALY A5 (firmware dependent)")
    }
}
