# SipSense Backend & ESP32 Simulation Guide

This folder contains a fully functional local simulator for the SipSense IoT ecosystem. Since the physical ESP32 (Type-C CP2102) hardware is not yet available, this simulator allows you to develop and test the Android application as if the smart bottle were actually connected and sending live data.

## Architecture

The simulation consists of two parts communicating over a live, public MQTT broker (`broker.hivemq.com`):

1. **`esp32-simulator.js` (The Smart Bottle)**
   Acts as the physical ESP32 device. It continuously generates mock telemetry data (water level, sip amounts, total consumed) and publishes it to an MQTT topic every 5 seconds.
2. **`server.js` (The Cloud Backend)**
   Acts as our backend API. It subscribes to the MQTT broker to receive the live data from the bottle and exposes a standard REST API (`http://localhost:3000`) for the Android app to consume.

## Prerequisites

- **Node.js** must be installed.
- Dependencies (`mqtt`, `express`, `cors`) are already installed in the `backend/node_modules` directory.

---

## How to Run the Simulation

You must run both the backend server and the simulator simultaneously in two separate terminal windows.

### Terminal 1: Start the Backend API

Open a terminal at the root of the `SipSense` project and run:

```bash
cd backend
node server.js
```
*Expected Output: You should see it connect to the MQTT broker and state that the REST API is running on port 3000.*

### Terminal 2: Start the ESP32 Smart Bottle Simulator

Open a **new** terminal window at the root of the `SipSense` project and run:

```bash
cd backend
node esp32-simulator.js
```
*Expected Output: You should see it connect to the broker and start publishing JSON data every 5 seconds indicating simulated "sips" being taken.*

---

## How to Test the Integration

With both terminals running, you can test if the backend is successfully receiving the simulated ESP32 data.

1. Open your web browser.
2. Navigate to: [http://localhost:3000/api/hydration](http://localhost:3000/api/hydration)
3. You will see a JSON response containing the current bottle status. 
4. **Refresh the page** every 5-10 seconds. You should see the `waterLevel` decreasing and the `totalConsumedToday` increasing, proving that the live data pipeline is working!

---

## Next Steps: Connecting the Android App

Once the simulator is running and you can see data in your browser, the next step is to update the SipSense Android app to fetch this data to display in the UI.

To do this, you will need to use an HTTP client in your Android code (like **Retrofit** or **Ktor**) to make a `GET` request to `http://10.0.2.2:3000/api/hydration`. 

*(Note: `10.0.2.2` is the special IP address the Android Emulator uses to access your computer's `localhost`.)*
