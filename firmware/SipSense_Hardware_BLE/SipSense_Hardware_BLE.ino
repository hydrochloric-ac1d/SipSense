/*
 * SipSense_Hardware_BLE.ino
 *
 * ESP32 + DHT11 + HX711 (Load Cell) - Pure Hardware Firmware (ZERO SIMULATION)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  NO SIMULATION • NO RANDOM NUMBERS • NO FAKE SIPS • NO SYNTHETIC VALUES
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * What it does:
 * 1. REAL DHT11:
 *    - Reads ambient temperature (°C) and relative humidity (%) from GPIO 19.
 *    - If the sensor is disconnected or fails, transmits explicit "Read Failed".
 *
 * 2. REAL HX711 & LOAD CELL:
 *    - Reads live weight (grams) and raw ADC counts from GPIO 21 (DT) and 22 (SCK).
 *    - Automatically loads saved calibration factor and zero offset from flash NVS.
 *    - Automatically detects whether the bottle is resting on the base platform
 *      or has been lifted off the base.
 *    - REAL SIP CALCULATION:
 *      Whenever the bottle is lifted and placed back onto the base, the firmware
 *      measures the actual difference in weight. If fluid was consumed, it calculates
 *      the exact sip volume (1 g water = 1 ml) and adds it to totalConsumedToday.
 *
 * 3. REAL BLE TELEMETRY:
 *    - Advertises as "SipSense-Bottle" with the standard SipSense GATT UUIDs.
 *    - Transmits live JSON telemetry directly to the SipSense Android app.
 *    - Feeds live [TEMP] and [HX711] readings into the Developer Mode Signals Terminal.
 *
 * 4. SERIAL MONITOR COMMANDS (115200 baud, line ending "Newline"):
 *    - 't' -> Tare / zero the scale
 *    - 'c' -> Guided 3-step calibration with a known weight
 *    - 's' -> Save calibration to flash NVS
 *    - 'l' -> Reload calibration from flash NVS
 *    - 'x' -> Erase calibration from flash NVS
 *    - 'i' -> Print live hardware telemetry status
 *    - 'h' -> Show help menu
 *
 * Hardware Pin Connections (USB-C Powered):
 * ───────────────────────────────────────────────────────────────────────────
 *   ESP32 3V3  ──┬──> DHT11 VCC
 *                └──> HX711 VCC / VDD
 *   ESP32 GND  ──┬──> DHT11 GND
 *                └──> HX711 GND
 *   ESP32 GPIO 19 ──> DHT11 DATA (10k pull-up to 3V3 if bare 4-pin)
 *   ESP32 GPIO 21 ──> HX711 DT (DOUT)
 *   ESP32 GPIO 22 ──> HX711 SCK (PD_SCK)
 *
 *   HX711 ── Load Cell (4-wire):
 *     E+  ── Red wire
 *     E-  ── Black wire
 *     A-  ── White wire
 *     A+  ── Green wire
 *
 * Required Libraries (Arduino Library Manager):
 *   - "DHT sensor library" by Adafruit
 *   - "Adafruit Unified Sensor" by Adafruit
 *   - "HX711 Arduino Library" by Bogdan Necula (bogde)
 *
 * Board: "ESP32 Dev Module"  |  Partition Scheme: "Huge APP"  |  Baud: 115200
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
#ifndef LED_PIN
#define LED_PIN         2       // On-board Status LED (Blinking = Advertising, Steady = Connected)
#endif
#define DHT_PIN         19
#define DHT_TYPE        DHT11

#define HX711_DT_PIN    21
#define HX711_SCK_PIN   22

// ── Flash NVS Storage Keys (Shared across SipSense sketches) ───────────
#define PREFS_NAMESPACE "sipsense"
#define KEY_FACTOR      "hx_factor"
#define KEY_OFFSET      "hx_offset"
#define KEY_CALIBRATED  "hx_calib"

// ── BLE GATT Identifiers (Must match mobile app BleConstants.kt) ───────
#define DEVICE_NAME         "SipSense-Bottle"
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// ── Timing Parameters ──────────────────────────────────────────────────
const unsigned long DHT_SAMPLE_INTERVAL_MS   = 2000; // DHT11 sampled every 2s
const unsigned long SCALE_SAMPLE_INTERVAL_MS = 300;  // HX711 sampled every 300ms
const unsigned long NOTIFY_INTERVAL_MS       = 2000; // BLE push every 2s
const unsigned long FIRST_NOTIFY_DELAY_MS    = 250;  // Delay for BLE CCCD subscription
const unsigned long HX711_TIMEOUT_MS         = 100;  // Non-blocking timeout
const uint8_t       TARE_SAMPLES             = 20;
const uint8_t       CALIBRATION_SAMPLES      = 20;
const uint8_t       READ_SAMPLES             = 3;    // Fast averaging for responsiveness
const long          MIN_CALIBRATION_DELTA    = 1000;
const float         LIFT_THRESHOLD_GRAMS     = -15.0f; // Weight drops below this when lifted
const float         SIP_MIN_THRESHOLD_GRAMS  = 5.0f;  // Minimum 5g weight drop to count as sip
const unsigned long LED_BLINK_INTERVAL_MS    = 350;   // Status LED blinking interval while advertising

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

// ── Status LED State (Blinking = Advertising, Steady = Connected) ───────
unsigned long lastLedBlinkMs     = 0;
bool          ledBlinkState      = false;

void updateLedIndicator() {
    const unsigned long now = millis();

    if (deviceConnected) {
        digitalWrite(LED_PIN, HIGH);
    } else {
        if (now - lastLedBlinkMs >= LED_BLINK_INTERVAL_MS) {
            lastLedBlinkMs = now;
            ledBlinkState = !ledBlinkState;
            digitalWrite(LED_PIN, ledBlinkState ? HIGH : LOW);
        }
    }
}

// ── Sensor State (Pure Hardware) ───────────────────────────────────────
// DHT11
float   lastTemperatureC    = NAN;
float   lastHumidity        = NAN;
bool    isDhtValid          = false;
uint8_t dhtFailureCount     = 0;

// HX711 & Weight
float   calibrationFactor   = 1.0f;
long    zeroOffset          = 0;
bool    isCalibrated        = false;
float   lastWeightGrams     = 0.0f;
long    lastRawCounts       = 0;
bool    isHx711Valid        = false;
uint8_t hx711FailureCount   = 0;

// Water Level & Sip Tracking (Real Hardware Calculations)
int     currentWaterLevel   = 0;     // ml (1 gram water = 1 ml)
int     bottleCapacity      = 1000;  // ml
bool    isBottleAtRest      = true;  // Bottle placed on base vs lifted
bool    wasBottleAtRest     = true;
float   weightBeforeLift    = 0.0f;  // Reference weight before user lifted bottle
int     lastSipAmount       = 0;     // Last consumed sip in ml
int     totalConsumedToday  = 0;     // Cumulative total in ml
int     batteryLevel        = 100;   // 100% on USB-C power

// Timers
unsigned long lastDhtMs     = 0;
unsigned long lastScaleMs   = 0;
unsigned long lastNotifyMs  = 0;

// Serial Buffer for interactive calibration
String        serialBuffer;
unsigned long lastCharMs    = 0;

// ── BLE Server Callbacks ───────────────────────────────────────────────
class HardwareServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* server) override {
        deviceConnected = true;
        digitalWrite(LED_PIN, HIGH); // Steady ON when connected
        Serial.println("\n📱 [BLE] Mobile app connected to SipSense-Bottle.");
    }

    void onDisconnect(BLEServer* server) override {
        deviceConnected = false;
        Serial.println("\n❌ [BLE] Mobile app disconnected. Resuming advertising...");
        server->startAdvertising();
    }
};

// Forward Declarations
void publishTelemetry(bool forceImmediate = false);
void printHelp();

// ── Flash Calibration Management ───────────────────────────────────────
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
        Serial.println("[FLASH] No saved calibration found. Scale running uncalibrated (factor=1.0).");
        isCalibrated = false;
        return false;
    }

    applyCalibration(factor, offset);
    isCalibrated = true;
    Serial.printf("[FLASH] Restored calibration -> Factor: %.4f | Zero Offset: %ld\n", factor, offset);
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
    Serial.println("[FLASH] Saved calibration erased. Reset to default 1.0.");
}

void tareScale() {
    if (!scale.wait_ready_timeout(1000)) {
        Serial.println("[TARE] ⚠️ HX711 not responding! Check DT/SCK wiring.");
        return;
    }
    Serial.println("[TARE] Zeroing platform... keep still.");
    scale.tare(TARE_SAMPLES);
    zeroOffset = scale.get_offset();
    Serial.printf("[TARE] Complete. Zero offset = %ld\n", zeroOffset);
}

// ── Real Physical Sensor Reads: DHT11 ──────────────────────────────────
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
            Serial.println("        Wiring check: VCC->3V3, GND->GND, DATA->GPIO 19");
        }
        return;
    }

    dhtFailureCount  = 0;
    isDhtValid       = true;
    lastTemperatureC = t;
    lastHumidity     = h;
}

// ── Real Physical Sensor Reads: HX711 & Sip Calculation ────────────────
void readScaleSensor() {
    if (!scale.wait_ready_timeout(HX711_TIMEOUT_MS)) {
        hx711FailureCount++;
        if (hx711FailureCount >= 5) {
            isHx711Valid = false;
        }
        return;
    }

    hx711FailureCount = 0;
    isHx711Valid      = true;

    // Actual raw ADC counts
    lastRawCounts = scale.get_value(1);

    if (isCalibrated) {
        // Actual weight in grams
        float weight = scale.get_units(READ_SAMPLES);

        // Detect bottle lifted vs resting on base platform
        if (weight < LIFT_THRESHOLD_GRAMS) {
            // Bottle is off the base
            if (isBottleAtRest) {
                // Transition: Bottle just lifted!
                isBottleAtRest = false;
                weightBeforeLift = (lastWeightGrams > 0.0f) ? lastWeightGrams : 0.0f;
                Serial.printf("[BASE] 🚀 Bottle lifted! Storing reference weight: %.1f g\n", weightBeforeLift);
                publishTelemetry(true);
            }
        } else {
            // Bottle is placed on the base
            float safeWeight = (weight < 0.0f) ? 0.0f : weight;
            lastWeightGrams = safeWeight;
            currentWaterLevel = constrain((int) lroundf(safeWeight), 0, bottleCapacity);

            if (!isBottleAtRest) {
                // Transition: Bottle returned to rest on base platform!
                isBottleAtRest = true;
                float weightDelta = weightBeforeLift - safeWeight;

                if (weightDelta >= SIP_MIN_THRESHOLD_GRAMS) {
                    // REAL SIP DETECTED!
                    int sipConsumed = (int) lroundf(weightDelta);
                    lastSipAmount = sipConsumed;
                    totalConsumedToday += sipConsumed;
                    Serial.printf("🥤 [REAL SIP DETECTED] Drank %d ml! Total today: %d ml\n",
                                  sipConsumed, totalConsumedToday);
                } else if (safeWeight > weightBeforeLift + 20.0f) {
                    // Refill detected
                    Serial.printf("💧 [REFILL DETECTED] Water added. New volume: %d ml\n", currentWaterLevel);
                }

                publishTelemetry(true);
            }
        }
    } else {
        // Uncalibrated raw counts mode
        lastWeightGrams   = (float) lastRawCounts;
        isBottleAtRest    = true;
        currentWaterLevel = 0;
    }
}

// ── BLE Telemetry Transmission ─────────────────────────────────────────
void publishTelemetry(bool forceImmediate) {
    if (!pTelemetryCharacteristic) return;

    char json[380];

    int tempInt = isDhtValid ? (int) lroundf(lastTemperatureC) : 0;
    int humInt  = isDhtValid ? (int) lroundf(lastHumidity) : 0;

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
            "\"sipAmount\":%d,\"totalConsumedToday\":%d,"
            "\"isBottleAtRest\":%s,\"isCalibrated\":%s,"
            "\"bottleCapacity\":%d,\"batteryLevel\":%d,"
            "\"sensor\":\"DHT11+HX711\",\"uptimeMs\":%lu}",
            isDhtValid ? "OK" : "Read Failed",
            tempInt, tempInt, humInt, isDhtValid ? lastTemperatureC : 0.0f,
            isHx711Valid ? "OK" : "Read Failed",
            currentWaterLevel, lastWeightGrams, lastRawCounts,
            lastSipAmount, totalConsumedToday,
            isBottleAtRest ? "true" : "false",
            isCalibrated ? "true" : "false",
            bottleCapacity, batteryLevel,
            millis());
    }

    // Set GATT characteristic value
    pTelemetryCharacteristic->setValue(json);

    // Notify connected client
    if (deviceConnected) {
        pTelemetryCharacteristic->notify();
        Serial.printf("[BLE -> APP] 📤 🌡️ %s | ⚖️ %s\n",
            isDhtValid ? (String(lastTemperatureC, 1) + " C (" + String(humInt) + "%)").c_str() : "TEMP FAILED",
            isHx711Valid ? (String(lastWeightGrams, 1) + " g (" + String(currentWaterLevel) + " ml)" + (isBottleAtRest ? " [At Rest]" : " [Lifted]")).c_str() : "HX711 NOT READY");
    }
}

// ── Interactive Serial Command Handler ─────────────────────────────────
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
    Serial.println("\nSTEP 1/3: Remove EVERYTHING from the platform.");
    Serial.println("          Type  y  and press Enter when platform is empty.");
    String ans = readLineBlocking();
    if (ans.equalsIgnoreCase("q")) { Serial.println("Calibration cancelled."); return; }

    scale.set_scale(1.0f);
    tareScale();

    // Step 2: Known weight
    Serial.println("\nSTEP 2/3: Place a KNOWN weight on the platform (e.g. 500g bottle).");
    Serial.println("          Enter weight in GRAMS (e.g. 500) and press Enter:");
    float knownGrams = 0.0f;
    while (true) {
        ans = readLineBlocking();
        if (ans.equalsIgnoreCase("q")) {
            Serial.println("Calibration cancelled.");
            scale.set_scale(calibrationFactor);
            return;
        }
        knownGrams = ans.toFloat();
        if (knownGrams > 0.0f) break;
        Serial.println("          Please enter a valid positive number in grams (e.g. 500):");
    }

    Serial.printf("          Measuring %.1f g reference... keep still.\n", knownGrams);
    delay(1500);

    if (!scale.wait_ready_timeout(1000)) {
        Serial.println("[ERROR] HX711 not responding during measurement.");
        scale.set_scale(calibrationFactor);
        return;
    }

    double rawDelta = scale.get_value(CALIBRATION_SAMPLES);
    if (fabs(rawDelta) < MIN_CALIBRATION_DELTA) {
        Serial.printf("[ERROR] Raw counts change too small (%.0f counts). Check wiring/mounting.\n", rawDelta);
        scale.set_scale(calibrationFactor);
        return;
    }

    // Step 3: Compute factor
    calibrationFactor = (float) (rawDelta / knownGrams);
    isCalibrated = true;
    scale.set_scale(calibrationFactor);

    Serial.println("\nSTEP 3/3: Calibration successful!");
    Serial.printf("          Raw Delta : %.0f counts\n", rawDelta);
    Serial.printf("          Factor    : %.4f counts/gram\n", calibrationFactor);
    Serial.printf("          Offset    : %ld\n", scale.get_offset());

    saveCalibration();
    Serial.println("          Saved to flash memory! Scale is active and calibrated.\n");
}

void printHelp() {
    Serial.println("\n── SipSense Hardware Serial Commands ──");
    Serial.println("  t       -> Tare / zero the scale");
    Serial.println("  c       -> Run 3-step guided calibration");
    Serial.println("  s       -> Save calibration to flash");
    Serial.println("  l       -> Reload calibration from flash");
    Serial.println("  x       -> Erase saved calibration from flash");
    Serial.println("  i       -> Print live hardware readings & status");
    Serial.println("  h       -> Show this help menu\n");
}

void printInfo() {
    Serial.println("\n═══════════ SIPSENSE REAL HARDWARE STATUS ═══════════");
    Serial.printf("  DHT11 Temp (°C)   : %.1f C (%.1f F)\n", lastTemperatureC, (lastTemperatureC * 1.8f) + 32.0f);
    Serial.printf("  DHT11 Humidity    : %.0f %%\n", lastHumidity);
    Serial.printf("  DHT11 Status      : %s\n", isDhtValid ? "OK" : "READ FAILED");
    Serial.println("  ───────────────────────────────────────────────────");
    Serial.printf("  HX711 Weight      : %.1f g\n", lastWeightGrams);
    Serial.printf("  Water Volume      : %d ml / %d ml\n", currentWaterLevel, bottleCapacity);
    Serial.printf("  Last Sip Measured : %d ml\n", lastSipAmount);
    Serial.printf("  Total Consumed    : %d ml\n", totalConsumedToday);
    Serial.printf("  Bottle Base State : %s\n", isBottleAtRest ? "AT REST ON BASE" : "LIFTED");
    Serial.printf("  HX711 Raw Counts  : %ld\n", lastRawCounts);
    Serial.printf("  HX711 Calibrated  : %s (Factor: %.4f, Offset: %ld)\n",
                  isCalibrated ? "YES" : "NO", calibrationFactor, scale.get_offset());
    Serial.printf("  HX711 Status      : %s\n", isHx711Valid ? "OK" : "READ FAILED / NOT READY");
    Serial.println("  ───────────────────────────────────────────────────");
    Serial.printf("  Power Source      : USB-C (5V -> 3.3V)\n");
    Serial.printf("  BLE App State     : %s\n", deviceConnected ? "CONNECTED" : "ADVERTISING");
    Serial.println("═════════════════════════════════════════════════════\n");
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
    Serial.println("  💧 SipSense | Pure Hardware BLE Firmware (NO SIM)    ");
    Serial.println("=======================================================");

    // 0. Initialize Status LED (GPIO 2)
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, LOW);
    Serial.printf("[INIT] Status LED initialized on GPIO %d (Blinking=Adv, Steady=Conn)\n", LED_PIN);

    // 1. Initialize DHT11
    dht.begin();
    Serial.printf("[INIT] DHT11 initialized on GPIO %d\n", DHT_PIN);

    // 2. Initialize HX711
    scale.begin(HX711_DT_PIN, HX711_SCK_PIN);
    Serial.printf("[INIT] HX711 initialized on DT=GPIO %d, SCK=GPIO %d\n", HX711_DT_PIN, HX711_SCK_PIN);

    // Load calibration from flash, or tare if not yet calibrated
    if (!loadCalibration()) {
        tareScale();
    }

    // 3. Initialize BLE Stack
    BLEDevice::init(DEVICE_NAME);
    BLEDevice::setMTU(512);

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new HardwareServerCallbacks());

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

    // First sensor reads
    delay(1000);
    readDhtSensor();
    readScaleSensor();
    publishTelemetry();

    lastDhtMs    = millis();
    lastScaleMs  = millis();
    lastNotifyMs = millis();
}

// ── Loop ───────────────────────────────────────────────────────────────
void loop() {
    const unsigned long now = millis();

    // Update Status LED (Blinking while broadcasting, Steady when connected)
    updateLedIndicator();

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

    // Sample HX711 load cell (every 300 ms)
    if (now - lastScaleMs >= SCALE_SAMPLE_INTERVAL_MS) {
        lastScaleMs = now;
        readScaleSensor();
    }

    // Sample DHT11 sensor (every 2 s)
    if (now - lastDhtMs >= DHT_SAMPLE_INTERVAL_MS) {
        lastDhtMs = now;
        readDhtSensor();
    }

    // Push initial notification after connection
    if (pendingFirstNotify && deviceConnected && now - connectedAtMs >= FIRST_NOTIFY_DELAY_MS) {
        pendingFirstNotify = false;
        lastNotifyMs = now;
        publishTelemetry();
    }

    // Push periodic telemetry to mobile app (every 2 s)
    if (now - lastNotifyMs >= NOTIFY_INTERVAL_MS) {
        lastNotifyMs = now;
        publishTelemetry();
    }

    // Handle interactive Serial commands from PC (tare, calibrate, etc.)
    handleSerialCommands();

    delay(10);
}
