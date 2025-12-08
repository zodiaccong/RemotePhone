package nmtpro.socmtool;

import android.content.Context;
import android.util.Log;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

import java.net.URISyntaxException;

public class SocketManager {
    private Socket socket;
    private Context context;
    private String serverIp;
    private String serverPort;
    private int displayWidth = 1080;
    private int displayHeight = 1920;

    private int swipeStartX = -1;
    private int swipeStartY = -1;
    private int lastMoveX = -1;
    private int lastMoveY = -1;
    private boolean isSwiping = false;

    public SocketManager(Context context, String serverIp, String serverPort) {
        this.context = context;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
    }

    public void connect() {
        try {
            String serverUrl = "http://" + serverIp + ":" + serverPort;
            Log.d("SocketManager", "Connecting to: " + serverUrl);

            IO.Options options = new IO.Options();
            options.forceNew = true;
            options.timeout = 10000; // 10 seconds timeout
            options.reconnection = true;
            options.reconnectionAttempts = 5;
            options.reconnectionDelay = 1000;

            socket = IO.socket(serverUrl, options);

            // Setup event listeners
            setupSocketEvents();

            socket.connect();

        } catch (URISyntaxException e) {
            Log.e("SocketManager", "Invalid server URL", e);
            e.printStackTrace();
        } catch (Exception e) {
            Log.e("SocketManager", "Connection error", e);
            e.printStackTrace();
        }
    }

