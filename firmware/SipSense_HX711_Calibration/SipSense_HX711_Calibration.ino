/*
 * SipSense_HX711_Calibration.ino
 *
 * ESP32 + HX711 + Load Cell - interactive calibration utility.
 *
 * What it does:
 * - Streams live weight readings over the Serial Monitor.
 * - Guides you through a 3-step calibration with a known reference weight.
 * - Computes the calibration factor (raw HX711 counts per gram).
 * - Saves the factor + zero offset to the ESP32's flash (NVS / Preferences),
 *   so it survives reboots AND can be reused later by the full SipSense firmware.
 *
 * Serial commands (type the letter, press Enter):
 *   c        -> guided calibration (tare -> place known weight -> compute factor)
 *   t        -> tare / zero the scale (platform must be EMPTY)
 *   f <num>  -> manually set the calibration factor, e.g.  f 421.35
 *   s        -> save factor + offset to flash
 *   l        -> load factor + offset from flash
 *   x        -> erase saved calibration from flash
 *   r        -> print one raw reading (averaged)
 *   p        -> pause / resume live streaming
 *   i        -> show current calibration info
 *   h        -> help
 *
 * Wiring (see firmware/PIN_DIAGRAM.md):
 *   HX711 VCC -> ESP32 3V3        (power from 3V3 so DT is 3.3 V logic!)
 *   HX711 GND -> ESP32 GND
 *   HX711 DT  -> ESP32 GPIO 21
 *   HX711 SCK -> ESP32 GPIO 22
 *   Load cell: Red->E+, Black->E-, White->A-, Green->A+
 *
 * Required library (Arduino Library Manager):
 *   - "HX711 Arduino Library" by Bogdan Necula (bogde)  ->  #include <HX711.h>
 *   - Preferences is built into the ESP32 Arduino core.
 *
 * Board: "ESP32 Dev Module"  |  Serial Monitor: 115200 baud, line ending "Newline"
 *
 * @project SipSense - Smart Bottle Ecosystem
 */

#include <HX711.h>
#include <Preferences.h>

// ── Pin Configuration ──────────────────────────────────────────────────
#define HX711_DT_PIN   21
#define HX711_SCK_PIN  22

// ── Flash storage keys (shared with future SipSense firmware) ──────────
#define PREFS_NAMESPACE   "sipsense"
#define KEY_FACTOR        "hx_factor"
#define KEY_OFFSET        "hx_offset"
#define KEY_CALIBRATED    "hx_calib"

// ── Tuning ─────────────────────────────────────────────────────────────
const uint8_t       TARE_SAMPLES          = 20;    // ~2 s at 10 SPS
const uint8_t       CALIBRATION_SAMPLES   = 20;
const uint8_t       STREAM_SAMPLES        = 5;
const unsigned long STREAM_INTERVAL_MS    = 1000;
const unsigned long HX711_READY_TIMEOUT   = 1500;
// Minimum raw change expected when the reference weight is placed.
const long          MIN_CALIBRATION_DELTA = 1000;

// ── Globals ────────────────────────────────────────────────────────────
HX711       scale;
Preferences prefs;

float calibrationFactor = 1.0f;   // raw counts per gram (1.0 = uncalibrated)
bool  isCalibrated      = false;
bool  streaming         = true;
unsigned long lastStreamMs = 0;

// ── Serial line input helpers ──────────────────────────────────────────
// Works with "Newline", "Both NL & CR" and "No line ending" Serial Monitor modes.
String serialBuffer;
unsigned long lastCharMs = 0;

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
    // "No line ending" mode: treat a short pause after typing as Enter.
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
        delay(5);
    }
    return line;
}

// ── HX711 helpers ──────────────────────────────────────────────────────
bool waitForHx711() {
    if (scale.wait_ready_timeout(HX711_READY_TIMEOUT)) {
        return true;
    }
    Serial.println("[HX711] Not responding! Check wiring:");
    Serial.println("        VCC->3V3, GND->GND, DT->GPIO 21, SCK->GPIO 22");
    return false;
}

void applyCalibration(float factor, long offset) {
    calibrationFactor = factor;
    scale.set_scale(factor);
    scale.set_offset(offset);
}

void doTare() {
    if (!waitForHx711()) return;
    Serial.println("[TARE] Zeroing... keep the platform EMPTY and still.");
    scale.tare(TARE_SAMPLES);
    Serial.printf("[TARE] Done. Zero offset = %ld\n", scale.get_offset());
}

// ── Flash storage ──────────────────────────────────────────────────────
void saveCalibration() {
    prefs.begin(PREFS_NAMESPACE, false);
    prefs.putFloat(KEY_FACTOR, calibrationFactor);
    prefs.putLong(KEY_OFFSET, (int32_t) scale.get_offset());
    prefs.putBool(KEY_CALIBRATED, isCalibrated);
    prefs.end();
    Serial.printf("[FLASH] Saved -> factor = %.4f, offset = %ld\n",
                  calibrationFactor, scale.get_offset());
}

