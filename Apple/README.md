# neX Connect for Apple platforms

A shared SwiftUI app target for iOS/iPadOS 16+ and macOS 13+. Open `neXConnect.xcodeproj` in Xcode and choose an iPhone/iPad or Mac destination. Select a signing team in Xcode to install on a physical iPhone or distribute the app.

## Ported features

- Shared monitor UI, Power Nex branding, dark appearance, battery profile and capacity choices, and inverter protocol selector.
- CoreBluetooth scan/connect/disconnect, remembered peripheral UUID, optional reconnect, GATT notifications and writes.
- Read-only DALY D2 Modbus BLE polling with A5 fallback and telemetry decoding for voltage, current, SOC, capacity, cells, temperatures, and cycle count.
- Local minute-sampled battery history retained for up to one year, with monthly SOC chart and reading list.
- Local-only setup parameter editor and About page.

Setup and inverter values are stored on the device; they are not sent to the BMS or inverter.

## Hardware/protocol limits

The Apple target ports the same DALY BLE channel assumptions and D2/A5 frame families as the Android app. This does not add Pylontech RS485 support. Pylon RS485 needs a compatible adapter and its serial transport. Generic iOS/macOS BLE discovery cannot talk to an RS485 battery directly. JK, JBD, and other vendor protocol decoders are also not implemented in this Apple target; the profile picker does not imply live protocol support for those models. Confirm exact battery firmware and adapter before relying on telemetry.

## Build notes

The macOS target builds on the current Intel Mac and produces a universal x86_64/arm64 app. The iOS device target builds without signing; Xcode signing is required to install it. This machine has no iOS Simulator runtime, so the iOS simulator build/preview is unavailable until that runtime is installed. On this machine, Xcode 26.5 is installed.
