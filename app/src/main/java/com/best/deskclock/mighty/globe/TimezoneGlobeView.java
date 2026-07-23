// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.globe;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.best.deskclock.data.City;

import java.util.ArrayList;
import java.util.List;

/**
 * Orthographic globe with filled continent outlines and markers for selected world-clock cities.
 * Horizontal drag rotates the globe; tapping a city (on the globe or via {@link #focusCity})
 * animates yaw so that city faces the viewer.
 */
public class TimezoneGlobeView extends View {

    private static final int BAND_COUNT = 6;
    private static final int EDGE_SUBDIVISIONS = 8;
    private static final float HIT_RADIUS_DP = 18f;

    /**
     * Continent outlines as closed [lat, lon] rings (more precise than the previous coarse blobs).
     */
    private static final float[][][] CONTINENTS = {
        // Africa
        {
            {37.0f, -9.5f}, {37.2f, -7.0f}, {36.8f, -2.0f}, {37.0f, 3.0f}, {37.1f, 9.5f},
            {36.0f, 10.5f}, {33.0f, 11.5f}, {32.5f, 15.0f}, {32.0f, 25.0f}, {31.2f, 32.5f},
            {29.5f, 34.9f}, {22.0f, 37.0f}, {15.0f, 42.0f}, {12.0f, 51.0f}, {5.0f, 48.0f},
            {-1.0f, 42.0f}, {-5.0f, 39.0f}, {-12.0f, 40.5f}, {-19.0f, 36.0f}, {-26.0f, 33.0f},
            {-34.8f, 25.5f}, {-34.8f, 20.0f}, {-34.0f, 18.0f}, {-28.0f, 16.0f}, {-22.0f, 14.0f},
            {-17.0f, 11.5f}, {-12.0f, 13.0f}, {-6.0f, 12.0f}, {0.0f, 9.0f}, {4.5f, 7.0f},
            {5.0f, -1.0f}, {4.5f, -8.0f}, {6.0f, -12.0f}, {10.0f, -16.0f}, {14.5f, -17.5f},
            {20.0f, -17.0f}, {24.0f, -16.0f}, {28.0f, -13.0f}, {32.0f, -9.5f}, {35.5f, -6.0f},
            {37.0f, -9.5f}
        },
        // Europe (incl. Scandinavia / UK)
        {
            {71.0f, -8.0f}, {70.5f, 5.0f}, {71.0f, 25.0f}, {70.0f, 30.0f}, {69.0f, 33.0f},
            {66.0f, 30.0f}, {64.0f, 40.0f}, {60.0f, 30.0f}, {56.0f, 28.0f}, {54.0f, 20.0f},
            {50.0f, 30.0f}, {46.0f, 35.0f}, {42.0f, 28.0f}, {41.0f, 29.0f}, {39.0f, 26.0f},
            {36.5f, 28.0f}, {36.0f, 22.0f}, {37.5f, 15.0f}, {38.0f, 12.0f}, {36.5f, -5.5f},
            {38.5f, -9.5f}, {43.0f, -9.5f}, {48.0f, -5.0f}, {50.0f, -5.5f}, {52.0f, -10.0f},
            {54.0f, -8.0f}, {58.0f, -7.0f}, {59.5f, -3.0f}, {62.0f, -7.0f}, {65.0f, -14.0f},
            {66.0f, -18.0f}, {65.0f, -22.0f}, {64.0f, -22.0f}, {63.5f, -16.0f}, {66.0f, -14.0f},
            {70.0f, -18.0f}, {71.0f, -8.0f}
        },
        // Asia
        {
            {77.0f, 60.0f}, {77.0f, 100.0f}, {75.0f, 140.0f}, {70.0f, 160.0f}, {65.0f, 170.0f},
            {60.0f, 165.0f}, {55.0f, 160.0f}, {50.0f, 155.0f}, {45.0f, 145.0f}, {42.0f, 142.0f},
            {35.0f, 140.0f}, {33.0f, 130.0f}, {30.0f, 122.0f}, {22.0f, 120.0f}, {18.0f, 110.0f},
            {10.0f, 105.0f}, {5.0f, 100.0f}, {1.0f, 104.0f}, {-5.0f, 105.0f}, {-8.0f, 115.0f},
            {-10.0f, 120.0f}, {-8.0f, 130.0f}, {-5.0f, 140.0f}, {0.0f, 130.0f}, {5.0f, 120.0f},
            {10.0f, 100.0f}, {15.0f, 95.0f}, {20.0f, 90.0f}, {22.0f, 70.0f}, {25.0f, 60.0f},
            {28.0f, 50.0f}, {30.0f, 48.0f}, {35.0f, 45.0f}, {40.0f, 44.0f}, {45.0f, 48.0f},
            {50.0f, 50.0f}, {55.0f, 45.0f}, {60.0f, 50.0f}, {65.0f, 55.0f}, {70.0f, 60.0f},
            {77.0f, 60.0f}
        },
        // North America
        {
            {71.0f, -156.0f}, {70.0f, -140.0f}, {70.0f, -120.0f}, {72.0f, -95.0f}, {70.0f, -80.0f},
            {65.0f, -65.0f}, {60.0f, -64.0f}, {55.0f, -60.0f}, {50.0f, -55.0f}, {47.0f, -52.0f},
            {45.0f, -60.0f}, {42.0f, -70.0f}, {35.0f, -75.0f}, {30.0f, -81.0f}, {25.0f, -80.0f},
            {24.0f, -82.0f}, {22.0f, -85.0f}, {18.0f, -88.0f}, {15.0f, -90.0f}, {14.0f, -92.0f},
            {16.0f, -98.0f}, {20.0f, -105.0f}, {23.0f, -110.0f}, {28.0f, -114.0f}, {32.0f, -117.0f},
            {35.0f, -121.0f}, {40.0f, -124.0f}, {45.0f, -124.0f}, {48.0f, -125.0f}, {50.0f, -128.0f},
            {55.0f, -132.0f}, {58.0f, -138.0f}, {60.0f, -146.0f}, {62.0f, -150.0f}, {65.0f, -155.0f},
            {68.0f, -165.0f}, {70.0f, -162.0f}, {71.0f, -156.0f}
        },
        // Central America / Mexico tip already in NA; add Greenland
        {
            {83.0f, -40.0f}, {80.0f, -20.0f}, {75.0f, -18.0f}, {70.0f, -22.0f}, {65.0f, -38.0f},
            {60.0f, -44.0f}, {60.0f, -50.0f}, {65.0f, -53.0f}, {70.0f, -54.0f}, {75.0f, -60.0f},
            {78.0f, -70.0f}, {80.0f, -65.0f}, {83.0f, -40.0f}
        },
        // South America
        {
            {12.5f, -71.0f}, {12.0f, -62.0f}, {10.0f, -61.0f}, {8.0f, -59.0f}, {5.0f, -52.0f},
            {2.0f, -50.0f}, {-2.0f, -44.0f}, {-5.0f, -35.0f}, {-10.0f, -36.0f}, {-15.0f, -39.0f},
            {-20.0f, -40.0f}, {-25.0f, -48.0f}, {-30.0f, -50.0f}, {-34.0f, -53.0f}, {-40.0f, -62.0f},
            {-45.0f, -66.0f}, {-50.0f, -69.0f}, {-54.0f, -68.0f}, {-55.0f, -67.0f}, {-54.0f, -70.0f},
            {-50.0f, -74.0f}, {-45.0f, -74.0f}, {-40.0f, -73.5f}, {-30.0f, -71.5f}, {-20.0f, -70.5f},
            {-15.0f, -75.5f}, {-10.0f, -78.0f}, {-5.0f, -81.0f}, {0.0f, -80.0f}, {5.0f, -77.0f},
            {10.0f, -75.0f}, {12.5f, -71.0f}
        },
        // Australia
        {
            {-10.5f, 142.0f}, {-12.0f, 136.0f}, {-14.0f, 130.0f}, {-16.0f, 124.0f}, {-20.0f, 118.0f},
            {-25.0f, 114.0f}, {-32.0f, 115.5f}, {-35.0f, 118.0f}, {-35.5f, 125.0f}, {-34.0f, 135.0f},
            {-38.0f, 141.0f}, {-39.0f, 146.0f}, {-37.5f, 150.0f}, {-32.0f, 152.5f}, {-28.0f, 153.5f},
            {-22.0f, 150.0f}, {-18.0f, 146.0f}, {-15.0f, 145.0f}, {-12.0f, 143.0f}, {-10.5f, 142.0f}
        },
        // New Zealand (North + South Island simplified)
        {
            {-34.5f, 173.0f}, {-36.0f, 174.5f}, {-38.0f, 178.0f}, {-41.5f, 175.0f}, {-41.0f, 173.0f},
            {-39.0f, 174.0f}, {-36.5f, 174.0f}, {-34.5f, 173.0f}
        },
        {
            {-40.5f, 172.5f}, {-41.5f, 174.0f}, {-43.0f, 173.0f}, {-46.5f, 168.0f}, {-46.0f, 166.5f},
            {-44.0f, 167.5f}, {-42.0f, 171.0f}, {-40.5f, 172.5f}
        },
        // Madagascar
        {
            {-12.0f, 49.5f}, {-15.0f, 50.5f}, {-20.0f, 48.5f}, {-25.5f, 47.0f}, {-25.0f, 44.0f},
            {-22.0f, 43.2f}, {-16.0f, 44.5f}, {-13.0f, 48.0f}, {-12.0f, 49.5f}
        },
        // Japan
        {
            {45.5f, 141.5f}, {43.0f, 145.5f}, {41.5f, 141.0f}, {38.0f, 141.0f}, {35.0f, 140.0f},
            {34.0f, 136.0f}, {33.0f, 133.0f}, {31.0f, 131.0f}, {33.5f, 130.0f}, {35.5f, 133.0f},
            {37.0f, 137.0f}, {40.0f, 140.0f}, {43.0f, 141.0f}, {45.5f, 141.5f}
        },
        // British Isles (sharper than Europe blob)
        {
            {58.5f, -5.0f}, {57.5f, -2.0f}, {55.0f, -1.5f}, {53.5f, 0.0f}, {51.5f, 1.5f},
            {50.5f, -1.0f}, {50.0f, -5.5f}, {51.5f, -5.0f}, {53.0f, -4.5f}, {54.5f, -5.5f},
            {55.5f, -6.5f}, {57.0f, -6.0f}, {58.5f, -5.0f}
        },
        {
            {55.3f, -6.0f}, {55.0f, -7.5f}, {54.0f, -10.0f}, {52.0f, -10.0f}, {51.5f, -9.5f},
            {52.0f, -6.0f}, {53.5f, -6.0f}, {54.5f, -5.5f}, {55.3f, -6.0f}
        },
        // Antarctica (split so dateline does not tear the fill)
        {
            {-70.0f, -170.0f}, {-68.0f, -120.0f}, {-66.0f, -60.0f}, {-65.0f, 0.0f}, {-66.0f, 60.0f},
            {-68.0f, 120.0f}, {-70.0f, 170.0f}, {-78.0f, 170.0f}, {-82.0f, 120.0f}, {-84.0f, 60.0f},
            {-85.0f, 0.0f}, {-84.0f, -60.0f}, {-82.0f, -120.0f}, {-78.0f, -170.0f}, {-70.0f, -170.0f}
        }
    };

