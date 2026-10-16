using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace NeXConnect.Windows;

public partial class MainWindow : Window
{
    private readonly BmsBluetooth _bluetooth = new();
    private readonly LocalStore _store = new();
    private readonly DispatcherTimer _refresh = new() { Interval = TimeSpan.FromMilliseconds(700) };
    private string _page = "Monitor";
    private DateTime _lastSavedSample = DateTime.MinValue;
    private readonly List<string> _profiles = ["Pylontech · US2000C", "Pylontech · US3000C", "Pylontech · US5000", "Pylontech · UP5000", "JK / Jikong · JK-B1A8S-10P", "JK / Jikong · JK-B2A8S-20P", "JK / Jikong · JK-PB2A16S-15P", "JBD / LLT · SP17S005 Smart BMS", "JBD / LLT · SP Series V4 BLE", "Daly · Smart BMS 4S–24S family", "NEEY · 24S8A / 10A / 15A Active Balancer", "JLGW · exact model required", "ARENQ · LiFePO₄ 100Ah", "Luminous · Helios 12.8V 100Ah", "Luminous · Helios 51.2V 100Ah", "MuscleGrid · Thunder Pro 48V 120Ah", "Other · custom model"];
    private readonly List<string> _inverters = ["NONE", "PYLON", "GROWATT", "SOFAR", "VOLTRONICPOWER", "GOODWE", "SRNE", "MUST", "VICTRONENERGY", "SMA", "DEYE", "AISWEI", "SACOLAR", "SOLARK", "XMT", "SOLIS", "LUXPOWERTEK", "STUDER", "SOROTEC"];

    public MainWindow()
    {
        InitializeComponent();
        ApplyTheme();
        _bluetooth.Changed += (_, _) => Dispatcher.Invoke(() =>
        {
            if (_bluetooth.Telemetry is { } t && t.Timestamp > _lastSavedSample && t.Voltage is not null && t.Current is not null && t.Soc is not null)
            {
                _store.Append(t); _lastSavedSample = t.Timestamp;
            }
            RefreshView();
        });
        _refresh.Tick += (_, _) => RefreshView();
        _refresh.Start();
        ShowPage("Monitor");
        RefreshView();
    }

    private void Navigate_Click(object sender, RoutedEventArgs e) => ShowPage((string)((Button)sender).Tag);

    private void ShowPage(string page)
    {
        _page = page;
        PageFrame.Content = page switch
        {
            "History" => BuildHistory(),
            "Settings" => BuildSettings(),
            "About" => BuildAbout(),
            _ => BuildMonitor()
        };
        RefreshView();
    }

