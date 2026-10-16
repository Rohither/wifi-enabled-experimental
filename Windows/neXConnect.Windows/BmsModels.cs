using System.Text.Json;
using System.Globalization;
using System.IO;

namespace NeXConnect.Windows;

public sealed record BmsDevice(ulong Address, string Name, short Rssi)
{
    public string Display => $"{Name}    ·    {Address:X12}    ·    {Rssi} dBm";
}

public sealed class BmsTelemetry
{
    public DateTime Timestamp { get; init; } = DateTime.Now;
    public double? Voltage { get; init; }
    public double? Current { get; init; }
    public double? Soc { get; init; }
    public double? RemainingAh { get; init; }
    public double? RatedAh { get; init; }
    public List<double> Cells { get; init; } = [];
    public List<int> Temperatures { get; init; } = [];
    public int? Cycles { get; init; }
    public bool? ChargeMos { get; init; }
    public bool? DischargeMos { get; init; }
    public string Mode { get; init; } = "—";
    public string ProtocolName { get; init; } = "Unknown";
    public double? PowerKW => Voltage is double v && Current is double a ? Math.Abs(v * a) / 1000 : null;
}

public sealed record BatterySample(DateTime Timestamp, double Voltage, double Current, double Soc, string Mode);

public sealed class LocalStore
{
    private readonly string _path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "neX Connect", "settings.json");
    private readonly string _historyPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "neX Connect", "history.json");
    private StoreData _data;
    public List<BatterySample> History { get; private set; }
    public string Profile { get => _data.Profile; set { _data.Profile = value; Save(); } }
    public string Inverter { get => _data.Inverter; set { _data.Inverter = value; Save(); } }
    public double CapacityAh { get => _data.CapacityAh; set { _data.CapacityAh = value; Save(); } }
    public bool DarkMode { get => _data.DarkMode; set { _data.DarkMode = value; Save(); } }
    public int TransparencyPercent { get => _data.TransparencyPercent; set { _data.TransparencyPercent = Math.Clamp(value, 60, 100); Save(); } }
    public LocalStore()
    {
        try { _data = JsonSerializer.Deserialize<StoreData>(File.ReadAllText(_path)) ?? new(); } catch { _data = new(); }
        try { History = JsonSerializer.Deserialize<List<BatterySample>>(File.ReadAllText(_historyPath)) ?? []; } catch { History = []; }
        History = History.Where(x => x.Timestamp >= DateTime.Now.AddDays(-366)).OrderBy(x => x.Timestamp).ToList();
    }
    private void Save() { Directory.CreateDirectory(Path.GetDirectoryName(_path)!); File.WriteAllText(_path, JsonSerializer.Serialize(_data)); }
    public void Append(BmsTelemetry t)
    {
        if (t.Voltage is not double v || t.Current is not double a || t.Soc is not double soc) return;
        var sample = new BatterySample(t.Timestamp, v, a, soc, t.Mode);
        var minute = new DateTime(sample.Timestamp.Year, sample.Timestamp.Month, sample.Timestamp.Day, sample.Timestamp.Hour, sample.Timestamp.Minute, 0);
        var idx = History.FindLastIndex(x => new DateTime(x.Timestamp.Year, x.Timestamp.Month, x.Timestamp.Day, x.Timestamp.Hour, x.Timestamp.Minute, 0) == minute);
        if (idx >= 0) History[idx] = sample; else History.Add(sample);
        History = History.Where(x => x.Timestamp >= DateTime.Now.AddDays(-366)).TakeLast(20000).ToList();
        Directory.CreateDirectory(Path.GetDirectoryName(_historyPath)!); File.WriteAllText(_historyPath, JsonSerializer.Serialize(History));
    }
    public void ClearHistory() { History.Clear(); if (File.Exists(_historyPath)) File.Delete(_historyPath); }
    public void ExportCsv(string path)
    {
        var lines = new List<string> { "Timestamp,Voltage_V,Current_A,SOC_percent,Mode" };
        lines.AddRange(History.Select(x => string.Join(",", x.Timestamp.ToString("O", CultureInfo.InvariantCulture), x.Voltage.ToString("0.###", CultureInfo.InvariantCulture), x.Current.ToString("0.###", CultureInfo.InvariantCulture), x.Soc.ToString("0.###", CultureInfo.InvariantCulture), $"\"{x.Mode.Replace("\"", "\"\"")}\"")));
        File.WriteAllLines(path, lines);
    }
    public sealed class StoreData
    {
        public string Profile { get; set; } = "ARENQ · LiFePO₄ 100Ah";
        public string Inverter { get; set; } = "PYLON";
        public double CapacityAh { get; set; } = 100;
        public bool DarkMode { get; set; }
        public int TransparencyPercent { get; set; } = 92;
    }
}

