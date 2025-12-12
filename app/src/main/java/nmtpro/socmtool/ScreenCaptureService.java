package nmtpro.socmtool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {
    private static final String TAG = "ScreenCaptureService";

    // Notification
    private static final String CHANNEL_ID = "ScreenCaptureChannel";
    private static final int NOTIFICATION_ID = 1;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private SocketManager socketManager;
    private Handler captureHandler;

    private int screenDensity;
    private int displayWidth = 1080;
    private int displayHeight = 1920;

    // Capture settings - TỐI ƯU FPS
    private static final int CAPTURE_FPS = 15; // GIẢM XUỐNG 15 FPS
    private static final long FRAME_INTERVAL = 1000 / CAPTURE_FPS;
    private int frameCount = 0;
    private boolean isCapturing = false;

    // Backpressure control
    private static final int MAX_QUEUED_FRAMES = 3;
    private int queuedFrames = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ScreenCaptureService created");

        createNotificationChannel();

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenDensity = metrics.densityDpi;
        displayWidth = metrics.widthPixels;
        displayHeight = metrics.heightPixels;

        Log.d(TAG, "Display metrics: " + displayWidth + "x" + displayHeight + " density: " + screenDensity);

        initializeSocketManager();
        captureHandler = new Handler();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ScreenCaptureService starting");

        if (intent == null) {
            Log.e(TAG, "Start command intent is null");
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");

        if (data == null) {
            Log.e(TAG, "MediaProjection data is null");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startForegroundService();

            MediaProjectionManager projectionManager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

            if (projectionManager == null) {
                Log.e(TAG, "MediaProjectionManager is null");
                stopSelf();
                return START_NOT_STICKY;
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, data);

            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null");
                stopSelf();
                return START_NOT_STICKY;
            }

            mediaProjection.registerCallback(new MediaProjectionCallback(), null);
            startCapture();

        } catch (Exception e) {
            Log.e(TAG, "Error starting screen capture", e);
            stopSelf();
        }

        return START_STICKY;
    }

    private void startForegroundService() {
        Notification notification = createNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        Log.d(TAG, "Foreground service started");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Capture Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Service for capturing screen content");

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Remote Screen Sharing")
                    .setContentText("Đang chia sẻ màn hình...")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .build();
        } else {
            return new Notification.Builder(this)
                    .setContentTitle("Remote Screen Sharing")
                    .setContentText("Đang chia sẻ màn hình...")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .build();
        }
    }

    private void initializeSocketManager() {
        try {
            SharedPreferences prefs = getSharedPreferences("ScreenCapturePrefs", Context.MODE_PRIVATE);
            String serverIp = prefs.getString("server_ip", null);
            String serverPort = prefs.getString("server_port", null);
            
            if (serverIp == null || serverPort == null) {
                Log.w(TAG, "Server config not found, skipping socket connection");
                return;
            }

            Log.d(TAG, "Initializing SocketManager with: " + serverIp + ":" + serverPort);

            socketManager = new SocketManager(this, serverIp, serverPort);
            socketManager.setDisplayDimensions(displayWidth, displayHeight);
            socketManager.connect();

        } catch (Exception e) {
            Log.e(TAG, "Error initializing SocketManager", e);
        }
    }

    private void startCapture() {
        try {
            // LẤY KÍCH THƯỚC MÀN HÌNH THỰC
            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            int captureWidth = 1080;
            int captureHeight = 1920;
            if (windowManager != null) {
                Display display = windowManager.getDefaultDisplay();
                display.getRealMetrics(metrics); // getRealMetrics để lấy kích thước thực (bao gồm status bar, navigation bar)

                captureWidth = metrics.widthPixels;
                captureHeight = metrics.heightPixels;
                screenDensity = metrics.densityDpi;

                Log.d(TAG, "Real screen size: " + captureWidth + "x" + captureHeight + ", density: " + screenDensity);

                // Hoặc nếu bạn muốn lấy kích thước không bao gồm navigation bar
                // display.getMetrics(metrics);
                // int captureWidth = metrics.widthPixels;
                // int captureHeight = metrics.heightPixels;
            } else {
                // Fallback nếu không lấy được
                screenDensity = DisplayMetrics.DENSITY_DEFAULT;
                Log.w(TAG, "Using fallback screen size: " + captureWidth + "x" + captureHeight);
            }

            Log.d(TAG, "Starting capture with REAL screen size: " + captureWidth + "x" + captureHeight);

            imageReader = ImageReader.newInstance(
                    captureWidth,
                    captureHeight,
                    android.graphics.PixelFormat.RGBA_8888,
                    2
            );

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    captureWidth,
                    captureHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    null
            );

            if (virtualDisplay != null) {
                Log.d(TAG, "VirtualDisplay created successfully with real screen size");
                isCapturing = true;
                startOptimizedCapture();
            } else {
                Log.e(TAG, "VirtualDisplay creation failed");
                stopSelf();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error starting capture", e);
            stopSelf();
        }
    }

    private void startOptimizedCapture() {
        Log.d(TAG, "Starting optimized capture at " + CAPTURE_FPS + " FPS");

        final Runnable captureRunnable = new Runnable() {
            @Override
            public void run() {
                if (isCapturing && queuedFrames < MAX_QUEUED_FRAMES) {
                    captureFrame();
                }

                if (isCapturing && captureHandler != null) {
                    captureHandler.postDelayed(this, FRAME_INTERVAL);
                }
            }
        };

        captureHandler.postDelayed(captureRunnable, 1000);
    }

    private void captureFrame() {
        try {
            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                queuedFrames++;
                frameCount++;

                // Xử lý trong thread riêng để không block main thread
                new Thread(() -> {
                    processRealImage(image);
                    image.close();
                    queuedFrames--;
                }).start();

                if (frameCount % 30 == 0) { // Log ít hơn
                    Log.d(TAG, "Captured " + frameCount + " frames");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error capturing frame", e);
        }
    }

    private void processRealImage(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) return;

            ByteBuffer buffer = planes[0].getBuffer();
            if (buffer == null) return;

            int imageSize = buffer.remaining();
            if (imageSize <= 0) return;

            byte[] rgbaData = new byte[imageSize];
            buffer.get(rgbaData);

            // TĂNG chất lượng JPEG
            byte[] jpegData = convertToHighQualityJpeg(rgbaData, image.getWidth(), image.getHeight());

            if (jpegData != null && jpegData.length > 0 &&
                    socketManager != null && socketManager.isConnected()) {
                socketManager.sendScreenData(jpegData);

                if (frameCount % 10 == 0) {
                    Log.d(TAG, "📤 Sent HD frame #" + frameCount + ": " + jpegData.length + " bytes");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing real image", e);
        }
    }

    private byte[] convertToHighQualityJpeg(byte[] rgbaData, int width, int height) {
        try {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height,
                    android.graphics.Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgbaData));

            // TĂNG chất lượng lên 85% và tối ưu hóa
            java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream);
            byte[] jpegData = stream.toByteArray();
            stream.close();
            bitmap.recycle();
            return jpegData;

        } catch (Exception e) {
            Log.e(TAG, "Error converting to high quality JPEG", e);
            return null;
        }
    }

    private class MediaProjectionCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            Log.d(TAG, "MediaProjection stopped");
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "ScreenCaptureService destroying");

        isCapturing = false;

        if (captureHandler != null) {
            captureHandler.removeCallbacksAndMessages(null);
        }

        stopForeground(true);

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        if (socketManager != null) {
            socketManager.disconnect();
            socketManager = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
