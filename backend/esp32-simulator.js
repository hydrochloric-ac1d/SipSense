const mqtt = require('mqtt');

// 1. MQTT SETUP (Simulating the ESP32 connection)
// We connect to the exact same broker and topic as the backend server
const brokerUrl = 'mqtt://broker.hivemq.com';
const topic = 'sipsense/devices/mock-bottle-001/telemetry';

console.log(`[ESP32 SIMULATOR] Connecting to ${brokerUrl}...`);
const client = mqtt.connect(brokerUrl);

// Simulated bottle state
let currentWaterLevel = 500; // ml
let totalConsumed = 0; // ml

client.on('connect', () => {
    console.log('[ESP32 SIMULATOR] ✅ Connected to Broker!');
    console.log(`[ESP32 SIMULATOR] Starting to send mock sip data to ${topic}...`);
    
    // Simulate someone taking a sip every 5 seconds
    setInterval(() => {
        takeASip();
    }, 5000);
});

function takeASip() {
    // Simulate a random sip between 10ml and 50ml
    const sipAmount = Math.floor(Math.random() * (50 - 10 + 1) + 10);
    
    if (currentWaterLevel - sipAmount < 0) {
        console.log('[ESP32 SIMULATOR] Bottle is empty! Please refill.');
        currentWaterLevel = 500; // Auto-refill for the simulation
        totalConsumed = 0;
        console.log('[ESP32 SIMULATOR] Bottle auto-refilled.');
        return;
    }

    currentWaterLevel -= sipAmount;
    totalConsumed += sipAmount;

    // Build the JSON payload that the real ESP32 would generate
    const payload = {
        waterLevel: currentWaterLevel,
        totalConsumedToday: totalConsumed,
        sipAmount: sipAmount
    };

    const payloadString = JSON.stringify(payload);
    
    console.log(`[ESP32 SIMULATOR] 📤 Publishing data: ${payloadString}`);
    
    // Send it over MQTT
    client.publish(topic, payloadString);
}
