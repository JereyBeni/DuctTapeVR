package com.jere.vrback;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliente mínimo de YouTube Data API v3 (tendencias y búsqueda).
 * La clave se lee de BuildConfig.YOUTUBE_API_KEY, que viene del archivo .env.
 * La clave nunca se escribe en logs ni en mensajes de error.
 */
public final class YouTubeApi {

    private static final String BASE = "https://www.googleapis.com/youtube/v3/";
    private static final int TIMEOUT_MS = 10000;

    public static final class Video {
        public final String id;
        public final String title;
        public final String channel;
        public final String thumbUrl;
        public volatile Bitmap thumb;

        Video(String id, String title, String channel, String thumbUrl) {
            this.id = id;
            this.title = title;
            this.channel = channel;
            this.thumbUrl = thumbUrl;
        }
    }

    private YouTubeApi() { }

    /** Videos populares en una región (código ISO, por ejemplo "AR"). */
    public static List<Video> trending(String key, String regionCode, int max) throws Exception {
        requireKey(key);
        String url = BASE + "videos?part=snippet&chart=mostPopular&regionCode=" + regionCode
                + "&maxResults=" + max + "&key=" + key;
        return parse(get(url));
    }

    /** Búsqueda de videos por texto. */
    public static List<Video> search(String key, String query, int max) throws Exception {
        requireKey(key);
        String url = BASE + "search?part=snippet&type=video&safeSearch=moderate&order=relevance"
                + "&maxResults=" + max
                + "&q=" + URLEncoder.encode(query, "UTF-8")
                + "&key=" + key;
        return parse(get(url));
    }

    /** Descarga una miniatura. Devuelve null si falla. */
    public static Bitmap downloadThumb(String url) {
        if (url == null || url.isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------- internos ----------

    private static void requireKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalStateException("Falta YOUTUBE_API_KEY en el archivo .env");
        }
    }

    private static List<Video> parse(String body) throws Exception {
        List<Video> out = new ArrayList<>();
        JSONArray items = new JSONObject(body).optJSONArray("items");
        if (items == null) return out;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            // En "videos" el id es un String; en "search" es un objeto con videoId.
            Object idObj = item.opt("id");
            String id = (idObj instanceof JSONObject)
                    ? ((JSONObject) idObj).optString("videoId", "")
                    : String.valueOf(idObj);
            JSONObject sn = item.optJSONObject("snippet");
            if (id.isEmpty() || sn == null) continue;

            out.add(new Video(
                    id,
                    unescape(sn.optString("title", "")),
                    unescape(sn.optString("channelTitle", "")),
                    pickThumb(sn.optJSONObject("thumbnails"))));
        }
        return out;
    }

    private static String pickThumb(JSONObject thumbs) {
        if (thumbs == null) return null;
        for (String size : new String[]{"medium", "high", "default"}) {
            JSONObject t = thumbs.optJSONObject(size);
            if (t != null && !t.optString("url", "").isEmpty()) return t.optString("url");
        }
        return null;
    }

    private static String get(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        try {
            int code = conn.getResponseCode();
            String body = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code != 200) {
                throw new Exception("HTTP " + code + (apiMessage(body).isEmpty() ? "" : " · " + apiMessage(body)));
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static String apiMessage(String body) {
        try {
            return new JSONObject(body).getJSONObject("error").optString("message", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream src = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = src.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }
}
