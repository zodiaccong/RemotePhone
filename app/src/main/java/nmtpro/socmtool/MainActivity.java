package nmtpro.socmtool;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 100;
    private static final int REQUEST_PERMISSIONS = 101;

    private MediaProjectionManager projectionManager;
    private SocketManager socketManager;

    // UI Components
    private Button startServiceButton, stopServiceButton, settingsButton, saveConfigButton;
    private EditText serverIpEditText, serverPortEditText;
    private TextView statusText, serverAddressText, logTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeUI();
        setupEventListeners();
        requestPermissions();
        loadServerConfig();
    }

    private void initializeUI() {
        // Initialize buttons
        startServiceButton = findViewById(R.id.startServiceButton);
        stopServiceButton = findViewById(R.id.stopServiceButton);
        settingsButton = findViewById(R.id.settingsButton);
        saveConfigButton = findViewById(R.id.saveConfigButton);

        // Initialize text fields
        serverIpEditText = findViewById(R.id.serverIpEditText);
        serverPortEditText = findViewById(R.id.serverPortEditText);

        // Initialize text views
        statusText = findViewById(R.id.statusText);
        serverAddressText = findViewById(R.id.serverAddressText);
        logTextView = findViewById(R.id.logTextView);

        // Set initial state
        stopServiceButton.setEnabled(false);
    }

    private void setupEventListeners() {
        startServiceButton.setOnClickListener(v -> startScreenCapture());

        stopServiceButton.setOnClickListener(v -> stopScreenCapture());

        saveConfigButton.setOnClickListener(v -> saveServerConfig());

        settingsButton.setOnClickListener(v -> showSettings());
    }

    private void saveServerConfig() {
        String serverIp = serverIpEditText.getText().toString().trim();
        String serverPort = serverPortEditText.getText().toString().trim();

        if (serverIp.isEmpty() || serverPort.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập đầy đủ thông tin server", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save to SharedPreferences
        SharedPreferences prefs = getSharedPreferences("ScreenCapturePrefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("server_ip", serverIp);
        editor.putString("server_port", serverPort);
        editor.apply();

        updateStatus("Đã lưu cấu hình server: " + serverIp + ":" + serverPort);
        addLog("Cấu hình server đã được cập nhật: " + serverIp + ":" + serverPort);

        // Update service if running
        updateServiceConfiguration(serverIp, serverPort);
    }

    private void updateServiceConfiguration(String serverIp, String serverPort) {
        // If service is running, update its configuration
        // You might need to implement a way to communicate with the running service
        // For now, we'll just reconnect the socket manager
        if (socketManager != null) {
            addLog("Đang kết nối lại với server mới...");
            socketManager.disconnect();
            socketManager = new SocketManager(this, serverIp, serverPort);
            socketManager.connect();
        }
    }

    private void loadServerConfig() {
        SharedPreferences prefs = getSharedPreferences("ScreenCapturePrefs", Context.MODE_PRIVATE);
        String serverIp = prefs.getString("server_ip", "192.168.5.214");
        String serverPort = prefs.getString("server_port", "3000");

        serverIpEditText.setText(serverIp);
        serverPortEditText.setText(serverPort);
        addLog("Đã tải cấu hình: " + serverIp + ":" + serverPort);
    }

    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean allGranted = true;
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
            } else {
                updateStatus("Đã có đủ quyền");
            }
        } else {
            updateStatus("Sẵn sàng");
        }
    }

    private void startScreenCapture() {
        String serverIp = serverIpEditText.getText().toString().trim();
        String serverPort = serverPortEditText.getText().toString().trim();

        if (serverIp.isEmpty() || serverPort.isEmpty()) {
            Toast.makeText(this, "Vui lòng cấu hình server IP và port trước", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save configuration first
        saveServerConfig();

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE);

        addLog("Đang yêu cầu quyền ghi màn hình...");
    }

    private void stopScreenCapture() {
        // Stop screen capture service
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        stopService(serviceIntent);

        // Disconnect socket
        if (socketManager != null) {
            socketManager.disconnect();
        }

        updateStatus("Đã dừng chia sẻ màn hình");
        stopServiceButton.setEnabled(false);
        startServiceButton.setEnabled(true);
        addLog("Đã dừng chia sẻ màn hình");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK) {
                // Start screen capture service as foreground service
                Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                serviceIntent.putExtra("resultCode", resultCode);
                serviceIntent.putExtra("data", data);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }

                updateStatus("Đang chia sẻ màn hình...");
                stopServiceButton.setEnabled(true);
                startServiceButton.setEnabled(false);
                addLog("Bắt đầu chia sẻ màn hình");
                addLog("Kết nối đến server: " + serverIpEditText.getText().toString() + ":" + serverPortEditText.getText().toString());
            } else {
                Toast.makeText(this, "Cần quyền ghi màn hình để tiếp tục", Toast.LENGTH_SHORT).show();
                addLog("Người dùng từ chối quyền ghi màn hình");
            }
        }
    }

    // Trong MainActivity class
    private void updateConnectionStatus() {
        runOnUiThread(() -> {
            if (socketManager != null) {
                String status = socketManager.getConnectionStatus();
                serverAddressText.setText("Server: " + status);

                if (socketManager.isConnected()) {
                    statusText.setText("Đã kết nối server");
                    statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
                } else {
                    statusText.setText("Mất kết nối server");
                    statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                }
            }
        });
    }

    // Gọi phương thức này khi kết nối/thay đổi trạng thái
    private void addLog(String log) {
        runOnUiThread(() -> {
            String currentLog = logTextView.getText().toString();
            String newLog = currentLog + "\n" + System.currentTimeMillis() + ": " + log;
            logTextView.setText(newLog);

            // Auto-scroll to bottom
            logTextView.post(() -> {
                ScrollView scrollView = findViewById(R.id.logScrollView);
                if (scrollView != null) {
                    scrollView.fullScroll(View.FOCUS_DOWN);
                }
            });
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                updateStatus("Đã có đủ quyền");
                addLog("Tất cả quyền đã được cấp");
            } else {
                updateStatus("Thiếu quyền cần thiết");
                addLog("Một số quyền chưa được cấp");
            }
        }
    }

    private void updateStatus(String message) {
        runOnUiThread(() -> statusText.setText(message));
    }

    private void showSettings() {
        // Implement settings dialog or activity
        Toast.makeText(this, "Mở cài đặt", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (socketManager != null) {
            socketManager.disconnect();
        }
    }
}
