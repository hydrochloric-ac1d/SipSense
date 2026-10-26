/*
 * SipSense_BLE_Simulator.ino
 *
 * ESP32 Bluetooth Low Energy (BLE) Firmware / Simulator for SipSense Smart Bottle.
 *
 * Architecture:
 * - Creates a BLE GATT Server advertising as "SipSense-Bottle"
 * - Service UUID: 4fafc201-1fb5-459e-8fcc-c5c9c331914b
 * - Characteristic UUID: beb5483e-36e1-4688-b7f5-ea07361b26a8 (Read + Notify)
 * - Transmits JSON telemetry payloads directly to the SipSense Android mobile app
 * - Auto-reconnect advertising when disconnected
 * - Can run in SIMULATION mode (built-in mock sips) OR HARDWARE mode (with physical sensors)
 *
 * Hardware Pin Connections (when physical modules are wired):
 * - HX711 Load Cell (Weight / Sips): DT -> GPIO 21, SCK -> GPIO 22
 * - DHT22 / DHT11 (Temp & Humidity): Data -> GPIO 19
 * - Battery Divider (Voltage): Input -> GPIO 34 (ADC1)
 *
 * @project SipSense - Smart Bottle Ecosystem
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ── GATT Service & Characteristic UUIDs (Must match mobile app) ────────
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

BLEServer* pServer = NULL;
BLECharacteristic* pTelemetryCharacteristic = NULL;
bool deviceConnected = false;
bool oldDeviceConnected = false;

// ── Bottle State Variables ─────────────────────────────────────────────
int currentWaterLevel = 650;      // Current water in bottle (ml)
int bottleCapacity = 1000;        // Total capacity (ml)
int totalConsumed = 0;            // Total consumed today (ml)
int batteryLevel = 88;            // Battery percentage (%)
int ambientTemp = 24;             // Ambient temperature (°C)
int humidity = 55;                // Relative humidity (%)
bool isBottleAtRest = true;       // Resting on base (true) vs lifted (false)

unsigned long lastSipTime = 0;
const unsigned long sipInterval = 5000; // Simulated sip every 5 seconds

// ── Server Callbacks (Handle Disconnections & Auto-Re-advertising) ────
class SipSenseServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true;
        Serial.println("📱 [SipSense BLE] Mobile app connected successfully!");
    };

    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false;
        Serial.println("❌ [SipSense BLE] Mobile app disconnected.");
    }
};

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n========================================");
    Serial.println("  💧 SipSense ESP32 BLE Smart Bottle    ");
    Serial.println("========================================");

    // 1. Initialize BLE stack
    BLEDevice::init("SipSense-Bottle");

    // 2. Set MTU size to 512 bytes (for complete JSON string payloads)
    BLEDevice::setMTU(512);

    // 3. Create the BLE Server
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new SipSenseServerCallbacks());

    // 4. Create the SipSense Service
    BLEService *pService = pServer->createService(SERVICE_UUID);

    // 5. Create Telemetry Characteristic with Read and Notify properties
    pTelemetryCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_READ |
        BLECharacteristic::PROPERTY_NOTIFY
    );

    // 6. Add standard CCCD descriptor for client notification subscriptions
    pTelemetryCharacteristic->addDescriptor(new BLE2902());

    // 7. Start the GATT Service
    pService->start();

    // 8. Start Advertising so the mobile app can discover the bottle
    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    pAdvertising->setMinPreferred(0x12);
    BLEDevice::startAdvertising();

    Serial.println("📡 BLE Server is advertising as 'SipSense-Bottle'...");
    Serial.println("   Waiting for mobile app to scan and connect...\n");
}

void loop() {
    unsigned long currentMillis = millis();

    // Auto-restart advertising when mobile disconnects
    if (!deviceConnected && oldDeviceConnected) {
        delay(500);
        pServer->startAdvertising();
        Serial.println("📡 Restarted advertising. Ready for reconnection...");
        oldDeviceConnected = deviceConnected;
    }
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
    }

    // ── Telemetry Generation & Notification ────────────────────────────
    if (currentMillis - lastSipTime >= sipInterval) {
        lastSipTime = currentMillis;

        // 1. SIMULATION LOGIC (Replace this block with sensor reads once wired)
        int sipAmount = random(10, 45); // Random sip between 10ml and 45ml

        if (currentWaterLevel - sipAmount <= 0) {
            currentWaterLevel = bottleCapacity; // Auto refill
            sipAmount = 0;
            Serial.println("🔄 [SIMULATOR] Bottle auto-refilled to 1000ml!");
        } else {
            currentWaterLevel -= sipAmount;
            totalConsumed += sipAmount;
        }

        // Slight natural variations
        ambientTemp = random(23, 26);
        humidity = random(52, 58);
        if (random(0, 10) > 7 && batteryLevel > 15) {
            batteryLevel -= 1;
        }

        // 2. Build JSON telemetry string matching SipSense schema
        char jsonBuffer[256];
        snprintf(jsonBuffer, sizeof(jsonBuffer),
            "{\"waterLevel\":%d,\"bottleCapacity\":%d,\"batteryLevel\":%d,"
            "\"ambientTemperature\":%d,\"temperature\":%d,\"humidity\":%d,"
            "\"sipAmount\":%d,\"totalConsumedToday\":%d,\"isBottleAtRest\":%s}",
            currentWaterLevel,
            bottleCapacity,
            batteryLevel,
            ambientTemp,
            ambientTemp,
            humidity,
            sipAmount,
            totalConsumed,
            isBottleAtRest ? "true" : "false"
        );

        Serial.print("📤 [TELEMETRY] ");
        Serial.println(jsonBuffer);

        // 3. Notify mobile app if connected
        if (deviceConnected) {
            pTelemetryCharacteristic->setValue(jsonBuffer);
            pTelemetryCharacteristic->notify();
            Serial.println("   ✅ Transmitted via BLE notification to mobile app.");
        }
    }
}
