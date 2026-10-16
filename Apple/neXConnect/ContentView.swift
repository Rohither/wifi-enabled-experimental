import SwiftUI
import CoreBluetooth
import Charts

private enum Brand {
    static let ink = Color.primary
    static let hero = Color(red: 0.08, green: 0.16, blue: 0.20)
    static let teal = Color(red: 0.00, green: 0.58, blue: 0.56)
    #if os(macOS)
    static let page = Color(nsColor: .windowBackgroundColor)
    #else
    static let page = Color(uiColor: .systemGroupedBackground)
    #endif
    static let muted = Color.secondary
}

struct ContentView: View {
    @StateObject private var bluetooth = BMSBluetooth()
    @State private var tab = 0
    #if os(macOS)
    @State private var macSelection: Int? = 0
    #endif
    @AppStorage("nexconnect.capacityAh") private var capacity = "100"
    @AppStorage("nexconnect.darkMode") private var darkMode = false
    @AppStorage("nexconnect.bmsModel") private var bmsModel = "ARENQ · LiFePO₄ 100Ah"
    @AppStorage("nexconnect.inverterProtocol") private var inverterProtocol = "PYLON"
    @State private var historyMonth = Date()
    @State private var localParameters = UserDefaults.standard.dictionary(forKey: "nexconnect.localParameters") as? [String: String] ?? [:]
    private let modelOptions = ["Pylontech · US2000/US3000 family", "JK / Jikong · JK-PB2A16S-15P", "JBD / LLT · SP17S005 Smart BMS", "Daly · Smart BMS 4S–24S", "ARENQ · LiFePO₄ 100Ah", "Luminous · Helios 12.8V 100Ah", "Luminous · Helios 51.2V 100Ah", "MuscleGrid · Thunder Pro 48V", "Other · Custom model"]
    private let inverterOptions = ["NONE", "PYLON", "GROWATT", "SOFAR", "VOLTRONICPOWER", "GOODWE", "SRNE", "MUST", "VICTRONENERGY", "SMA", "DEYE", "AISWEI", "SACOLAR", "SOLARK", "XMT", "SOLIS", "LUXPOWERTEK", "STUDER", "SOROTEC"]
    private let configurationGroups: [(String, [String])] = [
        ("Protection parameters", ["Cell over-voltage protection", "Cell over-voltage recovery", "Cell under-voltage protection", "Cell under-voltage recovery", "Pack over-voltage protection", "Pack under-voltage protection", "Cell voltage difference protection", "Charge over-current protection", "Discharge over-current protection"]),
        ("Cell characteristics", ["Battery chemistry code", "Rated capacity", "Nominal cell voltage", "Sleep waiting time", "SOC setting", "Balance start voltage", "Balance voltage difference"]),
        ("Collect board settings", ["Number of acquisition boards", "Board 1 cell count", "Board 2 cell count", "Board 3 cell count", "Board 1 temperature sensor count", "Board 2 temperature sensor count", "Board 3 temperature sensor count"]),
        ("Temperature protection", ["Charge high-temperature protection", "Charge low-temperature protection", "Discharge high-temperature protection", "Discharge low-temperature protection", "Temperature difference protection", "MOS temperature protection"]),
        ("System settings", ["Charge MOS switch", "Discharge MOS switch", "Data upload switch", "Heating switch", "Battery string count", "Current zero calibration", "Password"])
    ]