internal sealed class DalyDecoder
{
    private readonly List<byte> _d2 = [];
    private readonly List<byte> _a5 = [];
    private double? _voltage, _current, _soc, _capacity;
    private int? _cycles;
    private bool? _charge, _discharge;
    private string _mode = "—";
    private readonly SortedDictionary<int, double> _cells = [];
    private readonly SortedDictionary<int, int> _temps = [];
    public void Reset() { _d2.Clear(); _a5.Clear(); _voltage = _current = _soc = _capacity = null; _cycles = null; _charge = _discharge = null; _mode = "—"; _cells.Clear(); _temps.Clear(); }
    public static byte[] D2Request() { byte[] b = [0xD2, 0x03, 0, 0, 0, 0x3E]; var crc = Crc(b); return [.. b, (byte)crc, (byte)(crc >> 8)]; }
    public static byte[] A5Request(byte command) { var b = new byte[13]; b[0] = 0xA5; b[1] = 0x80; b[2] = command; b[3] = 8; b[12] = (byte)b.Take(12).Sum(x => x); return b; }
    private static int Crc(IEnumerable<byte> bytes) { var crc = 0xFFFF; foreach (var b in bytes) { crc ^= b; for (var i = 0; i < 8; i++) crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1; } return crc & 0xFFFF; }
    public BmsTelemetry? Accept(byte[] data)
    {
        _d2.AddRange(data);
        while (_d2.Count >= 5)
        {
            var start = _d2.IndexOf(0xD2); if (start < 0) { _d2.Clear(); break; } if (start > 0) _d2.RemoveRange(0, start);
            if (_d2.Count < 3) break;
            var size = _d2[1] == 3 ? 5 + _d2[2] : _d2[1] == 6 ? 8 : (_d2[1] & 0x80) != 0 ? 5 : 0;
            if (size == 0) { _d2.RemoveAt(0); continue; } if (_d2.Count < size) break;
            var f = _d2.Take(size).ToArray(); _d2.RemoveRange(0, size);
            if (size < 8 || Crc(f.Take(size - 2)) != (f[^2] | (f[^1] << 8)) || f[1] != 3 || f[2] < 124) continue;
            var p = f.Skip(3).Take(f[2]).ToArray(); int W(int o) => (p[o] << 8) | p[o + 1];
            var cellCount = Math.Clamp(W(98), 0, 48); var tempCount = Math.Clamp(W(100), 0, 16);
            var cells = Enumerable.Range(0, cellCount).Select(i => W(i * 2)).Where(x => x is >= 1000 and <= 6000).Select(x => x / 1000.0).ToList();
            var temps = Enumerable.Range(0, tempCount).Select(i => Math.Clamp(W(64 + i * 2) - 40, -50, 150)).ToList();
            var current = (W(82) - 30000) / 10.0;
            return new() { Voltage = W(80) / 10.0, Current = current, Soc = Math.Clamp(W(84) / 10.0, 0, 100), RemainingAh = W(96) / 10.0, Cycles = W(102), ChargeMos = W(106) != 0, DischargeMos = W(108) != 0, Cells = cells, Temperatures = temps, Mode = current > .1 ? "Charging" : current < -.1 ? "Discharging" : "Idle", ProtocolName = "DALY D2 Modbus BLE" };
        }
        _a5.AddRange(data); var changed = false;
        while (true)
        {
            var start = _a5.IndexOf(0xA5); if (start < 0) { _a5.Clear(); break; } if (start > 0) _a5.RemoveRange(0, start); if (_a5.Count < 13) break;
            var f = _a5.Take(13).ToArray(); if ((byte)f.Take(12).Sum(x => x) != f[12] || f[3] != 8) { _a5.RemoveAt(0); continue; }
            _a5.RemoveRange(0, 13); var p = f.Skip(4).Take(8).ToArray(); int W(int o) => (p[o] << 8) | p[o + 1];
            switch (f[2])
            {
                case 0x90: _voltage = W(0) / 10.0; _current = (W(4) - 30000) / 10.0; _soc = Math.Clamp(W(6) / 10.0, 0, 100); changed = true; break;
                case 0x93: _mode = p[0] == 0 ? "Idle" : p[0] == 1 ? "Charging" : "Discharging"; _charge = p[1] != 0; _discharge = p[2] != 0; var mah = ((uint)p[4] << 24) | ((uint)p[5] << 16) | ((uint)p[6] << 8) | p[7]; if (mah is > 0 and < 10_000_000) _capacity = mah / 1000.0; changed = true; break;
                case 0x94: _cycles = W(5); changed = true; break;
                case 0x95: var block = p[0]; for (var i = 0; i < 3; i++) { var mv = W(1 + i * 2); if (mv is >= 1000 and <= 6000) _cells[(block - 1) * 3 + i + 1] = mv / 1000.0; } changed = true; break;
                case 0x96: var tb = p[0]; for (var i = 0; i < 7; i++) { var temp = p[i + 1] - 40; if (temp is >= -50 and <= 150) _temps[(tb - 1) * 7 + i + 1] = temp; } changed = true; break;
            }
        }
        if (!changed || _voltage is null) return null;
        return new() { Voltage = _voltage, Current = _current, Soc = _soc, RatedAh = _capacity, Cycles = _cycles, ChargeMos = _charge, DischargeMos = _discharge, Cells = _cells.Values.ToList(), Temperatures = _temps.Values.ToList(), Mode = _mode, ProtocolName = "DALY A5 (firmware dependent)" };
    }
}
