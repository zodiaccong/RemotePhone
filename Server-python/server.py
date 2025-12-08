from flask import Flask, render_template, request
from flask_socketio import SocketIO, emit
import logging
import base64
import json
from datetime import datetime
import eventlet

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)
app.config['SECRET_KEY'] = 'your-secret-key-here'
socketio = SocketIO(app, cors_allowed_origins="*", async_mode='eventlet')

# Store connected clients
clients = {}
devices = {}
viewers = {}


@app.route('/')
def index():
    """Serve the main HTML page"""
    return render_template('index2.html')


@app.route('/health')
def health():
    """Health check endpoint"""
    return {'status': 'healthy', 'timestamp': datetime.now().isoformat()}


@socketio.on('connect')
def handle_connect():
    """Handle new client connection"""
    client_id = request.sid
    clients[client_id] = {
        'type': 'unknown',
        'connected_at': datetime.now().isoformat()
    }
    logger.info(f"Client connected: {client_id}")
    emit('connected', {'message': 'Connected to server', 'client_id': client_id})


@socketio.on('disconnect')
def handle_disconnect():
    """Handle client disconnect"""
    client_id = request.sid
    client_type = clients.get(client_id, {}).get('type')

    if client_id in clients:
        del clients[client_id]

    if client_id in devices:
        device_info = devices[client_id]
        del devices[client_id]
        logger.info(f"Device disconnected: {client_id} - {device_info.get('name', 'Unknown')}")

        # Notify all viewers that device disconnected
        emit('device_disconnected', {
            'device_id': client_id,
            'message': 'Device disconnected'
        }, broadcast=True, include_self=False)

    if client_id in viewers:
        del viewers[client_id]
        logger.info(f"Viewer disconnected: {client_id}")

        # Notify devices about viewer disconnect
        emit('viewer_disconnected', {
            'viewer_id': client_id,
            'message': 'Viewer disconnected'
        }, broadcast=True, include_self=False)

    logger.info(f"Client disconnected: {client_id}")


@socketio.on('register_device')
def handle_register_device(data):
    """Register Android device"""
    client_id = request.sid

    if not isinstance(data, dict):
        data = {}

    device_info = {
        'id': client_id,
        'name': data.get('name', 'Unknown Device'),
        'model': data.get('model', 'Unknown'),
        'screen_width': data.get('screen_width', 1080),
        'screen_height': data.get('screen_height', 1920),
        'registered_at': datetime.now().isoformat()
    }

    clients[client_id]['type'] = 'device'
    devices[client_id] = device_info

    logger.info(f"Device registered: {device_info['name']} ({client_id})")

    # Notify the device
    emit('device_registered', {
        'status': 'success',
        'device_id': client_id,
        'viewers_count': len(viewers)
    })

    # Notify all viewers about new device
    emit('device_connected', {
        'device_id': client_id,
        'device_info': device_info
    }, broadcast=True, include_self=False)


@socketio.on('register_viewer')
def handle_register_viewer(data):
    """Register web viewer"""
    client_id = request.sid

    if not isinstance(data, dict):
        data = {}

    viewer_info = {
        'id': client_id,
        'user_agent': request.headers.get('User-Agent', 'Unknown'),
        'registered_at': datetime.now().isoformat()
    }

    clients[client_id]['type'] = 'viewer'
    viewers[client_id] = viewer_info

    logger.info(f"Viewer registered: {client_id}")

    # Send available devices to the viewer
    available_devices = list(devices.values())
    emit('viewer_registered', {
        'status': 'success',
        'viewer_id': client_id,
        'devices': available_devices
    })

    # Notify all devices about new viewer
    emit('viewer_connected', {
        'viewer_id': client_id,
        'viewers_count': len(viewers)
    }, broadcast=True, include_self=False)