    private void setupSocketEvents() {
        // Connection events
        socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d("SocketManager", "Connected to server");

                // Register as device
                registerDevice();
            }
        });

        socket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d("SocketManager", "Disconnected from server");
            }
        });

        socket.on(Socket.EVENT_CONNECT_ERROR, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.e("SocketManager", "Connection error: " + args[0]);
            }
        });

        // Custom events
        socket.on("device_registered", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d("SocketManager", "Device registered successfully");
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    try {
                        JSONObject data = (JSONObject) args[0];
                        Log.d("SocketManager", "Registration response: " + data.toString());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });

        socket.on("control", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d("SocketManager", "Received control command");
                try {
                    if (args.length > 0 && args[0] instanceof JSONObject) {
                        JSONObject data = (JSONObject) args[0];
                        handleControlCommand(data.toString());
                    } else if (args.length > 0) {
                        handleControlCommand(args[0].toString());
                    }
                } catch (Exception e) {
                    Log.e("SocketManager", "Error handling control command", e);
                    e.printStackTrace();
                }
            }
        });

        socket.on("viewer_connected", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d("SocketManager", "Viewer connected to this device");
            }
        });

        socket.on("viewer_disconnected", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d("SocketManager", "Viewer disconnected");
            }
        });

        socket.on("error", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.e("SocketManager", "Server error: " + args[0]);
            }
        });
    }

    private void registerDevice() {
        try {
            JSONObject deviceInfo = new JSONObject();
            deviceInfo.put("name", Build.MANUFACTURER + " " + Build.MODEL);
            deviceInfo.put("model", Build.MODEL);
            deviceInfo.put("screen_width", displayWidth);
            deviceInfo.put("screen_height", displayHeight);
            deviceInfo.put("android_version", Build.VERSION.RELEASE);
            deviceInfo.put("sdk_version", Build.VERSION.SDK_INT);

            Log.d("SocketManager", "Registering device: " + deviceInfo.toString());
            socket.emit("register_device", deviceInfo);

        } catch (JSONException e) {
            Log.e("SocketManager", "Error creating device info", e);
            e.printStackTrace();
        }
    }

    public void sendScreenData(String imageData) {
        if (isConnected()) {
            try {
                JSONObject data = new JSONObject();
                data.put("image_data", imageData);
                data.put("timestamp", System.currentTimeMillis());
                data.put("width", displayWidth);
                data.put("height", displayHeight);
                data.put("device_id", getDeviceId());

                socket.emit("screen_data", data);
                Log.d("SocketManager", "Screen data sent, size: " + imageData.length() + " bytes");

            } catch (JSONException e) {
                Log.e("SocketManager", "Error creating screen data JSON", e);
                e.printStackTrace();
            }
        } else {
            Log.w("SocketManager", "Cannot send screen data - socket not connected");
        }
    }

    public void sendScreenData(byte[] imageData) {
        if (isConnected()) {
            try {
                String base64Image = android.util.Base64.encodeToString(imageData, android.util.Base64.DEFAULT);

                JSONObject data = new JSONObject();
                data.put("image_data", base64Image);
                data.put("timestamp", System.currentTimeMillis());
                data.put("width", displayWidth);
                data.put("height", displayHeight);
                data.put("device_id", getDeviceId());
                socket.emit("screen_data", data);

            } catch (JSONException e) {
                Log.e("SocketManager", "❌ Error creating screen data JSON", e);
            }
        } else {
            Log.w("SocketManager", "⚠️ Socket not connected, cannot send screen data");
        }
    }

    private void handleControlCommand(String command) {
        Log.d("SocketManager", "Handling control command: " + command);

        try {
            JSONObject jsonCommand = new JSONObject(command);
            String type = jsonCommand.optString("type", "");
            String action = jsonCommand.optString("command", "");

            switch (type) {
                case "touch":
                    handleTouchCommand(jsonCommand);
                    break;
                case "key":
                    handleKeyCommand(jsonCommand);
                    break;
                case "scroll":
                    handleScrollCommand(jsonCommand);
                    break;
                default:
                    Log.w("SocketManager", "Unknown command type: " + type);
            }
        } catch (JSONException e) {
            Log.e("SocketManager", "Error parsing control command", e);
            e.printStackTrace();
        }
    }

    private void handleTouchCommand(JSONObject command) {
        try {
            String action = command.getString("command");
            JSONObject data = command.getJSONObject("data");

            int webX = data.getInt("x");
            int webY = data.getInt("y");
            int webImageWidth = data.optInt("image_width", 720);
            int webImageHeight = data.optInt("image_height", 1280);

            // Tính toán tọa độ thực
            int realX = (int) ((float) webX / webImageWidth * displayWidth);
            int realY = (int) ((float) webY / webImageHeight * displayHeight);

            MyAccessibilityService accessibilityService = MyAccessibilityService.getInstance();
            if (accessibilityService == null) {
                Log.e("SocketManager", "Accessibility service not available");
                return;
            }

            switch (action) {
                case "down":
                    // Lưu điểm bắt đầu
                    swipeStartX = realX;
                    swipeStartY = realY;

                    // BẮT ĐẦU GIỮ TẠI VỊ TRÍ NÀY (không kéo đi đâu cả)
                    boolean holdResult = accessibilityService.startSwipeAndHold(
                            realX, realY,  // start
                            realX, realY,  // end (cùng vị trí = không di chuyển)
                            100            // duration rất ngắn vì không cần di chuyển
                    );

                    Log.d("SocketManager", "🟢 Touch down and hold at (" + realX + ", " + realY + "): " + holdResult);
                    break;

                case "move":
                    // Di chuyển đến vị trí mới trong khi vẫn giữ
                    if (swipeStartX != -1 && swipeStartY != -1) {
                        if (accessibilityService.isCurrentlyHolding()) {
                            long moveDuration = calculateSwipeDuration(swipeStartX, swipeStartY, realX, realY);

                            // Tiếp tục kéo đến vị trí mới NHƯNG KHÔNG KẾT THÚC
                            boolean moveResult = accessibilityService.continueSwipeTo(
                                    realX, realY,
                                    moveDuration,
                                    false  // false = tiếp tục giữ
                            );

                            Log.d("SocketManager", "🔄 Touch move to (" + realX + ", " + realY + "): " + moveResult);

                            // Cập nhật vị trí hiện tại
                            swipeStartX = realX;
                            swipeStartY = realY;
                        } else {
                            Log.w("SocketManager", "⚠️ Not currently holding, cannot move");
                        }
                    }
                    break;

                case "up":
                    // Thả tay - kết thúc gesture
                    if (swipeStartX != -1 && swipeStartY != -1) {
                        if (accessibilityService.isCurrentlyHolding()) {
                            // Nếu vị trí cuối khác vị trí hiện tại -> kéo đến rồi thả
                            if (realX != swipeStartX || realY != swipeStartY) {
                                long duration = calculateSwipeDuration(swipeStartX, swipeStartY, realX, realY);

                                boolean result = accessibilityService.continueSwipeTo(
                                        realX, realY,
                                        duration,
                                        true  // true = kết thúc sau khi kéo
                                );

                                Log.d("SocketManager", "🎯 Touch up with move to (" + realX + ", " + realY + "): " + result);
                            } else {
                                // Nếu cùng vị trí -> chỉ cần thả
                                boolean result = accessibilityService.releaseHold();
                                Log.d("SocketManager", "🎯 Touch up at (" + realX + ", " + realY + "): " + result);
                            }
                        } else {
                            Log.w("SocketManager", "⚠️ Not currently holding on up event");
                        }
                    }

                    // Reset
                    swipeStartX = -1;
                    swipeStartY = -1;
                    break;

                case "tap":
                    // Tap đơn giản (không cần hold)
                    boolean tapResult = accessibilityService.performTap(realX, realY);
                    Log.d("SocketManager", "👆 Tap at (" + realX + ", " + realY + "): " + tapResult);
                    break;
            }

        } catch (JSONException e) {
            Log.e("SocketManager", "Error parsing touch command", e);
            e.printStackTrace();
        }
    }

    // Helper function (giữ nguyên)
    private long calculateSwipeDuration(int startX, int startY, int endX, int endY) {
        double distance = Math.sqrt(Math.pow(endX - startX, 2) + Math.pow(endY - startY, 2));
        // Tốc độ: 1 pixel = 0.5ms, tối thiểu 100ms, tối đa 1000ms
        long duration = (long) (distance * 0.5);
        return Math.max(1, Math.min(100, duration));
    }

    private void performSwipeSegment(int fromX, int fromY, int toX, int toY) {
        MyAccessibilityService accessibilityService = MyAccessibilityService.getInstance();
        if (accessibilityService != null) {
            // Tính khoảng cách để điều chỉnh duration
            double distance = Math.sqrt(Math.pow(toX - fromX, 2) + Math.pow(toY - fromY, 2));
            long duration = Math.max(50, Math.min(200, (long)(distance * 2))); // Duration tỷ lệ với khoảng cách

            boolean result = accessibilityService.performSwipe(fromX, fromY, toX, toY, duration);
            Log.d("SocketManager", "↗️ Swipe segment: " + result + " from (" + fromX + "," + fromY + ") to (" + toX + "," + toY + ") duration: " + duration + "ms");
        }
    }

    private void performFinalSwipe(int startX, int startY, int endX, int endY) {
        MyAccessibilityService accessibilityService = MyAccessibilityService.getInstance();
        if (accessibilityService != null) {
            // Tính khoảng cách tổng
            double totalDistance = Math.sqrt(Math.pow(endX - startX, 2) + Math.pow(endY - startY, 2));
            long duration = Math.max(100, Math.min(500, (long)(totalDistance * 1.5)));

            boolean result = accessibilityService.performSwipe(startX, startY, endX, endY, duration);
            Log.d("SocketManager", "🎯 Final swipe: " + result + " from (" + startX + "," + startY + ") to (" + endX + "," + endY + ") duration: " + duration + "ms");
        }
    }

    private void resetSwipeState() {
        swipeStartX = -1;
        swipeStartY = -1;
        lastMoveX = -1;
        lastMoveY = -1;
        isSwiping = false;
    }

    private void handleKeyCommand(JSONObject command) {
        try {
            String key = command.getJSONObject("data").getString("key");
            Log.d("SocketManager", "Key command: " + key);

            MyAccessibilityService accessibilityService = MyAccessibilityService.getInstance();
            if (accessibilityService == null) {
                Log.e("SocketManager", "Accessibility service not available");
                return;
            }

            switch (key) {
                case "home":
                    accessibilityService.performHome();
                    break;
                case "back":
                    accessibilityService.performBack();
                    break;
                case "recent":
                    accessibilityService.performRecents();
                    break;
            }

        } catch (JSONException e) {
            Log.e("SocketManager", "Error parsing key command", e);
            e.printStackTrace();
        }
    }

    private void handleScrollCommand(JSONObject command) {
        try {
            int dx = command.getJSONObject("data").getInt("dx");
            int dy = command.getJSONObject("data").getInt("dy");

            Log.d("SocketManager", "Scroll command: (" + dx + ", " + dy + ")");

            // Implement scroll injection here

        } catch (JSONException e) {
            Log.e("SocketManager", "Error parsing scroll command", e);
            e.printStackTrace();
        }
    }

    public boolean isConnected() {
        return socket != null && socket.connected();
    }

    public String getConnectionStatus() {
        if (socket == null) {
            return "Not initialized";
        } else if (socket.connected()) {
            return "Connected";
        } else {
            return "Disconnected";
        }
    }

    private String getDeviceId() {
        return Build.MANUFACTURER + "_" + Build.MODEL + "_" + Build.SERIAL;
    }

    public void setDisplayDimensions(int width, int height) {
        this.displayWidth = width;
        this.displayHeight = height;
    }

    public void disconnect() {
        if (socket != null) {
            socket.disconnect();
            socket.off(); // Remove all listeners
        }
    }

    public void reconnect() {
        disconnect();
        connect();
    }
}