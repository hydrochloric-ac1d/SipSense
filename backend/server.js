const mqtt = require('mqtt');
const express = require('express');
const cors = require('cors');

const app = express();
const port = 3000;

// Enable CORS for frontend integration
app.use(cors());
app.use(express.json());

// 1. MQTT SETUP (The connection to the "ESP32")
// We use a free public broker for easy testing without setup.
const brokerUrl = 'mqtt://broker.hivemq.com';
const topic = 'sipsense/devices/mock-bottle-001/telemetry';

console.log(`Connecting to MQTT Broker: ${brokerUrl}...`);
const client = mqtt.connect(brokerUrl);

// Store the latest data received from the MQTT broker
let latestSensorData = {
    waterLevel: 0,
    lastSipTimestamp: null,
    totalConsumedToday: 0
};

client.on('connect', () => {
    console.log('✅ Backend connected to MQTT Broker!');
    
    // Subscribe to the topic where the ESP32 simulator is publishing
    client.subscribe(topic, (err) => {
        if (!err) {
            console.log(`✅ Subscribed to topic: ${topic}`);
        }
    });
});

client.on('message', (receivedTopic, message) => {
    // When a message arrives from the ESP32 (simulator)
    const dataString = message.toString();
    console.log(`\n📥 Received message on [${receivedTopic}]: ${dataString}`);
    
    try {
        const parsedData = JSON.parse(dataString);
        latestSensorData = {
            ...latestSensorData,
            ...parsedData,
            lastSipTimestamp: new Date().toISOString()
        };
        console.log('🔄 Updated latest sensor data in backend memory.');
    } catch (e) {
        console.error('Failed to parse MQTT message as JSON', e);
    }
});

// 2. REST API SETUP (The connection for the Android App)
// The Android app will call these endpoints to get the data

// Endpoint to get the current status of the bottle
app.get('/api/hydration', (req, res) => {
    res.json({
        status: 'success',
        data: latestSensorData
    });
});

// Start the Express Server
app.listen(port, () => {
    console.log(`\n🚀 Backend REST API running at http://localhost:${port}`);
    console.log(`   Test it by opening http://localhost:${port}/api/hydration in your browser.`);
});
