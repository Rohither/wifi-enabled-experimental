# neX Connect

Android app for monitoring LiFePO₄ batteries, branded with the supplied Power Nex artwork as its launcher icon.

## Current scope

The app includes an animated Power Nex launch screen with a short chime and the tagline “Powering a Better Tomorrow,” a live monitor with a battery-current-based remaining-time estimate, cell readings, protection state, locally saved charge/discharge history with day/hour/minute energy bars and month/year selectors, BLE discovery, light/dark themes, local configuration screens, an inverter-profile selector, and an About page with Power Nex and Chanakya artwork. Its catalog includes Pylontech, JK/Jikong, JBD, DALY, ARENQ, Luminous, MuscleGrid, and custom profiles.

The BLE layer identifies common DALY-style GATT channels, tries D2 Modbus BLE reads first, and falls back to A5 telemetry requests. It has been confirmed to provide live readings on the reported ARENQ battery by the user. Valid live samples are stored locally in SQLite at one-minute intervals, retained for up to one year, and grouped into charge/discharge sessions. Disconnecting clears the live dashboard while preserving saved history. Remaining time uses reported remaining Ah divided by present discharge current; it does not use grid or inverter power.

The setup screen follows the reference app's protection, cell, acquisition-board, temperature, and system sections. These setup values are saved locally only; BMS parameter writes are not enabled. Inverter protocol selection is separate from BMS Bluetooth telemetry.

## Build

Open this folder in Android Studio and sync with Gradle. The project targets Android 8.0+ (API 26) and uses Jetpack Compose. Android SDK platform 37 is configured.

## Windows desktop version

A native WPF desktop target is available under [`Windows`](Windows/README.md). It uses Windows Bluetooth LE and supports DALY D2/A5 read-only telemetry. Android remains the primary build; Windows-specific setup and build steps are in the Windows guide.

## Protocol research

- [Pylon low-voltage RS485 protocol V3.3 (reference copy)](https://github.com/Frankkkkk/python-pylontech/blob/master/RS485-protocol-pylon-low-voltage-V3.3-20180821.pdf)
- [Pylontech RS485 protocol reference page](https://powerforum.co.za/files/file/96-pylontech-rs485-protocol/)
- [JK/Jikong protocol downloads](https://www.jkbms.net/downloads)
- [JBD protocol and product downloads](https://www.jbd-bms.com/de/pages/download)
- [Daly product manuals](https://www.dalybms.com/daly-product-manual/)
- [DALY A5 BLE telemetry implementation reference](https://github.com/fl4p/batmon-ha/blob/master/bmslib/models/daly.py)
- [DALY Modbus-over-BLE implementation reference](https://github.com/fl4p/batmon-ha/blob/master/bmslib/models/daly2.py)
- [JK official Bluetooth app and protocol resources](https://jkvolt.com/pages/download-center)
- [JBD Bluetooth-enabled BMS product example](https://www.jbd-bms.com/products/sp17s005)
- [Luminous Helios 12.8V 100Ah product page](https://www.luminousindia.com/batteries/helios-128v-100ah)
- [MuscleGrid Thunder Pro 48V 120Ah product page](https://musclegrid.in/store/p/musclegrid-thunder-pro-48v-120ah-lifepo4-battery-6kwh/)
- [JK model manuals and parameter setting guide](https://www.jkbms.net/downloads)

These are reference copies, not a guarantee that a given battery exposes that revision. Confirm the applicable protocol and wiring with the battery manufacturer before implementing live polling.
