// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.globe;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Offline globe geometry loaded from text assets under {@code assets/globe/}.
 * <p>
 * Format per file: first line is the ring count. Each ring starts with its point count,
 * then one {@code lat lon} pair per line (closed rings include a repeated first point).
 * Source: Natural Earth / world-atlas TopoJSON, simplified offline.
 */
final class GlobeMapData {

    private static final String LAND_ASSET = "globe/land.txt";
    private static final String COUNTRIES_ASSET = "globe/countries.txt";

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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open(path), StandardCharsets.UTF_8))) {
            final int ringCount = Integer.parseInt(reader.readLine());
            if (ringCount < 0 || ringCount > 10_000) {
                return Collections.emptyList();
            }
            final List<float[][]> rings = new ArrayList<>(ringCount);
            for (int r = 0; r < ringCount; r++) {
                final int n = Integer.parseInt(reader.readLine());
                if (n < 3 || n > 50_000) {
                    return Collections.emptyList();
                }
                final float[][] ring = new float[n][2];
                for (int i = 0; i < n; i++) {
                    final String line = reader.readLine();
                    final int split = line.indexOf(' ');
                    ring[i][0] = Float.parseFloat(line.substring(0, split));
                    ring[i][1] = Float.parseFloat(line.substring(split + 1));
                }
                rings.add(ring);
            }
            return Collections.unmodifiableList(rings);
        } catch (IOException | NumberFormatException | NullPointerException
                 | StringIndexOutOfBoundsException e) {
            return Collections.emptyList();
        }
    }
}