    private final Paint mSpherePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mContinentFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mContinentStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFocusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mReusableOval = new RectF();
    private final Path mContinentPath = new Path();
    private final Path mSphereClipPath = new Path();

    private float mYawDegrees = 0f;
    private float mLastTouchX;
    private float mDownX;
    private float mDownY;
    private boolean mMoved;
    private List<City> mCities = new ArrayList<>();
    private String mFocusedCityId;
    private ValueAnimator mYawAnimator;

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
        mSpherePaint.setColor(Color.parseColor("#FF0E2A45"));

        mBandPaint.setStyle(Paint.Style.STROKE);
        mBandPaint.setStrokeWidth(dp(1.0f));
        mBandPaint.setColor(Color.parseColor("#443A7CA8"));

        mContinentFillPaint.setStyle(Paint.Style.FILL);
        mContinentFillPaint.setColor(Color.parseColor("#CC4CAF70"));

        mContinentStrokePaint.setStyle(Paint.Style.STROKE);
        mContinentStrokePaint.setStrokeWidth(dp(1.2f));
        mContinentStrokePaint.setColor(Color.parseColor("#EE2E6B45"));
        mContinentStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        mDotPaint.setStyle(Paint.Style.FILL);
        mDotPaint.setColor(Color.parseColor("#FFFFC107"));

