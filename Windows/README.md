# neX Connect for Windows

This is a native WPF desktop target for Windows 10 (build 19041) and Windows 11. It runs directly on the PC and uses the PC's Bluetooth LE adapter.

## Build and run

1. Install the .NET 8 SDK and a Windows 10/11 SDK.
2. Open `neXConnect.Windows/neXConnect.Windows.csproj` in Visual Studio 2022, or run `dotnet run --project neXConnect.Windows/neXConnect.Windows.csproj` from this folder.
3. Turn on Bluetooth in Windows, open **Monitor**, choose **Scan for BMS**, and select the battery.

The Windows API references come from the target framework. To create a standalone x64 executable that does not need a separately installed .NET Desktop Runtime, run this from the repository root:

```powershell
dotnet publish Windows/neXConnect.Windows/neXConnect.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
```

The executable is written under `Windows/neXConnect.Windows/bin/Release/net8.0-windows10.0.19041.0/win-x64/publish/`.

## Features

- Monitor, history, settings and About pages with the neX Connect branding.
- Windows Bluetooth LE scan, GATT service discovery, notification subscription and read-only polling.
- DALY D2 Modbus telemetry with DALY A5 fallback for pack voltage, current, SOC, capacity, cell voltages, temperatures, cycle count and MOS state.
- Local history sampled by minute for up to one year, plus CSV export and local profile/capacity/inverter preferences.

History and preferences are stored under `%LOCALAPPDATA%\neX Connect`. Settings are not sent to the BMS. The profile picker is descriptive: live polling is implemented for DALY D2/A5 BLE channels only. Pylontech RS485 and JK, JBD, ARENQ or other manufacturer-specific Bluetooth protocols require their own verified transport and decoder.

## Hardware notes

Windows BLE behavior depends on the PC adapter and its driver. The battery must expose a compatible BLE service with `FFF1`/`FFF2` or `FF01`/`FF02` receive/transmit channels. Keep the vendor app disconnected while testing if it holds the BMS connection exclusively.