    var body: some View {
        #if os(macOS)
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                macNavigationButton("Monitor", icon: "waveform.path.ecg", selection: 0)
                macNavigationButton("History", icon: "chart.bar.xaxis", selection: 1)
                macNavigationButton("Settings", icon: "slider.horizontal.3", selection: 2)
                macNavigationButton("About", icon: "info.circle", selection: 3)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.bar)
            Divider()
            selectedMacContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .tint(Brand.teal)
        .preferredColorScheme(darkMode ? .dark : .light)
        .frame(minWidth: 700, idealWidth: 820, maxWidth: .infinity,
               minHeight: 520, idealHeight: 620, maxHeight: .infinity)
        #else
        TabView(selection: $tab) {
            monitor.tag(0).tabItem { Label("Monitor", systemImage: "waveform.path.ecg") }
            history.tag(1).tabItem { Label("History", systemImage: "chart.bar.xaxis") }
            settings.tag(2).tabItem { Label("Settings", systemImage: "slider.horizontal.3") }
            about.tag(3).tabItem { Label("About", systemImage: "info.circle") }
        }
        .tint(Brand.teal)
        .preferredColorScheme(darkMode ? .dark : .light)
        #endif
    }

    #if os(macOS)
    private func macNavigationButton(_ title: String, icon: String, selection: Int) -> some View {
        let isSelected = macSelection == selection
        return Button {
            macSelection = selection
        } label: {
            Label(title, systemImage: icon)
                .font(.system(size: 13, weight: isSelected ? .semibold : .regular))
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .foregroundStyle(isSelected ? Brand.teal : Brand.ink)
                .background(isSelected ? Brand.teal.opacity(0.12) : Color.clear,
                            in: RoundedRectangle(cornerRadius: 8))
                .contentShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    @ViewBuilder private var selectedMacContent: some View {
        switch macSelection ?? 0 {
        case 1: history
        case 2: settings
        case 3: about
        default: monitor
        }
    }
    #endif

    private var monitor: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    HStack {
                        brandLogo().resizable().scaledToFit().frame(width: 205, height: 143)
                        Spacer(minLength: 10)
                        VStack(alignment: .trailing, spacing: 4) {
                            Text(bluetooth.connectedName.isEmpty ? "Waiting to Connect" : bluetooth.connectedName)
                                .font(.system(size: bluetooth.connectedName.isEmpty ? 13 : 16, weight: bluetooth.connectedName.isEmpty ? .regular : .bold))
                                .foregroundStyle(bluetooth.connectedName.isEmpty ? Brand.muted : Brand.ink)
                                .lineLimit(1)
                            Text(bluetooth.status).font(.caption2).foregroundStyle(bluetooth.isConnected ? Brand.teal : Brand.muted).lineLimit(1)
                        }
                    }.padding(.horizontal, 4)

                    VStack(alignment: .leading, spacing: 15) {
                        HStack {
                            VStack(alignment: .leading, spacing: 5) {
                                Text("STATE OF CHARGE").font(.caption.weight(.bold)).tracking(1).foregroundStyle(.white.opacity(0.72))
                                Text(bluetooth.soc.map { "\(Int($0))%" } ?? "—").font(.system(size: 52, weight: .bold, design: .rounded)).foregroundStyle(.white)
                            }
                            Spacer()
                            Image(systemName: "battery.100percent").font(.system(size: 46, weight: .light)).foregroundStyle(.white.opacity(0.9))
                        }
                        ProgressView(value: (bluetooth.soc ?? 0) / 100).tint(.white)
                        HStack { metric("Voltage", value: bluetooth.voltage.map { String(format: "%.2f V", $0) } ?? "—"); Spacer(); metric("Current", value: bluetooth.current.map { String(format: "%.1f A", $0) } ?? "—"); Spacer(); metric("Remaining", value: bluetooth.remainingAh.map { String(format: "%.1f Ah", $0) } ?? "—") }
                    }
                    .padding(18).background(Brand.hero, in: RoundedRectangle(cornerRadius: 24))

                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                        monitorStat("POWER", value: bluetooth.power.map { String(format: "%.3f kW", $0) } ?? "—", icon: "bolt.fill")
                        monitorStat("CYCLES", value: bluetooth.cycles.map(String.init) ?? "—", icon: "arrow.clockwise")
                        monitorStat("TEMP", value: bluetooth.temperatures.first.map { "\($0) °C" } ?? "—", icon: "thermometer")
                    }
                    HStack(spacing: 10) {
                        statusCard("Estimated remaining time", value: bluetooth.estimate(capacityAh: Double(capacity) ?? 100), icon: "clock")
                        statusCard("Operating mode", value: bluetooth.mode, icon: "arrow.left.arrow.right")
                    }
                    sectionCard("Cell Voltages", subtitle: bluetooth.cells.isEmpty ? "Connect to view cell-level readings" : "\(bluetooth.cells.count) cells") {
                        if bluetooth.cells.isEmpty { Text("Cell readings will appear when supported data is received.").font(.subheadline).foregroundStyle(Brand.muted) }
                        else {
                            let minCell = bluetooth.cells.min() ?? 0
                            let maxCell = bluetooth.cells.max() ?? 0
                            HStack { metricLine("Lowest", value: String(format: "%.3f V", minCell)); Spacer(); metricLine("Average", value: String(format: "%.3f V", bluetooth.cells.reduce(0, +) / Double(bluetooth.cells.count))); Spacer(); metricLine("Highest", value: String(format: "%.3f V", maxCell)) }
                            Divider()
                            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                                ForEach(Array(bluetooth.cells.enumerated()), id: \.offset) { index, value in HStack { Text("Cell \(index + 1)"); Spacer(minLength: 4); Text(String(format: "%.3f V", value)).fontWeight(.semibold) }.font(.caption).padding(8).background(Brand.page, in: RoundedRectangle(cornerRadius: 8)) }
                            }
                        }
                    }
                    sectionCard("Protection status", subtitle: "Live BMS switches and temperature sensors") {
                        HStack(spacing: 16) {
                            metricLine("Charge MOS", value: bluetooth.chargeMOS.map { $0 ? "On" : "Off" } ?? "—")
                            metricLine("Discharge MOS", value: bluetooth.dischargeMOS.map { $0 ? "On" : "Off" } ?? "—")
                            metricLine("Sensors", value: bluetooth.temperatures.isEmpty ? "—" : "\(bluetooth.temperatures.count)")
                        }
                        if !bluetooth.temperatures.isEmpty {
                            Divider()
                            HStack { ForEach(Array(bluetooth.temperatures.enumerated()), id: \.offset) { index, value in Text("T\(index + 1)  \(value) °C").font(.caption).foregroundStyle(Brand.ink).padding(7).background(Brand.page, in: RoundedRectangle(cornerRadius: 7)) } }
                        }
                    }
                    sectionCard("BMS details", subtitle: "Battery information reported by the connected device") {
                        metricLine("Connection", value: bluetooth.connectedName.isEmpty ? bluetooth.status : bluetooth.connectedName)
                        Divider()
                        metricLine("Protocol", value: bluetooth.protocolName)
                        metricLine("Battery cycles", value: bluetooth.cycles.map(String.init) ?? "—")
                    }
                    Button { bluetooth.isConnected ? bluetooth.disconnect() : bluetooth.scan() } label: { Label(bluetooth.isConnected ? "Disconnect BMS" : (bluetooth.isScanning ? "Scanning nearby BMS…" : "Scan for BMS"), systemImage: bluetooth.isConnected ? "cable.connector.slash" : "dot.radiowaves.left.and.right").frame(maxWidth: .infinity).padding(.vertical, 13) }
                        .buttonStyle(.borderedProminent).tint(Brand.teal).disabled(bluetooth.isScanning)
                    if !bluetooth.devices.isEmpty {
                        sectionCard("Nearby Bluetooth devices", subtitle: "Select your BMS") {
                            ForEach(bluetooth.devices, id: \.identifier) { device in Button { bluetooth.connect(device) } label: { HStack { Text(device.name ?? "Unknown BMS"); Spacer(); Image(systemName: "chevron.right") } }.foregroundStyle(Brand.ink) }
                        }
                    }
                    if !bluetooth.error.isEmpty { Text(bluetooth.error).font(.caption).foregroundStyle(.red).frame(maxWidth: .infinity, alignment: .leading) }
                }.padding(16)
            }
            .background(Brand.page.ignoresSafeArea())
            .navigationTitle("neX Connect")
        }
    }

    private var filteredHistory: [BatterySample] {
        let calendar = Calendar.current
        return bluetooth.history.filter { calendar.isDate($0.timestamp, equalTo: historyMonth, toGranularity: .month) }
    }

    private var history: some View {
        NavigationStack {
            Group {
                if bluetooth.history.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "chart.xyaxis.line").font(.largeTitle).foregroundStyle(Brand.teal)
                        Text("No history yet").font(.headline)
                        Text("Connect to a BMS to save readings each minute.").font(.subheadline).foregroundStyle(Brand.muted).multilineTextAlignment(.center)
                    }.padding()
                } else {
                    List {
                        Section {
                            DatePicker("Month", selection: $historyMonth, displayedComponents: .date)
                                .datePickerStyle(.compact)
                            if filteredHistory.isEmpty {
                                Text("No readings for this month").foregroundStyle(Brand.muted)
                            } else {
                                Chart(filteredHistory) { point in
                                    LineMark(x: .value("Time", point.timestamp), y: .value("Charge", point.soc))
                                        .foregroundStyle(Brand.teal).interpolationMethod(.catmullRom)
                                }
                                .chartYScale(domain: 0...100)
                                .chartYAxisLabel("State of charge (%)")
                                .frame(height: 190)
                            }
                        } header: { Text("State of charge") }
                        Section("Readings · \(filteredHistory.count)") {
                            ForEach(filteredHistory.suffix(500).reversed()) { point in
                                HStack {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(point.timestamp.formatted(date: .abbreviated, time: .shortened)).font(.subheadline.weight(.semibold))
                                        Text(point.mode).font(.caption).foregroundStyle(Brand.muted)
                                    }
                                    Spacer()
                                    VStack(alignment: .trailing, spacing: 3) {
                                        Text(String(format: "%.2f V · %.1f A", point.voltage, point.current)).font(.subheadline.monospacedDigit())
                                        Text(String(format: "%.0f%%", point.soc)).font(.caption.weight(.semibold)).foregroundStyle(Brand.teal)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("History")
            .toolbar { if !bluetooth.history.isEmpty { Button("Clear") { bluetooth.clearHistory() } } }
        }
    }

    private var settings: some View {
        NavigationStack {
            #if os(macOS)
            macSettingsContent
            #else
            Form {
                Section("Battery profile") {
                    Picker("BMS model", selection: $bmsModel) { ForEach(modelOptions, id: \.self) { Text($0) } }
                    HStack { Text("Rated capacity"); Spacer(); TextField("100", text: $capacity).multilineTextAlignment(.trailing).frame(maxWidth: 100); Text("Ah").foregroundStyle(Brand.muted) }
                }
                Section("Inverter protocol") {
                    Picker("Protocol", selection: $inverterProtocol) { ForEach(inverterOptions, id: \.self) { Text($0) } }
                    Text("Protocol selection is saved locally. This build does not send inverter commands.").font(.footnote).foregroundStyle(Brand.muted)
                }
                Section("Display") { Toggle("Dark appearance", isOn: $darkMode) }
                Section("Bluetooth BMS") {
                    Text(bluetooth.status)
                    Toggle("Reconnect to last BMS", isOn: Binding(get: { bluetooth.autoReconnect }, set: { bluetooth.setAutoReconnect($0) }))
                    if bluetooth.isConnected { Button("Disconnect", role: .destructive) { bluetooth.disconnect() } }
                    Text("Read-only BLE monitoring supports DALY D2 Modbus and DALY A5 frame families. Other model profiles require a matching protocol adapter.").font(.footnote).foregroundStyle(Brand.muted)
                }
                ForEach(configurationGroups, id: \.0) { group, names in
                    Section(group) {
                        ForEach(names, id: \.self) { name in
                            HStack {
                                Text(name).font(.subheadline)
                                Spacer()
                                TextField("Value", text: parameterBinding(name)).multilineTextAlignment(.trailing).frame(maxWidth: 100)
                            }
                        }
                    }
                }
                Section { Text("Configuration values are stored on this Mac or device only. They are not written to the battery.").font(.footnote).foregroundStyle(Brand.muted) }
            }
            #endif
        }
        .navigationTitle("Settings")
    }

    #if os(macOS)
    private var macSettingsContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                sectionCard("Battery profile", subtitle: "Choose the displayed BMS profile and pack capacity") {
                    Picker("BMS model", selection: $bmsModel) {
                        ForEach(modelOptions, id: \.self) { Text($0).tag($0) }
                    }.pickerStyle(.menu)
                    Divider()
                    HStack {
                        Text("Rated capacity")
                        Spacer()
                        TextField("100", text: $capacity).frame(width: 100)
                        Text("Ah").foregroundStyle(Brand.muted)
                    }
                }
                sectionCard("Inverter protocol", subtitle: "Local preference; this version does not send inverter commands") {
                    Picker("Protocol", selection: $inverterProtocol) {
                        ForEach(inverterOptions, id: \.self) { Text($0).tag($0) }
                    }.pickerStyle(.menu)
                }
                sectionCard("Display", subtitle: "Appearance") {
                    Toggle("Dark appearance", isOn: $darkMode)
                }
                sectionCard("Bluetooth BMS", subtitle: "Connection and read-only monitoring") {
                    Text(bluetooth.status).foregroundStyle(Brand.muted)
                    Toggle("Reconnect to last BMS", isOn: Binding(get: { bluetooth.autoReconnect }, set: { bluetooth.setAutoReconnect($0) }))
                    if bluetooth.isConnected { Button("Disconnect", role: .destructive) { bluetooth.disconnect() } }
                    Text("This build reads DALY D2 Modbus and DALY A5 BLE telemetry. Settings are saved on this Mac and are not written to the battery.")
                        .font(.footnote).foregroundStyle(Brand.muted).fixedSize(horizontal: false, vertical: true)
                }
                ForEach(configurationGroups, id: \.0) { group, names in
                    sectionCard(group, subtitle: "Saved locally; these values are not sent to the BMS") {
                        ForEach(names, id: \.self) { name in
                            HStack {
                                Text(name).lineLimit(2)
                                Spacer(minLength: 16)
                                TextField("Value", text: parameterBinding(name))
                                    .textFieldStyle(.roundedBorder).frame(width: 150)
                            }
                            if name != names.last { Divider() }
                        }
                    }
                }
            }
            .frame(maxWidth: 820, alignment: .leading)
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .top)
        }
        .background(Brand.page.ignoresSafeArea())
    }
    #endif

    private func parameterBinding(_ key: String) -> Binding<String> {
        Binding(get: { localParameters[key, default: ""] }, set: { value in
            localParameters[key] = value
            UserDefaults.standard.set(localParameters, forKey: "nexconnect.localParameters")
        })
    }

    private var about: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    Text("About neX Connect").font(.title.bold()).frame(maxWidth: .infinity)
                    VStack(spacing: 8) {
                        bundledImage("PowerNexAboutLogo").resizable().scaledToFit().frame(maxWidth: 520, maxHeight: 150)
                        Text("POWER NEX").font(.title2.weight(.heavy)).foregroundStyle(Brand.ink)
                        Text("Power solution").font(.headline).foregroundStyle(Brand.teal)
                    }
                    .frame(maxWidth: .infinity).padding(18).background(.background, in: RoundedRectangle(cornerRadius: 22))

                    VStack(spacing: 9) {
                        Text("NEXORA SOLUTIONS").font(.title3.bold()).foregroundStyle(Brand.ink)
                        Text("Near Bypass junction").font(.subheadline.weight(.semibold)).foregroundStyle(Brand.muted)
                        Text("Chembra Road, Perambra").font(.subheadline.weight(.semibold)).foregroundStyle(Brand.muted)
                        Divider().padding(.vertical, 4)
                        Text("Contact").font(.headline)
                        ForEach(["+919562060602", "+919745008687", "+918907606090"], id: \.self) { phone in
                            Text(phone).font(.headline).foregroundStyle(Brand.teal)
                        }
                    }
                    .frame(maxWidth: .infinity).padding(20).background(.background, in: RoundedRectangle(cornerRadius: 20))

                    VStack(spacing: 8) {
                        Text("Designed by").font(.subheadline.weight(.bold)).foregroundStyle(Brand.muted)
                        bundledImage("ChanakyaLogo").resizable().scaledToFit().frame(maxWidth: 560, maxHeight: 150)
                        Text("Chanakya · The Rhythm of Multimedia").font(.caption).foregroundStyle(Brand.muted)
                    }.frame(maxWidth: .infinity).padding(12)
                }
                .frame(maxWidth: 700)
                .padding(22)
                .frame(maxWidth: .infinity)
            }
            .background(Brand.page.ignoresSafeArea())
            .navigationTitle("About")
        }
    }

    private func brandLogo() -> Image { bundledImage("PowerNexLogo") }

    private func bundledImage(_ name: String) -> Image {
        #if os(iOS)
        return Image(uiImage: UIImage(contentsOfFile: Bundle.main.path(forResource: name, ofType: "png") ?? "") ?? UIImage())
        #else
        return Image(nsImage: NSImage(contentsOfFile: Bundle.main.path(forResource: name, ofType: "png") ?? "") ?? NSImage())
        #endif
    }

    private func metric(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) { Text(title.uppercased()).font(.system(size: 9, weight: .bold)).tracking(0.8).foregroundStyle(.white.opacity(0.65)); Text(value).font(.system(size: 16, weight: .semibold)).foregroundStyle(.white) }
    }

    private func monitorStat(_ title: String, value: String, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Image(systemName: icon).foregroundStyle(Brand.teal)
            Text(title).font(.caption2.weight(.bold)).tracking(0.6).foregroundStyle(Brand.muted)
            Text(value).font(.system(size: 15, weight: .semibold)).foregroundStyle(Brand.ink).lineLimit(1).minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, minHeight: 78, alignment: .leading)
        .padding(12).background(.background, in: RoundedRectangle(cornerRadius: 14))
    }

    private func metricLine(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(Brand.muted)
            Text(value).font(.subheadline.weight(.semibold)).foregroundStyle(Brand.ink).lineLimit(1).minimumScaleFactor(0.8)
        }
    }
    private func statusCard(_ title: String, value: String, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 9) { Image(systemName: icon).foregroundStyle(Brand.teal); Text(title).font(.caption).foregroundStyle(Brand.muted); Text(value).font(.headline).foregroundStyle(Brand.ink).lineLimit(1).minimumScaleFactor(0.75) }
            .frame(maxWidth: .infinity, minHeight: 95, alignment: .leading).padding(14).background(.background, in: RoundedRectangle(cornerRadius: 18))
    }
    private func sectionCard<Content: View>(_ title: String, subtitle: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) { VStack(alignment: .leading, spacing: 3) { Text(title).font(.headline); Text(subtitle).font(.caption).foregroundStyle(Brand.muted) }; content() }
            .frame(maxWidth: .infinity, alignment: .leading).padding(16).background(.background, in: RoundedRectangle(cornerRadius: 18))
    }
}

