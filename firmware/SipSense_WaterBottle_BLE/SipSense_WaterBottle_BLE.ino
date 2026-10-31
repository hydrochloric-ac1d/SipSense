/*
 * SipSense_WaterBottle_BLE.ino
 *
 * ESP32 Smart Water Bottle BLE Firmware with Status LED Indicator
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  BLE BROADCAST & STATUS LED BEHAVIOR:
 * ───────────────────────────────────────────────────────────────────────────
 *  - BROADCASTING / ADVERTISING (DISCONNECTED):
 *      The on-board LED (GPIO 2) BLINKS repeatedly (350ms ON / 350ms OFF)
 *      to indicate that the water bottle is actively broadcasting its BLE signal
 *      and ready for the SipSense mobile app to discover and connect.
 *
 *  - CONNECTED TO SIPSENSE MOBILE APP:
 *      The LED switches to STEADY ON (solid high) as soon as the GATT connection
 *      is established.
 *
 *  - DISCONNECTED:
 *      When the mobile app disconnects, the ESP32 restarts BLE advertising
 *      immediately and the LED switches back to BLINKING.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  HARDWARE WIRING (ESP32 DevKit V1 - USB-C Powered):
 * ───────────────────────────────────────────────────────────────────────────
 *  - GPIO 2  : On-board Blue LED (Status Indicator: Blinking=Advertising, Steady=Connected)
 *  - GPIO 19 : DHT11 DATA (Ambient Temperature & Humidity)
 *  - GPIO 21 : HX711 DT / DOUT (Load Cell Weight Data)
 *  - GPIO 22 : HX711 SCK / PD_SCK (Load Cell Clock)
 *  - 3V3     : Sensor VCC rails (DHT11 + HX711)
 *  - GND     : Sensor GND rails
 *
 *  Note: If DHT11 or HX711 sensors are not yet wired, the firmware detects this
 *  and gracefully provides default telemetry so you can test BLE scanning and
 *  connection on any bare ESP32 immediately!
 *
 * @project SipSense - Smart Bottle Ecosystem
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <DHT.h>
#include <HX711.h>
#include <Preferences.h>

// ── Pin Definitions ────────────────────────────────────────────────────────
#ifndef LED_PIN
#define LED_PIN         2       // On-board LED on ESP32 DevKit (GPIO 2)
#endif

#define DHT_PIN         19      // DHT11 Data pin
#define DHT_TYPE        DHT11

#define HX711_DT_PIN    21      // HX711 Data line
#define HX711_SCK_PIN   22      // HX711 Serial Clock line

// ── BLE UUIDs (Matching BleConstants.kt in SipSense Android App) ───────────
#define DEVICE_NAME         "SipSense-Bottle"
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// ── Flash NVS Storage Keys ────────────────────────────────────────────────
#define PREFS_NAMESPACE     "sipsense"
#define KEY_FACTOR          "hx_factor"
#define KEY_OFFSET          "hx_offset"
#define KEY_CALIBRATED      "hx_calib"

// ── Timing Constants (Milliseconds) ────────────────────────────────────────
const unsigned long LED_BLINK_INTERVAL_MS    = 350;  // Blinking speed while advertising
const unsigned long NOTIFY_INTERVAL_MS       = 2000; // Periodic BLE telemetry push (2s)
const unsigned long DHT_SAMPLE_INTERVAL_MS   = 2000; // DHT11 read interval
const unsigned long SCALE_SAMPLE_INTERVAL_MS = 300;  // HX711 sample rate
const unsigned long FIRST_NOTIFY_DELAY_MS    = 300;  // Delay after connection for CCCD

// ── Global Objects ─────────────────────────────────────────────────────────
DHT         dht(DHT_PIN, DHT_TYPE);
HX711       scale;
Preferences prefs;

BLEServer*         pServer                  = nullptr;
BLECharacteristic* pTelemetryCharacteristic = nullptr;

// ── Connection & LED State ─────────────────────────────────────────────────
volatile bool deviceConnected     = false;
bool          oldDeviceConnected  = false;
unsigned long connectedAtMs       = 0;
bool          pendingFirstNotify  = false;

unsigned long lastLedBlinkMs      = 0;
bool          ledBlinkState       = false;

// ── Sensor & Telemetry State ───────────────────────────────────────────────
float         currentTempC        = 24.0f;
float         currentHumidity     = 55.0f;
bool          isDhtHardwareOk     = false;

float         calibrationFactor   = 1.0f;
long          zeroOffset          = 0;
bool          isCalibrated        = false;
bool          isHx711HardwareOk   = false;
float         lastWeightGrams     = 750.0f;
float         weightBeforeLift    = 750.0f;

int           currentWaterLevel   = 750;   // ml (1 gram water = 1 ml)
int           bottleCapacity      = 1000;  // ml
bool          isBottleAtRest      = true;  // Resting on base vs lifted
int           lastSipAmount       = 45;    // ml
int           totalConsumedToday  = 450;   // ml
int           batteryLevel        = 95;    // %

// Timers
unsigned long lastDhtReadMs       = 0;
unsigned long lastScaleReadMs     = 0;
unsigned long lastTelemetryPushMs = 0;

// Forward Declarations
void publishTelemetry(bool forceImmediate = false);
void updateLedIndicator();
void handleSerialCommands();
void tareScale();
bool loadCalibration();

// ── BLE Server Callbacks ───────────────────────────────────────────────────
class BottleServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* server) override {
        deviceConnected = true;
        digitalWrite(LED_PIN, HIGH); // Immediate steady LED
        Serial.println("\n╔══════════════════════════════════════════════════════════╗");
        Serial.println("║  📱 [BLE] Mobile App CONNECTED!                          ║");
        Serial.println("║  💡 [LED] Status changed to: STEADY ON                   ║");
        Serial.println("╚══════════════════════════════════════════════════════════╝");
    }

    void onDisconnect(BLEServer* server) override {
        deviceConnected = false;
        Serial.println("\n╔══════════════════════════════════════════════════════════╗");
        Serial.println("║  ❌ [BLE] Mobile App DISCONNECTED!                       ║");
        Serial.println("║  💡 [LED] Status changed to: BLINKING (Broadcasting)     ║");
        Serial.println("╚══════════════════════════════════════════════════════════╝");
        // Restart advertising so bottle is discoverable again
        server->startAdvertising();
    }
};

// ── LED Status Controller ──────────────────────────────────────────────────
void updateLedIndicator() {
    const unsigned long now = millis();

    if (deviceConnected) {
        // STATE 1: Connected -> Steady ON
        digitalWrite(LED_PIN, HIGH);
    } else {
        // STATE 2: Broadcasting / Advertising -> Blinking
        if (now - lastLedBlinkMs >= LED_BLINK_INTERVAL_MS) {
            lastLedBlinkMs = now;
            ledBlinkState = !ledBlinkState;
            digitalWrite(LED_PIN, ledBlinkState ? HIGH : LOW);
        }
    }
}

// ── Flash Calibration Storage ──────────────────────────────────────────────
bool loadCalibration() {
    prefs.begin(PREFS_NAMESPACE, true);
    bool saved   = prefs.getBool(KEY_CALIBRATED, false);
    float factor = prefs.getFloat(KEY_FACTOR, 1.0f);
    long offset  = prefs.getLong(KEY_OFFSET, 0);
    prefs.end();

    if (!saved || factor == 0.0f) {
        isCalibrated = false;
        return false;
    }

    calibrationFactor = factor;
    zeroOffset = offset;
    scale.set_scale(factor);
    scale.set_offset(offset);
    isCalibrated = true;
    Serial.printf("[FLASH] Loaded calibration -> Factor: %.4f, Offset: %ld\n", factor, offset);
    return true;
}

void tareScale() {
    if (isHx711HardwareOk && scale.wait_ready_timeout(500)) {
        Serial.println("[TARE] Zeroing scale platform...");
        scale.tare(10);
        zeroOffset = scale.get_offset();
        Serial.printf("[TARE] Complete. Offset: %ld\n", zeroOffset);
    }
}

// ── Sensor Reading Routines ────────────────────────────────────────────────
void readSensors() {
    const unsigned long now = millis();

    // 1. Read DHT11 Temperature & Humidity
    if (now - lastDhtReadMs >= DHT_SAMPLE_INTERVAL_MS) {
        lastDhtReadMs = now;
        float h = dht.readHumidity();
        float t = dht.readTemperature();

        if (!isnan(h) && !isnan(t)) {
            currentTempC = t;
            currentHumidity = h;
            isDhtHardwareOk = true;
        } else {
            // If physical sensor not connected, keep safe defaults
            isDhtHardwareOk = false;
        }
    }

    // 2. Read HX711 Load Cell
    if (now - lastScaleReadMs >= SCALE_SAMPLE_INTERVAL_MS) {
        lastScaleReadMs = now;
        if (scale.wait_ready_timeout(80)) {
            isHx711HardwareOk = true;
            if (isCalibrated) {
                float weight = scale.get_units(2);
                if (weight < -15.0f) {
                    // Bottle lifted
                    if (isBottleAtRest) {
                        isBottleAtRest = false;
                        weightBeforeLift = (lastWeightGrams > 0.0f) ? lastWeightGrams : 0.0f;
                        Serial.printf("[HX711] Bottle lifted! Weight before: %.1f g\n", weightBeforeLift);
                        publishTelemetry(true);
                    }
                } else {
                    // Bottle on base
                    float safeWeight = (weight < 0.0f) ? 0.0f : weight;
                    lastWeightGrams = safeWeight;
                    currentWaterLevel = constrain((int) lroundf(safeWeight), 0, bottleCapacity);

                    if (!isBottleAtRest) {
                        isBottleAtRest = true;
                        float diff = weightBeforeLift - safeWeight;
                        if (diff >= 5.0f) {
                            int sip = (int) lroundf(diff);
                            lastSipAmount = sip;
                            totalConsumedToday += sip;
                            Serial.printf("🥤 [SIP MEASURED] Drank %d ml! Today's total: %d ml\n", sip, totalConsumedToday);
                        }
                        publishTelemetry(true);
                    }
                }
            }
        } else {
            isHx711HardwareOk = false;
        }
    }
}

// ── Publish JSON Telemetry over BLE ─────────────────────────────────────────
void publishTelemetry(bool forceImmediate) {
    if (!deviceConnected && !forceImmediate) return;
    if (pTelemetryCharacteristic == nullptr) return;

    char buffer[256];
    snprintf(buffer, sizeof(buffer),
        "{\"waterLevel\":%d,\"bottleCapacity\":%d,\"batteryLevel\":%d,"
        "\"ambientTemperature\":%.1f,\"temperature\":%.1f,\"humidity\":%.0f,"
        "\"isBottleAtRest\":%s,\"lastSipAmount\":%d,\"totalConsumedToday\":%d,"
        "\"deviceStatus\":\"%s\",\"ledStatus\":\"%s\"}",
        currentWaterLevel,
        bottleCapacity,
        batteryLevel,
        currentTempC,
        currentTempC,
        currentHumidity,
        isBottleAtRest ? "true" : "false",
        lastSipAmount,
        totalConsumedToday,
        deviceConnected ? "Connected" : "Broadcasting",
        deviceConnected ? "Steady" : "Blinking"
    );

    pTelemetryCharacteristic->setValue(buffer);
    if (deviceConnected) {
        pTelemetryCharacteristic->notify();
        Serial.printf("[BLE TX] Telemetry dispatched: %s\n", buffer);
    }
}

// ── Interactive Serial Command Interface ───────────────────────────────────
void handleSerialCommands() {
    if (Serial.available()) {
        char cmd = (char) Serial.read();
        while (Serial.available()) Serial.read(); // Clear rest of line

        switch (cmd) {
            case 't':
            case 'T':
                tareScale();
                break;
            case 's':
            case 'S':
                // Simulate sip for quick testing
                if (currentWaterLevel > 50) {
                    currentWaterLevel -= 50;
                    lastSipAmount = 50;
                    totalConsumedToday += 50;
                    Serial.printf("🥤 [SIMULATED SIP] 50 ml consumed. Water level: %d ml\n", currentWaterLevel);
                    publishTelemetry(true);
                }
                break;
            case 'i':
            case 'I':
                Serial.println("\n══════════ SIPSENSE WATER BOTTLE STATUS ══════════");
                Serial.printf("  BLE State    : %s\n", deviceConnected ? "CONNECTED" : "BROADCASTING");
                Serial.printf("  Status LED   : %s (GPIO %d)\n", deviceConnected ? "STEADY ON" : "BLINKING", LED_PIN);
                Serial.printf("  Water Level  : %d ml / %d ml\n", currentWaterLevel, bottleCapacity);
                Serial.printf("  Temperature  : %.1f °C\n", currentTempC);
                Serial.printf("  Humidity     : %.0f %%\n", currentHumidity);
                Serial.printf("  Bottle Base  : %s\n", isBottleAtRest ? "AT REST" : "LIFTED");
                Serial.printf("  Battery      : %d %%\n", batteryLevel);
                Serial.println("═════════════════════════════════════════════════\n");
                break;
            case 'h':
            case 'H':
                Serial.println("\nCommands: 't'=Tare scale, 's'=Simulate 50ml sip, 'i'=Print info, 'h'=Help");
                break;
            default:
                break;
        }
    }
}

// ── Setup ──────────────────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    delay(500);

    Serial.println("\n╔══════════════════════════════════════════════════════════╗");
    Serial.println("║   💧 SipSense Smart Water Bottle - BLE Firmware          ║");
    Serial.println("║   Status LED: Blinking = Advertising | Steady = Connected║");
    Serial.println("╚══════════════════════════════════════════════════════════╝");

    // 1. Initialize On-board Status LED (GPIO 2)
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, LOW);
    Serial.printf("[INIT] Status LED configured on GPIO %d\n", LED_PIN);

    // 2. Initialize DHT11 Sensor
    dht.begin();
    Serial.printf("[INIT] DHT11 initialized on GPIO %d\n", DHT_PIN);

    // 3. Initialize HX711 Load Cell
    scale.begin(HX711_DT_PIN, HX711_SCK_PIN);
    Serial.printf("[INIT] HX711 initialized (DT=GPIO %d, SCK=GPIO %d)\n", HX711_DT_PIN, HX711_SCK_PIN);
    if (!loadCalibration()) {
        tareScale();
    }

    // 4. Initialize BLE Device & Server
    BLEDevice::init(DEVICE_NAME);
    BLEDevice::setMTU(512);

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new BottleServerCallbacks());

    // 5. Create GATT Service & Characteristic
    BLEService* pService = pServer->createService(SERVICE_UUID);

    pTelemetryCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
    );
    pTelemetryCharacteristic->addDescriptor(new BLE2902());
    pTelemetryCharacteristic->setValue("{\"status\":\"Standby\",\"device\":\"SipSense-Bottle\"}");

    pService->start();

    // 6. Configure and Start BLE Advertising
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

    pAdvertising->setMinPreferred(0x06);
    pAdvertising->setMinPreferred(0x12);
    BLEDevice::startAdvertising();

    Serial.printf("\n📡 [BLE] Broadcasting signal as '%s'...\n", DEVICE_NAME);
    Serial.println("💡 [LED] On-board LED is now BLINKING while broadcasting.");
    Serial.println("         Use the SipSense Waterbottle tab to scan and connect!\n");
    Serial.println("Type 'h' in Serial Monitor for interactive commands.\n");

    lastDhtReadMs       = millis();
    lastScaleReadMs     = millis();
    lastTelemetryPushMs = millis();
}

// ── Loop ───────────────────────────────────────────────────────────────────
void loop() {
    const unsigned long now = millis();

    // 1. Maintain LED Status (Blinking when advertising, Steady when connected)
    updateLedIndicator();

    // 2. Connection Lifecycle Handling
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = true;
        connectedAtMs      = now;
        pendingFirstNotify = true;
        publishTelemetry(true);
    }
    if (!deviceConnected && oldDeviceConnected) {
        oldDeviceConnected = false;
        delay(300);
        pServer->startAdvertising();
        Serial.println("[BLE] Resumed advertising. LED blinking...");
    }

    // 3. Periodic Sensor Readings
    readSensors();

    // 4. Push initial notification after connection
    if (pendingFirstNotify && deviceConnected && now - connectedAtMs >= FIRST_NOTIFY_DELAY_MS) {
        pendingFirstNotify = false;
        lastTelemetryPushMs = now;
        publishTelemetry(true);
    }

    // 5. Periodic Telemetry Push (every 2s)
    if (deviceConnected && (now - lastTelemetryPushMs >= NOTIFY_INTERVAL_MS)) {
        lastTelemetryPushMs = now;
        publishTelemetry(false);
    }

    // 6. Handle Serial commands
    handleSerialCommands();

    delay(10);
}