        mDotRingPaint.setStyle(Paint.Style.STROKE);
        mDotRingPaint.setStrokeWidth(dp(1.5f));
        mDotRingPaint.setColor(Color.WHITE);

        mFocusPaint.setStyle(Paint.Style.STROKE);
        mFocusPaint.setStrokeWidth(dp(2.5f));
        mFocusPaint.setColor(Color.parseColor("#FFFF5722"));

        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextSize(dp(11f));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setFakeBoldText(true);

        mTextHaloPaint.setColor(Color.parseColor("#CC000000"));
        mTextHaloPaint.setTextSize(dp(11f));
        mTextHaloPaint.setTextAlign(Paint.Align.CENTER);
        mTextHaloPaint.setFakeBoldText(true);
    }

    /**
     * Updates the set of cities plotted on the globe (typically the user's selected world clocks).
     */
    public void setCities(List<City> cities) {
        mCities = cities != null ? new ArrayList<>(cities) : new ArrayList<>();
        invalidate();
    }

    /**
     * Animates the globe so {@code city} faces the viewer and highlights its marker.
     */
    public void focusCity(City city) {
        if (city == null) {
            return;
        }
        mFocusedCityId = city.getId();
        final float[] coords = CityCoordinates.forCity(city);
        final float targetYaw = normalizeDegrees(-coords[1]);
        animateYawTo(targetYaw);
    }

    private void animateYawTo(float targetYaw) {
        if (mYawAnimator != null) {
            mYawAnimator.cancel();
        }
        float from = normalizeDegrees(mYawDegrees);
        float to = targetYaw;
        float delta = to - from;
        if (delta > 180f) {
            delta -= 360f;
        } else if (delta < -180f) {
            delta += 360f;
        }
        final float start = from;
        final float change = delta;
        mYawAnimator = ValueAnimator.ofFloat(0f, 1f);
        mYawAnimator.setDuration(450);
        mYawAnimator.setInterpolator(new DecelerateInterpolator());
        mYawAnimator.addUpdateListener(animation -> {
            final float t = (float) animation.getAnimatedValue();
            mYawDegrees = normalizeDegrees(start + change * t);
            invalidate();
        });
        mYawAnimator.start();
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

        canvas.drawCircle(cx, cy, radius, mSpherePaint);

        for (int i = 0; i < BAND_COUNT; i++) {
            final float angle = (i * 180f / BAND_COUNT + mYawDegrees) % 180f;
            final float bandHalfWidth = (float) Math.abs(Math.cos(Math.toRadians(angle))) * radius;
            mReusableOval.set(cx - bandHalfWidth, cy - radius, cx + bandHalfWidth, cy + radius);
            canvas.drawOval(mReusableOval, mBandPaint);
        }
        canvas.drawLine(cx - radius, cy, cx + radius, cy, mBandPaint);

        final int save = canvas.save();
        mSphereClipPath.reset();
        mSphereClipPath.addCircle(cx, cy, radius, Path.Direction.CW);
        canvas.clipPath(mSphereClipPath);
        drawContinents(canvas, cx, cy, radius);
        canvas.restoreToCount(save);

        for (City city : mCities) {
            plotCity(canvas, city, cx, cy, radius);
        }
    }

    private void drawContinents(Canvas canvas, float cx, float cy, float radius) {
        for (float[][] continent : CONTINENTS) {
            drawContinentRing(canvas, continent, cx, cy, radius);
        }
    }

    /**
     * Projects a closed lat/lon ring with edge subdivision and draws filled + stroked visible segments.
     */
    private void drawContinentRing(Canvas canvas, float[][] ring, float cx, float cy, float radius) {
        if (ring.length < 3) {
            return;
        }

        mContinentPath.reset();
        boolean started = false;
        float firstX = 0f;
        float firstY = 0f;
        float prevX = 0f;
        float prevY = 0f;
        boolean prevFront = false;

        for (int i = 0; i < ring.length - 1; i++) {
            final float lat0 = ring[i][0];
            final float lon0 = ring[i][1];
            final float lat1 = ring[i + 1][0];
            final float lon1 = ring[i + 1][1];

            for (int s = 0; s < EDGE_SUBDIVISIONS; s++) {
                final float t = s / (float) EDGE_SUBDIVISIONS;
                final float lat = lat0 + (lat1 - lat0) * t;
                float lonDelta = lon1 - lon0;
                if (lonDelta > 180f) {
                    lonDelta -= 360f;
                } else if (lonDelta < -180f) {
                    lonDelta += 360f;
                }
                final float lon = lon0 + lonDelta * t;

                final float[] proj = project(lat, lon, cx, cy, radius, false);
                final boolean front = proj != null;
                if (front) {
                    if (!started) {
                        mContinentPath.moveTo(proj[0], proj[1]);
                        firstX = proj[0];
                        firstY = proj[1];
                        started = true;
                    } else if (prevFront) {
                        mContinentPath.lineTo(proj[0], proj[1]);
                    } else {
                        // Came back from the far side: start a new sub-path.
                        flushContinentPath(canvas);
                        mContinentPath.moveTo(proj[0], proj[1]);
                        firstX = proj[0];
                        firstY = proj[1];
                        started = true;
                    }
                    prevX = proj[0];
                    prevY = proj[1];
                } else if (started && prevFront) {
                    flushContinentPath(canvas);
                    started = false;
                }
                prevFront = front;
            }
        }

        if (started) {
            // Close only if the first and last points are both on the front and nearby.
            final float dist = (float) Math.hypot(prevX - firstX, prevY - firstY);
            if (dist < radius * 0.35f) {
                mContinentPath.close();
            }
            flushContinentPath(canvas);
        }
    }

    private void flushContinentPath(Canvas canvas) {
        if (!mContinentPath.isEmpty()) {
            canvas.drawPath(mContinentPath, mContinentFillPaint);
            canvas.drawPath(mContinentPath, mContinentStrokePaint);
            mContinentPath.reset();
        }
    }

    /**
     * Orthographic projection. Returns null when the point faces away (unless {@code allowBack}).
     * Result: {@code float[]{x, y, depth}} when allowBack, else {@code float[]{x, y}}.
     */
    private float[] project(float latitude, float longitude, float cx, float cy, float radius,
                            boolean allowBack) {
        final double lonRad = Math.toRadians(longitude + mYawDegrees);
        final double latRad = Math.toRadians(latitude);

        final double x = Math.cos(latRad) * Math.sin(lonRad);
        final double depth = Math.cos(latRad) * Math.cos(lonRad);
        final double y = Math.sin(latRad);

        if (!allowBack && depth < 0) {
            return null;
        }

        final float px = (float) (cx + x * radius * 0.92);
        final float py = (float) (cy - y * radius * 0.92);
        if (allowBack) {
            return new float[]{px, py, (float) depth};
        }
        return new float[]{px, py};
    }

    private void plotCity(Canvas canvas, City city, float cx, float cy, float radius) {
        final float[] coords = CityCoordinates.forCity(city);
        final float[] projected = project(coords[0], coords[1], cx, cy, radius, false);
        if (projected == null) {
            return;
        }

        final boolean focused = mFocusedCityId != null && mFocusedCityId.equals(city.getId());
        final float dotR = dp(focused ? 6.5f : 5f);

        canvas.drawCircle(projected[0], projected[1], dotR + dp(2f), mDotRingPaint);
        canvas.drawCircle(projected[0], projected[1], dotR, mDotPaint);
        if (focused) {
            canvas.drawCircle(projected[0], projected[1], dotR + dp(5f), mFocusPaint);
        }

        final float textY = projected[1] - dp(focused ? 12f : 10f);
        canvas.drawText(city.getName(), projected[0] + dp(0.8f), textY + dp(0.8f), mTextHaloPaint);
        canvas.drawText(city.getName(), projected[0], textY, mTextPaint);
    }

    private City hitTestCity(float touchX, float touchY) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0 || mCities.isEmpty()) {
            return null;
        }
        final float cx = w / 2f;
        final float cy = h / 2f;
        final float radius = Math.min(w, h) / 2f - dp(8f);
        final float hitR = dp(HIT_RADIUS_DP);

        City best = null;
        float bestDist = hitR * hitR;
        for (City city : mCities) {
            final float[] coords = CityCoordinates.forCity(city);
            final float[] projected = project(coords[0], coords[1], cx, cy, radius, false);
            if (projected == null) {
                continue;
            }
            final float dx = projected[0] - touchX;
            final float dy = projected[1] - touchY;
            final float d2 = dx * dx + dy * dy;
            if (d2 <= bestDist) {
                bestDist = d2;
                best = city;
            }
        }
        return best;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                mLastTouchX = event.getX();
                mDownX = event.getX();
                mDownY = event.getY();
                mMoved = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                final float dx = event.getX() - mLastTouchX;
                if (Math.abs(event.getX() - mDownX) > dp(4f) || Math.abs(event.getY() - mDownY) > dp(4f)) {
                    mMoved = true;
                }
                mLastTouchX = event.getX();
                if (mYawAnimator != null) {
                    mYawAnimator.cancel();
                }
                mYawDegrees = normalizeDegrees(mYawDegrees + dx * 0.5f);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (!mMoved) {
                    final City hit = hitTestCity(event.getX(), event.getY());
                    if (hit != null) {
                        focusCity(hit);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
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

    private static float normalizeDegrees(float degrees) {
        float d = degrees % 360f;
        if (d > 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
