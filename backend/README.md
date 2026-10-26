# SipSense Backend & ESP32 Simulation Guide

This folder contains the communication infrastructure and simulators for the SipSense IoT ecosystem.

SipSense supports two modes of operation:
1. **Bluetooth Low Energy (BLE) Mode (Recommended for physical ESP32 & portable usage)**: Connects the smart bottle directly to the Android mobile device without needing Wi-Fi or PC servers.
2. **Wi-Fi / MQTT Simulator Mode (Designed for local desktop & Android Emulator testing)**: Uses a Node.js simulator communicating over an MQTT broker and Express REST API.

---

## 🔵 Mode 1: Bluetooth Low Energy (BLE) Mode

The smart bottle operates as a BLE GATT Server advertising as **`SipSense-Bottle`**. The Android mobile app connects directly over Bluetooth radio and syncs data to Firebase.

### Firmware & Simulation File
- Arduino sketch location: [`backend/SipSense_BLE_Simulator.ino`](file:///c:/Users/Margareth%20Lim/Documents/GitHub/SipSense/backend/SipSense_BLE_Simulator.ino) (also in `firmware/SipSense_BLE_Simulator/SipSense_BLE_Simulator.ino`).

### Protocol Specifications
- **Device Name:** `SipSense-Bottle`
- **Primary Service UUID:** `4fafc201-1fb5-459e-8fcc-c5c9c331914b`
- **Telemetry Characteristic UUID:** `beb5483e-36e1-4688-b7f5-ea07361b26a8` (Read + Notify)
- **CCCD Descriptor:** `00002902-0000-1000-8000-00805f9b34fb`

### How to Run BLE Mode:
1. Open [`SipSense_BLE_Simulator.ino`](file:///c:/Users/Margareth%20Lim/Documents/GitHub/SipSense/backend/SipSense_BLE_Simulator.ino) in Arduino IDE.
2. Select your board (`ESP32 Dev Module`) and COM port.
3. Flash the sketch to your ESP32.
4. Launch the SipSense Android app on your phone. Grant Bluetooth permissions when prompted. The app will automatically discover and connect to your bottle!

---

## 🌐 Mode 2: Legacy Wi-Fi / Local REST API Simulator

If you are developing in the Android Studio Emulator on a PC without physical hardware or Bluetooth passthrough:

### Files:
1. **`esp32-simulator.js` (The Smart Bottle)**
   Acts as the physical ESP32 device. It publishes mock telemetry data to HiveMQ (`broker.hivemq.com`).
2. **`server.js` (The Cloud Backend)**
   Subscribes to the MQTT topic and exposes `http://localhost:3000/api/hydration` (mapped to `http://10.0.2.2:3000` in the Android Emulator).

### How to Run:
In two separate terminals:
```bash
# Terminal 1: Start REST API
cd backend
node server.js

# Terminal 2: Start Mock Bottle
cd backend
node esp32-simulator.js
```
