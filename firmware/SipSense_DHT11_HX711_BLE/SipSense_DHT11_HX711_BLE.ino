/*
 * SipSense_DHT11_HX711_BLE.ino
 *
 * ESP32 + DHT11 (Ambient Temp & Humidity) + HX711 (Weight / Load Cell)
 * Complete Hardware Firmware for the SipSense Smart Bottle Ecosystem.
 *
 * What it does:
 * 1. Reads ambient temperature (°C) and relative humidity (%) from a DHT11 on GPIO 19.
 * 2. Reads live weight (grams), raw counts, and resting status from an HX711 on GPIO 21 (DT) & 22 (SCK).
 * 3. Restores saved calibration factor and zero offset from ESP32 flash NVS (Preferences "sipsense").
 * 4. Advertises as "SipSense-Bottle" via Bluetooth Low Energy (BLE) using standard SipSense GATT UUIDs.
 * 5. Sends live combined JSON telemetry payloads to the SipSense Android app:
 *    - Updates the Dashboard & Devices water in bottle card in real-time.
 *    - Updates the ambient room temperature card in real-time.
 *    - Pushes live scale & temperature readings into the Developer Mode Signals Terminal.
 * 6. Provides an interactive Serial Monitor menu (115200 baud) for live tare and calibration.
 *
 * Wiring (see firmware/PIN_DIAGRAM.md):
 * ───────────────────────────────────────────────────────────────────────────
 *   DHT11:
 *     VCC  -> ESP32 3V3
 *     GND  -> ESP32 GND
 *     DATA -> ESP32 GPIO 19  (bare sensor needs 10k pull-up to 3V3)
 *
 *   HX711:
 *     VCC/VDD -> ESP32 3V3   (power from 3V3 so DT logic is 3.3 V safe!)
 *     GND     -> ESP32 GND
 *     DT      -> ESP32 GPIO 21
 *     SCK     -> ESP32 GPIO 22
 *
 *   Load Cell (4-wire):
 *     Red   -> E+ (Excitation +)
 *     Black -> E- (Excitation -)
 *     White -> A- (Signal -)
 *     Green -> A+ (Signal +)
 *
 * Serial Commands (115200 baud, line ending "Newline"):
 *   t        -> Tare / zero the scale (platform must be empty)
 *   c        -> Guided 3-step calibration with a known weight
 *   s        -> Save calibration factor & offset to flash NVS
 *   l        -> Reload calibration from flash NVS
 *   x        -> Erase saved calibration from flash NVS
 *   i        -> Show current calibration & sensor telemetry status
 *   h        -> Print help menu
 *
 * Required Libraries (Arduino Library Manager):
 *   - "DHT sensor library" by Adafruit
 *   - "Adafruit Unified Sensor" by Adafruit (dependency)
 *   - "HX711 Arduino Library" by Bogdan Necula (bogde)
 *   - Preferences and BLE are built into the ESP32 Arduino Core
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
#include <HX711.h>
#include <Preferences.h>

// ── Pin Configuration ──────────────────────────────────────────────────
#define DHT_PIN         19
#define DHT_TYPE        DHT11

#define HX711_DT_PIN    21
#define HX711_SCK_PIN   22

// ── Flash Storage Keys (Shared with SipSense ecosystem) ────────────────
#define PREFS_NAMESPACE "sipsense"
#define KEY_FACTOR      "hx_factor"
#define KEY_OFFSET      "hx_offset"
#define KEY_CALIBRATED  "hx_calib"

// ── BLE Identity (Matches app/src/main/java/com/sipsense/app/data/BleConstants.kt) ──
#define DEVICE_NAME         "SipSense-Bottle"
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// ── Timing Parameters ──────────────────────────────────────────────────
const unsigned long DHT_READ_INTERVAL_MS   = 2000;  // DHT11 sampled every 2s
const unsigned long HX711_READ_INTERVAL_MS = 500;   // Scale sampled every 500ms
const unsigned long NOTIFY_INTERVAL_MS     = 2000;  // Pushes BLE telemetry every 2s
const unsigned long FIRST_NOTIFY_DELAY_MS  = 200;   // Wait for CCCD negotiation
const unsigned long HX711_TIMEOUT_MS       = 100;   // Non-blocking timeout
const uint8_t       TARE_SAMPLES           = 20;
const uint8_t       CALIBRATION_SAMPLES    = 20;
const uint8_t       STREAM_SAMPLES         = 3;     // 3 samples for low latency
const long          MIN_CALIBRATION_DELTA  = 1000;  // Minimum delta counts for weight

// ── Globals ────────────────────────────────────────────────────────────
DHT         dht(DHT_PIN, DHT_TYPE);
HX711       scale;
Preferences prefs;

BLEServer*         pServer                  = nullptr;
BLECharacteristic* pTelemetryCharacteristic = nullptr;

volatile bool deviceConnected    = false;
bool          oldDeviceConnected = false;
unsigned long connectedAtMs      = 0;
bool          pendingFirstNotify = false;

// DHT11 State
float   lastTemperatureC    = NAN;
float   lastHumidity        = NAN;
bool    isDhtValid          = false;
uint8_t dhtFailureCount     = 0;

// HX711 State
float   calibrationFactor   = 1.0f;
long    zeroOffset          = 0;
bool    isCalibrated        = false;
float   lastWeightGrams     = 0.0f;
long    lastRawCounts       = 0;
bool    isHx711Valid        = false;
bool    isBottleAtRest      = true;
int     currentWaterLevel   = 0;
int     bottleCapacity      = 1000; // Standard 1000 ml SipSense bottle
int     batteryLevel        = 88;   // 88% nominal (until hardware ADC divider wired)
uint8_t hx711FailureCount   = 0;

// Timers
unsigned long lastDhtReadMs    = 0;
unsigned long lastHx711ReadMs  = 0;
unsigned long lastNotifyMs     = 0;

// Serial Buffer for interactive calibration
String        serialBuffer;
unsigned long lastCharMs       = 0;

// ── BLE Server Callbacks ───────────────────────────────────────────────
class SipSenseServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* server) override {
        deviceConnected = true;
        Serial.println("\n📱 [BLE] SipSense mobile app connected successfully!");
    }

    void onDisconnect(BLEServer* server) override {
        deviceConnected = false;
        Serial.println("\n❌ [BLE] SipSense mobile app disconnected. Resuming advertising...");
        server->startAdvertising();
    }
};

// Forward declarations
void publishTelemetry();
void printHelp();

// ── Flash Calibration Storage ──────────────────────────────────────────
void applyCalibration(float factor, long offset) {
    calibrationFactor = factor;
    zeroOffset = offset;
    scale.set_scale(factor);
    scale.set_offset(offset);
}

bool loadCalibration() {
    prefs.begin(PREFS_NAMESPACE, true);
    bool saved   = prefs.getBool(KEY_CALIBRATED, false);
    float factor = prefs.getFloat(KEY_FACTOR, 1.0f);
    long offset  = prefs.getLong(KEY_OFFSET, 0);
    prefs.end();

    if (!saved || factor == 0.0f) {
        Serial.println("[FLASH] No saved HX711 calibration found. Running with factor 1.0 (uncalibrated).");
        isCalibrated = false;
        return false;
    }

    applyCalibration(factor, offset);
    isCalibrated = true;
    Serial.printf("[FLASH] Loaded saved calibration -> Factor: %.4f | Offset: %ld\n", factor, offset);
    return true;
}

void saveCalibration() {
    prefs.begin(PREFS_NAMESPACE, false);
    prefs.putFloat(KEY_FACTOR, calibrationFactor);
    prefs.putLong(KEY_OFFSET, (int32_t) scale.get_offset());
    prefs.putBool(KEY_CALIBRATED, isCalibrated);
    prefs.end();
    Serial.printf("[FLASH] Calibration saved -> Factor: %.4f | Offset: %ld\n",
                  calibrationFactor, scale.get_offset());
}

void eraseCalibration() {
    prefs.begin(PREFS_NAMESPACE, false);
    prefs.remove(KEY_FACTOR);
    prefs.remove(KEY_OFFSET);
    prefs.remove(KEY_CALIBRATED);
    prefs.end();
    isCalibrated = false;
    calibrationFactor = 1.0f;
    scale.set_scale(1.0f);
    Serial.println("[FLASH] Calibration erased. Factor reset to 1.0.");
}

void tareScale() {
    if (!scale.wait_ready_timeout(1000)) {
        Serial.println("[TARE] ⚠️ HX711 not responding! Check DT/SCK wiring.");
        return;
    }
    Serial.println("[TARE] Zeroing scale... keep platform empty.");
    scale.tare(TARE_SAMPLES);
    zeroOffset = scale.get_offset();
    Serial.printf("[TARE] Scale zeroed. Offset = %ld\n", zeroOffset);
}

// ── Sensor Reading: DHT11 ──────────────────────────────────────────────
void readDhtSensor() {
    float h = dht.readHumidity();
    float t = dht.readTemperature();

    if (isnan(h) || isnan(t)) {
        dhtFailureCount++;
        isDhtValid       = false;
        lastTemperatureC = NAN;
        lastHumidity     = NAN;
        Serial.printf("[DHT11] ⚠️ Read failed (%u consecutive)\n", dhtFailureCount);
        if (dhtFailureCount >= 3) {
            Serial.println("        Check DHT11 wiring: VCC->3V3, GND->GND, DATA->GPIO 19");
        }
        return;
    }

    dhtFailureCount  = 0;
    isDhtValid       = true;
    lastTemperatureC = t;
    lastHumidity     = h;
}

// ── Sensor Reading: HX711 ──────────────────────────────────────────────
void readHx711Sensor() {
    if (!scale.wait_ready_timeout(HX711_TIMEOUT_MS)) {
        hx711FailureCount++;
        if (hx711FailureCount >= 5) {
            isHx711Valid = false;
        }
        return;
    }

    hx711FailureCount = 0;
    isHx711Valid      = true;

    // Get raw counts from the ADC
    lastRawCounts = scale.get_value(1);

    if (isCalibrated) {
        // Read weight in grams using calibrated factor
        float weight = scale.get_units(STREAM_SAMPLES);

        // If weight is significantly negative, bottle is lifted off base
        if (weight < -15.0f) {
            isBottleAtRest = false;
            // When lifted, hold last valid water level
        } else {
            isBottleAtRest  = true;
            lastWeightGrams = (weight < 0.0f) ? 0.0f : weight;
            // 1 gram of water ≈ 1 ml
            currentWaterLevel = constrain((int) lroundf(lastWeightGrams), 0, bottleCapacity);
        }
    } else {
        // Uncalibrated fallback
        lastWeightGrams = (float) lastRawCounts;
        isBottleAtRest  = true;
        currentWaterLevel = 0;
    }
}

// ── Publish Combined Telemetry over BLE ────────────────────────────────
void publishTelemetry() {
    if (!pTelemetryCharacteristic) return;

    char json[380];

    int tempInt = isDhtValid ? (int) lroundf(lastTemperatureC) : 0;
    int humInt  = isDhtValid ? (int) lroundf(lastHumidity) : 0;

    // Format complete JSON telemetry packet
    if (!isDhtValid && !isHx711Valid) {
        snprintf(json, sizeof(json),
            "{\"status\":\"Read Failed\",\"tempSensorStatus\":\"Read Failed\",\"hx711Status\":\"Read Failed\","
            "\"readFailed\":true,\"hx711ReadFailed\":true,\"sensor\":\"DHT11+HX711\",\"uptimeMs\":%lu}",
            millis());
    } else {
        snprintf(json, sizeof(json),
            "{\"status\":\"OK\","
            "\"tempSensorStatus\":\"%s\","
            "\"ambientTemperature\":%d,\"temperature\":%d,\"humidity\":%d,\"temperatureC\":%.1f,"
            "\"hx711Status\":\"%s\","
            "\"waterLevel\":%d,\"weightGrams\":%.1f,\"rawCounts\":%ld,"
            "\"isBottleAtRest\":%s,\"isCalibrated\":%s,"
            "\"bottleCapacity\":%d,\"batteryLevel\":%d,"
            "\"sensor\":\"DHT11+HX711\",\"uptimeMs\":%lu}",
            isDhtValid ? "OK" : "Read Failed",
            tempInt, tempInt, humInt, isDhtValid ? lastTemperatureC : 0.0f,
            isHx711Valid ? "OK" : "Read Failed",
            currentWaterLevel, lastWeightGrams, lastRawCounts,
            isBottleAtRest ? "true" : "false",
            isCalibrated ? "true" : "false",
            bottleCapacity, batteryLevel,
            millis());
    }

    // Update characteristic value so GATT read returns latest data
    pTelemetryCharacteristic->setValue(json);

    // Notify connected app
    if (deviceConnected) {
        pTelemetryCharacteristic->notify();
        Serial.printf("[BLE -> APP] 📤 Telemetry: 🌡️ %s | ⚖️ %s\n",
            isDhtValid ? (String(lastTemperatureC, 1) + " C (" + String(humInt) + "%)").c_str() : "TEMP FAILED",
            isHx711Valid ? (String(lastWeightGrams, 1) + " g (" + String(currentWaterLevel) + " ml)" + (isBottleAtRest ? " [Rest]" : " [Lifted]")).c_str() : "HX711 NOT READY");
    }
}

// ── Serial Input & Guided Calibration ──────────────────────────────────
bool pollSerialLine(String& out) {
    while (Serial.available()) {
        char c = (char) Serial.read();
        lastCharMs = millis();
        if (c == '\r' || c == '\n') {
            serialBuffer.trim();
            if (serialBuffer.length() > 0) {
                out = serialBuffer;
                serialBuffer = "";
                return true;
            }
        } else {
            serialBuffer += c;
        }
    }
    if (serialBuffer.length() > 0 && millis() - lastCharMs > 150) {
        serialBuffer.trim();
        out = serialBuffer;
        serialBuffer = "";
        return out.length() > 0;
    }
    return false;
}

String readLineBlocking() {
    String line;
    while (!pollSerialLine(line)) {
        delay(10);
    }
    return line;
}

void runGuidedCalibration() {
    Serial.println("\n============ GUIDED HX711 CALIBRATION ============");
    Serial.println("Type  q  at any prompt to cancel.");

    // Step 1: Tare
    Serial.println("\nSTEP 1/3: Remove EVERYTHING from the bottle platform.");
    Serial.println("          Type  y  and press Enter when it is empty.");
    String ans = readLineBlocking();
    if (ans.equalsIgnoreCase("q")) { Serial.println("Calibration cancelled."); return; }

    scale.set_scale(1.0f);
    tareScale();

    // Step 2: Known reference weight
    Serial.println("\nSTEP 2/3: Place a KNOWN reference weight on the platform (e.g., 500g water bottle).");
    Serial.println("          Enter the weight in GRAMS (e.g. 500) and press Enter:");
    float knownGrams = 0.0f;
    while (true) {
        ans = readLineBlocking();
        if (ans.equalsIgnoreCase("q")) {
            Serial.println("Calibration cancelled. Restoring previous factor.");
            scale.set_scale(calibrationFactor);
            return;
        }
        knownGrams = ans.toFloat();
        if (knownGrams > 0.0f) break;
        Serial.println("          Please enter a valid positive number in grams (e.g. 500):");
    }

    Serial.printf("          Measuring %.1f g... keep still.\n", knownGrams);
    delay(1500);

    if (!scale.wait_ready_timeout(1000)) {
        Serial.println("[ERROR] HX711 not responding during measurement.");
        scale.set_scale(calibrationFactor);
        return;
    }

    double rawDelta = scale.get_value(CALIBRATION_SAMPLES);
    if (fabs(rawDelta) < MIN_CALIBRATION_DELTA) {
        Serial.printf("[ERROR] Raw counts change too small (%.0f counts). Check wiring/flex direction.\n", rawDelta);
        scale.set_scale(calibrationFactor);
        return;
    }

    // Step 3: Compute factor
    calibrationFactor = (float) (rawDelta / knownGrams);
    isCalibrated = true;
    scale.set_scale(calibrationFactor);

    Serial.println("\nSTEP 3/3: Calibration computed successfully!");
    Serial.printf("          Raw Delta : %.0f counts\n", rawDelta);
    Serial.printf("          Factor    : %.4f counts/gram\n", calibrationFactor);
    Serial.printf("          Offset    : %ld\n", scale.get_offset());

    saveCalibration();
    Serial.println("          Saved to flash memory! Scale is now active & streaming to BLE.\n");
}

void printHelp() {
    Serial.println("\n── SipSense Serial Commands ──");
    Serial.println("  t       -> Tare / zero the scale");
    Serial.println("  c       -> Run 3-step guided calibration");
    Serial.println("  s       -> Save current calibration to flash");
    Serial.println("  l       -> Reload calibration from flash");
    Serial.println("  x       -> Erase saved calibration from flash");
    Serial.println("  i       -> Print current sensor readings & info");
    Serial.println("  h       -> Show this help menu\n");
}

void printInfo() {
    Serial.println("\n═══════════ SIPSENSE SENSOR STATUS ═══════════");
    Serial.printf("  DHT11 Temperature : %.1f C (%.1f F)\n", lastTemperatureC, (lastTemperatureC * 1.8f) + 32.0f);
    Serial.printf("  DHT11 Humidity    : %.0f %%\n", lastHumidity);
    Serial.printf("  DHT11 Status      : %s\n", isDhtValid ? "OK" : "READ FAILED");
    Serial.println("  ──────────────────────────────────────────");
    Serial.printf("  HX711 Weight      : %.1f g\n", lastWeightGrams);
    Serial.printf("  Water Volume      : %d ml / %d ml\n", currentWaterLevel, bottleCapacity);
    Serial.printf("  HX711 Raw Counts  : %ld\n", lastRawCounts);
    Serial.printf("  HX711 Calibrated  : %s (Factor: %.4f, Offset: %ld)\n",
                  isCalibrated ? "YES" : "NO", calibrationFactor, scale.get_offset());
    Serial.printf("  Bottle State      : %s\n", isBottleAtRest ? "AT REST ON BASE" : "LIFTED");
    Serial.printf("  HX711 Status      : %s\n", isHx711Valid ? "OK" : "READ FAILED / NOT READY");
    Serial.println("  ──────────────────────────────────────────");
    Serial.printf("  BLE App State     : %s\n", deviceConnected ? "CONNECTED" : "ADVERTISING");
    Serial.println("══════════════════════════════════════════════\n");
}

void handleSerialCommands() {
    String cmd;
    if (pollSerialLine(cmd)) {
        char action = tolower(cmd.charAt(0));
        switch (action) {
            case 't': tareScale(); break;
            case 'c': runGuidedCalibration(); break;
            case 's': saveCalibration(); break;
            case 'l': loadCalibration(); break;
            case 'x': eraseCalibration(); break;
            case 'i': printInfo(); break;
            case 'h': printHelp(); break;
            default:
                Serial.printf("Unknown command '%s'. Type 'h' for help.\n", cmd.c_str());
                break;
        }
    }
}

// ── Setup ──────────────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println("\n=======================================================");
    Serial.println("  💧 SipSense | ESP32 + DHT11 + HX711 -> BLE Firmware  ");
    Serial.println("=======================================================");

    // 1. Initialize DHT11
    dht.begin();
    Serial.printf("[INIT] DHT11 initialized on GPIO %d\n", DHT_PIN);

    // 2. Initialize HX711
    scale.begin(HX711_DT_PIN, HX711_SCK_PIN);
    Serial.printf("[INIT] HX711 initialized on DT=GPIO %d, SCK=GPIO %d\n", HX711_DT_PIN, HX711_SCK_PIN);

    // Load calibration from flash or tare initially
    if (!loadCalibration()) {
        tareScale();
    }

    // 3. Initialize BLE Stack
    BLEDevice::init(DEVICE_NAME);
    BLEDevice::setMTU(512);

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new SipSenseServerCallbacks());

    BLEService* pService = pServer->createService(SERVICE_UUID);

    pTelemetryCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
    );
    pTelemetryCharacteristic->addDescriptor(new BLE2902());
    pTelemetryCharacteristic->setValue("{\"status\":\"Initializing\",\"sensor\":\"DHT11+HX711\"}");

    pService->start();

    // 4. Start Advertising
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

    Serial.printf("[BLE] Advertising as '%s'. Connect with SipSense app.\n", DEVICE_NAME);
    printHelp();

    // Initial sensor readings
    delay(1000);
    readDhtSensor();
    readHx711Sensor();
    publishTelemetry();

    lastDhtReadMs   = millis();
    lastHx711ReadMs = millis();
    lastNotifyMs    = millis();
}

// ── Loop ───────────────────────────────────────────────────────────────
void loop() {
    const unsigned long now = millis();

    // Connection lifecycle
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = true;
        connectedAtMs      = now;
        pendingFirstNotify = true;
        publishTelemetry();
    }
    if (!deviceConnected && oldDeviceConnected) {
        oldDeviceConnected = false;
        delay(300);
        pServer->startAdvertising();
        Serial.println("[BLE] Advertising restarted. Waiting for reconnection...");
    }

    // Read HX711 scale periodically
    if (now - lastHx711ReadMs >= HX711_READ_INTERVAL_MS) {
        lastHx711ReadMs = now;
        readHx711Sensor();
    }

    // Read DHT11 sensor periodically
    if (now - lastDhtReadMs >= DHT_READ_INTERVAL_MS) {
        lastDhtReadMs = now;
        readDhtSensor();
    }

    // Push initial telemetry after connection
    if (pendingFirstNotify && deviceConnected && now - connectedAtMs >= FIRST_NOTIFY_DELAY_MS) {
        pendingFirstNotify = false;
        lastNotifyMs = now;
        publishTelemetry();
    }

    // Push periodic telemetry to mobile app
    if (now - lastNotifyMs >= NOTIFY_INTERVAL_MS) {
        lastNotifyMs = now;
        publishTelemetry();
    }

    // Check for interactive Serial commands from PC
    handleSerialCommands();

    delay(10);
}