final class BMSBluetooth: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    @Published var devices: [CBPeripheral] = []
    @Published var status = "Bluetooth is starting…"
    @Published var connectedName = ""
    @Published var isScanning = false
    @Published var isConnected = false
    @Published var voltage: Double?
    @Published var current: Double?
    @Published var soc: Double?
    @Published var remainingAh: Double?
    @Published var cells: [Double] = []
    @Published var temperatures: [Int] = []
    @Published var cycles: Int?
    @Published var chargeMOS: Bool?
    @Published var dischargeMOS: Bool?
    @Published var protocolName = "—"
    @Published var mode = "—"
    @Published var error = ""
    @Published var history = BatteryHistoryStore.load()
    @Published var autoReconnect = UserDefaults.standard.bool(forKey: "nexconnect.autoReconnect")
    private var central: CBCentralManager!
    private var active: CBPeripheral?
    private var receiveCharacteristic: CBCharacteristic?
    private var transmitCharacteristic: CBCharacteristic?
    private var pollTimer: Timer?
    private var reconnectTimer: Timer?
    private var reconnectAttempts = 0
    private let d2 = DalyD2Decoder()
    private let a5 = DalyA5Decoder()
    private var pollAttempts = 0
    private var a5Index = 0
    private var receivedD2 = false
    private let a5Commands: [UInt8] = [0x90, 0x93, 0x94, 0x95, 0x96]

    override init() { super.init(); central = CBCentralManager(delegate: self, queue: .main) }
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            status = "Ready to scan"
            if autoReconnect, let saved = UserDefaults.standard.string(forKey: "nexconnect.savedBMSUUID"), let id = UUID(uuidString: saved), let peripheral = central.retrievePeripherals(withIdentifiers: [id]).first {
                active = peripheral; peripheral.delegate = self; status = "Reconnecting to saved BMS…"; central.connect(peripheral)
            }
        case .poweredOff: status = "Bluetooth is off"
        case .unauthorized: status = "Bluetooth permission is needed"
        case .unsupported: status = "Bluetooth LE is unavailable"
        default: status = "Bluetooth is starting…"
        }
    }
    func scan() {
        guard central.state == .poweredOn else { error = "Turn on Bluetooth and allow neX Connect to use it."; return }
        devices.removeAll(); error = ""; isScanning = true; status = "Scanning nearby devices…"
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self, self.isScanning else { return }; self.central.stopScan(); self.isScanning = false
            if !self.isConnected { self.status = self.devices.isEmpty ? "No BMS found nearby" : "Choose a device to connect" }
        }
    }
    func connect(_ device: CBPeripheral) {
        central.stopScan(); isScanning = false
        if let active { central.cancelPeripheralConnection(active) }
        resetTelemetry(); self.active = device; device.delegate = self
        autoReconnect = true; UserDefaults.standard.set(true, forKey: "nexconnect.autoReconnect")
        UserDefaults.standard.set(device.identifier.uuidString, forKey: "nexconnect.savedBMSUUID")
        status = "Connecting…"; central.connect(device)
    }
    func disconnect() {
        autoReconnect = false; UserDefaults.standard.set(false, forKey: "nexconnect.autoReconnect")
        reconnectTimer?.invalidate(); pollTimer?.invalidate()
        if let active { central.cancelPeripheralConnection(active) }
    }
    func setAutoReconnect(_ enabled: Bool) {
        autoReconnect = enabled; UserDefaults.standard.set(enabled, forKey: "nexconnect.autoReconnect")
        if enabled, let active { UserDefaults.standard.set(active.identifier.uuidString, forKey: "nexconnect.savedBMSUUID") }
        if !enabled { reconnectTimer?.invalidate(); UserDefaults.standard.removeObject(forKey: "nexconnect.savedBMSUUID") }
        if enabled, !isConnected, central.state == .poweredOn, let saved = UserDefaults.standard.string(forKey: "nexconnect.savedBMSUUID"), let id = UUID(uuidString: saved), let peripheral = central.retrievePeripherals(withIdentifiers: [id]).first {
            active = peripheral; peripheral.delegate = self; status = "Reconnecting to saved BMS…"; central.connect(peripheral)
        }
    }
    func clearHistory() { BatteryHistoryStore.clear(); history.removeAll() }
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard !devices.contains(where: { $0.identifier == peripheral.identifier }) else { return }; devices.append(peripheral)
    }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        reconnectTimer?.invalidate(); reconnectAttempts = 0; isConnected = true; connectedName = peripheral.name?.isEmpty == false ? peripheral.name! : "CONNECTED BMS"
        status = "Connected · discovering services…"; peripheral.discoverServices(nil)
    }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) { isConnected = false; status = "Connection failed"; self.error = error?.localizedDescription ?? "Could not connect to this device." }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        pollTimer?.invalidate(); isConnected = false; connectedName = ""; resetTelemetry(); status = "Waiting to Connect"
        if autoReconnect {
            reconnectTimer?.invalidate()
            reconnectTimer = Timer.scheduledTimer(withTimeInterval: min(3.0 * Double(1 << min(reconnectAttempts, 3)), 24), repeats: false) { [weak self] _ in
                guard let self, self.autoReconnect, let active = self.active else { return }
                self.reconnectAttempts = min(self.reconnectAttempts + 1, 4); self.status = "Reconnecting to saved BMS…"; self.central.connect(active)
            }
        }
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let services = peripheral.services else { self.error = error?.localizedDescription ?? "Could not read device services."; return }
        for service in services { peripheral.discoverCharacteristics(nil, for: service) }
        status = "Connected · discovering BMS channels"
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else { self.error = error?.localizedDescription ?? "Characteristic discovery failed."; return }
        for characteristic in service.characteristics ?? [] {
            let id = characteristic.uuid.uuidString.lowercased()
            if (id.hasSuffix("fff1") || id.hasSuffix("ff01")) && (characteristic.properties.contains(.notify) || characteristic.properties.contains(.indicate)) { receiveCharacteristic = characteristic }
            if (id.hasSuffix("fff2") || id.hasSuffix("ff02")) && (characteristic.properties.contains(.write) || characteristic.properties.contains(.writeWithoutResponse)) { transmitCharacteristic = characteristic }
            if characteristic.properties.contains(.notify) || characteristic.properties.contains(.indicate) { peripheral.setNotifyValue(true, for: characteristic) }
        }
        if receiveCharacteristic != nil, transmitCharacteristic != nil {
            status = "Connected · requesting DALY battery data"
            pollTimer?.invalidate(); pollAttempts = 0; receivedD2 = false
            pollTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in self?.poll() }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in self?.poll() }
        } else { status = "Connected · no known DALY BLE channel pair"; self.error = "This device did not expose the DALY FFF1/FFF2 or FF01/FF02 BLE channels." }
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if let error { self.error = "Could not enable BMS notifications: \(error.localizedDescription)" }
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error { self.error = error.localizedDescription; return }
        guard let data = characteristic.value else { return }; receive(data)
    }
    private func poll() {
        guard let active, let tx = transmitCharacteristic, isConnected else { return }
        let command: Data
        let fallback = pollAttempts >= 5 && !receivedD2
        if fallback { command = a5.request(a5Commands[a5Index]); a5Index = (a5Index + 1) % a5Commands.count }
        else { command = d2.request() }
        let writeType: CBCharacteristicWriteType = tx.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        active.writeValue(command, for: tx, type: writeType); pollAttempts += 1
        if pollAttempts == 5 { status = "No D2 reply yet · trying DALY A5 telemetry" }
    }
    private func receive(_ data: Data) {
        if let sample = d2.accept(data) { receivedD2 = true; apply(sample); status = "Connected · \(sample.protocolName)" }
        else if let sample = a5.accept(data) { apply(sample); status = "Connected · \(sample.protocolName)" }
    }
    private func apply(_ sample: BMSTelemetry) {
        voltage = sample.voltage; current = sample.current; soc = sample.soc; remainingAh = sample.remainingAh
        cells = sample.cells; temperatures = sample.temperatures; cycles = sample.cycles
        chargeMOS = sample.chargeMOS; dischargeMOS = sample.dischargeMOS
        protocolName = sample.protocolName; mode = sample.mode
        BatteryHistoryStore.append(sample); history = BatteryHistoryStore.load()
    }
    private func resetTelemetry() {
        voltage = nil; current = nil; soc = nil; remainingAh = nil; cells = []; temperatures = []
        cycles = nil; chargeMOS = nil; dischargeMOS = nil; protocolName = "—"; mode = "—"
        receiveCharacteristic = nil; transmitCharacteristic = nil; d2.reset(); a5.reset(); pollAttempts = 0; receivedD2 = false
    }
    var power: Double? { guard let voltage, let current else { return nil }; return abs(voltage * current) / 1000 }
    func estimate(capacityAh: Double) -> String {
        guard let current, let soc, current < -0.05 else { return current.map { $0 > 0.05 ? "Charging" : "No load" } ?? "—" }
        let available = remainingAh ?? (capacityAh * soc / 100)
        let hours = available / abs(current)
        return hours >= 1 ? String(format: "%.1f h", hours) : String(format: "%.0f min", hours * 60)
    }
}
