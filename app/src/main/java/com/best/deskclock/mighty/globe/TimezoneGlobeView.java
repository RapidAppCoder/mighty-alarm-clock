// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.globe;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.best.deskclock.data.City;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Orthographic globe with Natural Earth land polygons, optional country borders when zoomed,
 * and markers for selected world-clock cities.
 * <p>
 * Slowly auto-rotates until the user drags or pinches; auto-rotation resumes after 30 seconds
 * of idle time or when {@link #resumeAutoRotation()} is called. Dragging rotates freely
 * (trackball); pinch zooms so medium/large country borders become visible. Tapping a city
 * animates so that city faces the viewer.
 * <p>
 * A translucent night overlay follows the real solar terminator (UTC time + seasonal declination).
 */
public class TimezoneGlobeView extends View {

    private static final int BAND_COUNT = 6;
    private static final int TERMINATOR_SAMPLES = 96;
    private static final int GRATICULE_SAMPLES = 48;
    private static final float HIT_RADIUS_DP = 18f;
    private static final float DRAG_DEG_PER_PX = 0.5f;
    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 5f;
    /** Country borders appear at and above this zoom level. */
    private static final float COUNTRY_BORDER_ZOOM = 1.65f;
    /** One full revolution about every 90 seconds. */
    private static final float AUTO_ROTATION_DEG_PER_SEC = 4f;
    private static final long AUTO_ROTATION_RESUME_DELAY_MS = 30_000L;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private final Paint mSpherePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mContinentFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mContinentStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCountryBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFocusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mContinentPath = new Path();
    private final Path mNightPath = new Path();
    private final Path mSphereClipPath = new Path();
    private final Path mBandPath = new Path();
    private final Calendar mUtcCalendar = Calendar.getInstance(UTC);

    /** Reusable unit-sphere buffers for hemisphere clipping (x, y, depth). */
    private float[] mInX = new float[256];
    private float[] mInY = new float[256];
    private float[] mInZ = new float[256];
    private float[] mOutX = new float[256];
    private float[] mOutY = new float[256];
    private float[] mOutZ = new float[256];
    private boolean[] mRunSeen = new boolean[256];

    /**
     * Row-major 3×3 rotation: view = R × geo, where geo is
     * {@code (cosφ·sinλ, sinφ, cosφ·cosλ)}.
     */
    private final float[] mRot = {
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    };
    private final float[] mTmpA = new float[9];
    private final float[] mTmpB = new float[9];
    private final float[] mAnimFrom = new float[9];
    private final float[] mAnimTo = new float[9];
    private final float[] mViewTmp = new float[3];
    private final float[] mSunGeo = new float[3];

    private float mLastTouchX;
    private float mLastTouchY;
    private float mDownX;
    private float mDownY;
    private boolean mMoved;
    private boolean mScaling;
    private float mZoom = 1f;
    private int mEdgeSubdivisions = 4;
    private List<City> mCities = new ArrayList<>();
    private String mFocusedCityId;
    private ValueAnimator mRotationAnimator;
    private GlobeMapData mMapData;
    private ScaleGestureDetector mScaleDetector;

    private boolean mAutoRotating;
    private long mLastFrameTimeNanos;
    private final Runnable mResumeAutoRotationRunnable = this::resumeAutoRotation;
    private final Choreographer.FrameCallback mAutoRotateCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mAutoRotating) {
                return;
            }
            if (mLastFrameTimeNanos != 0L) {
                float dtSec = (frameTimeNanos - mLastFrameTimeNanos) / 1_000_000_000f;
                if (dtSec > 0.1f) {
                    dtSec = 0.1f;
                }
                if (dtSec > 0f) {
                    // Spin around the view vertical so the globe keeps drifting east–west.
                    setRotationY(mTmpA, AUTO_ROTATION_DEG_PER_SEC * dtSec);
                    multiplyInto(mTmpA, mRot, mTmpB);
                    System.arraycopy(mTmpB, 0, mRot, 0, 9);
                    invalidate();
                }
            }
            mLastFrameTimeNanos = frameTimeNanos;
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

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
        mMapData = GlobeMapData.get(getContext());
        mScaleDetector = new ScaleGestureDetector(getContext(),
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector detector) {
                    mScaling = true;
                    onUserInteracted();
                    if (mRotationAnimator != null) {
                        mRotationAnimator.cancel();
                    }
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    mZoom = clamp(mZoom * detector.getScaleFactor(), MIN_ZOOM, MAX_ZOOM);
                    invalidate();
                    return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector detector) {
                    mScaling = false;
                }
            });

        mSpherePaint.setStyle(Paint.Style.FILL);
        // Brighter day-side ocean; night is darkened by {@link #drawNightSide}.
        mSpherePaint.setColor(Color.parseColor("#FF1A6A9E"));

        mBandPaint.setStyle(Paint.Style.STROKE);
        mBandPaint.setStrokeWidth(dp(1.0f));
        mBandPaint.setColor(Color.parseColor("#553A8CB8"));

        mContinentFillPaint.setStyle(Paint.Style.FILL);
        mContinentFillPaint.setColor(Color.parseColor("#CC5BC97E"));

        mContinentStrokePaint.setStyle(Paint.Style.STROKE);
        mContinentStrokePaint.setStrokeWidth(dp(1.0f));
        mContinentStrokePaint.setColor(Color.parseColor("#EE2E6B45"));
        mContinentStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        mCountryBorderPaint.setStyle(Paint.Style.STROKE);
        mCountryBorderPaint.setStrokeWidth(dp(1.0f));
        mCountryBorderPaint.setColor(Color.parseColor("#DD1B4D3A"));
        mCountryBorderPaint.setStrokeJoin(Paint.Join.ROUND);

        mNightPaint.setStyle(Paint.Style.FILL);
        mNightPaint.setColor(Color.parseColor("#B3081428"));

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
        if (mFocusedCityId == null && !mCities.isEmpty()) {
            City preferred = null;
            for (City city : mCities) {
                if ("C364".equals(city.getId())) {
                    preferred = city;
                    break;
                }
            }
            if (preferred == null) {
                preferred = mCities.get(0);
            }
            orientToCity(preferred);
        }
        invalidate();
    }

    /** Instantly orients the globe so {@code city} faces the viewer (no animation). */
    private void orientToCity(City city) {
        final float[] coords = CityCoordinates.forCity(city);
        setRotationY(mTmpA, -coords[1]);
        setRotationX(mTmpB, coords[0]);
        multiplyInto(mTmpB, mTmpA, mRot);
        mFocusedCityId = city.getId();
    }

    /**
     * Starts or restarts slow auto-rotation immediately (e.g. after a tab/screen change).
     */
    public void resumeAutoRotation() {
        removeCallbacks(mResumeAutoRotationRunnable);
        if (!isShown() || getWindowVisibility() != VISIBLE || getVisibility() != VISIBLE) {
            stopAutoRotateLoop();
            return;
        }
        startAutoRotateLoop();
    }

    /**
     * Stops auto-rotation without scheduling a delayed resume (e.g. leaving the clock tab).
     */
    public void pauseAutoRotation() {
        removeCallbacks(mResumeAutoRotationRunnable);
        stopAutoRotateLoop();
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
        // R = Rx(lat) · Ry(-lon) brings (lat, lon) to the view center facing the camera.
        setRotationY(mTmpA, -coords[1]);
        setRotationX(mTmpB, coords[0]);
        multiplyInto(mTmpB, mTmpA, mAnimTo);
        onUserInteracted();
        animateRotationTo(mAnimTo);
    }

    private void onUserInteracted() {
        stopAutoRotateLoop();
        removeCallbacks(mResumeAutoRotationRunnable);
        postDelayed(mResumeAutoRotationRunnable, AUTO_ROTATION_RESUME_DELAY_MS);
    }

    private void startAutoRotateLoop() {
        if (mAutoRotating) {
            return;
        }
        mAutoRotating = true;
        mLastFrameTimeNanos = 0L;
        Choreographer.getInstance().postFrameCallback(mAutoRotateCallback);
    }

    private void stopAutoRotateLoop() {
        if (!mAutoRotating) {
            mLastFrameTimeNanos = 0L;
            return;
        }
        mAutoRotating = false;
        mLastFrameTimeNanos = 0L;
        Choreographer.getInstance().removeFrameCallback(mAutoRotateCallback);
    }

    private void animateRotationTo(float[] targetRot) {
        if (mRotationAnimator != null) {
            mRotationAnimator.cancel();
        }
        System.arraycopy(mRot, 0, mAnimFrom, 0, 9);
        System.arraycopy(targetRot, 0, mAnimTo, 0, 9);
        mRotationAnimator = ValueAnimator.ofFloat(0f, 1f);
        mRotationAnimator.setDuration(450);
        mRotationAnimator.setInterpolator(new DecelerateInterpolator());
        mRotationAnimator.addUpdateListener(animation -> {
            final float t = (float) animation.getAnimatedValue();
            lerpRotation(mAnimFrom, mAnimTo, t, mRot);
            invalidate();
        });
        mRotationAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                mRotationAnimator = null;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                System.arraycopy(mAnimTo, 0, mRot, 0, 9);
                mRotationAnimator = null;
            }
        });
        mRotationAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        pauseAutoRotation();
        if (mRotationAnimator != null) {
            mRotationAnimator.cancel();
            mRotationAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // Pause when the window is not visible; restart is owned by the host (tab/resume).
        if (visibility != VISIBLE) {
            pauseAutoRotation();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility != VISIBLE) {
            pauseAutoRotation();
        }
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
        final float baseRadius = Math.min(w, h) / 2f - dp(8f);
        if (baseRadius <= 0) {
            return;
        }
        final float radius = baseRadius * mZoom;
        mEdgeSubdivisions = mZoom >= 2.5f ? 3 : (mZoom >= COUNTRY_BORDER_ZOOM ? 2 : 1);

        canvas.drawCircle(cx, cy, radius, mSpherePaint);
        drawGraticule(canvas, cx, cy, radius);

        final int save = canvas.save();
        mSphereClipPath.reset();
        mSphereClipPath.addCircle(cx, cy, radius, Path.Direction.CW);
        canvas.clipPath(mSphereClipPath);
        drawLand(canvas, cx, cy, radius);
        if (mZoom >= COUNTRY_BORDER_ZOOM) {
            drawCountryBorders(canvas, cx, cy, radius);
        }
        drawNightSide(canvas, cx, cy, radius);
        canvas.restoreToCount(save);

        for (City city : mCities) {
            plotCity(canvas, city, cx, cy, radius);
        }
    }

    /** Draws equator and a few meridians transformed by the current rotation. */
    private void drawGraticule(Canvas canvas, float cx, float cy, float radius) {
        drawParallel(canvas, 0f, cx, cy, radius);
        final float lonStep = 180f / BAND_COUNT;
        for (int i = 0; i < BAND_COUNT; i++) {
            drawMeridian(canvas, -180f + i * lonStep, cx, cy, radius);
        }
    }

    private void drawParallel(Canvas canvas, float latitude, float cx, float cy, float radius) {
        mBandPath.reset();
        boolean started = false;
        boolean prevFront = false;
        for (int i = 0; i <= GRATICULE_SAMPLES; i++) {
            final float lon = -180f + 360f * (i / (float) GRATICULE_SAMPLES);
            latLonToView(latitude, lon, mViewTmp);
            if (mViewTmp[2] >= 0f) {
                final float x = cx + mViewTmp[0] * radius;
                final float y = cy - mViewTmp[1] * radius;
                if (!started || !prevFront) {
                    mBandPath.moveTo(x, y);
                    started = true;
                } else {
                    mBandPath.lineTo(x, y);
                }
                prevFront = true;
            } else {
                prevFront = false;
            }
        }
        canvas.drawPath(mBandPath, mBandPaint);
    }

    private void drawMeridian(Canvas canvas, float longitude, float cx, float cy, float radius) {
        mBandPath.reset();
        boolean started = false;
        boolean prevFront = false;
        for (int i = 0; i <= GRATICULE_SAMPLES; i++) {
            final float lat = -90f + 180f * (i / (float) GRATICULE_SAMPLES);
            latLonToView(lat, longitude, mViewTmp);
            if (mViewTmp[2] >= 0f) {
                final float x = cx + mViewTmp[0] * radius;
                final float y = cy - mViewTmp[1] * radius;
                if (!started || !prevFront) {
                    mBandPath.moveTo(x, y);
                    started = true;
                } else {
                    mBandPath.lineTo(x, y);
                }
                prevFront = true;
            } else {
                prevFront = false;
            }
        }
        canvas.drawPath(mBandPath, mBandPaint);
    }

    /**
     * Darkens the night hemisphere using the current UTC solar terminator, transformed by rotation.
     */
    private void drawNightSide(Canvas canvas, float cx, float cy, float radius) {
        mUtcCalendar.setTimeInMillis(System.currentTimeMillis());
        final float hour = mUtcCalendar.get(Calendar.HOUR_OF_DAY)
            + mUtcCalendar.get(Calendar.MINUTE) / 60f
            + mUtcCalendar.get(Calendar.SECOND) / 3600f;
        final float subsolarLon = normalizeDegrees(15f * (12f - hour));
        final float declination = (float) (23.44 * Math.sin(
            Math.toRadians(360.0 / 365.0 * (mUtcCalendar.get(Calendar.DAY_OF_YEAR) - 81))));

        latLonToGeo(declination, subsolarLon, mSunGeo);
        rotateToView(mSunGeo[0], mSunGeo[1], mSunGeo[2], mViewTmp);
        final float sx = mViewTmp[0];
        final float sy = mViewTmp[1];
        final float sz = mViewTmp[2];

        if (sz >= 0.998f) {
            return;
        }
        if (sz <= -0.998f) {
            canvas.drawCircle(cx, cy, radius, mNightPaint);
            return;
        }

        // Orthonormal basis spanning the terminator plane (perpendicular to the sun).
        float refX = 0f;
        float refY = 1f;
        float refZ = 0f;
        if (Math.abs(sy) > 0.9f) {
            refX = 1f;
            refY = 0f;
        }
        float uX = sy * refZ - sz * refY;
        float uY = sz * refX - sx * refZ;
        float uZ = sx * refY - sy * refX;
        final float uLen = (float) Math.sqrt(uX * uX + uY * uY + uZ * uZ);
        if (uLen < 1e-6f) {
            return;
        }
        uX /= uLen;
        uY /= uLen;
        uZ /= uLen;

        final float vX = sy * uZ - sz * uY;
        final float vY = sz * uX - sx * uZ;
        final float vZ = sx * uY - sy * uX;

        ensureClipCapacity(TERMINATOR_SAMPLES);
        for (int i = 0; i < TERMINATOR_SAMPLES; i++) {
            final double phi = 2.0 * Math.PI * i / TERMINATOR_SAMPLES;
            final float c = (float) Math.cos(phi);
            final float s = (float) Math.sin(phi);
            mInX[i] = c * uX + s * vX;
            mInY[i] = c * uY + s * vY;
            mInZ[i] = c * uZ + s * vZ;
        }

        // Contiguous front arc of the terminator (z >= 0), starting at a back→front crossing.
        int start = 0;
        for (int i = 0; i < TERMINATOR_SAMPLES; i++) {
            final int next = (i + 1) % TERMINATOR_SAMPLES;
            if (mInZ[i] < 0f && mInZ[next] >= 0f) {
                start = next;
                break;
            }
        }

        int count = 0;
        int idx = start;
        for (int k = 0; k < TERMINATOR_SAMPLES; k++) {
            if (mInZ[idx] < 0f) {
                break;
            }
            mOutX[count] = mInX[idx];
            mOutY[count] = mInY[idx];
            count++;
            idx = (idx + 1) % TERMINATOR_SAMPLES;
        }
        if (count < 1) {
            return;
        }

        mNightPath.reset();
        mNightPath.moveTo(cx + mOutX[0] * radius, cy - mOutY[0] * radius);
        for (int i = 1; i < count; i++) {
            mNightPath.lineTo(cx + mOutX[i] * radius, cy - mOutY[i] * radius);
        }

        // Close along the limb through the night side (illumination sx*x + sy*y < 0 on z = 0).
        final float a0 = (float) Math.atan2(mOutY[count - 1], mOutX[count - 1]);
        final float a1 = (float) Math.atan2(mOutY[0], mOutX[0]);
        float deltaCcw = a1 - a0;
        while (deltaCcw <= 0f) {
            deltaCcw += (float) (2.0 * Math.PI);
        }
        final float midCcw = a0 + deltaCcw * 0.5f;
        final float illumCcw = sx * (float) Math.cos(midCcw) + sy * (float) Math.sin(midCcw);
        final float delta = illumCcw < 0f ? deltaCcw : deltaCcw - (float) (2.0 * Math.PI);

        final int limbSteps = Math.max(1, Math.round(Math.abs(delta) / ((float) Math.PI / 48f)));
        for (int s = 1; s <= limbSteps; s++) {
            final float a = a0 + delta * (s / (float) limbSteps);
            mNightPath.lineTo(cx + (float) Math.cos(a) * radius, cy - (float) Math.sin(a) * radius);
        }
        mNightPath.close();
        canvas.drawPath(mNightPath, mNightPaint);
    }

    private void drawLand(Canvas canvas, float cx, float cy, float radius) {
        for (float[][] ring : mMapData.getLandRings()) {
            drawPolygonRing(canvas, ring, cx, cy, radius, true);
        }
    }

    private void drawCountryBorders(Canvas canvas, float cx, float cy, float radius) {
        mCountryBorderPaint.setStrokeWidth(dp(Math.max(0.7f, 1.15f / mZoom)));
        for (float[][] ring : mMapData.getCountryRings()) {
            drawPolygonRing(canvas, ring, cx, cy, radius, false);
        }
    }

    /**
     * Draws a lat/lon ring by extracting contiguous front-facing chains and closing each
     * along the silhouette arc. Avoids Sutherland–Hodgman overfill / triangle artifacts when
     * large Natural Earth polygons cross the left/right limb during rotation.
     */
    private void drawPolygonRing(Canvas canvas, float[][] ring, float cx, float cy, float radius,
                                 boolean filled) {
        if (ring.length < 3 || isMostlyBackFacing(ring)) {
            return;
        }

        final int n = buildSubdividedSphereRing(ring);
        if (n < 3) {
            return;
        }

        boolean allFront = true;
        for (int i = 0; i < n; i++) {
            if (mInZ[i] < 0f) {
                allFront = false;
                break;
            }
        }
        if (allFront) {
            mContinentPath.reset();
            mContinentPath.moveTo(cx + mInX[0] * radius, cy - mInY[0] * radius);
            for (int i = 1; i < n; i++) {
                mContinentPath.lineTo(cx + mInX[i] * radius, cy - mInY[i] * radius);
            }
            mContinentPath.close();
            strokeOrFill(canvas, filled);
            return;
        }

        if (mRunSeen.length < n) {
            mRunSeen = new boolean[Math.max(n, mRunSeen.length * 2)];
        }
        java.util.Arrays.fill(mRunSeen, 0, n, false);

        for (int i = 0; i < n; i++) {
            if (mRunSeen[i] || mInZ[i] < 0f) {
                continue;
            }
            final int prev = (i - 1 + n) % n;
            if (mInZ[prev] >= 0f) {
                continue; // not the start of a front run
            }

            int j = i;
            float sumX = 0f;
            float sumY = 0f;
            int count = 0;
            do {
                mRunSeen[j] = true;
                sumX += mInX[j];
                sumY += mInY[j];
                count++;
                j = (j + 1) % n;
            } while (j != i && mInZ[j] >= 0f);

            final int end = (j - 1 + n) % n;
            if (count < 2) {
                continue;
            }
            drawFrontRun(canvas, i, end, n, sumX / count, sumY / count, cx, cy, radius, filled);
        }
    }

    private void drawFrontRun(Canvas canvas, int start, int end, int n,
                              float centX, float centY,
                              float cx, float cy, float radius, boolean filled) {
        final int prev = (start - 1 + n) % n;
        final int next = (end + 1) % n;
        final boolean hasEntry = mInZ[prev] < 0f;
        final boolean hasExit = mInZ[next] < 0f;

        mContinentPath.reset();

        float pathStartX;
        float pathStartY;
        float pathEndX;
        float pathEndY;

        if (hasEntry) {
            limbIntersection(mInX[prev], mInY[prev], mInZ[prev],
                mInX[start], mInY[start], mInZ[start], mViewTmp);
            pathStartX = mViewTmp[0];
            pathStartY = mViewTmp[1];
            mContinentPath.moveTo(cx + pathStartX * radius, cy - pathStartY * radius);
        } else {
            pathStartX = mInX[start];
            pathStartY = mInY[start];
            mContinentPath.moveTo(cx + pathStartX * radius, cy - pathStartY * radius);
        }

        int idx = start;
        while (true) {
            pathEndX = mInX[idx];
            pathEndY = mInY[idx];
            mContinentPath.lineTo(cx + pathEndX * radius, cy - pathEndY * radius);
            if (idx == end) {
                break;
            }
            idx = (idx + 1) % n;
        }

        if (hasExit) {
            limbIntersection(mInX[end], mInY[end], mInZ[end],
                mInX[next], mInY[next], mInZ[next], mViewTmp);
            pathEndX = mViewTmp[0];
            pathEndY = mViewTmp[1];
            mContinentPath.lineTo(cx + pathEndX * radius, cy - pathEndY * radius);
        }

        if (filled && (hasEntry || hasExit)) {
            appendLimbArcToward(pathEndX, pathEndY, pathStartX, pathStartY,
                centX, centY, cx, cy, radius);
            mContinentPath.close();
            strokeOrFill(canvas, true);
        } else if (filled) {
            mContinentPath.close();
            strokeOrFill(canvas, true);
        } else {
            strokeOrFill(canvas, false);
        }
    }

    private void strokeOrFill(Canvas canvas, boolean filled) {
        if (mContinentPath.isEmpty()) {
            return;
        }
        if (filled) {
            canvas.drawPath(mContinentPath, mContinentFillPaint);
            canvas.drawPath(mContinentPath, mContinentStrokePaint);
        } else {
            canvas.drawPath(mContinentPath, mCountryBorderPaint);
        }
    }

    /**
     * Connects two silhouette points along the limb arc whose midpoint lies closer to the
     * visible land centroid — prevents half-disk overfill from choosing the long way around.
     */
    private void appendLimbArcToward(float x0, float y0, float x1, float y1,
                                     float centX, float centY,
                                     float cx, float cy, float radius) {
        final float a0 = (float) Math.atan2(y0, x0);
        final float a1 = (float) Math.atan2(y1, x1);
        float deltaCcw = a1 - a0;
        while (deltaCcw <= 0f) {
            deltaCcw += (float) (2.0 * Math.PI);
        }
        final float deltaCw = deltaCcw - (float) (2.0 * Math.PI);

        final float midCcw = a0 + deltaCcw * 0.5f;
        final float midCw = a0 + deltaCw * 0.5f;
        final float distCcw = (float) Math.hypot(Math.cos(midCcw) - centX, Math.sin(midCcw) - centY);
        final float distCw = (float) Math.hypot(Math.cos(midCw) - centX, Math.sin(midCw) - centY);
        final float delta = distCcw <= distCw ? deltaCcw : deltaCw;

        if (Math.abs(delta) < 1e-4f) {
            mContinentPath.lineTo(cx + x1 * radius, cy - y1 * radius);
            return;
        }

        final int steps = Math.max(1, Math.round(Math.abs(delta) / ((float) Math.PI / 36f)));
        for (int s = 1; s <= steps; s++) {
            final float a = a0 + delta * (s / (float) steps);
            mContinentPath.lineTo(
                cx + (float) Math.cos(a) * radius,
                cy - (float) Math.sin(a) * radius);
        }
    }

    /** Quick reject for rings entirely on the far side of the globe. */
    private boolean isMostlyBackFacing(float[][] ring) {
        final int step = Math.max(1, ring.length / 10);
        for (int i = 0; i < ring.length; i += step) {
            latLonToView(ring[i][0], ring[i][1], mViewTmp);
            if (mViewTmp[2] >= -0.08f) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fills {@link #mInX}/{@link #mInY}/{@link #mInZ} with unit-sphere samples along {@code ring}.
     *
     * @return number of points written
     */
    private int buildSubdividedSphereRing(float[][] ring) {
        int count = 0;
        final int edgeCount = ring.length - 1;
        final int subdivisions = Math.max(1, mEdgeSubdivisions);
        final int estimated = edgeCount * subdivisions + 1;
        ensureClipCapacity(estimated);

        for (int i = 0; i < edgeCount; i++) {
            final float lat0 = ring[i][0];
            final float lon0 = ring[i][1];
            final float lat1 = ring[i + 1][0];
            final float lon1 = ring[i + 1][1];

            float lonDelta = lon1 - lon0;
            if (lonDelta > 180f) {
                lonDelta -= 360f;
            } else if (lonDelta < -180f) {
                lonDelta += 360f;
            }

            for (int s = 0; s < subdivisions; s++) {
                final float t = s / (float) subdivisions;
                final float lat = lat0 + (lat1 - lat0) * t;
                final float lon = lon0 + lonDelta * t;
                count = appendSpherePoint(count, lat, lon);
            }
        }
        count = appendSpherePoint(count, ring[ring.length - 1][0], ring[ring.length - 1][1]);
        return count;
    }

    private int appendSpherePoint(int count, float latitude, float longitude) {
        ensureClipCapacity(count + 1);
        latLonToView(latitude, longitude, mViewTmp);
        final float x = mViewTmp[0];
        final float y = mViewTmp[1];
        final float z = mViewTmp[2];
        // Skip near-duplicates from closed rings / dense samples.
        if (count > 0) {
            final float dx = x - mInX[count - 1];
            final float dy = y - mInY[count - 1];
            final float dz = z - mInZ[count - 1];
            if (dx * dx + dy * dy + dz * dz < 1e-10f) {
                return count;
            }
        }
        mInX[count] = x;
        mInY[count] = y;
        mInZ[count] = z;
        return count + 1;
    }

    private static void limbIntersection(float ax, float ay, float az,
                                         float bx, float by, float bz,
                                         float[] out) {
        final float denom = az - bz;
        final float t = Math.abs(denom) < 1e-8f ? 0.5f : az / denom;
        float x = ax + t * (bx - ax);
        float y = ay + t * (by - ay);
        final float len = (float) Math.hypot(x, y);
        if (len > 1e-6f) {
            x /= len;
            y /= len;
        }
        out[0] = x;
        out[1] = y;
        out[2] = 0f;
    }

    private void ensureClipCapacity(int needed) {
        if (needed <= mInX.length) {
            return;
        }
        int cap = mInX.length;
        while (cap < needed) {
            cap *= 2;
        }
        mInX = copyOf(mInX, cap);
        mInY = copyOf(mInY, cap);
        mInZ = copyOf(mInZ, cap);
        mOutX = copyOf(mOutX, cap);
        mOutY = copyOf(mOutY, cap);
        mOutZ = copyOf(mOutZ, cap);
    }

    private static float[] copyOf(float[] src, int newLength) {
        final float[] dst = new float[newLength];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }

    /**
     * Orthographic projection. Returns null when the point faces away (unless {@code allowBack}).
     * Result: {@code float[]{x, y, depth}} when allowBack, else {@code float[]{x, y}}.
     */
    private float[] project(float latitude, float longitude, float cx, float cy, float radius,
                            boolean allowBack) {
        latLonToView(latitude, longitude, mViewTmp);
        final float x = mViewTmp[0];
        final float y = mViewTmp[1];
        final float depth = mViewTmp[2];

        if (!allowBack && depth < 0) {
            return null;
        }

        final float px = cx + x * radius;
        final float py = cy - y * radius;
        if (allowBack) {
            return new float[]{px, py, depth};
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
        final float radius = (Math.min(w, h) / 2f - dp(8f)) * mZoom;
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
        mScaleDetector.onTouchEvent(event);
        final int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mScaling = false;
        }

        // Pinch / multi-touch: zoom only (scale detector already handled).
        if (mScaling || event.getPointerCount() > 1) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN -> {
                mLastTouchX = event.getX();
                mLastTouchY = event.getY();
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
                final float dy = event.getY() - mLastTouchY;
                if (Math.abs(event.getX() - mDownX) > dp(4f) || Math.abs(event.getY() - mDownY) > dp(4f)) {
                    if (!mMoved) {
                        onUserInteracted();
                    }
                    mMoved = true;
                }
                mLastTouchX = event.getX();
                mLastTouchY = event.getY();
                if (mMoved) {
                    if (mRotationAnimator != null) {
                        mRotationAnimator.cancel();
                    }
                    applyTrackballDrag(dx, dy);
                    invalidate();
                }
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

    /**
     * Trackball-style rotation: horizontal drag spins around view-Y, vertical around view-X.
     * Screen Y grows downward; a downward drag must apply a positive view-X rotation so the
     * grabbed surface follows the finger (projection uses {@code cy - y}).
     * Sensitivity decreases when zoomed in for finer control.
     */
    private void applyTrackballDrag(float dxPx, float dyPx) {
        final float sens = DRAG_DEG_PER_PX / mZoom;
        setRotationY(mTmpA, dxPx * sens);
        setRotationX(mTmpB, dyPx * sens);
        // delta = Rx · Ry, then R = delta · R
        multiplyInto(mTmpB, mTmpA, mAnimFrom);
        multiplyInto(mAnimFrom, mRot, mTmpB);
        System.arraycopy(mTmpB, 0, mRot, 0, 9);
    }

    private void latLonToGeo(float latitudeDeg, float longitudeDeg, float[] out) {
        final double lonRad = Math.toRadians(longitudeDeg);
        final double latRad = Math.toRadians(latitudeDeg);
        out[0] = (float) (Math.cos(latRad) * Math.sin(lonRad));
        out[1] = (float) Math.sin(latRad);
        out[2] = (float) (Math.cos(latRad) * Math.cos(lonRad));
    }

    private void rotateToView(float gx, float gy, float gz, float[] out) {
        out[0] = mRot[0] * gx + mRot[1] * gy + mRot[2] * gz;
        out[1] = mRot[3] * gx + mRot[4] * gy + mRot[5] * gz;
        out[2] = mRot[6] * gx + mRot[7] * gy + mRot[8] * gz;
    }

    private void latLonToView(float latitudeDeg, float longitudeDeg, float[] out) {
        latLonToGeo(latitudeDeg, longitudeDeg, out);
        final float gx = out[0];
        final float gy = out[1];
        final float gz = out[2];
        rotateToView(gx, gy, gz, out);
    }

    private static void setRotationX(float[] out, float degrees) {
        final float rad = (float) Math.toRadians(degrees);
        final float c = (float) Math.cos(rad);
        final float s = (float) Math.sin(rad);
        out[0] = 1f;
        out[1] = 0f;
        out[2] = 0f;
        out[3] = 0f;
        out[4] = c;
        out[5] = -s;
        out[6] = 0f;
        out[7] = s;
        out[8] = c;
    }

    private static void setRotationY(float[] out, float degrees) {
        final float rad = (float) Math.toRadians(degrees);
        final float c = (float) Math.cos(rad);
        final float s = (float) Math.sin(rad);
        out[0] = c;
        out[1] = 0f;
        out[2] = s;
        out[3] = 0f;
        out[4] = 1f;
        out[5] = 0f;
        out[6] = -s;
        out[7] = 0f;
        out[8] = c;
    }

    /** out = a · b (row-major 3×3). {@code out} must not alias {@code a} or {@code b}. */
    private static void multiplyInto(float[] a, float[] b, float[] out) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                out[row * 3 + col] =
                    a[row * 3] * b[col]
                        + a[row * 3 + 1] * b[3 + col]
                        + a[row * 3 + 2] * b[6 + col];
            }
        }
    }

    private static void lerpRotation(float[] from, float[] to, float t, float[] out) {
        for (int i = 0; i < 9; i++) {
            out[i] = from[i] + (to[i] - from[i]) * t;
        }
        orthonormalize(out);
    }

    /** Re-orthogonalizes a nearly-rotated 3×3 matrix (Gram–Schmidt on rows). */
    private static void orthonormalize(float[] m) {
        normalizeRow(m, 0);
        // row1 -= proj(row1 onto row0)
        float dot10 = m[3] * m[0] + m[4] * m[1] + m[5] * m[2];
        m[3] -= dot10 * m[0];
        m[4] -= dot10 * m[1];
        m[5] -= dot10 * m[2];
        normalizeRow(m, 1);
        // row2 = row0 × row1
        m[6] = m[1] * m[5] - m[2] * m[4];
        m[7] = m[2] * m[3] - m[0] * m[5];
        m[8] = m[0] * m[4] - m[1] * m[3];
        normalizeRow(m, 2);
    }

    private static void normalizeRow(float[] m, int row) {
        final int i = row * 3;
        final float len = (float) Math.sqrt(m[i] * m[i] + m[i + 1] * m[i + 1] + m[i + 2] * m[i + 2]);
        if (len > 1e-8f) {
            m[i] /= len;
            m[i + 1] /= len;
            m[i + 2] /= len;
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

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
