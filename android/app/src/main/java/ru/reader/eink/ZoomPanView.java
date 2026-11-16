package ru.reader.eink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

final class ZoomPanView extends View {
    interface Listener {
        void onTapAtFit();
        void onZoomChanged(float zoom);
    }

    private static final float[] ZOOM_STEPS = {1f, 2f, 3f, 4f};
    private static final long REDRAW_INTERVAL_MS = 150;

    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float touchSlop;
    private Bitmap bitmap;
    private Listener listener;
    private float zoom = 1f;
    private float panX;
    private float panY;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private boolean moved;
    private long lastRedraw;

    ZoomPanView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        zoom = 1f;
        panX = 0f;
        panY = 0f;
        invalidate();
    }

    void zoomStep(int direction) {
        int index = 0;
        for (int i = 0; i < ZOOM_STEPS.length; i++) {
            if (ZOOM_STEPS[i] <= zoom + 0.01f) {
                index = i;
            }
        }
        int next = Math.max(0, Math.min(ZOOM_STEPS.length - 1, index + direction));
        setZoom(ZOOM_STEPS[next]);
    }

    private void setZoom(float newZoom) {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float clamped = Math.max(1f, Math.min(ZOOM_STEPS[ZOOM_STEPS.length - 1], newZoom));
        float ratio = clamped / zoom;
        panX *= ratio;
        panY *= ratio;
        zoom = clamped;
        clampPan();
        invalidate();
        if (listener != null) {
            listener.onZoomChanged(zoom);
        }
    }

    private float fitScale() {
        return Math.min(getWidth() / (float) bitmap.getWidth(),
                getHeight() / (float) bitmap.getHeight());
    }

    private void clampPan() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float scale = fitScale() * zoom;
        float maxX = Math.max(0f, (bitmap.getWidth() * scale - getWidth()) / 2f);
        float maxY = Math.max(0f, (bitmap.getHeight() * scale - getHeight()) / 2f);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        clampPan();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float scale = fitScale() * zoom;
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate((getWidth() - bitmap.getWidth() * scale) / 2f + panX,
                (getHeight() - bitmap.getHeight() * scale) / 2f + panY);
        canvas.drawBitmap(bitmap, matrix, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) {
            return false;
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > touchSlop
                        || Math.abs(event.getY() - downY) > touchSlop) {
                    moved = true;
                }
                if (zoom > 1f) {
                    panX += event.getX() - lastX;
                    panY += event.getY() - lastY;
                    clampPan();
                    long now = SystemClock.uptimeMillis();
                    if (now - lastRedraw >= REDRAW_INTERVAL_MS) {
                        lastRedraw = now;
                        invalidate();
                    }
                }
                lastX = event.getX();
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (zoom > 1f) {
                    invalidate();
                } else if (!moved && listener != null) {
                    listener.onTapAtFit();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (zoom > 1f) {
                    invalidate();
                }
                return true;
            default:
                return true;
        }
    }
}