bool loadCalibration() {
    prefs.begin(PREFS_NAMESPACE, true);
    bool saved   = prefs.getBool(KEY_CALIBRATED, false);
    float factor = prefs.getFloat(KEY_FACTOR, 1.0f);
    long offset  = prefs.getLong(KEY_OFFSET, 0);
    prefs.end();

    if (!saved || factor == 0.0f) {
        Serial.println("[FLASH] No saved calibration found.");
        return false;
    }
    applyCalibration(factor, offset);
    isCalibrated = true;
    Serial.printf("[FLASH] Loaded -> factor = %.4f, offset = %ld\n", factor, offset);
    return true;
}

void eraseCalibration() {
    prefs.begin(PREFS_NAMESPACE, false);
    prefs.remove(KEY_FACTOR);
    prefs.remove(KEY_OFFSET);
    prefs.remove(KEY_CALIBRATED);
    prefs.end();
    isCalibrated = false;
    applyCalibration(1.0f, scale.get_offset());
    Serial.println("[FLASH] Saved calibration erased. Factor reset to 1.0.");
}

// ── Guided calibration ─────────────────────────────────────────────────
void runCalibration() {
    bool wasStreaming = streaming;
    streaming = false;

    Serial.println();
    Serial.println("============ GUIDED CALIBRATION ============");
    Serial.println("Type  q  at any prompt to cancel.");
    Serial.println();

    // Step 1 - tare
    Serial.println("STEP 1/3: Remove EVERYTHING from the load cell platform.");
    Serial.println("          Type  y  and press Enter when it is empty.");
    String answer = readLineBlocking();
    if (answer.equalsIgnoreCase("q")) { Serial.println("Cancelled."); streaming = wasStreaming; return; }

    if (!waitForHx711()) { streaming = wasStreaming; return; }
    scale.set_scale(1.0f);              // work in raw counts during calibration
    doTare();

    // Step 2 - known weight
    Serial.println();
    Serial.println("STEP 2/3: Place your KNOWN reference weight on the platform.");
    Serial.println("          Type its mass in GRAMS (e.g. 500) and press Enter.");
    float knownGrams = 0.0f;
    while (true) {
        answer = readLineBlocking();
        if (answer.equalsIgnoreCase("q")) {
            Serial.println("Cancelled. Restoring previous factor.");
            scale.set_scale(calibrationFactor);
            streaming = wasStreaming;
            return;
        }
        knownGrams = answer.toFloat();
        if (knownGrams > 0.0f) break;
        Serial.println("          Please enter a positive number in grams (e.g. 500).");
    }

    Serial.printf("          Measuring %.1f g reference... keep it still.\n", knownGrams);
    delay(1500);                        // let the platform settle
    if (!waitForHx711()) { streaming = wasStreaming; return; }
    double rawDelta = scale.get_value(CALIBRATION_SAMPLES);   // average - offset

    if (fabs(rawDelta) < MIN_CALIBRATION_DELTA) {
        Serial.printf("[ERROR] Raw change too small (%.0f counts).\n", rawDelta);
        Serial.println("        - Is the weight actually on the platform?");
        Serial.println("        - Is the load cell mounted so it can flex (arrow = force direction)?");
        Serial.println("        - Check E+/E-/A+/A- wiring to the HX711.");
        scale.set_scale(calibrationFactor);
        streaming = wasStreaming;
        return;
    }

    float newFactor = (float) (rawDelta / knownGrams);
    calibrationFactor = newFactor;
    scale.set_scale(newFactor);
    isCalibrated = true;

    // Step 3 - verify
    Serial.println();
    Serial.println("STEP 3/3: Verifying...");
    delay(500);
    if (waitForHx711()) {
        float measured = scale.get_units(10);
        float errorPct = (measured - knownGrams) / knownGrams * 100.0f;
        Serial.printf("          Reference: %.1f g | Measured: %.1f g | Error: %+.2f %%\n",
                      knownGrams, measured, errorPct);
    }

    Serial.println();
    Serial.printf(">>> CALIBRATION FACTOR = %.4f  (raw counts per gram)\n", newFactor);
    Serial.printf(">>> ZERO OFFSET        = %ld\n", scale.get_offset());
    if (newFactor < 0) {
        Serial.println("    (Negative factor = A+/A- are swapped. That's OK, readings will still be positive.)");
    }
    Serial.println("    Write these values down for the SipSense firmware.");
    Serial.println();
    Serial.println("Save to flash? Type  y  to save or  n  to skip.");
    answer = readLineBlocking();
    if (answer.equalsIgnoreCase("y")) {
        saveCalibration();
    } else {
        Serial.println("Not saved (type  s  later to save).");
    }
    Serial.println("============================================");
    Serial.println();

    streaming = wasStreaming;
}

