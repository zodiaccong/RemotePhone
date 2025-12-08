package nmtpro.socmtool;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class MyAccessibilityService extends AccessibilityService {
    private static final String TAG = "MyAccessibilityService";
    private static MyAccessibilityService instance;

    // Biến quản lý gesture đang giữ
    private GestureDescription.StrokeDescription currentStroke;
    private boolean isHolding = false;
    private int currentX, currentY; // Lưu vị trí hiện tại

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Có thể xử lý events ở đây nếu cần
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted");
        // Hủy gesture nếu đang giữ
        if (isHolding) {
            forceReleaseHold();
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "Accessibility service connected");
    }

    public static MyAccessibilityService getInstance() {
        return instance;
    }

    public boolean performTap(int x, int y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);

            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                    path, 0, 50 // Tap trong 50ms
            );

            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(stroke);

            return dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            Log.e(TAG, "Error performing tap", e);
            return false;
        }
    }

    public boolean performSwipe(int startX, int startY, int endX, int endY, long duration) {
        try {
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);

            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                    path, 0, duration
            );

            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(stroke);

            return dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            Log.e(TAG, "Error performing swipe", e);
            return false;
        }
    }

    // ==================== GESTURE KÉO VÀ GIỮ ====================

    /**
     * Bắt đầu kéo từ điểm A đến điểm B và giữ tại đó
     * @param startX Tọa độ X điểm bắt đầu
     * @param startY Tọa độ Y điểm bắt đầu
     * @param endX Tọa độ X điểm kết thúc (điểm B)
     * @param endY Tọa độ Y điểm kết thúc (điểm B)
     * @param duration Thời gian kéo (ms)
     * @return true nếu thành công
     */
    public boolean startSwipeAndHold(int startX, int startY, int endX, int endY, long duration) {
        if (isHolding) {
            Log.w(TAG, "Đang có gesture giữ, hủy gesture cũ trước");
            forceReleaseHold();
        }

        try {
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);

            // willContinue = true: gesture chưa kết thúc
            currentStroke = new GestureDescription.StrokeDescription(
                    path, 0, duration, true
            );

            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(currentStroke);

            currentX = endX;
            currentY = endY;
            isHolding = true;

            boolean result = dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    Log.d(TAG, "Đã kéo đến (" + endX + ", " + endY + ") và đang giữ");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    Log.w(TAG, "Gesture bị hủy");
                    isHolding = false;
                    currentStroke = null;
                }
            }, null);

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Error starting swipe and hold", e);
            isHolding = false;
            currentStroke = null;
            return false;
        }
    }

    /**
     * Giữ tại vị trí hiện tại (không di chuyển)
     * @param holdDuration Thời gian giữ thêm (ms)
     * @return true nếu thành công
     */
    public boolean holdAt(long holdDuration) {
        if (!isHolding || currentStroke == null) {
            Log.w(TAG, "Không có gesture nào đang giữ");
            return false;
        }

        try {
            Path path = new Path();
            path.moveTo(currentX, currentY);
            path.lineTo(currentX, currentY); // Không di chuyển

            // Tiếp tục giữ
            currentStroke = currentStroke.continueStroke(path, 0, holdDuration, true);

            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(currentStroke);

            return dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    Log.d(TAG, "Đang tiếp tục giữ tại (" + currentX + ", " + currentY + ")");
                }
            }, null);

        } catch (Exception e) {
            Log.e(TAG, "Error holding position", e);
            isHolding = false;
            return false;
        }
    }

    /**
     * Thả tay - kết thúc gesture tại vị trí hiện tại
     * @return true nếu thành công
     */
    public boolean releaseHold() {
        if (!isHolding || currentStroke == null) {
            Log.w(TAG, "Không có gesture nào đang giữ");
            return false;
        }

        try {
            Path path = new Path();
            path.moveTo(currentX, currentY);

            // willContinue = false: kết thúc gesture
            GestureDescription.StrokeDescription endStroke =
                    currentStroke.continueStroke(path, 0, 1, false);

            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(endStroke);

            isHolding = false;
            currentStroke = null;

            boolean result = dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    Log.d(TAG, "Đã thả tay tại (" + currentX + ", " + currentY + ")");
                }
            }, null);

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Error releasing hold", e);
            isHolding = false;
            currentStroke = null;
            return false;
        }
    }

    /**
     * Tiếp tục kéo đến vị trí mới
     * @param endX Tọa độ X đích
     * @param endY Tọa độ Y đích
     * @param duration Thời gian kéo (ms)
     * @param shouldEnd true = kết thúc sau khi kéo, false = tiếp tục giữ
     * @return true nếu thành công
     */
    public boolean continueSwipeTo(int endX, int endY, long duration, boolean shouldEnd) {
        if (!isHolding || currentStroke == null) {
            Log.w(TAG, "Không có gesture nào đang giữ");
            return false;
        }

        try {
            Path path = new Path();
            path.moveTo(currentX, currentY);
            path.lineTo(endX, endY);

            // shouldEnd = true -> willContinue = false
            GestureDescription.StrokeDescription continueStroke =
                    currentStroke.continueStroke(path, 0, duration, !shouldEnd);

            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(continueStroke);

            int oldX = currentX;
            int oldY = currentY;
            currentX = endX;
            currentY = endY;

            if (shouldEnd) {
                isHolding = false;
                currentStroke = null;
            } else {
                currentStroke = continueStroke;
            }

            boolean result = dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    if (shouldEnd) {
                        Log.d(TAG, "Đã kéo từ (" + oldX + ", " + oldY + ") đến ("
                                + endX + ", " + endY + ") và kết thúc");
                    } else {
                        Log.d(TAG, "Đã kéo đến (" + endX + ", " + endY + ") và tiếp tục giữ");
                    }
                }
            }, null);

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Error continuing swipe", e);
            isHolding = false;
            currentStroke = null;
            return false;
        }
    }

    /**
     * Kiểm tra có đang giữ gesture không
     */
    public boolean isCurrentlyHolding() {
        return isHolding;
    }

    /**
     * Buộc hủy gesture đang giữ (dùng khi cần reset)
     */
    private void forceReleaseHold() {
        isHolding = false;
        currentStroke = null;
        Log.d(TAG, "Force release hold");
    }

    // ==================== CÁC HÀM GỐC ====================

    public boolean performBack() {
        try {
            return performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Exception e) {
            Log.e(TAG, "Error performing back action", e);
            return false;
        }
    }

    public boolean performHome() {
        try {
            return performGlobalAction(GLOBAL_ACTION_HOME);
        } catch (Exception e) {
            Log.e(TAG, "Error performing home action", e);
            return false;
        }
    }

    public boolean performRecents() {
        try {
            return performGlobalAction(GLOBAL_ACTION_RECENTS);
        } catch (Exception e) {
            Log.e(TAG, "Error performing recents action", e);
            return false;
        }
    }
}