    private UIElement BuildMonitor()
    {
        var root = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = new StackPanel { Margin = new Thickness(28, 22, 28, 28) } };
        var panel = (StackPanel)root.Content;
        panel.Children.Add(Heading("Battery monitor", "Live read-only telemetry from a supported Bluetooth LE BMS."));
        var hero = Card();
        var heroGrid = new Grid(); heroGrid.ColumnDefinitions.Add(new ColumnDefinition()); heroGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(240) });
        var left = new StackPanel { Margin = new Thickness(8) };
        left.Children.Add(Label("STATE OF CHARGE", 12, MutedBrush, true));
        left.Children.Add(new TextBlock { Name = "SocValue", Text = "—%", FontSize = 58, FontWeight = FontWeights.Bold, Foreground = (Brush)FindResource("TealBrush"), Margin = new Thickness(0, 3, 0, 0) });
        left.Children.Add(new TextBlock { Name = "ModeValue", Text = "Waiting for BMS data", FontSize = 16, Margin = new Thickness(0, 3, 0, 0) });
        left.Children.Add(new ProgressBar { Name = "SocBar", Height = 10, Maximum = 100, Margin = new Thickness(0, 16, 12, 4), Foreground = (Brush)FindResource("TealBrush") });
        Grid.SetColumn(left, 0); heroGrid.Children.Add(left);
        var actions = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(16) };
        actions.Children.Add(new TextBlock { Name = "DeviceValue", Text = "No battery connected", FontSize = 16, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 10) });
        var scan = new Button { Name = "ScanButton", Content = "Scan for BMS" }; scan.Click += async (_, _) => await _bluetooth.ScanAsync(); actions.Children.Add(scan);
        var disconnect = new Button { Content = "Disconnect" }; disconnect.Click += async (_, _) => await _bluetooth.DisconnectAsync(); actions.Children.Add(disconnect);
        Grid.SetColumn(actions, 1); heroGrid.Children.Add(actions);
        hero.Child = heroGrid; panel.Children.Add(hero);
        panel.Children.Add(new TextBlock { Text = "Nearby Bluetooth LE devices", FontSize = 18, FontWeight = FontWeights.SemiBold, Margin = new Thickness(4, 20, 4, 8) });
        var deviceList = new ListBox { Name = "DeviceList", MinHeight = 76, MaxHeight = 150, ItemsSource = _bluetooth.Devices, DisplayMemberPath = "Display", Background = InputBrush, Foreground = InkBrush, BorderThickness = new Thickness(0) };
        deviceList.SelectionChanged += DeviceList_SelectionChanged;
        panel.Children.Add(new Border { Child = deviceList, Background = CardBrush, CornerRadius = new CornerRadius(10), Padding = new Thickness(6) });
        var metrics = new UniformGrid { Columns = 4, Margin = new Thickness(-4, 12, -4, 2) };
        foreach (var (key, title) in new[] { ("Voltage", "Pack voltage"), ("Current", "Current"), ("Power", "Power"), ("Estimate", "Remaining time") })
        {
            var c = Card(); var s = new StackPanel(); s.Children.Add(Label(title.ToUpperInvariant(), 11, MutedBrush, true)); s.Children.Add(new TextBlock { Name = key, Text = "—", FontSize = 24, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 7, 0, 0) }); c.Child = s; metrics.Children.Add(c);
        }
        panel.Children.Add(metrics);
        var runtime = Card(); var runtimeStack = new StackPanel(); runtimeStack.Children.Add(Label("Runtime & load", 18, InkBrush, true)); runtimeStack.Children.Add(new TextBlock { Name = "RuntimeDetail", Text = "Backup estimate and connected load appear when readings are available.", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 0) }); runtime.Child = runtimeStack; panel.Children.Add(runtime);
        var cellCard = Card(); var cellStack = new StackPanel(); cellStack.Children.Add(Label("Cell voltage", 18, InkBrush, true)); cellStack.Children.Add(new TextBlock { Name = "CellDetail", Text = "Minimum, average, maximum and individual cell voltages.", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 0) }); cellCard.Child = cellStack; panel.Children.Add(cellCard);
        var protection = Card(); var protectStack = new StackPanel(); protectStack.Children.Add(Label("Protection status", 18, InkBrush, true)); protectStack.Children.Add(new TextBlock { Name = "ProtectionDetail", Text = "Charge MOS · Discharge MOS · Balance · Alarms · Temperature sensors", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 0) }); protection.Child = protectStack; panel.Children.Add(protection);
        var details = Card(); var detailsStack = new StackPanel(); detailsStack.Children.Add(Label("BMS details", 18, InkBrush, true)); detailsStack.Children.Add(new TextBlock { Name = "Details", Text = "Device, connection, protocol, electrical readings, capacity, cells, temperatures, cycle count and firmware.", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 10, 0, 0) }); details.Child = detailsStack; panel.Children.Add(details);
        return root;
    }

    private UIElement BuildHistory()
    {
        var panel = new StackPanel { Margin = new Thickness(28, 22, 28, 28) };
        panel.Children.Add(Heading("Charge & discharge history", "Local readings are sampled once per minute and retained for up to one year."));
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        var export = new Button { Content = "Export CSV" }; export.Click += (_, _) => ExportHistory(); actions.Children.Add(export);
        var clear = new Button { Content = "Clear history" }; clear.Click += (_, _) => { if (MessageBox.Show("Delete all saved battery history from this computer?", "Clear history", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes) { _store.ClearHistory(); ShowPage("History"); } }; actions.Children.Add(clear);
        panel.Children.Add(actions);
        var samples = _store.History.OrderByDescending(x => x.Timestamp).ToList();
        var thisMonth = samples.Where(x => x.Timestamp.Year == DateTime.Now.Year && x.Timestamp.Month == DateTime.Now.Month).ToList();
        var summary = new UniformGrid { Columns = 3, Margin = new Thickness(-4, 8, -4, 4) };
        summary.Children.Add(SummaryCard("SAMPLES", samples.Count.ToString()));
        summary.Children.Add(SummaryCard("LATEST SOC", samples.FirstOrDefault()?.Soc.ToString("0.#%") ?? "—"));
        summary.Children.Add(SummaryCard("LATEST VOLTAGE", samples.FirstOrDefault()?.Voltage.ToString("0.00 V") ?? "—"));
        panel.Children.Add(summary);
        var sessionCard = Card(); var session = new StackPanel(); session.Children.Add(Label("Charge & discharge sessions · this month", 17, InkBrush, true));
        var activity = thisMonth.GroupBy(x => x.Current >= 0 ? "Charging" : "Discharging").ToDictionary(g => g.Key, g => g.ToList());
        foreach (var key in new[] { "Charging", "Discharging" })
        {
            var list = activity.GetValueOrDefault(key) ?? [];
            var ah = list.Sum(x => Math.Abs(x.Current)) / 60.0; var kwh = list.Sum(x => Math.Abs(x.Voltage * x.Current)) / 60000.0;
            session.Children.Add(new TextBlock { Text = $"{key}     {list.Count} readings     {kwh:0.00} kWh     {ah:0.0} Ah", Foreground = MutedBrush, Margin = new Thickness(0, 9, 0, 0) });
        }
        sessionCard.Child = session; panel.Children.Add(sessionCard);
        var table = new DataGrid { Name = "HistoryGrid", IsReadOnly = true, AutoGenerateColumns = true, CanUserAddRows = false, Margin = new Thickness(0, 10, 0, 0), Background = CardBrush, Foreground = InkBrush, ItemsSource = samples.Take(500).ToList(), MinHeight = 210 };
        panel.Children.Add(Label("Recent readings", 17, InkBrush, true)); panel.Children.Add(table); return new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = panel };
    }

    private UIElement BuildSettings()
    {
        var panel = new StackPanel { Margin = new Thickness(28, 22, 28, 28), MaxWidth = 760, HorizontalAlignment = HorizontalAlignment.Left };
        panel.Children.Add(Heading("Settings", "Preferences are saved on this Windows PC. They are not written to the BMS."));
        panel.Children.Add(Label("Battery profile", 13, InkBrush, true));
        var profile = new ComboBox { Name = "ProfilePicker", ItemsSource = _profiles, SelectedItem = _profiles.Contains(_store.Profile) ? _store.Profile : _profiles.FirstOrDefault(x => x.StartsWith("ARENQ")), Margin = new Thickness(4, 6, 4, 18), Padding = new Thickness(8) }; profile.SelectionChanged += (_, _) => { if (profile.SelectedItem is string v) _store.Profile = v; }; panel.Children.Add(profile);
        panel.Children.Add(SectionLabel("Appearance"));
        var themeRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 4, 0, 16) };
        foreach (var mode in new[] { ("Light", false), ("Dark", true) }) { var b = new RadioButton { Content = mode.Item1, IsChecked = _store.DarkMode == mode.Item2, GroupName = "theme", Margin = new Thickness(8, 4, 18, 4), Foreground = InkBrush }; b.Checked += (_, _) => { _store.DarkMode = mode.Item2; ApplyTheme(); }; themeRow.Children.Add(b); }
        panel.Children.Add(themeRow);
        panel.Children.Add(SectionLabel("Windows transparency"));
        var opacity = new Slider { Minimum = 60, Maximum = 100, Value = _store.TransparencyPercent, TickFrequency = 5, IsSnapToTickEnabled = true, Margin = new Thickness(4, 8, 4, 2) };
        var opacityLabel = new TextBlock { Text = $"{_store.TransparencyPercent}% glass opacity", Foreground = MutedBrush, Margin = new Thickness(4, 0, 4, 16) };
        opacity.ValueChanged += (_, _) => { _store.TransparencyPercent = (int)opacity.Value; opacityLabel.Text = $"{_store.TransparencyPercent}% glass opacity"; ApplyTheme(); };
        panel.Children.Add(opacity); panel.Children.Add(opacityLabel);
        panel.Children.Add(Label("Pack capacity (Ah)", 13, InkBrush, true));
        var capacity = new TextBox { Name = "CapacityInput", Text = _store.CapacityAh.ToString("0.##"), Margin = new Thickness(4, 6, 4, 18), Padding = new Thickness(8), Width = 220, HorizontalAlignment = HorizontalAlignment.Left }; capacity.LostFocus += (_, _) => { if (double.TryParse(capacity.Text, out var n) && n > 0) _store.CapacityAh = n; else capacity.Text = _store.CapacityAh.ToString("0.##"); }; panel.Children.Add(capacity);
        panel.Children.Add(Label("Inverter protocol", 13, InkBrush, true));
        var inverter = new ComboBox { Name = "InverterPicker", ItemsSource = _inverters, SelectedItem = _store.Inverter, Margin = new Thickness(4, 6, 4, 18), Padding = new Thickness(8), Width = 280, HorizontalAlignment = HorizontalAlignment.Left }; inverter.SelectionChanged += (_, _) => { if (inverter.SelectedItem is string v) _store.Inverter = v; }; panel.Children.Add(inverter);
        panel.Children.Add(Label("Bluetooth BMS", 16, InkBrush, true));
        var ble = new Button { Content = "Set up Bluetooth BMS" }; ble.Click += async (_, _) => await _bluetooth.ScanAsync(); panel.Children.Add(ble);
        panel.Children.Add(new TextBlock { Text = "Available models and inverter protocols mirror Android. Setup profile values are local only. Bluetooth telemetry currently reads DALY D2 Modbus and DALY A5 when the BMS exposes matching channels.", TextWrapping = TextWrapping.Wrap, Foreground = MutedBrush, Margin = new Thickness(4, 12, 4, 4) });
        return new ScrollViewer { Content = panel };
    }

    private UIElement BuildAbout()
    {
        var panel = new StackPanel { Margin = new Thickness(28, 22, 28, 28), MaxWidth = 760, HorizontalAlignment = HorizontalAlignment.Left };
        panel.Children.Add(Heading("About neX Connect", "Powering a Better Tomorrow"));
        var logos = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(4, 0, 4, 14) };
        foreach (var logo in new[] { "PowerNexLogo.png", "ChanakyaLogo.png" })
        {
            var uri = new Uri($"pack://application:,,,/Assets/{logo}", UriKind.Absolute);
            logos.Children.Add(new Image { Source = new BitmapImage(uri), Height = 76, MaxWidth = 220, Stretch = Stretch.Uniform, Margin = new Thickness(0, 0, 22, 0) });
        }
        panel.Children.Add(logos);
        panel.Children.Add(Card(new StackPanel { Children = { new TextBlock { Text = "neX Connect for Windows", FontSize = 22, FontWeight = FontWeights.SemiBold }, new TextBlock { Text = "Native Windows desktop app for monitoring compatible LiFePO₄ battery systems.", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 0) }, new TextBlock { Text = "Bluetooth BLE uses the Windows Bluetooth radio and supports read-only DALY telemetry. Other BMS profiles are for display and do not imply a compatible polling driver.", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 10, 0, 0) } } }));
        return panel;
    }

    private void DeviceList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (sender is ListBox { SelectedItem: BmsDevice d }) _ = _bluetooth.ConnectAsync(d);
    }

    private void RefreshView()
    {
        HeaderStatus.Text = _bluetooth.Status;
        if (_page != "Monitor" || PageFrame.Content is not ScrollViewer scroll || scroll.Content is not StackPanel stack) return;
        SetText(stack, "SocValue", _bluetooth.Telemetry?.Soc is double soc ? $"{soc:0}%" : "—%");
        SetText(stack, "ModeValue", _bluetooth.Telemetry?.Mode ?? (_bluetooth.Connected ? "Connected · waiting for readings" : "Waiting for BMS data"));
        SetText(stack, "DeviceValue", _bluetooth.ConnectedName.Length > 0 ? _bluetooth.ConnectedName : "No battery connected");
        var bar = Find<ProgressBar>(stack, "SocBar"); if (bar != null) bar.Value = _bluetooth.Telemetry?.Soc ?? 0;
        SetText(stack, "Voltage", _bluetooth.Telemetry?.Voltage is double v ? $"{v:0.00} V" : "—");
        SetText(stack, "Current", _bluetooth.Telemetry?.Current is double a ? $"{a:0.00} A" : "—");
        SetText(stack, "Power", _bluetooth.Telemetry?.PowerKW is double p ? $"{p:0.00} kW" : "—");
        SetText(stack, "Estimate", _bluetooth.RemainingTime(_store.CapacityAh));
        var t = _bluetooth.Telemetry;
        var detail = t is null ? "Cells, temperatures, cycle count and protection state appear after a valid BMS response." :
            $"Protocol: {t.ProtocolName}    ·    Rated capacity: {t.RatedAh?.ToString("0.#") ?? "—"} Ah\nCells: {(t.Cells.Count > 0 ? string.Join(" V, ", t.Cells.Select(x => x.ToString("0.000"))) + " V" : "—")}\nTemperatures: {(t.Temperatures.Count > 0 ? string.Join(" °C, ", t.Temperatures) + " °C" : "—")}    ·    Cycles: {t.Cycles?.ToString() ?? "—"}\nCharge MOS: {OnOff(t.ChargeMos)}    ·    Discharge MOS: {OnOff(t.DischargeMos)}";
        SetText(stack, "Details", detail);
        var cells = t?.Cells ?? [];
        SetText(stack, "CellDetail", cells.Count == 0 ? "No cell voltages reported by this protocol." : $"Min {cells.Min():0.000} V    ·    Avg {cells.Average():0.000} V    ·    Max {cells.Max():0.000} V    ·    Δ {(cells.Max()-cells.Min())*1000:0} mV\n" + string.Join("     ", cells.Select((x, i) => $"C{i + 1}  {x:0.000} V")));
        SetText(stack, "ProtectionDetail", t is null ? "Charge MOS · Discharge MOS · Balance · Alarms · Temperature sensors" : $"Charge MOS  {OnOff(t.ChargeMos)}    ·    Discharge MOS  {OnOff(t.DischargeMos)}    ·    Balance  —\nAlarms  —    ·    {t.Temperatures.Count} temperature sensor(s)    ·    MOS temperature  {t.Temperatures.LastOrDefault().ToString()} °C");
        SetText(stack, "RuntimeDetail", t is null ? "Backup estimate and connected load appear when readings are available." : $"{_bluetooth.RemainingTime(_store.CapacityAh)}    ·    Load power {t.PowerKW?.ToString("0.00 kW") ?? "—"}    ·    Current {t.Current?.ToString("0.0 A") ?? "—"}\nRemaining capacity  {t.RemainingAh?.ToString("0.0 Ah") ?? "—"}    ·    Rated capacity  {t.RatedAh?.ToString("0.0 Ah") ?? $"{_store.CapacityAh:0.#} Ah"}");
    }

    private static string OnOff(bool? value) => value is null ? "—" : value.Value ? "On" : "Off";
    private static void SetText(DependencyObject root, string name, string value) { var c = Find<TextBlock>(root, name); if (c != null) c.Text = value; }
    private static T? Find<T>(DependencyObject root, string name) where T : FrameworkElement
    {
        if (root is T t && t.Name == name) return t;
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++) { var found = Find<T>(VisualTreeHelper.GetChild(root, i), name); if (found != null) return found; }
        return null;
    }
    private static Brush InkBrush => (Brush)Application.Current.Resources["InkBrush"];
    private static Brush MutedBrush => (Brush)Application.Current.Resources["MutedBrush"];
    private static Brush CardBrush => (Brush)Application.Current.Resources["CardBrush"];
    private static Brush InputBrush => (Brush)Application.Current.Resources["InputBrush"];
    private static Border Card(UIElement? child = null) => new() { Background = CardBrush, BorderBrush = (Brush)Application.Current.Resources["BorderBrush"], BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(16), Padding = new Thickness(20), Margin = new Thickness(4, 5, 4, 5), Child = child };
    private static TextBlock Label(string text, double size, Brush color, bool bold = false) => new() { Text = text, FontSize = size, Foreground = color, FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal };
    private static StackPanel Heading(string title, string subtitle) { var s = new StackPanel { Margin = new Thickness(4, 0, 4, 14) }; s.Children.Add(Label(title, 26, InkBrush, true)); s.Children.Add(new TextBlock { Text = subtitle, FontSize = 13, Foreground = MutedBrush, Margin = new Thickness(0, 4, 0, 0) }); return s; }
    private static TextBlock SectionLabel(string text) => Label(text, 13, InkBrush, true);
    private static Border SummaryCard(string title, string value) => Card(new StackPanel { Children = { Label(title, 11, MutedBrush, true), Label(value, 21, InkBrush, true) } });
    private void ApplyTheme()
    {
        var opacity = Math.Clamp(_store.TransparencyPercent / 100.0, .60, 1.0);
        Color Make(byte a, byte r, byte g, byte b) => Color.FromArgb((byte)(a * opacity), r, g, b);
        var dark = _store.DarkMode;
        Application.Current.Resources["PageBrush"] = new SolidColorBrush(dark ? Make(255, 16, 24, 32) : Make(255, 244, 247, 250));
        Application.Current.Resources["CardBrush"] = new SolidColorBrush(dark ? Make(255, 28, 41, 51) : Make(255, 255, 255, 255));
        Application.Current.Resources["InputBrush"] = new SolidColorBrush(dark ? Make(255, 21, 33, 42) : Make(255, 248, 250, 252));
        Application.Current.Resources["InkBrush"] = new SolidColorBrush(dark ? Color.FromRgb(234, 242, 246) : Color.FromRgb(20, 42, 58));
        Application.Current.Resources["MutedBrush"] = new SolidColorBrush(dark ? Color.FromRgb(170, 184, 193) : Color.FromRgb(125, 141, 153));
        Application.Current.Resources["TealBrush"] = new SolidColorBrush(dark ? Color.FromRgb(57, 199, 213) : Color.FromRgb(24, 184, 201));
        Application.Current.Resources["BorderBrush"] = new SolidColorBrush(dark ? Color.FromRgb(52, 69, 80) : Color.FromRgb(220, 229, 232));
        Background = Brushes.Transparent; PageFrame.Background = Brushes.Transparent;
    }
    private void Window_Loaded(object sender, RoutedEventArgs e) { try { int backdrop = 2, dark = _store.DarkMode ? 1 : 0; DwmSetWindowAttribute(new System.Windows.Interop.WindowInteropHelper(this).Handle, 38, ref backdrop, sizeof(int)); DwmSetWindowAttribute(new System.Windows.Interop.WindowInteropHelper(this).Handle, 20, ref dark, sizeof(int)); } catch { } }
    [System.Runtime.InteropServices.DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    private void ExportHistory()
    {
        var dialog = new Microsoft.Win32.SaveFileDialog { Filter = "CSV files (*.csv)|*.csv", FileName = $"nexconnect-history-{DateTime.Now:yyyyMMdd}.csv" };
        if (dialog.ShowDialog() == true) _store.ExportCsv(dialog.FileName);
    }
    private async void Window_Closing(object? sender, CancelEventArgs e) { await _bluetooth.DisposeAsync(); }
}
