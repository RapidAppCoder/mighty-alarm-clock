// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.globe;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Offline globe geometry loaded from compact big-endian binary assets under {@code assets/globe/}.
 * <p>
 * Format per file: {@code int32 ringCount}; then for each ring {@code int32 n} followed by
 * {@code n} pairs of {@code float32 lat, float32 lon} (closed rings include a repeated first point).
 * Source: Natural Earth / world-atlas TopoJSON, simplified offline.
 */
final class GlobeMapData {

    private static final String LAND_ASSET = "globe/land.bin";
    private static final String COUNTRIES_ASSET = "globe/countries.bin";

    private static volatile GlobeMapData sInstance;

    private final List<float[][]> mLandRings;
    private final List<float[][]> mCountryRings;

    private GlobeMapData(List<float[][]> landRings, List<float[][]> countryRings) {
        mLandRings = landRings;
        mCountryRings = countryRings;
    }

    static GlobeMapData get(Context context) {
        GlobeMapData local = sInstance;
        if (local != null) {
            return local;
        }
        synchronized (GlobeMapData.class) {
            if (sInstance == null) {
                final AssetManager assets = context.getApplicationContext().getAssets();
                sInstance = new GlobeMapData(
                    loadRings(assets, LAND_ASSET),
                    loadRings(assets, COUNTRIES_ASSET));
            }
            return sInstance;
        }
    }

    List<float[][]> getLandRings() {
        return mLandRings;
    }

    List<float[][]> getCountryRings() {
        return mCountryRings;
    }

    private static List<float[][]> loadRings(AssetManager assets, String path) {
        try (InputStream in = assets.open(path);
             DataInputStream data = new DataInputStream(in)) {
            final int ringCount = data.readInt();
            if (ringCount < 0 || ringCount > 10_000) {
                return Collections.emptyList();
            }
            final List<float[][]> rings = new ArrayList<>(ringCount);
            for (int r = 0; r < ringCount; r++) {
                final int n = data.readInt();
                if (n < 3 || n > 50_000) {
                    return Collections.emptyList();
                }
                final float[][] ring = new float[n][2];
                for (int i = 0; i < n; i++) {
                    ring[i][0] = data.readFloat();
                    ring[i][1] = data.readFloat();
                }
                rings.add(ring);
            }
            return Collections.unmodifiableList(rings);
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }
}
