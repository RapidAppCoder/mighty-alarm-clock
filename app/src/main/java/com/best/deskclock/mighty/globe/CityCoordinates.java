// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.globe;

import com.best.deskclock.data.City;

import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Approximate geographic coordinates for world-clock cities.
 * Prefers a lookup by city resource id (so cities that share a timezone stay distinct),
 * then falls back to timezone id, then to a UTC-offset estimate.
 */
final class CityCoordinates {

    private static final Map<String, float[]> BY_CITY_ID = new HashMap<>();
    private static final Map<String, float[]> BY_ZONE = new HashMap<>();

    static {
        // Cities that share a timezone need explicit coordinates.
        putCity("C263", 48.14f, 11.58f); // Munich
        putCity("C265", 50.11f, 8.68f);  // Frankfurt
        putCity("C293", 53.55f, 9.99f);  // Hamburg
        putCity("C362", 49.45f, 11.08f); // Nuremberg
        putCity("C363", 49.44f, 11.86f); // Amberg
        putCity("C364", 49.39f, 11.94f); // Ebermannsdorf
        putCity("C365", 48.92f, 10.71f); // Polsingen

        // Europe
        put("Europe/London", 51.51f, -0.13f);
        put("Europe/Dublin", 53.35f, -6.26f);
        put("Europe/Paris", 48.86f, 2.35f);
        put("Europe/Berlin", 52.52f, 13.41f);
        put("Europe/Amsterdam", 52.37f, 4.90f);
        put("Europe/Brussels", 50.85f, 4.35f);
        put("Europe/Madrid", 40.42f, -3.70f);
        put("Europe/Lisbon", 38.72f, -9.14f);
        put("Europe/Rome", 41.90f, 12.50f);
        put("Europe/Vienna", 48.21f, 16.37f);
        put("Europe/Zurich", 47.38f, 8.54f);
        put("Europe/Stockholm", 59.33f, 18.07f);
        put("Europe/Oslo", 59.91f, 10.75f);
        put("Europe/Copenhagen", 55.68f, 12.57f);
        put("Europe/Helsinki", 60.17f, 24.94f);
        put("Europe/Warsaw", 52.23f, 21.01f);
        put("Europe/Prague", 50.08f, 14.44f);
        put("Europe/Budapest", 47.50f, 19.04f);
        put("Europe/Athens", 37.98f, 23.73f);
        put("Europe/Bucharest", 44.43f, 26.10f);
        put("Europe/Sofia", 42.70f, 23.32f);
        put("Europe/Belgrade", 44.79f, 20.45f);
        put("Europe/Zagreb", 45.81f, 15.98f);
        put("Europe/Moscow", 55.76f, 37.62f);
        put("Europe/Kiev", 50.45f, 30.52f);
        put("Europe/Kyiv", 50.45f, 30.52f);
        put("Europe/Istanbul", 41.01f, 28.98f);
        put("Europe/Tirane", 41.33f, 19.82f);
        put("Europe/Vaduz", 47.14f, 9.52f);
        put("Atlantic/Reykjavik", 64.15f, -21.94f);
        put("Atlantic/Azores", 37.74f, -25.67f);

        // Africa
        put("Africa/Cairo", 30.04f, 31.24f);
        put("Africa/Johannesburg", -26.20f, 28.05f);
        put("Africa/Lagos", 6.52f, 3.38f);
        put("Africa/Nairobi", -1.29f, 36.82f);
        put("Africa/Casablanca", 33.57f, -7.59f);
        put("Africa/Algiers", 36.75f, 3.06f);
        put("Africa/Tunis", 36.81f, 10.18f);
        put("Africa/Addis_Ababa", 9.03f, 38.74f);
        put("Africa/Accra", 5.60f, -0.19f);
        put("Africa/Windhoek", -22.56f, 17.08f);
        put("Africa/Douala", 4.05f, 9.71f);

        // Middle East / Asia
        put("Asia/Dubai", 25.20f, 55.27f);
        put("Asia/Riyadh", 24.71f, 46.68f);
        put("Asia/Tehran", 35.69f, 51.39f);
        put("Asia/Baghdad", 33.32f, 44.37f);
        put("Asia/Jerusalem", 31.77f, 35.23f);
        put("Asia/Beirut", 33.89f, 35.50f);
        put("Asia/Karachi", 24.86f, 67.01f);
        put("Asia/Kolkata", 22.57f, 88.36f);
        put("Asia/Dhaka", 23.81f, 90.41f);
        put("Asia/Kathmandu", 27.72f, 85.32f);
        put("Asia/Colombo", 6.93f, 79.85f);
        put("Asia/Bangkok", 13.76f, 100.50f);
        put("Asia/Jakarta", -6.21f, 106.85f);
        put("Asia/Singapore", 1.35f, 103.82f);
        put("Asia/Kuala_Lumpur", 3.14f, 101.69f);
        put("Asia/Manila", 14.60f, 120.98f);
        put("Asia/Hong_Kong", 22.32f, 114.17f);
        put("Asia/Shanghai", 31.23f, 121.47f);
        put("Asia/Taipei", 25.03f, 121.57f);
        put("Asia/Tokyo", 35.68f, 139.69f);
        put("Asia/Seoul", 37.57f, 126.98f);
        put("Asia/Vladivostok", 43.12f, 131.89f);
        put("Asia/Yakutsk", 62.03f, 129.73f);
        put("Asia/Novosibirsk", 55.03f, 82.92f);
        put("Asia/Almaty", 43.24f, 76.95f);
        put("Asia/Tashkent", 41.30f, 69.24f);
        put("Asia/Tbilisi", 41.72f, 44.79f);
        put("Asia/Yerevan", 40.18f, 44.51f);
        put("Asia/Baku", 40.41f, 49.87f);
        put("Asia/Thimphu", 27.47f, 89.64f);
        put("Asia/Vientiane", 17.98f, 102.63f);
        put("Asia/Ho_Chi_Minh", 10.82f, 106.63f);
        put("Asia/Phnom_Penh", 11.56f, 104.93f);
        put("Indian/Maldives", 4.18f, 73.51f);
        put("Indian/Mauritius", -20.16f, 57.50f);

        // Oceania
        put("Australia/Sydney", -33.87f, 151.21f);
        put("Australia/Melbourne", -37.81f, 144.96f);
        put("Australia/Brisbane", -27.47f, 153.03f);
        put("Australia/Perth", -31.95f, 115.86f);
        put("Australia/Adelaide", -34.93f, 138.60f);
        put("Australia/Darwin", -12.46f, 130.84f);
        put("Australia/Eucla", -31.68f, 128.88f);
        put("Pacific/Auckland", -36.85f, 174.76f);
        put("Pacific/Fiji", -18.14f, 178.44f);
        put("Pacific/Honolulu", 21.31f, -157.86f);
        put("Pacific/Guam", 13.44f, 144.79f);
        put("Pacific/Port_Moresby", -9.44f, 147.18f);
        put("Pacific/Nauru", -0.55f, 166.92f);
        put("Pacific/Niue", -19.05f, -169.92f);
        put("Pacific/Midway", 28.21f, -177.38f);
        put("Pacific/Pago_Pago", -14.28f, -170.70f);
        put("Pacific/Marquesas", -9.00f, -139.50f);
        put("Pacific/Tahiti", -17.65f, -149.43f);
        put("Antarctica/McMurdo", -77.85f, 166.67f);

        // Americas
        put("America/New_York", 40.71f, -74.01f);
        put("America/Chicago", 41.88f, -87.63f);
        put("America/Denver", 39.74f, -104.99f);
        put("America/Los_Angeles", 34.05f, -118.24f);
        put("America/Anchorage", 61.22f, -149.90f);
        put("America/Phoenix", 33.45f, -112.07f);
        put("America/Toronto", 43.65f, -79.38f);
        put("America/Vancouver", 49.28f, -123.12f);
        put("America/Winnipeg", 49.90f, -97.14f);
        put("America/Halifax", 44.65f, -63.58f);
        put("America/St_Johns", 47.56f, -52.71f);
        put("America/Mexico_City", 19.43f, -99.13f);
        put("America/Tijuana", 32.51f, -117.04f);
        put("America/Costa_Rica", 9.93f, -84.08f);
        put("America/Bogota", 4.71f, -74.07f);
        put("America/Lima", -12.05f, -77.04f);
        put("America/Caracas", 10.48f, -66.90f);
        put("America/La_Paz", -16.50f, -68.15f);
        put("America/Santiago", -33.45f, -70.67f);
        put("America/Sao_Paulo", -23.55f, -46.63f);
        put("America/Argentina/Buenos_Aires", -34.60f, -58.38f);
        put("America/Montevideo", -34.90f, -56.16f);
        put("America/Manaus", -3.12f, -60.02f);
        put("America/Barbados", 13.10f, -59.61f);
        put("America/Dominica", 15.30f, -61.39f);
        put("America/Godthab", 64.18f, -51.72f);
        put("America/Nuuk", 64.18f, -51.72f);
        put("America/Noronha", -3.85f, -32.42f);
        put("America/Miquelon", 46.78f, -56.18f);
        put("America/Regina", 50.45f, -104.61f);
        put("America/Adak", 51.88f, -176.66f);
        put("UTC", 0f, 0f);
    }

    private CityCoordinates() {
    }

    /**
     * @return {@code float[]{latitude, longitude}} for the city
     */
    static float[] forCity(City city) {
        final float[] byId = BY_CITY_ID.get(city.getId());
        if (byId != null) {
            return byId;
        }

        final TimeZone timeZone = city.getTimeZone();
        final float[] known = BY_ZONE.get(timeZone.getID());
        if (known != null) {
            return known;
        }

        final float longitude = timeZone.getRawOffset() / 3_600_000f * 15f;
        final float latitude = (Math.abs(city.getId().hashCode()) % 140) - 70f;
        return new float[]{latitude, longitude};
    }

    private static void putCity(String cityId, float lat, float lon) {
        BY_CITY_ID.put(cityId, new float[]{lat, lon});
    }

    private static void put(String zoneId, float lat, float lon) {
        BY_ZONE.put(zoneId, new float[]{lat, lon});
    }
}
