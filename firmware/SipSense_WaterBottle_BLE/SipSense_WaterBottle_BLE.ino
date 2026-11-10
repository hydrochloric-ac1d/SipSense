/*
 * =========================================================================================
 *  SipSense_WaterBottle_BLE.ino
 *  MAIN ESP32 PRODUCTION FIRMWARE for SipSense Smart Water Bottle Ecosystem
 * =========================================================================================
 *  Hardware Connections:
 *    • Status LED:   GPIO 2  (Blinks 350ms when broadcasting; Steady ON when BLE connected)
 *    • DHT11 Sensor: GPIO 19 (Measures ambient temperature & humidity)
 *    • HX711 Amp:    DT = GPIO 21, SCK = GPIO 22 (Measures bottle weight in grams)
 *
 *  Core Features:
 *    1. Two-State Machine:
 *       - STATE_IDLE: Bottle on base (> 30g). Monitors resting weight.
 *       - STATE_LIFTED: Bottle picked up (< 30g). Locks Weight_Start. No drinking data sent.
 *       - STATE_SETTLING: Bottle put down (> 30g). Waits 1.2s for vibrations to settle,
 *         reads Weight_End, calculates: Water_Drank = Weight_Start - Weight_End.
 *         Only logs drink event if Water_Drank >= 5 ml. Detects refills if weight increased.
 *    2. Remote BLE & Serial Commands:
 *       - "t" or "tare"     : Zeroes empty base platform (saved to flash memory).
 *       - "r" or "refill"   : Sets bottle water level to 100% capacity and updates reference weight.
 *       - "cap <ml>"        : Dynamically configures bottle capacity (e.g. "cap 1000", saved to flash).
 *       - "reset" / "z"     : Resets today's water intake progress to 0 ml.
 *       - "c <grams>"       : Calibrates load cell using reference weight (e.g. "c 1000").
 *       - "i"               : Prints live hardware status, raw ADC counts, and current weight.
 *       - "s"               : Test drinking deduction (-50 ml).
 *    3. Inverted Load Cell Protection:
 *       - Automatically detects and corrects negative polarity if downward pressure
 *         decreases ADC counts.
 *    4. Arduino ESP32 Core Compatibility:
 *       - Fully compatible with both ESP32 Core v2.x and v3.x.
 * =========================================================================================
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Preferences.h>
#include <DHT.h>
#include <HX711.h>

// ═══════════════════════════════════════════════════════════════════════════
//  PIN ASSIGNMENTS & HARDWARE CONFIGURATION
// ═══════════════════════════════════════════════════════════════════════════
#define LED_PIN                     2   // Onboard Blue LED (GPIO 2)
#define DHT_PIN                    19   // DHT11 Data Pin (GPIO 19)
#define DHT_TYPE                DHT11   // DHT11 Sensor
#define HX711_DT_PIN               21   // HX711 Serial Data Output (GPIO 21)
#define HX711_SCK_PIN              22   // HX711 Serial Clock Input (GPIO 22)

// ═══════════════════════════════════════════════════════════════════════════
//  BLE GATT SERVICE & CHARACTERISTIC UUIDS (Must match Android app)
// ═══════════════════════════════════════════════════════════════════════════
#define DEVICE_NAME             "SipSense-Bottle"
#define SERVICE_UUID            "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID     "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// ═══════════════════════════════════════════════════════════════════════════
//  HYDRATION STATE MACHINE THRESHOLDS & TIMERS
// ═══════════════════════════════════════════════════════════════════════════
const float         BOTTLE_PRESENT_THRESHOLD_GRAMS = 30.0f; // > 30g means bottle is resting on base
const float         SIP_MIN_THRESHOLD_GRAMS        =  5.0f; // Ignore changes < 5g (noise / drop)
const float         REFILL_MIN_THRESHOLD_GRAMS     = 25.0f; // Weight increase >= 25g is refill
const unsigned long SETTLE_DELAY_MS                = 1200;  // 1.2s settle delay after bottle put down
const unsigned long LED_BLINK_INTERVAL_MS          =  350;  // 350ms toggle for advertising LED
const unsigned long DHT_SAMPLE_INTERVAL_MS         = 2000;  // Read DHT11 every 2s
const unsigned long SCALE_SAMPLE_INTERVAL_MS       =  250;  // Read HX711 every 250ms
const unsigned long TELEMETRY_PUSH_INTERVAL_MS     = 1000;  // BLE notify every 1s
const unsigned long FIRST_NOTIFY_DELAY_MS          =  600;  // Delay before first notify upon connect

const int           DEFAULT_BOTTLE_CAPACITY_ML     = 1000;  // Default capacity if not configured

// State machine enum
enum BottleState {
    STATE_IDLE,      // Bottle is resting stably on base platform (> 30g)
    STATE_LIFTED,    // Bottle is removed from base platform (< 30g)
    STATE_SETTLING   // Bottle was just returned to base; waiting for vibration to settle
};

// ═══════════════════════════════════════════════════════════════════════════
//  GLOBAL SENSORS & NVS STORAGE
// ═══════════════════════════════════════════════════════════════════════════
DHT         dht(DHT_PIN, DHT_TYPE);
HX711       scale;
Preferences prefs;

const char* PREFS_NAMESPACE = "sipsense";
const char* KEY_CALIBRATED  = "is_cal";
const char* KEY_FACTOR      = "cal_factor";
const char* KEY_OFFSET      = "zero_offset";
const char* KEY_CAPACITY    = "bottle_cap";

// ═══════════════════════════════════════════════════════════════════════════
//  GLOBAL SYSTEM STATE
// ═══════════════════════════════════════════════════════════════════════════
BLEServer*          pServer                  = nullptr;
BLECharacteristic*  pTelemetryCharacteristic = nullptr;
bool                deviceConnected          = false;
bool                oldDeviceConnected       = false;
bool                pendingFirstNotify       = false;
unsigned long       connectedAtMs            = 0;

// LED State
unsigned long       lastLedBlinkMs           = 0;
bool                ledBlinkState            = false;

// DHT11 State
float               currentTempC             = 24.0f;
float               currentHumidity          = 55.0f;
bool                isDhtReadFailed          = false;
int                 dhtFailStreak            = 0;

// HX711 Load Cell State
float               calibrationFactor        = 420.0f; // Default counts per gram
long                zeroOffset               = 0;
bool                isCalibrated             = false;
bool                isHx711ReadFailed        = false;
long                latestRawCounts          = 0;
float               latestWeightGrams        = 0.0f;
float               containerTareGrams       = 0.0f;
bool                hasBottleBeenPlaced      = false;

// State Machine Variables
BottleState         bottleState              = STATE_LIFTED; // Starts empty/lifted
float               weightStartGrams         = 0.0f;
float               lastStableWeightGrams    = 0.0f;
unsigned long       settleStartTimeMs        = 0;

// Hydration Metrics
int                 currentWaterLevel        = 0; // Starts at 0 ml until bottle is placed
int                 bottleCapacity           = DEFAULT_BOTTLE_CAPACITY_ML;
bool                isBottleAtRest           = false;
int                 lastSipAmount            = 0;
int                 totalConsumedToday       = 0;
int                 batteryLevel             = 95;

// Loop Timers
unsigned long       lastDhtReadMs            = 0;
unsigned long       lastScaleReadMs          = 0;
unsigned long       lastTelemetryPushMs      = 0;

// Forward Declarations
void updateLedIndicator();
void readDht11Sensor();
void readScaleSensor();
void publishTelemetry(bool forceImmediate = false);
void handleSerialCommands();
void tareScale();
void calibrateScale(float knownWeightGrams);
void setBottleCapacity(int newCap);
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
        digitalWrite(LED_PIN, HIGH); // Solid ON when connected
        Serial.println("\n╔══════════════════════════════════════════════════════════╗");
        Serial.println("║  [BLE] Mobile App CONNECTED!                             ║");
        Serial.println("║  [LED] GPIO 2 status: SOLID ON                           ║");
        Serial.println("╚══════════════════════════════════════════════════════════╝");
    }

    void onDisconnect(BLEServer* server) override {
        deviceConnected = false;
        Serial.println("\n╔══════════════════════════════════════════════════════════╗");
        Serial.println("║  [BLE] Mobile App DISCONNECTED!                          ║");
        Serial.println("║  [LED] GPIO 2 status: BLINKING (Broadcasting BLE)        ║");
        Serial.println("╚══════════════════════════════════════════════════════════╝");
        // Restart advertising so the app can scan and reconnect
        server->startAdvertising();
    }
};

// ═══════════════════════════════════════════════════════════════════════════
//  BLE CHARACTERISTIC COMMAND CALLBACKS
// ═══════════════════════════════════════════════════════════════════════════
class BottleCharCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pChar) override {
        // Universal compatibility for both Arduino ESP32 core v2.x (std::string) and v3.x (String)
        String line = "";
        #if defined(ESP_ARDUINO_VERSION_MAJOR) && (ESP_ARDUINO_VERSION_MAJOR >= 3)
            line = pChar->getValue();
        #else
            line = String(pChar->getValue().c_str());
        #endif
        line.trim();

        if (line.length() > 0) {
            Serial.printf("\n📥 [BLE RX] Command received: '%s'\n", line.c_str());

            if (line.equalsIgnoreCase("t") || line.equalsIgnoreCase("tare") || line.startsWith("t") || line.startsWith("tare")) {
                Serial.println("⚖️  [BLE CMD] Tare scale requested!");
                tareScale();
            } else if (line.startsWith("cap ") || line.startsWith("cap:") || line.startsWith("capacity")) {
                int sep = line.indexOf(' ');
                if (sep < 0) sep = line.indexOf(':');
                if (sep >= 0) {
                    int val = line.substring(sep + 1).toInt();
                    if (val >= 100 && val <= 5000) {
                        setBottleCapacity(val);
                    }
                }
            } else if (line.equalsIgnoreCase("r") || line.equalsIgnoreCase("refill")) {
                currentWaterLevel     = bottleCapacity;
                hasBottleBeenPlaced   = true;
                isBottleAtRest        = true;
                bottleState           = STATE_IDLE;
                if (scale.is_ready()) {
                    latestWeightGrams     = scale.get_units(3);
                    lastStableWeightGrams = latestWeightGrams;
                } else {
                    latestWeightGrams     = (float)bottleCapacity;
                    lastStableWeightGrams = (float)bottleCapacity;
                }
                Serial.printf("🚰 [BLE CMD] Bottle refilled to %d ml! (Weight: %.1fg)\n", bottleCapacity, latestWeightGrams);
                publishTelemetry(true);
            } else if (line.equalsIgnoreCase("reset") || line.equalsIgnoreCase("reset_goal") || line.equalsIgnoreCase("g0") || line.equalsIgnoreCase("z")) {
                totalConsumedToday = 0;
                lastSipAmount      = 0;
                Serial.println("🔄 [BLE CMD] Daily water intake reset to 0 ml!");
                publishTelemetry(true);
            }
        }
    }
};

// ═══════════════════════════════════════════════════════════════════════════
//  BOTTLE CAPACITY CONTROLLER (Dynamic from app or flash)
// ═══════════════════════════════════════════════════════════════════════════
void setBottleCapacity(int newCap) {
    if (newCap < 100 || newCap > 5000) return;
    bottleCapacity = newCap;

    if (hasBottleBeenPlaced && isBottleAtRest) {
        if (currentWaterLevel > bottleCapacity) {
            currentWaterLevel = bottleCapacity;
        }
    } else if (!hasBottleBeenPlaced) {
        currentWaterLevel = 0; // Empty platform stays 0 ml
    }

    prefs.begin(PREFS_NAMESPACE, false);
    prefs.putInt(KEY_CAPACITY, bottleCapacity);
    prefs.end();

    Serial.printf("\n🍾 [CAPACITY] Bottle capacity set to %d ml (saved to flash | Level: %d ml)\n",
                  bottleCapacity, currentWaterLevel);
    publishTelemetry(true);
}

// ═══════════════════════════════════════════════════════════════════════════
//  LED CONTROLLER (Blinks when disconnected, Solid ON when connected)
// ═══════════════════════════════════════════════════════════════════════════
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

// ═══════════════════════════════════════════════════════════════════════════
//  NVS CALIBRATION STORAGE
// ═══════════════════════════════════════════════════════════════════════════
bool loadCalibration() {
    prefs.begin(PREFS_NAMESPACE, true);
    bool saved   = prefs.getBool(KEY_CALIBRATED, false);
    float factor = prefs.getFloat(KEY_FACTOR, 420.0f);
    long offset  = prefs.getLong(KEY_OFFSET, 0);
    prefs.end();

    if (saved && fabs(factor) >= 10.0f) {
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
//  HX711 TARE & CALIBRATION
// ═══════════════════════════════════════════════════════════════════════════
void tareScale() {
    Serial.println("\n⚖️  [HX711] Taring scale... Ensure the platform is completely EMPTY.");
    if (!scale.wait_ready_timeout(1000)) {
        Serial.println("❌ [HX711] Not responding! Check wiring: DT=GPIO21, SCK=GPIO22, VCC, GND.");
        isHx711ReadFailed = true;
        return;
    }

    scale.set_scale(calibrationFactor);
    scale.tare(15);
    zeroOffset = scale.get_offset();
    scale.set_scale(calibrationFactor);

    saveCalibration(calibrationFactor, zeroOffset);

    currentWaterLevel     = 0;
    weightStartGrams      = 0.0f;
    lastStableWeightGrams = 0.0f;
    hasBottleBeenPlaced   = false;
    isBottleAtRest        = false;
    bottleState           = STATE_LIFTED; // Ready for bottle placement
    latestWeightGrams     = 0.0f;
    isHx711ReadFailed     = false;

    Serial.printf("✅ [HX711] Tare complete! Zero offset = %ld. Base is 0.0g (Level: 0 / %d ml).\n", zeroOffset, bottleCapacity);
    Serial.println("👉 Platform is now 0g. Place your filled water bottle onto the base.");
    publishTelemetry(true);
}

void calibrateScale(float knownWeightGrams) {
    if (knownWeightGrams <= 0.0f) {
        Serial.println("⚠️  [HX711] Known weight must be > 0 grams (e.g. 'c 1000').");
        return;
    }

    Serial.printf("\n⚖️  [HX711] Calibrating with %.1f g... Hold weight steady.\n", knownWeightGrams);
    if (!scale.wait_ready_timeout(1000)) {
        Serial.println("❌ [HX711] Timeout waiting for HX711 during calibration.");
        isHx711ReadFailed = true;
        return;
    }

    long rawAvg = scale.read_average(15);
    long netCounts = rawAvg - zeroOffset;

    if (abs(netCounts) < 50) {
        Serial.println("⚠️  [HX711] Weight change too small! Run 't' with empty scale first, then place weight and run 'c <grams>'.");
        return;
    }

    float newFactor = (float)netCounts / knownWeightGrams;
    scale.set_scale(newFactor);
    scale.set_offset(zeroOffset);
    saveCalibration(newFactor, zeroOffset);

    float verifiedGrams = scale.get_units(5);
    latestWeightGrams   = max(0.0f, verifiedGrams);
    hasBottleBeenPlaced = true;
    isBottleAtRest      = true;
    bottleState         = STATE_IDLE;
    lastStableWeightGrams = latestWeightGrams;
    currentWaterLevel   = bottleCapacity;
    isHx711ReadFailed   = false;

    Serial.println("╔══════════════════════════════════════════════════════════╗");
    Serial.printf("║  ✅ CALIBRATION SAVED TO FLASH!                          ║\n");
    Serial.printf("║  New Calibration Factor : %.4f counts/gram             ║\n", newFactor);
    Serial.printf("║  Zero Offset            : %ld                         ║\n", zeroOffset);
    Serial.printf("║  Verified Scale Weight  : %.1f g                        ║\n", verifiedGrams);
    Serial.printf("║  Water Level Initialized: %d / %d ml                    ║\n", currentWaterLevel, bottleCapacity);
    Serial.println("╚══════════════════════════════════════════════════════════╝");
    publishTelemetry(true);
}

// ═══════════════════════════════════════════════════════════════════════════
//  PHYSICAL SENSOR READERS
// ═══════════════════════════════════════════════════════════════════════════
void readDht11Sensor() {
    float h = dht.readHumidity();
    float t = dht.readTemperature();

    if (isnan(h) || isnan(t)) {
        dhtFailStreak++;
        if (dhtFailStreak >= 3) {
            isDhtReadFailed = true;
        }
    } else {
        dhtFailStreak   = 0;
        isDhtReadFailed = false;
        currentTempC    = t;
        currentHumidity = h;
    }
}

static unsigned long lastReadyWarnMs = 0;
static unsigned long lastWeightLogMs = 0;

void readScaleSensor() {
    if (!scale.is_ready()) {
        isHx711ReadFailed = true;
        if (millis() - lastReadyWarnMs >= 4000) {
            lastReadyWarnMs = millis();
            Serial.println("⚠️  [HX711 NOT READY] Scale is not responding! Check wiring: DT=GPIO21, SCK=GPIO22, VCC, GND.");
        }
        return;
    }

    isHx711ReadFailed = false;
    latestRawCounts   = scale.read();

    // Read current weight in grams (2-sample quick average)
    float weight = scale.get_units(2);

    // Auto-detect & auto-correct inverted load cell polarity
    if (weight < -25.0f) {
        calibrationFactor = -calibrationFactor;
        scale.set_scale(calibrationFactor);
        weight = scale.get_units(2);
        Serial.printf("🔄 [HX711] Inverted polarity auto-corrected! Flipped factor to: %.2f (Weight: %.1fg)\n",
                      calibrationFactor, weight);
    }

    if (weight < 0.0f && weight > -8.0f) {
        weight = 0.0f;
    }

    // Print live weight diagnostics to Serial Monitor every 2.5 seconds
    if (millis() - lastWeightLogMs >= 2500) {
        lastWeightLogMs = millis();
        Serial.printf("⚖️  [HX711 Live] Weight: %.1fg | Raw ADC: %ld | ZeroOffset: %ld | State: %s\n",
                      weight, latestRawCounts, zeroOffset,
                      bottleState == STATE_IDLE ? "BOTTLE_ON_BASE" : (bottleState == STATE_SETTLING ? "SETTLING" : "EMPTY_BASE"));
    }

    // ── Two-State Event Machine ───────────────────────────────────────────
    switch (bottleState) {
        case STATE_IDLE: {
            if (weight >= BOTTLE_PRESENT_THRESHOLD_GRAMS) {
                // State 1: Bottle on Base (> 30g).
                // Only update stable resting weight when reading is steady (not in the middle of being lifted)
                if (fabs(weight - lastStableWeightGrams) < 4.0f) {
                    lastStableWeightGrams = weight;
                } else if (weight > lastStableWeightGrams) {
                    // Refill while sitting on base
                    lastStableWeightGrams = weight;
                }
                latestWeightGrams = weight;
                isBottleAtRest    = true;
            } else {
                // State 2: Pick Up -> Weight drops to near zero (< 30g).
                // Locks true resting weight before the hand started lifting it
                bottleState       = STATE_LIFTED;
                weightStartGrams  = lastStableWeightGrams;
                isBottleAtRest    = false;
                latestWeightGrams = 0.0f;
                lastSipAmount     = 0;

                Serial.printf("\n🥤 [STATE: PICK UP] Bottle lifted from base!\n");
                Serial.printf("   Weight_Start locked at: %.1f g | Current Level: %d ml\n", weightStartGrams, currentWaterLevel);
                Serial.println("   (No drinking data sent yet. Waiting for bottle to be replaced...)");
                publishTelemetry(true);
            }
            break;
        }

        case STATE_LIFTED: {
            latestWeightGrams = 0.0f;
            isBottleAtRest    = false;

            if (weight >= BOTTLE_PRESENT_THRESHOLD_GRAMS) {
                // Put Down -> Weight returns above 30g!
                bottleState       = STATE_SETTLING;
                settleStartTimeMs = millis();
                Serial.printf("\n⚖️  [STATE: PUT DOWN] Bottle returned to base! Detected: %.1f g\n", weight);
                Serial.printf("   Waiting %lu ms for mechanical settling...\n", SETTLE_DELAY_MS);
            }
            break;
        }

        case STATE_SETTLING: {
            if (millis() - settleStartTimeMs >= SETTLE_DELAY_MS) {
                float weightEndGrams = scale.get_units(5);
                if (weightEndGrams < BOTTLE_PRESENT_THRESHOLD_GRAMS) {
                    bottleState = STATE_LIFTED;
                    return;
                }

                latestWeightGrams = weightEndGrams;

                // First placement after boot or tare
                if (!hasBottleBeenPlaced || weightStartGrams < BOTTLE_PRESENT_THRESHOLD_GRAMS) {
                    hasBottleBeenPlaced   = true;
                    currentWaterLevel     = bottleCapacity; // Set to full bottle capacity upon placement!
                    lastStableWeightGrams = weightEndGrams;
                    isBottleAtRest        = true;
                    bottleState           = STATE_IDLE;
                    Serial.printf("🍾 [INITIAL PLACEMENT] Bottle placed on base: %.1f g | Initialized to %d / %d ml\n",
                                  weightEndGrams, currentWaterLevel, bottleCapacity);
                    publishTelemetry(true);
                    return;
                }

                // Calculate: Water_Drank = Weight_Start - Weight_End
                float waterDrank = weightStartGrams - weightEndGrams;
                Serial.printf("\n📊 [CALCULATE]\n");
                Serial.printf("   Weight_Start : %.1f g\n", weightStartGrams);
                Serial.printf("   Weight_End   : %.1f g\n", weightEndGrams);
                Serial.printf("   Water_Drank  : %.1f g\n", waterDrank);

                if (waterDrank >= SIP_MIN_THRESHOLD_GRAMS) {
                    int drinkMl = (int)lroundf(waterDrank);
                    currentWaterLevel  = max(0, currentWaterLevel - drinkMl);
                    lastSipAmount      = drinkMl;
                    totalConsumedToday += drinkMl;
                    isBottleAtRest     = true;
                    lastStableWeightGrams = weightEndGrams;
                    bottleState        = STATE_IDLE;

                    Serial.printf("💧 [DRINK DETECTED] Water drank: %d ml | New Level: %d/%d ml | Daily Total: %d ml\n",
                                  drinkMl, currentWaterLevel, bottleCapacity, totalConsumedToday);
                    publishTelemetry(true);
                } else if (waterDrank <= -REFILL_MIN_THRESHOLD_GRAMS) {
                    int refillMl = (int)lroundf(-waterDrank);
                    currentWaterLevel = min(bottleCapacity, currentWaterLevel + refillMl);
                    lastSipAmount     = 0;
                    isBottleAtRest    = true;
                    lastStableWeightGrams = weightEndGrams;
                    bottleState       = STATE_IDLE;

                    Serial.printf("🚰 [REFILL DETECTED] Added: +%d ml | New Level: %d/%d ml\n",
                                  refillMl, currentWaterLevel, bottleCapacity);
                    publishTelemetry(true);
                } else {
                    lastSipAmount     = 0;
                    isBottleAtRest    = true;
                    lastStableWeightGrams = weightEndGrams;
                    bottleState       = STATE_IDLE;
                    Serial.println("ℹ️  [NO DRINK] Negligible change (< 5g). Ignored as noise.");
                    publishTelemetry(true);
                }
            }
            break;
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  BLE TELEMETRY PUBLISHER
// ═══════════════════════════════════════════════════════════════════════════
void publishTelemetry(bool forceImmediate) {
    if (!deviceConnected && !forceImmediate) return;
    if (pTelemetryCharacteristic == nullptr) return;

    int tempRounded = (int)lroundf(currentTempC);
    int humRounded  = (int)lroundf(currentHumidity);

    char payload[384];
    if (isDhtReadFailed) {
        snprintf(
            payload,
            sizeof(payload),
            "{\"waterLevel\":%d,\"bottleCapacity\":%d,\"batteryLevel\":%d,"
            "\"tempSensorStatus\":\"Read Failed\",\"readFailed\":true,"
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

    lastSipAmount = 0; // Clear one-shot drink notification

    if (forceImmediate) {
        Serial.printf("📡 [BLE TX Immediate] %s\n", payload);
    } else {
        Serial.printf("📡 [BLE TX] Water: %d/%dml | Weight: %.1fg | Temp: %.1fC | Batt: %d%% | AtRest: %s\n",
                      currentWaterLevel, bottleCapacity, latestWeightGrams, currentTempC, batteryLevel,
                      isBottleAtRest ? "YES" : "NO");
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  SERIAL COMMAND HANDLERS
// ═══════════════════════════════════════════════════════════════════════════
void printDeviceStatus() {
    Serial.println("\n──────────────────── SIPSENSE STATUS ────────────────────");
    Serial.printf("  BLE Connection    : %s\n", deviceConnected ? "CONNECTED (LED Steady ON)" : "BROADCASTING (LED Blinking)");
    Serial.printf("  DHT11 (GPIO 19)   : %s (Temp: %.1f°C, Humidity: %.0f%%)\n",
                  isDhtReadFailed ? "READ FAILED" : "OK", currentTempC, currentHumidity);
    Serial.printf("  HX711 (GPIO21/22) : %s | Calibrated: %s\n",
                  isHx711ReadFailed ? "READ FAILED" : "OK", isCalibrated ? "YES" : "NO");
    Serial.printf("  Scale Factor/Zero : factor=%.4f, offset=%ld\n", calibrationFactor, zeroOffset);

    if (scale.is_ready()) {
        long raw = scale.read();
        float units = scale.get_units(3);
        Serial.printf("  Live HX711 ADC    : raw=%ld, zeroOffset=%ld, delta=%ld\n", raw, zeroOffset, raw - zeroOffset);
        Serial.printf("  Live Calculated   : %.2f grams (Detection threshold: >= %.1fg)\n", units, BOTTLE_PRESENT_THRESHOLD_GRAMS);
    } else {
        Serial.println("  Live HX711 ADC    : ❌ NOT READY! Check wiring: DT=GPIO21, SCK=GPIO22, VCC, GND.");
    }

    Serial.printf("  Bottle State      : %s (hasPlaced=%s, atRest=%s)\n",
                  bottleState == STATE_IDLE ? "IDLE (On Base)" : (bottleState == STATE_SETTLING ? "SETTLING" : "LIFTED / EMPTY"),
                  hasBottleBeenPlaced ? "YES" : "NO", isBottleAtRest ? "YES" : "NO");
    Serial.printf("  Water Level       : %d ml / %d ml (%.2f L)\n",
                  currentWaterLevel, bottleCapacity, currentWaterLevel / 1000.0f);
    Serial.printf("  Daily Consumed    : %d ml\n", totalConsumedToday);
    Serial.println("─────────────────────────────────────────────────────────\n");
}

void printHelp() {
    Serial.println("\n══════════ SERIAL MONITOR COMMANDS (115200 baud) ══════════");
    Serial.println("  t         -> Tare / Zero the scale (empty platform = 0g)");
    Serial.println("  r         -> Refill / Set bottle to full capacity (e.g. 1000ml)");
    Serial.println("  c <grams> -> Calibrate scale with known weight (e.g. c 1000)");
    Serial.println("  z         -> Reset today's consumed drinking water to 0 ml");
    Serial.println("  s         -> Deduct 50 ml from water level (test drinking)");
    Serial.println("  i         -> Print live sensor & BLE status and raw ADC counts");
    Serial.println("  h or ?    -> Print this help menu");
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
            Serial.println("🚰 Bottle is empty (0 ml)! Send 'r' to refill.");
        }
    } else if (cmd == 'r') {
        currentWaterLevel     = bottleCapacity;
        hasBottleBeenPlaced   = true;
        isBottleAtRest        = true;
        bottleState           = STATE_IDLE;
        if (scale.is_ready()) {
            latestWeightGrams     = scale.get_units(3);
            lastStableWeightGrams = latestWeightGrams;
        } else {
            latestWeightGrams     = (float)bottleCapacity;
            lastStableWeightGrams = (float)bottleCapacity;
        }
        Serial.printf("🚰 [REFILL] Bottle water level reset to %d / %d ml (%.2f L, Weight: %.1fg)\n",
                      currentWaterLevel, bottleCapacity, (float)bottleCapacity / 1000.0f, latestWeightGrams);
        publishTelemetry(true);
    } else if (cmd == 'z') {
        totalConsumedToday = 0;
        lastSipAmount      = 0;
        Serial.println("🔄 [RESET] Today's consumed water reset to 0 ml!");
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
    Serial.println("║     SipSense Smart Water Bottle - Main BLE Firmware      ║");
    Serial.println("║     Event State Machine + DHT11 (IO19) + HX711 (IO21/22) ║");
    Serial.println("╚══════════════════════════════════════════════════════════╝");

    // 2. Initialize Physical DHT11 Sensor
    dht.begin();
    Serial.printf("✅ [DHT11] Initialized on GPIO %d\n", DHT_PIN);

    // 3. Initialize Physical HX711 Load Cell Amplifier
    scale.begin(HX711_DT_PIN, HX711_SCK_PIN);

    prefs.begin(PREFS_NAMESPACE, true);
    if (prefs.isKey(KEY_CAPACITY)) {
        bottleCapacity = prefs.getInt(KEY_CAPACITY, DEFAULT_BOTTLE_CAPACITY_ML);
        Serial.printf("📦 [NVS] Loaded saved bottle capacity: %d ml\n", bottleCapacity);
    }
    prefs.end();

    // Start with 0 ml when empty until bottle is placed
    currentWaterLevel = 0;
    latestWeightGrams = 0.0f;
    isBottleAtRest    = false;
    bottleState       = STATE_LIFTED;

    if (loadCalibration()) {
        scale.set_scale(calibrationFactor);
        scale.set_offset(zeroOffset);
        Serial.printf("✅ [HX711] Loaded saved calibration (factor=%.4f, offset=%ld)\n",
                      calibrationFactor, zeroOffset);
    } else {
        Serial.println("ℹ️  [HX711] Using default load cell factor (420.0 counts/gram).");
        Serial.println("ℹ️  Send 't' to tare empty base, 'r' when filled bottle is placed.");
        calibrationFactor = 420.0f;
        scale.set_scale(calibrationFactor);
        if (scale.wait_ready_timeout(800)) {
            scale.tare(10);
            zeroOffset = scale.get_offset();
        }
        isCalibrated = true;
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
        BLECharacteristic::PROPERTY_WRITE_NR |
        BLECharacteristic::PROPERTY_NOTIFY
    );
    pTelemetryCharacteristic->setCallbacks(new BottleCharCallbacks());

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
    Serial.println("💡 [LED] Blinking GPIO 2 while waiting for connection from app.");
    printHelp();
}

// ═══════════════════════════════════════════════════════════════════════════
//  ARDUINO MAIN LOOP
// ═══════════════════════════════════════════════════════════════════════════
void loop() {
    const unsigned long now = millis();

    // 1. Update LED indicator
    updateLedIndicator();

    // 2. Handle incoming Serial Monitor commands
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

    // 4. Send initial telemetry snapshot right after connection
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

    // 6. Read physical DHT11 sensor every 2000ms
    if (now - lastDhtReadMs >= DHT_SAMPLE_INTERVAL_MS) {
        lastDhtReadMs = now;
        readDht11Sensor();
    }

    // 7. Periodic telemetry push via BLE notify every 1000ms
    if (now - lastTelemetryPushMs >= TELEMETRY_PUSH_INTERVAL_MS) {
        lastTelemetryPushMs = now;
        publishTelemetry(false);
    }

    delay(10);
}
