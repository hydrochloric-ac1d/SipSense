/*
 * SipSense_WaterBottle_BLE.ino
 *
 * ESP32 Smart Water Bottle Firmware (Physical DHT11 + HX711 + BLE Status LED)
 * Fixed 500 ml Bottle Capacity & Automatic Water Volume Deduction Tracking
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  REQUIRED ARDUINO IDE LIBRARIES (Sketch -> Include Library -> Manage Libraries):
 * ───────────────────────────────────────────────────────────────────────────
 *  1. "DHT sensor library" by Adafruit        (search: DHT sensor library)
 *  2. "Adafruit Unified Sensor" by Adafruit   (installed automatically with DHT)
 *  3. "HX711 Arduino Library" by Bogdan Necula (search: HX711 Arduino Library)
 *  *  BLE libraries (BLEDevice.h, BLEServer.h, BLEUtils.h, BLE2902.h) and
 *     Preferences.h are built-in with the ESP32 Board Package by Espressif.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  BLE BROADCAST & STATUS LED BEHAVIOR (GPIO 2):
 * ───────────────────────────────────────────────────────────────────────────
 *  - BROADCASTING / ADVERTISING (DISCONNECTED):
 *      The on-board LED (GPIO 2) BLINKS continuously (350ms ON / 350ms OFF)
 *      while broadcasting the BLE signal ("SipSense-Bottle").
 *
 *  - CONNECTED TO SIPSENSE MOBILE APP:
 *      Once the user manually connects from the Water Bottle tab in the
 *      SipSense Android app, the LED turns STEADY ON (solid HIGH).
 *
 *  - DISCONNECTED:
 *      When the user manually disconnects from the app, BLE advertising
 *      restarts immediately and the LED returns to BLINKING.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  500 ML WATER BOTTLE & DAILY GOAL DEDUCTION LOGIC:
 * ───────────────────────────────────────────────────────────────────────────
 *  - Bottle Capacity is fixed at 500 ml (initial full water level = 500 ml).
 *  - Every time the water volume decreases (is deducted), the deducted ml
 *    amount is automatically counted as drinking water towards the Daily Goal.
 *  - Lifting the bottle off the base pauses measurement (holds currentWaterLevel)
 *    so lifting never drops the displayed water level to 0 ml.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  PHYSICAL HARDWARE WIRING (ESP32 DevKit V1):
 * ───────────────────────────────────────────────────────────────────────────
 *  1. Status LED:
 *     - GPIO 2  --> On-board Blue LED (Blinking = Broadcasting, Steady = Connected)
 *
 *  2. Physical DHT11 Sensor (Ambient Temperature & Humidity):
 *     - VCC / + --> ESP32 3V3
 *     - DATA/OUT--> ESP32 GPIO 19
 *     - GND / - --> ESP32 GND
 *
 *  3. Physical HX711 Load Cell Amplifier (Water Weight / Sip Detection):
 *     - VCC     --> ESP32 3V3
 *     - GND     --> ESP32 GND
 *     - DT / DO --> ESP32 GPIO 21
 *     - SCK/CLK --> ESP32 GPIO 22
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  SERIAL MONITOR COMMANDS (115200 baud):
 * ───────────────────────────────────────────────────────────────────────────
 *  - 't'       : Tare / Zero the scale (set current weight as 0g)
 *  - 'c <wt>'  : Calibrate with a known weight in grams (e.g., "c 500")
 *  - 's'       : Deduct 50 ml from water level (test Daily Goal drinking water)
 *  - 'r'       : Refill / reset water bottle level back to 500 ml
 *  - 'i'       : Print current calibration & live sensor status
 *  - 'h'       : Print help menu
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
#include <math.h>

// ── Pin Definitions ────────────────────────────────────────────────────────
#ifndef LED_PIN
#define LED_PIN         2       // On-board Status LED (GPIO 2)
#endif

#define DHT_PIN         19      // Physical DHT11 DATA pin (GPIO 19)
#define DHT_TYPE        DHT11   // DHT11 sensor type

#define HX711_DT_PIN    21      // Physical HX711 DT / DOUT pin (GPIO 21)
#define HX711_SCK_PIN   22      // Physical HX711 SCK / CLK pin (GPIO 22)

// ── Bottle Constants ───────────────────────────────────────────────────────
#define BOTTLE_CAPACITY_ML  500 // Fixed 500 ml Smart Water Bottle capacity

// ── BLE UUIDs (Must match BleConstants.kt in SipSense Android App) ─────────
#define DEVICE_NAME         "SipSense-Bottle"
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// ── Flash NVS Keys for Saving HX711 Calibration ────────────────────────────
#define PREFS_NAMESPACE     "sipsense"
#define KEY_FACTOR          "hx_factor"
#define KEY_OFFSET          "hx_offset"
#define KEY_CALIBRATED      "hx_calib"

// ── Timing & Threshold Constants ───────────────────────────────────────────
const unsigned long LED_BLINK_INTERVAL_MS    = 350;   // Blink speed while advertising (350ms)
const unsigned long NOTIFY_INTERVAL_MS       = 2000;  // Periodic BLE notify interval (2s)
const unsigned long DHT_SAMPLE_INTERVAL_MS   = 2000;  // DHT11 minimum sampling period (2s)
const unsigned long SCALE_SAMPLE_INTERVAL_MS = 250;   // HX711 sampling period (250ms)
const unsigned long FIRST_NOTIFY_DELAY_MS    = 350;   // Delay after connect for CCCD setup

const float SIP_THRESHOLD_GRAMS              = 5.0f;  // Minimum weight decrease to count as deduction (5g = 5ml)
const float MIN_BOTTLE_ON_BASE_GRAMS         = 25.0f; // Weight threshold to detect bottle resting on base
const float LIFT_NEGATIVE_THRESHOLD_GRAMS    = -15.0f;// Threshold when tared with bottle already on base

// ── Global Hardware Instances ──────────────────────────────────────────────
DHT         dht(DHT_PIN, DHT_TYPE);
HX711       scale;
Preferences prefs;

BLEServer*         pServer                  = nullptr;
BLECharacteristic* pTelemetryCharacteristic = nullptr;

// ── BLE Connection & LED State ─────────────────────────────────────────────
volatile bool deviceConnected     = false;
bool          oldDeviceConnected  = false;
unsigned long connectedAtMs       = 0;
bool          pendingFirstNotify  = false;

unsigned long lastLedBlinkMs      = 0;
bool          ledBlinkState       = false;

// ── Physical DHT11 State ───────────────────────────────────────────────────
float         currentTempC        = 24.0f;
float         currentHumidity     = 55.0f;
bool          isDhtReadFailed     = false;
int           dhtFailStreak       = 0;
const int     MAX_DHT_FAIL_STREAK = 3;

// ── Physical HX711 Load Cell State (Fixed 500 ml Bottle) ───────────────────
float         calibrationFactor   = 1.0f;
long          zeroOffset          = 0;
bool          isCalibrated        = false;
bool          isHx711ReadFailed   = false;
long          latestRawCounts     = 0;
float         latestWeightGrams   = 500.0f;
float         containerTareGrams  = 0.0f;
bool          hasBottleBeenPlaced = false;

int           currentWaterLevel   = BOTTLE_CAPACITY_ML; // Starts at 500 ml
int           bottleCapacity      = BOTTLE_CAPACITY_ML; // Fixed 500 ml
bool          isBottleAtRest      = true;
int           lastSipAmount       = 0;
int           totalConsumedToday  = 0;
int           batteryLevel        = 95;

// ── Loop Timers ────────────────────────────────────────────────────────────
unsigned long lastDhtReadMs       = 0;
unsigned long lastScaleReadMs     = 0;
unsigned long lastTelemetryPushMs = 0;

// Forward Declarations
void updateLedIndicator();
void readDht11Sensor();
void readScaleSensor();
void publishTelemetry(bool forceImmediate = false);
void handleSerialCommands();
void tareScale();
void calibrateScale(float knownWeightGrams);
bool loadCalibration();
void saveCalibration(float factor, long offset);
void printDeviceStatus();
void printHelp();

// ═══════════════════════════════════════════════════════════════════════════
//  BLE SERVER CONNECTION CALLBACKS
// ═══════════════════════════════════════════════════════════════════════════
class BottleServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* server) override {
        deviceConnected = true;
        digitalWrite(LED_PIN, HIGH); // Immediately turn LED STEADY ON when connected
        Serial.println("\n╔══════════════════════════════════════════════════════════╗");
        Serial.println("║  [BLE] Mobile App CONNECTED!                             ║");
        Serial.println("║  [LED] GPIO 2 changed to: STEADY ON                      ║");
        Serial.println("╚══════════════════════════════════════════════════════════╝");
    }

    void onDisconnect(BLEServer* server) override {
        deviceConnected = false;
        Serial.println("\n╔══════════════════════════════════════════════════════════╗");
        Serial.println("║  [BLE] Mobile App DISCONNECTED!                          ║");
        Serial.println("║  [LED] GPIO 2 changed to: BLINKING (Broadcasting BLE)    ║");
        Serial.println("╚══════════════════════════════════════════════════════════╝");
        // Immediately restart BLE advertising so the app can scan & reconnect
        server->startAdvertising();
    }
};

// ═══════════════════════════════════════════════════════════════════════════
//  STATUS LED CONTROLLER (Blinking when Advertising, Steady ON when Connected)
// ═══════════════════════════════════════════════════════════════════════════
void updateLedIndicator() {
    const unsigned long now = millis();

    if (deviceConnected) {
        // Connected -> LED is solid ON
        digitalWrite(LED_PIN, HIGH);
    } else {
        // Broadcasting BLE -> Blink LED every 350ms
        if (now - lastLedBlinkMs >= LED_BLINK_INTERVAL_MS) {
            lastLedBlinkMs = now;
            ledBlinkState = !ledBlinkState;
            digitalWrite(LED_PIN, ledBlinkState ? HIGH : LOW);
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  NVS FLASH CALIBRATION STORAGE
// ═══════════════════════════════════════════════════════════════════════════
bool loadCalibration() {
    prefs.begin(PREFS_NAMESPACE, true);
    bool saved   = prefs.getBool(KEY_CALIBRATED, false);
    float factor = prefs.getFloat(KEY_FACTOR, 1.0f);
    long offset  = prefs.getLong(KEY_OFFSET, 0);
    prefs.end();

    if (saved && fabs(factor) > 0.0001f) {
        calibrationFactor = factor;
        zeroOffset        = offset;
        isCalibrated      = true;
        return true;
    }
    return false;
}

void saveCalibration(float factor, long offset) {
    prefs.begin(PREFS_NAMESPACE, false);
    prefs.putBool(KEY_CALIBRATED, true);
    prefs.putFloat(KEY_FACTOR, factor);
    prefs.putLong(KEY_OFFSET, offset);
    prefs.end();

    calibrationFactor = factor;
    zeroOffset        = offset;
    isCalibrated      = true;
}

// ═══════════════════════════════════════════════════════════════════════════
//  HX711 TARE & CALIBRATION ROUTINES
// ═══════════════════════════════════════════════════════════════════════════
void tareScale() {
    Serial.println("\n⚖️  [HX711] Taring scale... Keep platform still.");
    if (!scale.wait_ready_timeout(1000)) {
        Serial.println("❌ [HX711] Not responding! Check DT=GPIO21, SCK=GPIO22, VCC=3V3, GND.");
        isHx711ReadFailed = true;
        return;
    }

    scale.set_scale();
    scale.tare(20);
    zeroOffset = scale.get_offset();

    if (isCalibrated) {
        scale.set_scale(calibrationFactor);
        saveCalibration(calibrationFactor, zeroOffset);
    }

    containerTareGrams  = 0.0f;
    hasBottleBeenPlaced = false;
    currentWaterLevel   = BOTTLE_CAPACITY_ML;
    latestWeightGrams   = (float)BOTTLE_CAPACITY_ML;
    isHx711ReadFailed   = false;

    Serial.printf("✅ [HX711] Tare complete! Zero offset = %ld | Bottle reset to %d ml\n", zeroOffset, currentWaterLevel);
    publishTelemetry(true);
}

void calibrateScale(float knownWeightGrams) {
    if (knownWeightGrams <= 0.0f) {
        Serial.println("⚠️  [HX711] Known weight must be > 0 grams (e.g. 'c 500').");
        return;
    }

    Serial.printf("\n⚖️  [HX711] Calibrating with %.1f g... Hold weight steady.\n", knownWeightGrams);
    if (!scale.wait_ready_timeout(1000)) {
        Serial.println("❌ [HX711] Timeout waiting for HX711 during calibration.");
        isHx711ReadFailed = true;
        return;
    }

    long rawAvg = scale.read_average(20);
    long netCounts = rawAvg - zeroOffset;

    if (abs(netCounts) < 100) {
        Serial.println("⚠️  [HX711] Weight change too small! Run 't' with empty scale first, then place weight and run 'c <grams>'.");
        return;
    }

    float newFactor = (float)netCounts / knownWeightGrams;
    scale.set_scale(newFactor);
    scale.set_offset(zeroOffset);
    saveCalibration(newFactor, zeroOffset);

    float verifiedGrams = scale.get_units(10);
    latestWeightGrams   = max(0.0f, verifiedGrams);
    hasBottleBeenPlaced = true;
    if (latestWeightGrams > (float)BOTTLE_CAPACITY_ML) {
        containerTareGrams = latestWeightGrams - (float)BOTTLE_CAPACITY_ML;
    } else {
        containerTareGrams = 0.0f;
    }
    currentWaterLevel = constrain((int)lroundf(latestWeightGrams - containerTareGrams), 0, BOTTLE_CAPACITY_ML);
    isHx711ReadFailed = false;

    Serial.println("╔══════════════════════════════════════════════════════════╗");
    Serial.printf("║  ✅ CALIBRATION SAVED TO FLASH!                          ║\n");
    Serial.printf("║  • Factor         : %-12.4f                         ║\n", calibrationFactor);
    Serial.printf("║  • Zero Offset    : %-12ld                         ║\n", zeroOffset);
    Serial.printf("║  • Verified Read  : %-8.1f g (%4d / %4d ml)          ║\n", verifiedGrams, currentWaterLevel, bottleCapacity);
    Serial.println("╚══════════════════════════════════════════════════════════╝");

    publishTelemetry(true);
}

// ═══════════════════════════════════════════════════════════════════════════
//  PHYSICAL SENSOR READERS (DHT11 & HX711 WITH 500ML DEDUCTION LOGIC)
// ═══════════════════════════════════════════════════════════════════════════
void readDht11Sensor() {
    float h = dht.readHumidity();
    float t = dht.readTemperature(); // Celsius

    if (isnan(h) || isnan(t)) {
        dhtFailStreak++;
        Serial.printf("⚠️  [DHT11] Read failed on GPIO %d (streak %d/%d)\n",
                      DHT_PIN, dhtFailStreak, MAX_DHT_FAIL_STREAK);
        if (dhtFailStreak >= MAX_DHT_FAIL_STREAK) {
            isDhtReadFailed = true;
        }
    } else {
        dhtFailStreak   = 0;
        isDhtReadFailed = false;
        currentTempC    = t;
        currentHumidity = h;
        Serial.printf("🌡️  [DHT11] Ambient Temp: %.1f°C | Humidity: %.0f%%\n",
                      currentTempC, currentHumidity);
    }
}

void readScaleSensor() {
    if (!scale.wait_ready_timeout(200)) {
        isHx711ReadFailed = true;
        Serial.println("⚠️  [HX711] Sensor not ready (check DT=GPIO21, SCK=GPIO22).");
        return;
    }

    isHx711ReadFailed = false;
    latestRawCounts   = scale.read_average(5);

    if (!isCalibrated) {
        // Uncalibrated: keep bottle level at 500 ml (or simulated level) until calibrated
        return;
    }

    float weight = scale.get_units(5);
    if (fabs(weight) < 2.0f) {
        weight = 0.0f;
    }

    // Detect if the bottle is lifted off the base:
    // Case A: Scale was tared with bottle on it -> lifting produces negative weight (< -15g)
    // Case B: Scale was tared empty -> after bottle is placed (>= 25g), lifting drops weight back near 0g (< 25g)
    bool liftedNow = (weight < LIFT_NEGATIVE_THRESHOLD_GRAMS) ||
                     (hasBottleBeenPlaced && weight < MIN_BOTTLE_ON_BASE_GRAMS);

    if (liftedNow) {
        if (isBottleAtRest) {
            isBottleAtRest = false;
            Serial.printf("🥤 [SCALE] Bottle LIFTED from base! Holding water level at %d ml / %d ml\n",
                          currentWaterLevel, bottleCapacity);
            publishTelemetry(true);
        }
        return;
    }

    // If weight is below minimum placement threshold and no bottle has been placed yet,
    // keep the default 500 ml full level ready until the bottle is placed on the scale.
    if (!hasBottleBeenPlaced && weight < MIN_BOTTLE_ON_BASE_GRAMS) {
        return;
    }

    // Bottle is resting on the base with valid weight (>= 25g)
    bool justPlacedOnBase = !hasBottleBeenPlaced || !isBottleAtRest;
    hasBottleBeenPlaced = true;
    isBottleAtRest      = true;
    latestWeightGrams   = max(0.0f, weight);

    // If total weight exceeds 500g (due to physical bottle container tare weight) or is a refill,
    // adjust containerTareGrams so a full bottle reads 500 ml.
    float waterGrams = latestWeightGrams - containerTareGrams;
    if (waterGrams > (float)BOTTLE_CAPACITY_ML) {
        containerTareGrams = latestWeightGrams - (float)BOTTLE_CAPACITY_ML;
        waterGrams = (float)BOTTLE_CAPACITY_ML;
    }

    int measuredWaterMl = constrain((int)lroundf(waterGrams), 0, BOTTLE_CAPACITY_ML);
    int levelDifference = currentWaterLevel - measuredWaterMl;

    // 1. Water Volume Deducted -> Count as Drinking Water towards Daily Goal!
    if (levelDifference >= (int)SIP_THRESHOLD_GRAMS) {
        lastSipAmount       = levelDifference;
        totalConsumedToday += lastSipAmount;
        currentWaterLevel   = measuredWaterMl;

        Serial.printf("💧 [DRINK DETECTED] Deducted: -%d ml | Bottle Level: %d / %d ml | Daily Total: %d ml\n",
                      lastSipAmount, currentWaterLevel, bottleCapacity, totalConsumedToday);
        publishTelemetry(true);
    }
    // 2. Water Refilled -> Increase water level (up to 500 ml) without adding a sip
    else if (measuredWaterMl - currentWaterLevel >= (int)SIP_THRESHOLD_GRAMS) {
        currentWaterLevel = measuredWaterMl;
        Serial.printf("🚰 [REFILL DETECTED] Bottle Level updated to: %d / %d ml\n",
                      currentWaterLevel, bottleCapacity);
        publishTelemetry(true);
    }
    // 3. Bottle returned to base without volume change -> Notify rest state
    else if (justPlacedOnBase) {
        Serial.printf("⚖️  [SCALE] Bottle placed at rest on base (%d / %d ml)\n",
                      currentWaterLevel, bottleCapacity);
        publishTelemetry(true);
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  BLE TELEMETRY PUBLISHER
// ═══════════════════════════════════════════════════════════════════════════
void publishTelemetry(bool forceImmediate) {
    if (!deviceConnected || pTelemetryCharacteristic == nullptr) {
        return;
    }

    char payload[440];
    int tempRounded = (int)lroundf(currentTempC);
    int humRounded  = (int)lroundf(currentHumidity);

    if (isDhtReadFailed) {
        snprintf(
            payload,
            sizeof(payload),
            "{\"waterLevel\":%d,\"bottleCapacity\":%d,\"batteryLevel\":%d,"
            "\"status\":\"Read Failed\",\"tempSensorStatus\":\"Read Failed\",\"readFailed\":true,"
            "\"weightGrams\":%.1f,\"rawCounts\":%ld,\"isCalibrated\":%s,"
            "\"hx711Status\":\"%s\",\"isBottleAtRest\":%s,\"sipAmount\":%d,\"lastSipAmount\":%d,\"totalConsumedToday\":%d}",
            currentWaterLevel,
            bottleCapacity,
            batteryLevel,
            latestWeightGrams,
            latestRawCounts,
            isCalibrated ? "true" : "false",
            isHx711ReadFailed ? "Read Failed" : "OK",
            isBottleAtRest ? "true" : "false",
            lastSipAmount,
            lastSipAmount,
            totalConsumedToday
        );
    } else {
        snprintf(
            payload,
            sizeof(payload),
            "{\"waterLevel\":%d,\"bottleCapacity\":%d,\"batteryLevel\":%d,"
            "\"ambientTemperature\":%d,\"temperature\":%d,\"temperatureC\":%.1f,\"humidity\":%d,"
            "\"tempSensorStatus\":\"OK\",\"readFailed\":false,"
            "\"weightGrams\":%.1f,\"rawCounts\":%ld,\"isCalibrated\":%s,"
            "\"hx711Status\":\"%s\",\"isBottleAtRest\":%s,\"sipAmount\":%d,\"lastSipAmount\":%d,\"totalConsumedToday\":%d}",
            currentWaterLevel,
            bottleCapacity,
            batteryLevel,
            tempRounded,
            tempRounded,
            currentTempC,
            humRounded,
            latestWeightGrams,
            latestRawCounts,
            isCalibrated ? "true" : "false",
            isHx711ReadFailed ? "Read Failed" : "OK",
            isBottleAtRest ? "true" : "false",
            lastSipAmount,
            lastSipAmount,
            totalConsumedToday
        );
    }

    pTelemetryCharacteristic->setValue((uint8_t*)payload, strlen(payload));
    pTelemetryCharacteristic->notify();

    // Clear one-shot sipAmount after transmitting so periodic notifies don't repeat it
    lastSipAmount = 0;

    if (forceImmediate) {
        Serial.printf("📡 [BLE TX Immediate] %s\n", payload);
    } else {
        Serial.printf("📡 [BLE TX] Water: %d/%dml | Temp: %.1fC | Batt: %d%% | AtRest: %s\n",
                      currentWaterLevel, bottleCapacity, currentTempC, batteryLevel,
                      isBottleAtRest ? "YES" : "NO");
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  SERIAL MONITOR COMMAND HANDLER
// ═══════════════════════════════════════════════════════════════════════════
void printDeviceStatus() {
    Serial.println("\n──────────────────── SIPSENSE STATUS ────────────────────");
    Serial.printf("  BLE Connection    : %s\n", deviceConnected ? "CONNECTED (LED Steady ON)" : "BROADCASTING (LED Blinking)");
    Serial.printf("  DHT11 (GPIO 19)   : %s (Temp: %.1f°C, Humidity: %.0f%%)\n",
                  isDhtReadFailed ? "READ FAILED" : "OK", currentTempC, currentHumidity);
    Serial.printf("  HX711 (GPIO21/22) : %s | Calibrated: %s\n",
                  isHx711ReadFailed ? "READ FAILED" : "OK", isCalibrated ? "YES" : "NO");
    Serial.printf("  Scale Factor/Zero : factor=%.4f, offset=%ld\n", calibrationFactor, zeroOffset);
    Serial.printf("  Water Level       : %d ml / %d ml (%.2f L)\n",
                  currentWaterLevel, bottleCapacity, currentWaterLevel / 1000.0f);
    Serial.printf("  Daily Consumed    : %d ml\n", totalConsumedToday);
    Serial.println("─────────────────────────────────────────────────────────\n");
}

void printHelp() {
    Serial.println("\n══════════ SERIAL MONITOR COMMANDS (115200 baud) ══════════");
    Serial.println("  t         -> Tare / Zero the scale (empty platform)");
    Serial.println("  c <grams> -> Calibrate scale with known weight (e.g. c 500)");
    Serial.println("  s         -> Deduct 50 ml from water level (test Daily Goal)");
    Serial.println("  r         -> Refill / reset water bottle level to 500 ml");
    Serial.println("  i         -> Print live sensor & BLE status");
    Serial.println("  h         -> Show this help menu");
    Serial.println("═══════════════════════════════════════════════════════════\n");
}

void handleSerialCommands() {
    if (!Serial.available()) return;

    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) return;

    char cmd = tolower(line.charAt(0));
    if (cmd == 't') {
        tareScale();
    } else if (cmd == 'c') {
        String arg = line.substring(1);
        arg.trim();
        float knownGrams = arg.toFloat();
        calibrateScale(knownGrams);
    } else if (cmd == 's') {
        int deductMl = min(50, currentWaterLevel);
        if (deductMl > 0) {
            currentWaterLevel  -= deductMl;
            latestWeightGrams   = (float)currentWaterLevel + containerTareGrams;
            lastSipAmount       = deductMl;
            totalConsumedToday += deductMl;
            isBottleAtRest      = true;
            Serial.printf("💧 [TEST DEDUCTION] Deducted -%d ml | Bottle Level: %d / %d ml | Daily Total: %d ml\n",
                          deductMl, currentWaterLevel, bottleCapacity, totalConsumedToday);
            publishTelemetry(true);
        } else {
            Serial.println("🚰 Bottle is empty (0 ml)! Send 'r' to refill to 500 ml.");
        }
    } else if (cmd == 'r') {
        currentWaterLevel = BOTTLE_CAPACITY_ML;
        latestWeightGrams = (float)BOTTLE_CAPACITY_ML + containerTareGrams;
        isBottleAtRest    = true;
        Serial.printf("🚰 [REFILL] Bottle water level reset to %d / %d ml (0.50 L)\n",
                      currentWaterLevel, bottleCapacity);
        publishTelemetry(true);
    } else if (cmd == 'i') {
        printDeviceStatus();
    } else if (cmd == 'h' || cmd == '?') {
        printHelp();
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  ARDUINO SETUP
// ═══════════════════════════════════════════════════════════════════════════
void setup() {
    Serial.begin(115200);
    delay(200);

    // 1. Configure Status LED on GPIO 2
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, LOW);

    Serial.println("\n╔══════════════════════════════════════════════════════════╗");
    Serial.println("║     SipSense Smart Water Bottle - ESP32 BLE Firmware     ║");
    Serial.println("║     500 ml Capacity + DHT11 (GPIO19) + HX711 (GPIO21/22) ║");
    Serial.println("╚══════════════════════════════════════════════════════════╝");

    // 2. Initialize Physical DHT11 Sensor
    dht.begin();
    Serial.printf("✅ [DHT11] Initialized on GPIO %d\n", DHT_PIN);

    // 3. Initialize Physical HX711 Load Cell Amplifier
    scale.begin(HX711_DT_PIN, HX711_SCK_PIN);
    if (loadCalibration()) {
        scale.set_scale(calibrationFactor);
        scale.set_offset(zeroOffset);
        Serial.printf("✅ [HX711] Loaded saved calibration (factor=%.4f, offset=%ld)\n",
                      calibrationFactor, zeroOffset);
    } else {
        Serial.println("ℹ️  [HX711] Not calibrated yet. Bottle starts at 500 ml / 500 ml.");
        Serial.println("ℹ️  Send 't' to tare, 'c 500' to calibrate, or 's' to test 50ml deduction.");
        if (scale.wait_ready_timeout(800)) {
            scale.set_scale();
            scale.tare(10);
            zeroOffset = scale.get_offset();
        }
    }

    // 4. Initialize BLE Server & Advertising
    BLEDevice::init(DEVICE_NAME);
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new BottleServerCallbacks());

    BLEService* pService = pServer->createService(SERVICE_UUID);

    pTelemetryCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_READ |
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_NOTIFY
    );

    BLE2902* p2902 = new BLE2902();
    p2902->setNotifications(true);
    pTelemetryCharacteristic->addDescriptor(p2902);

    pService->start();

    BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    pAdvertising->setMinPreferred(0x12);
    BLEDevice::startAdvertising();

    Serial.println("📡 [BLE] Broadcasting as 'SipSense-Bottle'...");
    Serial.println("💡 [LED] Blinking GPIO 2 while waiting for manual connection from app.");
    printHelp();
}

// ═══════════════════════════════════════════════════════════════════════════
//  ARDUINO MAIN LOOP
// ═══════════════════════════════════════════════════════════════════════════
void loop() {
    const unsigned long now = millis();

    // 1. Update LED (Blinks when disconnected/advertising, Steady ON when connected)
    updateLedIndicator();

    // 2. Check Serial Monitor commands ('t', 'c <grams>', 's', 'r', 'i', 'h')
    handleSerialCommands();

    // 3. Handle BLE connection state transitions
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
        connectedAtMs      = now;
        pendingFirstNotify = true;
    }

    if (!deviceConnected && oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
        pendingFirstNotify = false;
    }

    // 4. Send initial telemetry snapshot right after mobile app enables notifications
    if (deviceConnected && pendingFirstNotify && (now - connectedAtMs >= FIRST_NOTIFY_DELAY_MS)) {
        pendingFirstNotify = false;
        readDht11Sensor();
        readScaleSensor();
        publishTelemetry(true);
    }

    // 5. Read physical HX711 load cell every 250ms
    if (now - lastScaleReadMs >= SCALE_SAMPLE_INTERVAL_MS) {
        lastScaleReadMs = now;
        readScaleSensor();
    }

    // 6. Read physical DHT11 ambient temperature & humidity every 2000ms
    if (now - lastDhtReadMs >= DHT_SAMPLE_INTERVAL_MS) {
        lastDhtReadMs = now;
        readDht11Sensor();
    }

    // 7. Periodically push live telemetry over BLE every 2000ms while connected
    if (deviceConnected && (now - lastTelemetryPushMs >= NOTIFY_INTERVAL_MS)) {
        lastTelemetryPushMs = now;
        publishTelemetry(false);
    }
}