// ── Info / help ────────────────────────────────────────────────────────
void printInfo() {
    Serial.println();
    Serial.println("---------- Calibration Info ----------");
    Serial.printf("Pins        : DT=GPIO %d, SCK=GPIO %d\n", HX711_DT_PIN, HX711_SCK_PIN);
    Serial.printf("Calibrated  : %s\n", isCalibrated ? "YES" : "NO");
    Serial.printf("Factor      : %.4f counts/g\n", calibrationFactor);
    Serial.printf("Zero offset : %ld\n", scale.get_offset());
    Serial.println("--------------------------------------");
}

void printHelp() {
    Serial.println();
    Serial.println("Commands (type letter + Enter):");
    Serial.println("  c        guided calibration with a known weight");
    Serial.println("  t        tare / zero (platform must be empty)");
    Serial.println("  f <num>  set calibration factor manually, e.g.  f 421.35");
    Serial.println("  s        save factor + offset to flash");
    Serial.println("  l        load factor + offset from flash");
    Serial.println("  x        erase saved calibration");
    Serial.println("  r        print one raw reading");
    Serial.println("  p        pause / resume live readings");
    Serial.println("  i        show calibration info");
    Serial.println("  h        show this help");
    Serial.println();
}

void handleCommand(String cmd) {
    cmd.trim();
    if (cmd.length() == 0) return;
    char c = tolower(cmd.charAt(0));

    switch (c) {
        case 'c': runCalibration(); break;
        case 't': doTare(); break;
        case 'f': {
            float value = cmd.substring(1).toFloat();
            if (value == 0.0f) {
                Serial.println("Usage: f <factor>   e.g.  f 421.35");
            } else {
                calibrationFactor = value;
                scale.set_scale(value);
                isCalibrated = true;
                Serial.printf("[CAL] Factor set to %.4f (type  s  to save).\n", value);
            }
            break;
        }
        case 's': saveCalibration(); break;
        case 'l': loadCalibration(); break;
        case 'x': eraseCalibration(); break;
        case 'r':
            if (waitForHx711()) {
                Serial.printf("[RAW] average = %ld | offset = %ld | delta = %ld\n",
                              scale.read_average(10), scale.get_offset(),
                              (long) scale.get_value(10));
            }
            break;
        case 'p':
            streaming = !streaming;
            Serial.printf("Live readings %s.\n", streaming ? "RESUMED" : "PAUSED");
            break;
        case 'i': printInfo(); break;
        case 'h': case '?': printHelp(); break;
        default:
            Serial.printf("Unknown command '%s'. Type  h  for help.\n", cmd.c_str());
    }
}

// ── Live readings ──────────────────────────────────────────────────────
void streamReading() {
    if (!scale.wait_ready_timeout(HX711_READY_TIMEOUT)) {
        Serial.println("[HX711] Not ready - check wiring (DT->21, SCK->22, VCC->3V3).");
        return;
    }
    long raw = scale.read_average(STREAM_SAMPLES);
    long delta = raw - scale.get_offset();

    if (!isCalibrated) {
        Serial.printf("raw: %ld | delta: %ld   (uncalibrated - type  c  to calibrate)\n",
                      raw, delta);
        return;
    }

    float grams = delta / calibrationFactor;
    // For water, 1 g ~= 1 mL - handy for SipSense bottle testing.
    Serial.printf("Weight: %8.1f g  (~%5.0f mL water) | raw: %ld\n",
                  grams, grams, raw);
}

// ── Setup / Loop ───────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println();
    Serial.println("========================================");
    Serial.println("  SipSense | HX711 Load Cell Calibration ");
    Serial.println("========================================");
    Serial.println("Serial Monitor: 115200 baud, line ending 'Newline'.");

    scale.begin(HX711_DT_PIN, HX711_SCK_PIN);   // channel A, gain 128

    if (!waitForHx711()) {
        Serial.println("Will keep retrying in the main loop...");
    }

    if (!loadCalibration()) {
        // Fresh board: assume the platform is empty and zero it.
        doTare();
        Serial.println("Type  c  to start guided calibration.");
    } else {
        Serial.println("Tip: if the platform is empty, type  t  to re-zero.");
    }

    printHelp();
}

void loop() {
    String line;
    if (pollSerialLine(line)) {
        handleCommand(line);
    }

    if (streaming && millis() - lastStreamMs >= STREAM_INTERVAL_MS) {
        lastStreamMs = millis();
        streamReading();
    }

    delay(5);
}
