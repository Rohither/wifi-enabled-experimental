using System.Collections.ObjectModel;
using System.Windows;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace NeXConnect.Windows;

public sealed class BmsBluetooth
{
    private readonly ObservableCollection<BmsDevice> _devices = [];
    private readonly DalyDecoder _decoder = new();
    private BluetoothLEAdvertisementWatcher? _watcher;
    private BluetoothLEDevice? _device;
    private GattCharacteristic? _tx;
    private readonly List<GattCharacteristic> _rx = [];
    private readonly List<GattDeviceService> _services = [];
    private CancellationTokenSource? _scanTimeout;
    private CancellationTokenSource? _pollLoop;
    private int _pollAttempts;
    private int _a5Index;
    private bool _receivedD2;
    private static readonly byte[] A5Commands = [0x90, 0x93, 0x94, 0x95, 0x96];

    public event EventHandler? Changed;
    public IReadOnlyList<BmsDevice> Devices => _devices;
    public string Status { get; private set; } = "Ready to scan";
    public string ConnectedName { get; private set; } = "";
    public bool Connected => _device is not null && _tx is not null;
    public BmsTelemetry? Telemetry { get; private set; }

    public async Task ScanAsync()
    {
        StopScan();
        _devices.Clear();
        _watcher = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Active };
        _watcher.Received += Watcher_Received;
        _watcher.Stopped += Watcher_Stopped;
        try
        {
            _watcher.Start();
            Status = "Scanning nearby Bluetooth LE devices…";
            _scanTimeout = new CancellationTokenSource();
            _ = StopScanAfterDelay(_scanTimeout.Token);
        }
        catch (Exception ex) { Status = $"Bluetooth scan could not start: {ex.Message}"; }
        NotifyChanged();
        await Task.CompletedTask;
    }

    private async Task StopScanAfterDelay(CancellationToken token)
    {
        try { await Task.Delay(TimeSpan.FromSeconds(15), token); }
        catch (OperationCanceledException) { return; }
        await OnUiAsync(() => { StopScan(); Status = Devices.Count == 0 ? "No BMS found nearby" : "Choose a device to connect"; NotifyChanged(); });
    }

    private void Watcher_Received(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs args)
    {
        var name = string.IsNullOrWhiteSpace(args.Advertisement.LocalName) ? "Bluetooth LE device" : args.Advertisement.LocalName;
        _ = OnUiAsync(() =>
        {
            var existing = _devices.FirstOrDefault(x => x.Address == args.BluetoothAddress);
            if (existing is null) _devices.Add(new(args.BluetoothAddress, name, args.RawSignalStrengthInDBm));
            if (Status.StartsWith("Scanning", StringComparison.Ordinal)) { Status = $"Scanning… {_devices.Count} device(s) found"; NotifyChanged(); }
        });
    }

    private void Watcher_Stopped(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementWatcherStoppedEventArgs args)
    {
        if (args.Error != BluetoothError.Success) _ = OnUiAsync(() => { Status = $"Bluetooth scan stopped: {args.Error}"; NotifyChanged(); });
    }

    public async Task ConnectAsync(BmsDevice picked)
    {
        StopScan();
        await DisconnectAsync(keepStatus: true);
        _decoder.Reset(); Telemetry = null; Status = $"Connecting to {picked.Name}…"; NotifyChanged();
        try
        {
            _device = await BluetoothLEDevice.FromBluetoothAddressAsync(picked.Address);
            if (_device is null) throw new InvalidOperationException("Windows could not open this device. Scan again and retry.");
            ConnectedName = string.IsNullOrWhiteSpace(_device.Name) ? picked.Name : _device.Name;
            Status = "Connected · discovering GATT services…"; NotifyChanged();
            var servicesResult = await _device.GetGattServicesAsync(BluetoothCacheMode.Uncached);
            if (servicesResult.Status != GattCommunicationStatus.Success) throw new InvalidOperationException($"GATT service discovery failed ({servicesResult.Status}).");
            foreach (var service in servicesResult.Services)
            {
                _services.Add(service);
                var result = await service.GetCharacteristicsAsync(BluetoothCacheMode.Uncached);
                if (result.Status != GattCommunicationStatus.Success) continue;
                foreach (var characteristic in result.Characteristics)
                {
                    var id = characteristic.Uuid.ToString("N").ToLowerInvariant();
                    var shortId = id.Length == 4 ? id : id.Length == 32 && id.EndsWith("00001000800000805f9b34fb") ? id.Substring(4, 4) : id;
                    if ((shortId == "fff1" || shortId == "ff01") && (characteristic.CharacteristicProperties & (GattCharacteristicProperties.Notify | GattCharacteristicProperties.Indicate)) != 0)
                    {
                        if (!_rx.Contains(characteristic)) { _rx.Add(characteristic); characteristic.ValueChanged += Characteristic_ValueChanged; }
                        var notify = await characteristic.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue.Notify);
                        if (notify != GattCommunicationStatus.Success) await characteristic.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue.Indicate);
                    }
                    if ((shortId == "fff2" || shortId == "ff02") && (characteristic.CharacteristicProperties & (GattCharacteristicProperties.Write | GattCharacteristicProperties.WriteWithoutResponse)) != 0)
                        _tx ??= characteristic;
                }
            }
            if (_tx is null || _rx.Count == 0) throw new InvalidOperationException("This device did not expose the expected DALY BLE FFF1/FFF2 or FF01/FF02 channel pair.");
            ConnectedName = _device.Name is { Length: > 0 } name ? name : picked.Name;
            Status = "Connected · requesting DALY battery data"; _pollAttempts = 0; _a5Index = 0; _receivedD2 = false; NotifyChanged();
            _pollLoop = new CancellationTokenSource(); _ = PollLoop(_pollLoop.Token);
        }
        catch (Exception ex)
        {
            await DisconnectAsync(keepStatus: true);
            Status = $"Connection failed · {ex.Message}"; NotifyChanged();
        }
    }

    private async Task PollLoop(CancellationToken token)
    {
        while (!token.IsCancellationRequested && _tx is not null)
        {
            try
            {
                await Task.Delay(_pollAttempts == 0 ? 500 : 1500, token);
                var fallback = _pollAttempts >= 5 && !_receivedD2;
                var request = fallback ? DalyDecoder.A5Request(A5Commands[_a5Index++ % A5Commands.Length]) : DalyDecoder.D2Request();
                var writer = new DataWriter(); writer.WriteBytes(request);
                var option = (_tx.CharacteristicProperties & GattCharacteristicProperties.WriteWithoutResponse) != 0 ? GattWriteOption.WriteWithoutResponse : GattWriteOption.WriteWithResponse;
                var result = await _tx.WriteValueAsync(writer.DetachBuffer(), option);
                if (result != GattCommunicationStatus.Success && _pollAttempts < 5) await OnUiAsync(() => { Status = $"BMS request failed · {result}"; NotifyChanged(); });
                _pollAttempts++;
                if (_pollAttempts == 5 && !_receivedD2) await OnUiAsync(() => { Status = "No D2 reply yet · trying DALY A5 telemetry"; NotifyChanged(); });
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex) { await OnUiAsync(() => { Status = $"BMS polling stopped · {ex.Message}"; NotifyChanged(); }); break; }
        }
    }

    private void Characteristic_ValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        var reader = DataReader.FromBuffer(args.CharacteristicValue); var bytes = new byte[reader.UnconsumedBufferLength]; reader.ReadBytes(bytes);
        var telemetry = _decoder.Accept(bytes); if (telemetry is null) return;
        if (telemetry.ProtocolName.StartsWith("DALY D2", StringComparison.Ordinal)) _receivedD2 = true;
        _ = OnUiAsync(() => { Telemetry = telemetry; Status = $"Connected · {telemetry.ProtocolName}"; NotifyChanged(); });
    }

    public async Task DisconnectAsync(bool keepStatus = false)
    {
        StopScan(); _pollLoop?.Cancel(); _pollLoop?.Dispose(); _pollLoop = null;
        foreach (var characteristic in _rx) characteristic.ValueChanged -= Characteristic_ValueChanged;
        _rx.Clear(); _tx = null;
        foreach (var service in _services) service.Dispose(); _services.Clear();
        _device?.Dispose(); _device = null; ConnectedName = ""; Telemetry = null; _decoder.Reset();
        if (!keepStatus) Status = "Disconnected";
        NotifyChanged(); await Task.CompletedTask;
    }

    private void StopScan()
    {
        _scanTimeout?.Cancel(); _scanTimeout?.Dispose(); _scanTimeout = null;
        if (_watcher is not null) { _watcher.Received -= Watcher_Received; _watcher.Stopped -= Watcher_Stopped; try { _watcher.Stop(); } catch { } _watcher = null; }
    }

    public string RemainingTime(double capacityAh)
    {
        var t = Telemetry;
        if (t?.Current is not double current || t.Soc is not double soc) return "—";
        if (current > .05) return "Charging";
        if (current >= -.05) return "No load";
        var hours = (t.RemainingAh ?? capacityAh * soc / 100) / Math.Abs(current);
        return hours >= 1 ? $"{hours:0.0} h" : $"{hours * 60:0} min";
    }

    private void NotifyChanged() => Changed?.Invoke(this, EventArgs.Empty);
    private static Task OnUiAsync(Action action)
    {
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher is null || dispatcher.CheckAccess()) { action(); return Task.CompletedTask; }
        return dispatcher.InvokeAsync(action).Task;
    }
    public async Task DisposeAsync() { await DisconnectAsync(); }
}
