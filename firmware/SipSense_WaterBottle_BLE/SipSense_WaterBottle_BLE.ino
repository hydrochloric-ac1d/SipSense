/*
 * SipSense_WaterBottle_BLE.ino
 *
 * ESP32 Smart Water Bottle Firmware (Physical DHT11 + Physical HX711)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  REQUIRED ARDUINO LIBRARIES:
 * ───────────────────────────────────────────────────────────────────────────
 *  Install both libraries in Arduino IDE (Tools -> Manage Libraries...):
 *    1. "DHT sensor library" by Adafruit  (Click "Install All" for unified sensor)
 *    2. "HX711 Arduino Library" by Bogdan Necula (bogde)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  STATUS LED BEHAVIOR:
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
 *  HARDWARE PIN CONNECTIONS (ESP32 DevKit V1 - USB-C Powered):
 * ───────────────────────────────────────────────────────────────────────────
 *  - GPIO 2  : On-board Blue LED (Status Indicator: Blinking=Adv, Steady=Conn)
 *  - GPIO 19 : DHT11 DATA (Ambient Temperature & Humidity)
 *  - GPIO 21 : HX711 DT / DOUT (Load Cell Data Out)
 *  - GPIO 22 : HX711 SCK / PD_SCK (Load Cell Clock)
 *  - 3V3     : Sensor VCC rails (DHT11 + HX711)
 *  - GND     : Sensor GND rails
 *
 *  HX711 to 4-Wire Load Cell:
 *    E+ -> Red | E- -> Black | A- -> White | A+ -> Green
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  SERIAL COMMANDS (115200 baud, newline enabled):
 * ───────────────────────────────────────────────────────────────────────────
 *  - 't' -> Tare / zero the scale platform
 *  - 'c' -> Run 3-step interactive calibration with a known weight
 *  - 's' -> Save calibration to Flash NVS
 *  - 'l' -> Reload calibration from Flash NVS
 *  - 'x' -> Erase saved calibration
 *  - 'i' -> Print live hardware sensor readings
 *  - 'h' -> Show help menu
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

// ── Pin Configuration ──────────────────────────────────────────────────────
#define LED_PIN         2       // On-board LED on ESP32 DevKit (GPIO 2)
#define DHT_PIN         19      // DHT11 Data pin (GPIO 19)
#define DHT_TYPE        DHT11   // Sensor type: DHT11

#define HX711_DT_PIN    21      // HX711 DT (Data Out)
#define HX711_SCK_PIN   22      // HX711 SCK (Clock)

// ── BLE Identifiers (Must match BleConstants.kt in SipSense Android App) ──
#define DEVICE_NAME         "SipSense-Bottle"
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// ── Flash NVS Storage Keys ────────────────────────────────────────────────
#define PREFS_NAMESPACE     "sipsense"
#define KEY_FACTOR          "hx_factor"
#define KEY_OFFSET          "hx_offset"
#define KEY_CALIBRATED      "hx_calib"

// ── Timing Parameters (Milliseconds) ───────────────────────────────────────
const unsigned long LED_BLINK_INTERVAL_MS    = 350;  // LED blink speed while advertising
const unsigned long NOTIFY_INTERVAL_MS       = 2000; // Periodic BLE telemetry push (2s)
const unsigned long DHT_SAMPLE_INTERVAL_MS   = 2000; // DHT11 sample interval (2s)
const unsigned long SCALE_SAMPLE_INTERVAL_MS = 300;  // HX711 sample rate (300ms)
const unsigned long FIRST_NOTIFY_DELAY_MS    = 250;  // Delay after connection for CCCD
const unsigned long HX711_TIMEOUT_MS         = 100;  // Non-blocking timeout
const uint8_t       TARE_SAMPLES             = 15;
const uint8_t       READ_SAMPLES             = 3;    // Fast averaging for responsive sip detection
const float         LIFT_THRESHOLD_GRAMS     = -15.0f; // Weight drop threshold indicating bottle is lifted
const float         SIP_MIN_THRESHOLD_GRAMS  = 5.0f;   // Minimum 5g drop to record a valid sip

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

// ── Sensor State (Physical DHT11 + Physical HX711) ─────────────────────────
// DHT11
float         lastTemperatureC    = 24.0f;
float         lastHumidity        = 50.0f;
bool          isDhtValid          = false;
uint8_t       dhtFailureCount     = 0;

// HX711 & Weight
float         calibrationFactor   = 1.0f;
long          zeroOffset          = 0;
bool          isCalibrated        = false;
float         lastWeightGrams     = 0.0f;
long          lastRawCounts       = 0;
bool          isHx711Valid        = false;
uint8_t       hx711FailureCount   = 0;

// Water Level & Real Sip Tracking
int           currentWaterLevel   = 0;     // Current water in bottle (ml, 1g water = 1ml)
int           bottleCapacity      = 1000;  // Bottle capacity in ml
bool          isBottleAtRest      = true;  // Resting on base platform vs lifted
float         weightBeforeLift    = 0.0f;  // Reference weight before user lifted the bottle
int           lastSipAmount       = 0;     // Last measured sip volume in ml
int           totalConsumedToday  = 0;     // Cumulative water consumed in ml
int           batteryLevel        = 100;   // 100% on USB-C power

// Timers
unsigned long lastDhtMs           = 0;
unsigned long lastScaleMs         = 0;
unsigned long lastNotifyMs        = 0;

// Forward Declarations
void publishTelemetry(bool forceImmediate = false);
void updateLedIndicator();
void handleSerialCommands();
void tareScale();
void runGuidedCalibration();
bool loadCalibration();
void saveCalibration();
void printInfo();
void printHelp();

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
        // Immediately restart BLE advertising
        server->startAdvertising();
    }
};

// ── LED Status Controller (Non-Blocking) ───────────────────────────────────
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

// ── Flash Calibration Management ───────────────────────────────────────────
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

void tareScale() {
    if (!scale.wait_ready_timeout(1000)) {
        Serial.println("[TARE] ⚠️ HX711 not responding! Check DT (GPIO 21) & SCK (GPIO 22) wiring.");
        return;
    }
    Serial.println("[TARE] Zeroing scale platform... keep platform still.");
    scale.tare(TARE_SAMPLES);
    zeroOffset = scale.get_offset();
    Serial.printf("[TARE] Complete. Zero offset = %ld\n", zeroOffset);
}

// ── Real Physical Sensor Reads: DHT11 ──────────────────────────────────────
void readDhtSensor() {
    float h = dht.readHumidity();
    float t = dht.readTemperature();

    if (isnan(h) || isnan(t)) {
        dhtFailureCount++;
        isDhtValid = false;
        if (dhtFailureCount >= 3) {
            Serial.printf("[DHT11] ⚠️ Read failed. Check wiring: VCC->3V3, GND->GND, DATA->GPIO %d\n", DHT_PIN);
        }
        return;
    }

    dhtFailureCount  = 0;
    isDhtValid       = true;
    lastTemperatureC = t;
    lastHumidity     = h;
}

// ── Real Physical Sensor Reads: HX711 & Real Sip Calculation ───────────────
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

    // Actual raw ADC counts from load cell
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
                Serial.printf("\n[BASE] 🚀 Bottle lifted! Storing reference weight: %.1f g\n", weightBeforeLift);
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
                    // REAL PHYSICAL SIP MEASURED!
                    int sipConsumed = (int) lroundf(weightDelta);
                    lastSipAmount = sipConsumed;
                    totalConsumedToday += sipConsumed;
                    Serial.printf("\n🥤 [REAL SIP MEASURED] Consumed %d ml! Today's total: %d ml\n",
                                  sipConsumed, totalConsumedToday);
                }
                publishTelemetry(true);
            }
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
        lastTemperatureC,
        lastTemperatureC,
        lastHumidity,
        isBottleAtRest ? "true" : "false",
        lastSipAmount,
        totalConsumedToday,
        deviceConnected ? "Connected" : "Broadcasting",
        deviceConnected ? "Steady" : "Blinking"
    );

    pTelemetryCharacteristic->setValue(buffer);
    if (deviceConnected) {
        pTelemetryCharacteristic->notify();
        Serial.printf("[BLE TX] Telemetry -> %s\n", buffer);
    }
}

// ── Guided 3-Step Calibration via Serial Monitor ───────────────────────────
String readLineBlocking() {
    while (Serial.available()) Serial.read(); // Clear buffer
    while (!Serial.available()) {
        updateLedIndicator();
        delay(10);
    }
    String line = Serial.readStringUntil('\n');
    line.trim();
    return line;
}

void runGuidedCalibration() {
    Serial.println("\n╔══════════════════════════════════════════════════════════╗");
    Serial.println("║       GUIDED 3-STEP LOAD CELL CALIBRATION                ║");
    Serial.println("╚══════════════════════════════════════════════════════════╝");
    Serial.println("Type 'q' at any prompt to cancel.\n");

    // Step 1: Tare
    Serial.println("STEP 1/3: Remove EVERYTHING from the scale platform.");
    Serial.println("          Type 'y' and press Enter when empty:");
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

    Serial.printf("          Measuring %.1f g reference weight... keep platform steady.\n", knownGrams);
    delay(1500);

    if (!scale.wait_ready_timeout(1000)) {
        Serial.println("[ERROR] HX711 not responding during measurement.");
        scale.set_scale(calibrationFactor);
        return;
    }

    double rawDelta = scale.get_value(20);
    if (fabs(rawDelta) < 500) {
        Serial.printf("[ERROR] Raw counts change too small (%.0f counts). Check wiring/mounting.\n", rawDelta);
        scale.set_scale(calibrationFactor);
        return;
    }

    // Step 3: Compute & save factor
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

// ── Interactive Serial Command Interface ───────────────────────────────────
void printHelp() {
    Serial.println("\n── SipSense Hardware Serial Commands ──");
    Serial.println("  t  -> Tare / zero the scale platform");
    Serial.println("  c  -> Run 3-step interactive calibration");
    Serial.println("  s  -> Save calibration to flash");
    Serial.println("  l  -> Reload calibration from flash");
    Serial.println("  i  -> Print live hardware sensor readings & status");
    Serial.println("  h  -> Show this help menu\n");
}

void printInfo() {
    Serial.println("\n═══════════ SIPSENSE REAL HARDWARE STATUS ═══════════");
    Serial.printf("  DHT11 Temp (°C)   : %.1f C (%.1f F)\n", lastTemperatureC, (lastTemperatureC * 1.8f) + 32.0f);
    Serial.printf("  DHT11 Humidity    : %.0f %%\n", lastHumidity);
    Serial.printf("  DHT11 Status      : %s (GPIO %d)\n", isDhtValid ? "OK" : "READ FAILED", DHT_PIN);
    Serial.println("  ───────────────────────────────────────────────────");
    Serial.printf("  HX711 Weight      : %.1f g\n", lastWeightGrams);
    Serial.printf("  Water Volume      : %d ml / %d ml\n", currentWaterLevel, bottleCapacity);
    Serial.printf("  Last Sip Measured : %d ml\n", lastSipAmount);
    Serial.printf("  Total Consumed    : %d ml\n", totalConsumedToday);
    Serial.printf("  Bottle Base State : %s\n", isBottleAtRest ? "AT REST ON BASE" : "LIFTED");
    Serial.printf("  HX711 Raw Counts  : %ld\n", lastRawCounts);
    Serial.printf("  HX711 Calibrated  : %s (Factor: %.4f, Offset: %ld)\n",
                  isCalibrated ? "YES" : "NO", calibrationFactor, scale.get_offset());
    Serial.printf("  HX711 Status      : %s (DT=%d, SCK=%d)\n", isHx711Valid ? "OK" : "NOT READY", HX711_DT_PIN, HX711_SCK_PIN);
    Serial.println("  ───────────────────────────────────────────────────");
    Serial.printf("  Status LED        : %s (GPIO %d)\n", deviceConnected ? "STEADY ON" : "BLINKING", LED_PIN);
    Serial.printf("  BLE App State     : %s\n", deviceConnected ? "CONNECTED" : "BROADCASTING");
    Serial.println("═════════════════════════════════════════════════════\n");
}

void handleSerialCommands() {
    if (Serial.available()) {
        char cmd = (char) Serial.read();
        while (Serial.available()) Serial.read(); // Clear rest of line

        switch (tolower(cmd)) {
            case 't': tareScale(); break;
            case 'c': runGuidedCalibration(); break;
            case 's': saveCalibration(); break;
            case 'l': loadCalibration(); break;
            case 'i': printInfo(); break;
            case 'h': printHelp(); break;
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
    Serial.println("║   💧 SipSense Smart Water Bottle - Real Hardware BLE     ║");
    Serial.println("║   DHT11 (Temp/Hum) + HX711 (Load Cell Weight)            ║");
    Serial.println("║   Status LED: Blinking = Advertising | Steady = Connected║");
    Serial.println("╚══════════════════════════════════════════════════════════╝");

    // 1. Initialize Status LED (GPIO 2)
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, LOW);
    Serial.printf("[INIT] Status LED configured on GPIO %d\n", LED_PIN);

    // 2. Initialize DHT11 Sensor (GPIO 19)
    dht.begin();
    Serial.printf("[INIT] DHT11 initialized on GPIO %d\n", DHT_PIN);

    // 3. Initialize HX711 Load Cell (DT=GPIO 21, SCK=GPIO 22)
    scale.begin(HX711_DT_PIN, HX711_SCK_PIN);
    Serial.printf("[INIT] HX711 initialized (DT=GPIO %d, SCK=GPIO %d)\n", HX711_DT_PIN, HX711_SCK_PIN);
    if (!loadCalibration()) {
        tareScale();
    }

    // 4. Initialize BLE Stack
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
    Serial.println("💡 [LED] Status LED is now BLINKING while broadcasting.");
    Serial.println("         Use the SipSense Waterbottle tab in the app to scan and connect!\n");
    printHelp();

    // Initial sensor reads
    delay(1000);
    readDhtSensor();
    readScaleSensor();
    publishTelemetry(true);

    lastDhtMs    = millis();
    lastScaleMs  = millis();
    lastNotifyMs = millis();
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

    // 3. Periodic HX711 Load Cell Read & Sip Tracking (every 300ms)
    if (now - lastScaleMs >= SCALE_SAMPLE_INTERVAL_MS) {
        lastScaleMs = now;
        readScaleSensor();
    }

    // 4. Periodic DHT11 Temperature & Humidity Read (every 2s)
    if (now - lastDhtMs >= DHT_SAMPLE_INTERVAL_MS) {
        lastDhtMs = now;
        readDhtSensor();
    }

    // 5. Push initial notification after connection
    if (pendingFirstNotify && deviceConnected && now - connectedAtMs >= FIRST_NOTIFY_DELAY_MS) {
        pendingFirstNotify = false;
        lastNotifyMs = now;
        publishTelemetry(true);
    }

    // 6. Periodic Telemetry Push (every 2s)
    if (deviceConnected && (now - lastNotifyMs >= NOTIFY_INTERVAL_MS)) {
        lastNotifyMs = now;
        publishTelemetry(false);
    }

    // 7. Handle Interactive Serial Commands (t, c, s, l, i, h)
    handleSerialCommands();

    delay(10);
}
