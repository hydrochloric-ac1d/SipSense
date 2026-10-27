/*
 * SipSense_DHT11_BLE.ino
 *
 * ESP32 + DHT11 -> SipSense Android app (Bluetooth Low Energy).
 *
 * What it does:
 * - Reads ambient temperature (°C) and relative humidity (%) from a DHT11.
 * - Advertises as "SipSense-Bottle" using the SAME GATT service/characteristic
 *   UUIDs the SipSense app scans for (see app/.../data/BleConstants.kt).
 * - Sends a JSON telemetry notification every few seconds while the app is
 *   connected, and keeps the characteristic value updated for GATT reads.
 *
 * JSON payload sent to the app (keys parsed by DashboardFragment.processTelemetryData):
 *   {"ambientTemperature":25,"temperature":25,"humidity":61,
 *    "temperatureC":25.4,"sensor":"DHT11","uptimeMs":123456}
 *
 *   - ambientTemperature / temperature : integer °C (the app uses optInt)
 *   - humidity                         : integer %RH
 *   - temperatureC, sensor, uptimeMs   : extra diagnostic fields (ignored by the app)
 *
 *   Water level, battery, sip and base-rest keys are intentionally NOT sent,
 *   so the app keeps its own values for those until the HX711/battery
 *   hardware is integrated.
 *
 * Wiring (see firmware/PIN_DIAGRAM.md):
 *   DHT11 VCC  -> ESP32 3V3
 *   DHT11 GND  -> ESP32 GND
 *   DHT11 DATA -> ESP32 GPIO 19   (10 kΩ pull-up to 3V3 if using a bare 4-pin sensor)
 *
 * Required libraries (Arduino Library Manager):
 *   - "DHT sensor library" by Adafruit
 *   - "Adafruit Unified Sensor" by Adafruit (dependency)
 *   - BLE library is built into the ESP32 Arduino core (by Espressif)
 *
 * Board: "ESP32 Dev Module"  |  Serial Monitor: 115200 baud
 *
 * @project SipSense - Smart Bottle Ecosystem
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <DHT.h>

// ── Pin & Sensor Configuration ─────────────────────────────────────────
#define DHT_PIN   19
#define DHT_TYPE  DHT11

// ── BLE Identity (MUST match app/src/main/java/com/sipsense/app/data/BleConstants.kt) ──
#define DEVICE_NAME         "SipSense-Bottle"
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// ── Timing ─────────────────────────────────────────────────────────────
// DHT11 can only be sampled about once per second; 2 s keeps it reliable.
const unsigned long SENSOR_READ_INTERVAL_MS = 2000;
// How often telemetry is pushed to the app.
const unsigned long NOTIFY_INTERVAL_MS      = 3000;
// Delay after connecting before the first push, giving the app time to
// negotiate MTU and subscribe (write the CCCD descriptor).
const unsigned long FIRST_NOTIFY_DELAY_MS   = 200;
// After this many consecutive failed reads, print a wiring hint.
const uint8_t       MAX_FAILED_READS_WARN   = 3;

// ── Globals ────────────────────────────────────────────────────────────
DHT dht(DHT_PIN, DHT_TYPE);

BLEServer*         pServer                  = nullptr;
BLECharacteristic* pTelemetryCharacteristic = nullptr;

volatile bool deviceConnected    = false;
bool          oldDeviceConnected = false;

float lastTemperatureC = NAN;
float lastHumidity     = NAN;
bool  hasValidReading  = false;
uint8_t consecutiveFailures = 0;

unsigned long lastSensorReadMs = 0;
unsigned long lastNotifyMs     = 0;
unsigned long connectedAtMs    = 0;
bool          pendingFirstNotify = false;

// ── BLE Server Callbacks ───────────────────────────────────────────────
class SipSenseServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* server) override {
        deviceConnected = true;
        Serial.println("[BLE] SipSense app connected.");
    }

    void onDisconnect(BLEServer* server) override {
        deviceConnected = false;
        Serial.println("[BLE] SipSense app disconnected. Resuming advertising...");
        server->startAdvertising();
    }
};

// Forward declaration
void publishTelemetry();

// ── Sensor ─────────────────────────────────────────────────────────────
void readDhtSensor() {
    float humidity     = dht.readHumidity();
    float temperatureC = dht.readTemperature();   // Celsius

    if (isnan(humidity) || isnan(temperatureC)) {
        consecutiveFailures++;
        hasValidReading  = false;
        lastTemperatureC = NAN;
        lastHumidity     = NAN;
        Serial.printf("[DHT11] ⚠️ Read failed (%u in a row) - Sensor returned NaN!\n", consecutiveFailures);
        if (consecutiveFailures >= MAX_FAILED_READS_WARN) {
            Serial.println("[DHT11] Check wiring: VCC->3V3, GND->GND, DATA->GPIO 19.");
            Serial.println("        Bare 4-pin DHT11 needs a 10k pull-up from DATA to 3V3.");
        }
        publishTelemetry();
        return;
    }

    consecutiveFailures = 0;
    lastTemperatureC = temperatureC;
    lastHumidity     = humidity;
    hasValidReading  = true;

    float tempF = (temperatureC * 9.0f / 5.0f) + 32.0f;
    Serial.printf("[DHT11] 🌡️ Temperature: %.1f C (%.1f F) | 💧 Humidity: %.0f %%\n",
                  temperatureC, tempF, humidity);
    publishTelemetry();
}

// ── Telemetry ──────────────────────────────────────────────────────────
void publishTelemetry() {
    if (!pTelemetryCharacteristic) return;

    char json[220];

    if (!hasValidReading) {
        // Send explicit "Read Failed" signal to SipSense app
        snprintf(json, sizeof(json),
            "{\"status\":\"Read Failed\",\"tempSensorStatus\":\"Read Failed\","
            "\"error\":\"Read Failed\",\"sensor\":\"DHT11\",\"readFailed\":true,"
            "\"failCount\":%u,\"uptimeMs\":%lu}",
            consecutiveFailures, millis());

        pTelemetryCharacteristic->setValue(json);

        if (deviceConnected) {
            pTelemetryCharacteristic->notify();
            Serial.print("[BLE] ❌ Transmitted 'Read Failed' signal -> ");
            Serial.println(json);
        } else {
            Serial.println("[BLE] DHT11 read failed; characteristic set to 'Read Failed'.");
        }
        return;
    }

    const int tempInt     = (int) lroundf(lastTemperatureC);
    const int humidityInt = (int) lroundf(lastHumidity);

    snprintf(json, sizeof(json),
        "{\"status\":\"OK\",\"tempSensorStatus\":\"OK\",\"ambientTemperature\":%d,\"temperature\":%d,\"humidity\":%d,"
        "\"temperatureC\":%.1f,\"sensor\":\"DHT11\",\"uptimeMs\":%lu}",
        tempInt, tempInt, humidityInt, lastTemperatureC, millis());

    // Always update the value so a GATT read returns the latest data.
    pTelemetryCharacteristic->setValue(json);

    if (deviceConnected) {
        pTelemetryCharacteristic->notify();
        Serial.print("[BLE] 📤 Transmitted Temperature Reading -> ");
        Serial.println(json);
    }
}

// ── Setup ──────────────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println();
    Serial.println("========================================");
    Serial.println("  SipSense | ESP32 + DHT11 -> BLE        ");
    Serial.println("========================================");

    // 1. Sensor
    dht.begin();
    Serial.printf("[DHT11] Initialized on GPIO %d\n", DHT_PIN);

    // 2. BLE stack
    BLEDevice::init(DEVICE_NAME);
    BLEDevice::setMTU(512);

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new SipSenseServerCallbacks());

    BLEService* pService = pServer->createService(SERVICE_UUID);

    pTelemetryCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
    );
    // CCCD descriptor - the app writes 0x0001 here to subscribe to notifications.
    pTelemetryCharacteristic->addDescriptor(new BLE2902());
    pTelemetryCharacteristic->setValue("{\"status\":\"Read Failed\",\"tempSensorStatus\":\"Read Failed\",\"readFailed\":true}");

    pService->start();

    // 3. Advertising (app filters by service UUID OR device name)
    BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);

    BLEAdvertisementData advData;
    advData.setName(DEVICE_NAME);
    advData.setCompleteServices(BLEUUID(SERVICE_UUID));
    pAdvertising->setAdvertisementData(advData);

    BLEAdvertisementData scanData;
    scanData.setName(DEVICE_NAME);
    pAdvertising->setScanResponseData(scanData);

    pAdvertising->setMinPreferred(0x06);   // helps iPhone/Android connection params
    pAdvertising->setMinPreferred(0x12);
    BLEDevice::startAdvertising();

    Serial.printf("[BLE] Advertising as '%s'. Open the SipSense app to connect.\n\n",
                  DEVICE_NAME);

    // First sensor read right away (DHT11 needs ~1 s after power-up).
    delay(1000);
    readDhtSensor();
    lastSensorReadMs = millis();
}

// ── Loop ───────────────────────────────────────────────────────────────
void loop() {
    const unsigned long now = millis();

    // Connection state transitions
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = true;
        connectedAtMs      = now;
        pendingFirstNotify = true;
        publishTelemetry();
    }
    if (!deviceConnected && oldDeviceConnected) {
        oldDeviceConnected = false;
        delay(300);                         // let the BLE stack settle
        pServer->startAdvertising();
        Serial.println("[BLE] Advertising restarted. Waiting for reconnection...");
    }

    // Periodic sensor sampling (readDhtSensor calls publishTelemetry internally)
    if (now - lastSensorReadMs >= SENSOR_READ_INTERVAL_MS) {
        lastSensorReadMs = now;
        readDhtSensor();
    }

    // First push shortly after connecting (once the app has subscribed)
    if (pendingFirstNotify && deviceConnected &&
        now - connectedAtMs >= FIRST_NOTIFY_DELAY_MS) {
        pendingFirstNotify = false;
        lastNotifyMs = now;
        publishTelemetry();
    }

    // Regular telemetry pushes
    if (now - lastNotifyMs >= NOTIFY_INTERVAL_MS) {
        lastNotifyMs = now;
        publishTelemetry();
    }

    delay(10);
}
