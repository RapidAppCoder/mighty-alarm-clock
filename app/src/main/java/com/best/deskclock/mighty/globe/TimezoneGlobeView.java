// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.globe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.best.deskclock.data.City;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * A minimal custom view that draws an orthographic-projection "globe" (solid sphere with
 * longitude bands to suggest UTC time bands) and plots the user's selected world clock cities on
 * it as dots. The globe can be rotated (yaw only) by dragging horizontally.
 * <p>
 * Since {@link City} does not carry real latitude/longitude, longitude is derived from the city's
 * timezone UTC offset, and latitude is derived from a stable hash of the city id purely for
 * visual distribution purposes.
 */
public class TimezoneGlobeView extends View {

    private static final int BAND_COUNT = 6;

    private final Paint mSpherePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mReusableOval = new RectF();

    private float mYawDegrees = 0f;
    private float mLastTouchX;
    private List<City> mCities = new ArrayList<>();

    public TimezoneGlobeView(Context context) {
        this(context, null);
    }

    public TimezoneGlobeView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TimezoneGlobeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mSpherePaint.setStyle(Paint.Style.FILL);
        mSpherePaint.setColor(Color.parseColor("#FF1B3A5C"));

        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(dp(1.2f));
        mLinePaint.setColor(Color.parseColor("#552F6FA0"));

        mDotPaint.setStyle(Paint.Style.FILL);
        mDotPaint.setColor(Color.parseColor("#FFFFC107"));

        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextSize(dp(11f));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * Updates the set of cities plotted on the globe.
     */
    public void setCities(List<City> cities) {
        mCities = cities != null ? cities : new ArrayList<>();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        final float cx = w / 2f;
        final float cy = h / 2f;
        final float radius = Math.min(w, h) / 2f - dp(8f);
        if (radius <= 0) {
            return;
        }

        // Solid sphere.
        canvas.drawCircle(cx, cy, radius, mSpherePaint);

        // Longitude "UTC bands" drawn as vertically squashed ellipses that rotate with yaw, to
        // give the illusion of a rotating sphere.
        for (int i = 0; i < BAND_COUNT; i++) {
            final float angle = (i * 180f / BAND_COUNT + mYawDegrees) % 180f;
            final float bandHalfWidth = (float) Math.abs(Math.cos(Math.toRadians(angle))) * radius;
            mReusableOval.set(cx - bandHalfWidth, cy - radius, cx + bandHalfWidth, cy + radius);
            canvas.drawOval(mReusableOval, mLinePaint);
        }

        // Equator.
        canvas.drawLine(cx - radius, cy, cx + radius, cy, mLinePaint);

        for (City city : mCities) {
            plotCity(canvas, city, cx, cy, radius);
        }
    }

    private void plotCity(Canvas canvas, City city, float cx, float cy, float radius) {
        final TimeZone timeZone = city.getTimeZone();
        final float offsetHours = timeZone.getRawOffset() / 3_600_000f;
        final float longitude = offsetHours * 15f; // 15 degrees of longitude per UTC hour.
        final float latitude = (Math.abs(city.getId().hashCode()) % 140) - 70f; // Pseudo latitude in [-70, 70].

        final double lonRad = Math.toRadians(longitude + mYawDegrees);
        final double latRad = Math.toRadians(latitude);

        // Orthographic projection: only draw the point when it faces the viewer.
        final double x = Math.cos(latRad) * Math.sin(lonRad);
        final double depth = Math.cos(latRad) * Math.cos(lonRad);
        final double y = Math.sin(latRad);

        if (depth < 0) {
            return;
        }

        final float px = (float) (cx + x * radius * 0.9);
        final float py = (float) (cy - y * radius * 0.9);

        canvas.drawCircle(px, py, dp(4f), mDotPaint);
        canvas.drawText(city.getName(), px, py - dp(8f), mTextPaint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                mLastTouchX = event.getX();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                final float dx = event.getX() - mLastTouchX;
                mLastTouchX = event.getX();
                mYawDegrees = (mYawDegrees + dx * 0.5f) % 360f;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            }
            default -> {
                return super.onTouchEvent(event);
            }
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