@socketio.on('screen_data')
def handle_screen_data(data):
    """Handle screen data from Android device"""
    client_id = request.sid

    if client_id not in devices:
        logger.warning(f"Received screen data from unregistered device: {client_id}")
        return

    if not isinstance(data, dict):
        data = {}

    # Log chi tiết
    image_data = data.get('image_data')
    device_info = devices[client_id]

    logger.info(f"📱 Received screen data from {device_info['name']} ({client_id})")
    logger.info(
        f"📊 Data size: {len(image_data) if image_data else 0} chars, width: {data.get('width')}, height: {data.get('height')}")

    # Add device info to the data
    screen_data = {
        'device_id': client_id,
        'image_data': image_data,
        'timestamp': data.get('timestamp', datetime.now().isoformat()),
        'width': data.get('width', 1080),
        'height': data.get('height', 1920)
    }

    # Log số lượng viewers đang kết nối
    viewers_count = len([v for v in viewers.values()])
    logger.info(f"👥 Broadcasting to {viewers_count} viewers")

    # Broadcast to all viewers
    emit('screen_update', screen_data, broadcast=True, include_self=False)
    logger.info(f"✅ Screen data broadcasted to viewers")


@socketio.on('control_command')
def handle_control_command(data):
    """Handle control commands from web viewer"""
    client_id = request.sid

    if not isinstance(data, dict):
        data = {}

    target_device_id = data.get('device_id')

    if not target_device_id:
        # If no specific device, send to all devices
        target_device_id = list(devices.keys())

    command_data = {
        'viewer_id': client_id,
        'command': data.get('command'),
        'type': data.get('type'),
        'data': data.get('data'),
        'timestamp': datetime.now().isoformat()
    }

    if isinstance(target_device_id, list):
        # Send to all devices
        for device_id in target_device_id:
            if device_id in devices:
                emit('control', command_data, room=device_id)
    else:
        # Send to specific device
        if target_device_id in devices:
            emit('control', command_data, room=target_device_id)
        else:
            emit('error', {'message': 'Target device not found'})


@socketio.on('ping')
def handle_ping(data=None):
    """Handle ping for latency measurement"""
    if data is None:
        data = {}

    # THÊM: Trả về device_id nếu có trong data
    response = {
        'timestamp': datetime.now().isoformat(),
        'received_data': data
    }

    # THÊM: Bao gồm device_id trong response nếu client gửi lên
    if 'device_id' in data:
        response['device_id'] = data['device_id']

    emit('pong', response)

@socketio.on('get_devices')
def handle_get_devices(data=None):
    """Return list of connected devices"""
    if data is None:
        data = {}

    client_id = request.sid
    available_devices = list(devices.values())
    emit('devices_list', {'devices': available_devices})


@socketio.on('select_device')
def handle_select_device(data):
    """Handle viewer selecting a specific device"""
    client_id = request.sid

    if not isinstance(data, dict):
        data = {}

    device_id = data.get('device_id')

    if device_id in devices:
        if client_id in viewers:
            viewers[client_id]['selected_device'] = device_id
        emit('device_selected', {
            'status': 'success',
            'device_id': device_id,
            'device_info': devices[device_id]
        })
    else:
        emit('error', {'message': 'Device not found'})


@socketio.on('error')
def handle_error(data):
    """Handle client errors"""
    client_id = request.sid
    logger.error(f"Client error from {client_id}: {data}")


def get_server_stats():
    """Get server statistics"""
    return {
        'total_clients': len(clients),
        'devices_count': len(devices),
        'viewers_count': len(viewers),
        'uptime': datetime.now().isoformat()
    }


@app.route('/stats')
def get_stats():
    """API endpoint to get server statistics"""
    return get_server_stats()


if __name__ == '__main__':
    logger.info("Starting Remote Screen Server...")
    logger.info("Server will be available at: http://0.0.0.0:3000")
    logger.info("Make sure you have created the 'templates' folder with 'index.html' inside")

    socketio.run(
        app,
        host='0.0.0.0',
        port=3000,
        debug=True,
        use_reloader=False
    )