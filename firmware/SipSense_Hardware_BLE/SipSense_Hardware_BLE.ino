/*
 * SipSense_Hardware_BLE.ino
 *
 * ESP32 Smart Water Bottle Firmware (Physical DHT11 + HX711 + BLE Status LED)
 * Fixed 500 ml Bottle Capacity & Automatic Water Volume Deduction Tracking
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
const unsigned long LED_BLINK_INTERVAL_MS    = 350;
const unsigned long NOTIFY_INTERVAL_MS       = 2000;
const unsigned long DHT_SAMPLE_INTERVAL_MS   = 2000;
const unsigned long SCALE_SAMPLE_INTERVAL_MS = 250;
const unsigned long FIRST_NOTIFY_DELAY_MS    = 350;

const float SIP_THRESHOLD_GRAMS              = 5.0f;
const float MIN_BOTTLE_ON_BASE_GRAMS         = 25.0f;
const float LIFT_NEGATIVE_THRESHOLD_GRAMS    = -15.0f;

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

int           currentWaterLevel   = BOTTLE_CAPACITY_ML;
int           bottleCapacity      = BOTTLE_CAPACITY_ML;
bool          isBottleAtRest      = true;
int           lastSipAmount       = 0;
int           totalConsumedToday  = 0;
int           batteryLevel        = 95;

// ── Loop Timers ────────────────────────────────────────────────────────────
unsigned long lastDhtReadMs       = 0;
unsigned long lastScaleReadMs     = 0;
unsigned long lastTelemetryPushMs = 0;

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

class BottleServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* server) override {
        deviceConnected = true;
        digitalWrite(LED_PIN, HIGH);
        Serial.println("\n[BLE] Mobile App CONNECTED! LED -> STEADY ON");
    }

    void onDisconnect(BLEServer* server) override {
        deviceConnected = false;
        Serial.println("\n[BLE] Mobile App DISCONNECTED! LED -> BLINKING");
        server->startAdvertising();
    }
};

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

void tareScale() {
    if (!scale.wait_ready_timeout(1000)) {
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
    publishTelemetry(true);
}

void calibrateScale(float knownWeightGrams) {
    if (knownWeightGrams <= 0.0f || !scale.wait_ready_timeout(1000)) return;
    long rawAvg = scale.read_average(20);
    long netCounts = rawAvg - zeroOffset;
    if (abs(netCounts) < 100) return;

    float newFactor = (float)netCounts / knownWeightGrams;
    scale.set_scale(newFactor);
    scale.set_offset(zeroOffset);
    saveCalibration(newFactor, zeroOffset);

    float verifiedGrams = scale.get_units(10);
    latestWeightGrams   = max(0.0f, verifiedGrams);
    hasBottleBeenPlaced = true;
    containerTareGrams  = (latestWeightGrams > (float)BOTTLE_CAPACITY_ML)
        ? (latestWeightGrams - (float)BOTTLE_CAPACITY_ML) : 0.0f;
    currentWaterLevel   = constrain((int)lroundf(latestWeightGrams - containerTareGrams), 0, BOTTLE_CAPACITY_ML);
    isHx711ReadFailed   = false;
    publishTelemetry(true);
}

void readDht11Sensor() {
    float h = dht.readHumidity();
    float t = dht.readTemperature();
    if (isnan(h) || isnan(t)) {
        dhtFailStreak++;
        if (dhtFailStreak >= MAX_DHT_FAIL_STREAK) isDhtReadFailed = true;
    } else {
        dhtFailStreak   = 0;
        isDhtReadFailed = false;
        currentTempC    = t;
        currentHumidity = h;
    }
}

void readScaleSensor() {
    if (!scale.wait_ready_timeout(200)) {
        isHx711ReadFailed = true;
        return;
    }
    isHx711ReadFailed = false;
    latestRawCounts   = scale.read_average(5);
    if (!isCalibrated) return;

    float weight = scale.get_units(5);
    if (fabs(weight) < 2.0f) weight = 0.0f;

    bool liftedNow = (weight < LIFT_NEGATIVE_THRESHOLD_GRAMS) ||
                     (hasBottleBeenPlaced && weight < MIN_BOTTLE_ON_BASE_GRAMS);

    if (liftedNow) {
        if (isBottleAtRest) {
            isBottleAtRest = false;
            publishTelemetry(true);
        }
        return;
    }

    if (!hasBottleBeenPlaced && weight < MIN_BOTTLE_ON_BASE_GRAMS) return;

    bool justPlacedOnBase = !hasBottleBeenPlaced || !isBottleAtRest;
    hasBottleBeenPlaced = true;
    isBottleAtRest      = true;
    latestWeightGrams   = max(0.0f, weight);

    float waterGrams = latestWeightGrams - containerTareGrams;
    if (waterGrams > (float)BOTTLE_CAPACITY_ML) {
        containerTareGrams = latestWeightGrams - (float)BOTTLE_CAPACITY_ML;
        waterGrams = (float)BOTTLE_CAPACITY_ML;
    }

    int measuredWaterMl = constrain((int)lroundf(waterGrams), 0, BOTTLE_CAPACITY_ML);
    int levelDifference = currentWaterLevel - measuredWaterMl;

    if (levelDifference >= (int)SIP_THRESHOLD_GRAMS) {
        lastSipAmount       = levelDifference;
        totalConsumedToday += lastSipAmount;
        currentWaterLevel   = measuredWaterMl;
        publishTelemetry(true);
    } else if (measuredWaterMl - currentWaterLevel >= (int)SIP_THRESHOLD_GRAMS) {
        currentWaterLevel = measuredWaterMl;
        publishTelemetry(true);
    } else if (justPlacedOnBase) {
        publishTelemetry(true);
    }
}

void publishTelemetry(bool forceImmediate) {
    if (!deviceConnected || pTelemetryCharacteristic == nullptr) return;

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
    lastSipAmount = 0;
}

void printDeviceStatus() {
    Serial.printf("Water: %d/%d ml | Daily: %d ml | Temp: %.1fC\n",
                  currentWaterLevel, bottleCapacity, totalConsumedToday, currentTempC);
}

void printHelp() {
    Serial.println("Commands: t (tare), c <grams> (calibrate), s (deduct 50ml), r (refill 500ml), i (info)");
}

void handleSerialCommands() {
    if (!Serial.available()) return;
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) return;

    char cmd = tolower(line.charAt(0));
    if (cmd == 't') tareScale();
    else if (cmd == 'c') {
        String arg = line.substring(1);
        arg.trim();
        calibrateScale(arg.toFloat());
    } else if (cmd == 's') {
        int deductMl = min(50, currentWaterLevel);
        if (deductMl > 0) {
            currentWaterLevel  -= deductMl;
            latestWeightGrams   = (float)currentWaterLevel + containerTareGrams;
            lastSipAmount       = deductMl;
            totalConsumedToday += deductMl;
            isBottleAtRest      = true;
            publishTelemetry(true);
        }
    } else if (cmd == 'r') {
        currentWaterLevel = BOTTLE_CAPACITY_ML;
        latestWeightGrams = (float)BOTTLE_CAPACITY_ML + containerTareGrams;
        isBottleAtRest    = true;
        publishTelemetry(true);
    } else if (cmd == 'i') printDeviceStatus();
    else if (cmd == 'h') printHelp();
}

void setup() {
    Serial.begin(115200);
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, LOW);

    dht.begin();
    scale.begin(HX711_DT_PIN, HX711_SCK_PIN);
    if (loadCalibration()) {
        scale.set_scale(calibrationFactor);
        scale.set_offset(zeroOffset);
    } else if (scale.wait_ready_timeout(800)) {
        scale.set_scale();
        scale.tare(10);
        zeroOffset = scale.get_offset();
    }

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
}

void loop() {
    const unsigned long now = millis();
    updateLedIndicator();
    handleSerialCommands();

    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
        connectedAtMs      = now;
        pendingFirstNotify = true;
    }
    if (!deviceConnected && oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
        pendingFirstNotify = false;
    }
    if (deviceConnected && pendingFirstNotify && (now - connectedAtMs >= FIRST_NOTIFY_DELAY_MS)) {
        pendingFirstNotify = false;
        readDht11Sensor();
        readScaleSensor();
        publishTelemetry(true);
    }
    if (now - lastScaleReadMs >= SCALE_SAMPLE_INTERVAL_MS) {
        lastScaleReadMs = now;
        readScaleSensor();
    }
    if (now - lastDhtReadMs >= DHT_SAMPLE_INTERVAL_MS) {
        lastDhtReadMs = now;
        readDht11Sensor();
    }
    if (deviceConnected && (now - lastTelemetryPushMs >= NOTIFY_INTERVAL_MS)) {
        lastTelemetryPushMs = now;
        publishTelemetry(false);
    }
}
