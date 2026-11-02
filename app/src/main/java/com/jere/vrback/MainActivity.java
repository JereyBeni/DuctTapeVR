package com.jere.vrback;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity 2: VR completo.
 * - Cámara trasera en pantalla dividida (un ojo por mitad), con velo oscuro bajo la GUI.
 * - Orientación de cabeza con el sensor de rotación (con recentrado).
 * - Manos con MediaPipe HandLandmarker (modo LIVE_STREAM).
 * - GUI estilo Oculus: barra superior de pestañas, tarjetas de inicio y cuadrícula de juegos/videos.
 *   Los paneles viven en "mundo" (yaw/pitch fijos) y se proyectan según la cabeza.
 * - Play Story: lista los juegos instalados y los abre.
 * - YouTube: tendencias y búsqueda por tema con YouTube Data API v3 (clave en .env).
 * - Si el dispositivo de salida es TV, envía telemetría por UDP.
 */
public class MainActivity extends ComponentActivity implements SensorEventListener {

    private static final String TAG = "VRBack";
    private static final int CAMERA_PERMISSION_REQ = 42;
    private static final int TV_TELEMETRY_PORT = 5005;

    private static final long DWELL_MS = 700;      // tiempo con el dedo sobre un panel para activarlo
    private static final float FOV_DEG = 70f;      // campo de visión horizontal de cada ojo
    private static final float IPD_PX = 24f;       // separación entre ojos (ajustable)
    private static final float MAX_ANGLE_DEG = 80f;
    private static final int PAGE_SIZE = 6;        // elementos por página en cuadrículas
    private static final String YT_REGION = "AR";

    private enum Screen { HOME, GAMES, YT_MENU, YT_RESULTS }
    private enum Kind { NAV, TILE, CARD }
    private enum Action {
        GO_HOME, GO_GAMES, GO_YOUTUBE, YT_TRENDING, YT_SEARCH,
        NEXT_PAGE, PREV_PAGE, LAUNCH_GAME, OPEN_VIDEO, RECENTER, EXIT
    }

    private VrView vrView;
    private boolean showGui;
    private boolean sendTelemetry;
    private String allowedApps;

    private SensorManager sensorManager;
    private volatile float headYawDeg = 0f, headPitchDeg = 0f; // relativos al centro
    private float baseYaw = 0f, basePitch = 0f;
    private boolean haveBase = false;

    private HandLandmarker handLandmarker;
    private ExecutorService analysisExecutor;
    private final ExecutorService netExecutor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private volatile Bitmap latestFrame;
    private volatile float fingerX = -1f, fingerY = -1f; // normalizado 0..1 sobre el frame
    private volatile boolean handVisible = false;

    private DatagramSocket telemetrySocket;

    // ----- estado de la interfaz -----
    private Screen screen = Screen.HOME;
    private int page = 0;
    private String status = null;             // mensaje de estado en la parte inferior

    private List<GameLibrary.Game> games = new ArrayList<>();
    private boolean gamesLoaded = false, gamesLoading = false;

    private List<YouTubeApi.Video> videos = new ArrayList<>();
    private boolean ytLoading = false;
    private String ytTitle = "YouTube";

    private final List<Panel> panels = new ArrayList<>();
    private int dwellIndex = -1;
    private long dwellStart = 0;
    private boolean dwellFired = false;

    // Un panel: posición en grados (yaw/pitch) y tamaño en grados de visión.
    private static class Panel {
        final String label;
        final String subtitle;
        final float yawDeg, pitchDeg, wDeg, hDeg;
        final Kind kind;
        final Action action;
        final String data;
        final boolean selected;
        GameLibrary.Game game;
        YouTubeApi.Video video;

        Panel(String label, String subtitle, float yawDeg, float pitchDeg, float wDeg, float hDeg,
              Kind kind, Action action, String data, boolean selected) {
            this.label = label;
            this.subtitle = subtitle;
            this.yawDeg = yawDeg;
            this.pitchDeg = pitchDeg;
            this.wDeg = wDeg;
            this.hDeg = hDeg;
            this.kind = kind;
            this.action = action;
            this.data = data;
            this.selected = selected;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        showGui = getIntent().getBooleanExtra(MainActivityTutorial.KEY_GUI, true);
        sendTelemetry = "tv".equals(getIntent().getStringExtra(MainActivityTutorial.KEY_OUTPUT));
        allowedApps = getIntent().getStringExtra(MainActivityTutorial.KEY_ALLOWED_APPS);

        vrView = new VrView(this);
        setContentView(vrView);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        analysisExecutor = Executors.newSingleThreadExecutor();

        if (sendTelemetry) {
            try {
                telemetrySocket = new DatagramSocket();
                telemetrySocket.setBroadcast(true);
            } catch (Exception e) {
                Log.e(TAG, "No se pudo abrir el socket de telemetría", e);
            }
        }

        rebuildPanels();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startAll();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQ);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQ && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startAll();
        } else {
            Log.e(TAG, "Permiso de cámara denegado");
            finish();
        }
    }

    private void startAll() {
        setupHandLandmarker();
        startCamera();
    }

    // ---------- MediaPipe ----------

    private void setupHandLandmarker() {
        BaseOptions base = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build();

        HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(this::onHandResult)
                .setErrorListener(e -> Log.e(TAG, "MediaPipe error", e))
                .build();

        handLandmarker = HandLandmarker.createFromOptions(this, options);
    }

    private void onHandResult(HandLandmarkerResult result, MPImage input) {
        List<List<NormalizedLandmark>> hands = result.landmarks();
        if (hands.isEmpty()) {
            handVisible = false;
            return;
        }
        handVisible = true;
        NormalizedLandmark indexTip = hands.get(0).get(8); // punta del índice
        fingerX = indexTip.x();
        fingerY = indexTip.y();
        if (sendTelemetry) {
            sendTelemetryPacket();
        }
    }

    // ---------- Cámara trasera con CameraX ----------

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                analysis.setAnalyzer(analysisExecutor, this::analyzeFrame);

                provider.unbindAll();
                provider.bindToLifecycle((LifecycleOwner) this,
                        CameraSelector.DEFAULT_BACK_CAMERA, analysis);
            } catch (Exception e) {
                Log.e(TAG, "No se pudo iniciar la cámara", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(ImageProxy proxy) {
        try {
            Bitmap bmp = proxy.toBitmap();
            latestFrame = bmp;

            MPImage mpImage = new BitmapImageBuilder(bmp).build();
            long ts = System.currentTimeMillis();
            handLandmarker.detectAsync(mpImage, ts);
        } catch (Exception e) {
            Log.e(TAG, "Error en el análisis de frame", e);
        } finally {
            proxy.close();
        }
    }

    // ---------- Sensor de cabeza ----------

    @Override
    protected void onResume() {
        super.onResume();
        Sensor rot = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rot != null) {
            sensorManager.registerListener(this, rot, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        float[] rotMatrix = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values);
        SensorManager.getOrientation(rotMatrix, orientation);

        float yawRad = orientation[0];
        float pitchRad = orientation[1];

        // La primera lectura (o tras "Centrar") fija el frente de la vista.
        if (!haveBase) {
            baseYaw = yawRad;
            basePitch = pitchRad;
            haveBase = true;
        }
        headYawDeg = (float) Math.toDegrees(wrapRad(yawRad - baseYaw));
        headPitchDeg = (float) Math.toDegrees(pitchRad - basePitch);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private static float wrapRad(float a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    // ---------- Telemetría para el TV ----------

    private void sendTelemetryPacket() {
        if (telemetrySocket == null) return;
        String json = String.format(Locale.US,
                "{\"yaw\":%.2f,\"pitch\":%.2f,\"fx\":%.3f,\"fy\":%.3f,\"hand\":%b,\"gui\":%b,\"apps\":\"%s\"}",
                headYawDeg, headPitchDeg, fingerX, fingerY, handVisible, showGui, allowedApps);
        byte[] data = json.getBytes();
        analysisExecutor.execute(() -> {
            try {
                DatagramPacket p = new DatagramPacket(data, data.length,
                        InetAddress.getByName("255.255.255.255"), TV_TELEMETRY_PORT);
                telemetrySocket.send(p);
            } catch (Exception e) {
                Log.w(TAG, "Telemetría fallida: " + e.getMessage());
            }
        });
    }

    // ---------- Navegación y construcción de paneles ----------

    private void setScreen(Screen s) {
        screen = s;
        page = 0;
        rebuildPanels();
    }

    /** Ubicación de la celda k dentro de una cuadrícula de 3 columnas x 2 filas. */
    private static float[] slot(int k) {
        int col = k % 3;
        int row = k / 3;
        return new float[]{(col - 1) * 24f, row == 0 ? 9f : -13f};
    }

    /** Reconstruye los paneles de la pantalla actual. Sólo en el hilo de UI. */
    private void rebuildPanels() {
        panels.clear();
        dwellIndex = -1;
        dwellFired = false;

        // Barra superior de pestañas (estilo Oculus).
        panels.add(new Panel("Inicio", "", -20f, 24f, 14f, 6f, Kind.NAV, Action.GO_HOME, null,
                screen == Screen.HOME));
        panels.add(new Panel("Play Story", "", 0f, 24f, 14f, 6f, Kind.NAV, Action.GO_GAMES, null,
                screen == Screen.GAMES));
        panels.add(new Panel("YouTube", "", 20f, 24f, 14f, 6f, Kind.NAV, Action.GO_YOUTUBE, null,
                screen == Screen.YT_MENU || screen == Screen.YT_RESULTS));

        // Barra inferior.
        panels.add(new Panel("Centrar", "", -10f, -26f, 12f, 6f, Kind.NAV, Action.RECENTER, null, false));
        panels.add(new Panel("Salir", "", 10f, -26f, 12f, 6f, Kind.NAV, Action.EXIT, null, false));

        switch (screen) {
            case HOME:
                panels.add(new Panel("Play Story", "Tus juegos instalados", -16f, -2f, 22f, 24f,
                        Kind.CARD, Action.GO_GAMES, null, false));
                panels.add(new Panel("YouTube", "Tendencias y búsqueda por tema", 16f, -2f, 22f, 24f,
                        Kind.CARD, Action.GO_YOUTUBE, null, false));
                break;

            case GAMES: {
                int start = page * PAGE_SIZE;
                int end = Math.min(games.size(), start + PAGE_SIZE);
                for (int i = start; i < end; i++) {
                    GameLibrary.Game g = games.get(i);
                    float[] s = slot(i - start);
                    Panel p = new Panel(g.label, "", s[0], s[1], 14f, 14f,
                            Kind.TILE, Action.LAUNCH_GAME, g.packageName, false);
                    p.game = g;
                    panels.add(p);
                }
                addPager(games.size());
                break;
            }

            case YT_MENU: {
                panels.add(new Panel("Tendencias", "Argentina", 0f, 0f, 30f, 20f,
                        Kind.CARD, Action.YT_TRENDING, null, false));
                String[] topics = {"Música", "Juegos", "Ciencia", "Tutoriales"};
                float[] yaws = {-22.5f, -7.5f, 7.5f, 22.5f};
                for (int i = 0; i < topics.length; i++) {
                    panels.add(new Panel(topics[i], "", yaws[i], -16f, 12f, 6f,
                            Kind.NAV, Action.YT_SEARCH, topics[i].toLowerCase(Locale.ROOT), false));
                }
                break;
            }

            case YT_RESULTS: {
                int start = page * PAGE_SIZE;
                int end = Math.min(videos.size(), start + PAGE_SIZE);
                for (int i = start; i < end; i++) {
                    YouTubeApi.Video v = videos.get(i);
                    float[] s = slot(i - start);
                    Panel p = new Panel(v.title, v.channel, s[0], s[1], 14f, 14f,
                            Kind.TILE, Action.OPEN_VIDEO, v.id, false);
                    p.video = v;
                    panels.add(p);
                }
                addPager(videos.size());
                break;
            }
        }
    }

    private void addPager(int total) {
        if (page > 0) {
            panels.add(new Panel("Anterior", "", -32f, -26f, 14f, 6f,
                    Kind.NAV, Action.PREV_PAGE, null, false));
        }
        if ((page + 1) * PAGE_SIZE < total) {
            panels.add(new Panel("Siguiente", "", 32f, -26f, 14f, 6f,
                    Kind.NAV, Action.NEXT_PAGE, null, false));
        }
    }

    // ---------- Acciones ----------

    private void onPanelPressed(Panel p) {
        Log.i(TAG, "Activado: " + p.label);
        switch (p.action) {
            case GO_HOME:
                setScreen(Screen.HOME);
                break;
            case GO_GAMES:
                setScreen(Screen.GAMES);
                loadGamesIfNeeded();
                break;
            case GO_YOUTUBE:
                setScreen(Screen.YT_MENU);
                break;
            case YT_TRENDING:
                ytTitle = "Tendencias en Argentina";
                setScreen(Screen.YT_RESULTS);
                fetchVideos(null);
                break;
            case YT_SEARCH:
                ytTitle = "Búsqueda: " + p.label;
                setScreen(Screen.YT_RESULTS);
                fetchVideos(p.data);
                break;
            case NEXT_PAGE:
                page++;
                rebuildPanels();
                break;
            case PREV_PAGE:
                page--;
                rebuildPanels();
                break;
            case LAUNCH_GAME:
                launchGame(p.data, p.label);
                break;
            case OPEN_VIDEO:
                openVideo(p.data);
                break;
            case RECENTER:
                haveBase = false; // el próximo sensor fija un nuevo frente
                break;
            case EXIT:
                finish();
                break;
        }
    }

    private void loadGamesIfNeeded() {
        if (gamesLoaded || gamesLoading) return;
        gamesLoading = true;
        status = "Buscando juegos instalados…";
        Context app = getApplicationContext();
        netExecutor.execute(() -> {
            List<GameLibrary.Game> list = GameLibrary.loadInstalledGames(app);
            main.post(() -> {
                games = list;
                gamesLoaded = true;
                gamesLoading = false;
                status = list.isEmpty()
                        ? "No se encontraron juegos instalados (con categoría de juego)."
                        : null;
                if (screen == Screen.GAMES) rebuildPanels();
            });
        });
    }

    private void fetchVideos(String query) {
        if (ytLoading) return;
        ytLoading = true;
        videos = new ArrayList<>();
        status = "Cargando videos…";
        rebuildPanels();

        final String key = BuildConfig.YOUTUBE_API_KEY;
        netExecutor.execute(() -> {
            try {
                List<YouTubeApi.Video> list = (query == null)
                        ? YouTubeApi.trending(key, YT_REGION, 12)
                        : YouTubeApi.search(key, query, 12);
                main.post(() -> {
                    ytLoading = false;
                    videos = list;
                    status = list.isEmpty() ? "Sin resultados." : null;
                    if (screen == Screen.YT_RESULTS) rebuildPanels();
                    for (YouTubeApi.Video v : list) loadThumb(v);
                });
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                main.post(() -> {
                    ytLoading = false;
                    status = "Error de YouTube: " + msg;
                });
            }
        });
    }

    private void loadThumb(YouTubeApi.Video v) {
        netExecutor.execute(() -> {
            Bitmap b = YouTubeApi.downloadThumb(v.thumbUrl);
            if (b != null) v.thumb = b; // se dibuja en el siguiente frame
        });
    }

    private void launchGame(String pkg, String label) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                status = "No se puede abrir " + label + ".";
            } else {
                startActivity(i);
            }
        } catch (ActivityNotFoundException e) {
            status = "No se puede abrir " + label + ".";
        }
    }

    private void openVideo(String videoId) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/watch?v=" + videoId)));
        } catch (ActivityNotFoundException e) {
            status = "No hay app para abrir el video.";
        }
    }

    // ---------- Vista de VR ----------

    private class VrView extends View {
        private final Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint scrimPaint = new Paint();
        private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint panelHoverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dwellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint brandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        VrView(Context c) {
            super(c);
            scrimPaint.setColor(Color.argb(150, 8, 10, 16));
            panelPaint.setColor(Color.argb(215, 24, 28, 40));
            panelHoverPaint.setColor(Color.argb(240, 42, 112, 210));
            dwellPaint.setColor(Color.argb(240, 255, 200, 0));
            dwellPaint.setStyle(Paint.Style.STROKE);
            dwellPaint.setStrokeWidth(8f);
            cursorPaint.setColor(Color.RED);
            brandPaint.setColor(Color.rgb(160, 190, 240));
            hudPaint.setColor(Color.WHITE);
            hudPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setColor(Color.WHITE);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            subPaint.setColor(Color.rgb(190, 200, 220));
            subPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int eyeW = w / 2;
            float u = h / 100f;               // unidad relativa al alto de pantalla
            float focal = focalPx(eyeW);
            long t = System.currentTimeMillis();

            canvas.drawColor(Color.BLACK);

            // El fondo se desplaza con la cabeza.
            Bitmap frame = latestFrame;
            float bgShift = -headYawDeg / FOV_DEG * eyeW;

            for (int eye = 0; eye < 2; eye++) {
                int left = eye * eyeW;
                float ipd = (eye == 0) ? -IPD_PX / 2 : IPD_PX / 2;

                canvas.save();
                canvas.clipRect(left, 0, left + eyeW, h);
                if (frame != null) {
                    canvas.drawBitmap(frame, null,
                            new RectF(left + bgShift, 0, left + eyeW + bgShift, h), framePaint);
                }
                if (showGui) {
                    canvas.drawRect(left, 0, left + eyeW, h, scrimPaint); // velo para leer la GUI
                    drawPanels(canvas, left, eyeW, h, focal, ipd, t, u);
                }
                canvas.restore();
            }

            if (showGui) {
                drawHud(canvas, w, h, u, t);
            }

            // Cursor del dedo, en ambos ojos.
            if (handVisible && fingerX >= 0) {
                float cx = fingerX * eyeW;
                float cy = fingerY * h;
                canvas.drawCircle(cx, cy, 18f, cursorPaint);
                canvas.drawCircle(cx + eyeW, cy, 18f, cursorPaint);
                if (showGui) {
                    checkDwell(cx, cy, eyeW, h, focal, t);
                }
            } else {
                dwellIndex = -1;
                dwellFired = false;
            }

            postInvalidateOnAnimation();
        }

        private float focalPx(int eyeW) {
            return (eyeW / 2f) / (float) Math.tan(Math.toRadians(FOV_DEG / 2));
        }

        private float px(float deg, float focal) {
            return focal * (float) Math.tan(Math.toRadians(deg));
        }

        // Proyecta un panel del "mundo" a coordenadas de pantalla dentro de un ojo.
        // Devuelve null si el panel está fuera del campo de visión.
        private float[] project(Panel p, int eyeW, int h, float focal, float bobPx) {
            float dyaw = p.yawDeg - headYawDeg;
            float dpitch = p.pitchDeg - headPitchDeg;
            if (Math.abs(dyaw) > MAX_ANGLE_DEG || Math.abs(dpitch) > MAX_ANGLE_DEG) return null;
            float x = eyeW / 2f + (float) Math.tan(Math.toRadians(dyaw)) * focal;
            float y = h / 2f - (float) Math.tan(Math.toRadians(dpitch)) * focal + bobPx;
            return new float[]{x, y};
        }

        private float bobFor(int index, long t) {
            return (float) Math.sin(t / 500.0 + index * 1.3) * 8f; // flotación suave
        }

        private RectF rectFor(Panel p, float cx, float cy, float focal) {
            float bw = px(p.wDeg, focal);
            float bh = px(p.hDeg, focal);
            return new RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
        }

        private void drawPanels(Canvas c, int left, int eyeW, int h, float focal, float ipd,
                                long t, float u) {
            for (int i = 0; i < panels.size(); i++) {
                Panel p = panels.get(i);
                float[] pos = project(p, eyeW, h, focal, bobFor(i, t));
                if (pos == null) continue;
                RectF r = rectFor(p, left + pos[0] + ipd, pos[1], focal);
                drawPanel(c, p, r, i == dwellIndex, u);
            }
        }

        private void drawPanel(Canvas c, Panel p, RectF r, boolean hovered, float u) {
            c.save();
            if (hovered) c.scale(1.06f, 1.06f, r.centerX(), r.centerY());

            Paint fill = (p.selected || hovered) ? panelHoverPaint : panelPaint;
            float radius = 2.5f * u;
            c.drawRoundRect(r, radius, radius, fill);

            switch (p.kind) {
                case TILE: {
                    RectF art = new RectF(r.left + 0.08f * r.width(), r.top + 0.08f * r.height(),
                            r.right - 0.08f * r.width(), r.top + 0.66f * r.height());
                    drawArt(c, p, art);
                    labelPaint.setTextSize(2.8f * u);
                    subPaint.setTextSize(2.2f * u);
                    String label = TextUtils.ellipsize(p.label, labelPaint, r.width() * 0.92f,
                            TextUtils.TruncateAt.END).toString();
                    c.drawText(label, r.centerX(), r.top + 0.80f * r.height(), labelPaint);
                    if (!p.subtitle.isEmpty()) {
                        String sub = TextUtils.ellipsize(p.subtitle, subPaint, r.width() * 0.92f,
                                TextUtils.TruncateAt.END).toString();
                        c.drawText(sub, r.centerX(), r.top + 0.92f * r.height(), subPaint);
                    }
                    break;
                }
                case CARD: {
                    labelPaint.setTextSize(5f * u);
                    subPaint.setTextSize(2.6f * u);
                    c.drawText(p.label, r.centerX(), r.top + 0.45f * r.height(), labelPaint);
                    if (!p.subtitle.isEmpty()) {
                        c.drawText(p.subtitle, r.centerX(), r.top + 0.62f * r.height(), subPaint);
                    }
                    break;
                }
                case NAV: {
                    labelPaint.setTextSize(3.0f * u);
                    c.drawText(p.label, r.centerX(), r.centerY() + labelPaint.getTextSize() / 3, labelPaint);
                    break;
                }
            }

            if (hovered) c.drawRoundRect(r, radius, radius, dwellPaint);
            c.restore();
        }

        private void drawArt(Canvas c, Panel p, RectF art) {
            if (p.game != null) {
                Drawable icon = p.game.icon;
                if (icon != null) {
                    icon.setBounds((int) art.left, (int) art.top, (int) art.right, (int) art.bottom);
                    icon.draw(c);
                }
            } else if (p.video != null && p.video.thumb != null) {
                c.drawBitmap(p.video.thumb, null, art, framePaint);
            }
        }

        private void drawHud(Canvas c, int w, int h, float u, long t) {
            // Marca a la izquierda, hora a la derecha.
            brandPaint.setTextSize(3f * u);
            brandPaint.setTextAlign(Paint.Align.LEFT);
            c.drawText("VR Back", 4 * u, 6 * u, brandPaint);

            brandPaint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(clockFmt.format(new Date(t)), w - 4 * u, 6 * u, brandPaint);

            // Título de la pantalla actual.
            hudPaint.setTextSize(4f * u);
            c.drawText(screenTitle(), w / 2f, 20 * u, hudPaint);

            // Mensaje de estado.
            if (status != null) {
                hudPaint.setTextSize(2.6f * u);
                c.drawText(status, w / 2f, h - 3 * u, hudPaint);
            }
        }

        private String screenTitle() {
            switch (screen) {
                case HOME:
                    return "Inicio";
                case GAMES:
                    return "Play Story · " + (gamesLoaded ? games.size() + " juegos" : "cargando");
                case YT_MENU:
                    return "YouTube";
                case YT_RESULTS:
                    return ytTitle;
                default:
                    return "";
            }
        }

        // Hit-test en coordenadas del ojo izquierdo (sin separación entre ojos).
        private void checkDwell(float cx, float cy, int eyeW, int h, float focal, long t) {
            int hit = -1;
            for (int i = 0; i < panels.size(); i++) {
                Panel p = panels.get(i);
                float[] pos = project(p, eyeW, h, focal, bobFor(i, t));
                if (pos == null) continue;
                RectF r = rectFor(p, pos[0], pos[1], focal);
                if (r.contains(cx, cy)) {
                    hit = i;
                    break;
                }
            }

            if (hit == -1) {
                dwellIndex = -1;
                dwellFired = false;
                return;
            }
            if (hit != dwellIndex) {
                dwellIndex = hit;
                dwellStart = t;
                dwellFired = false;
            } else if (!dwellFired && t - dwellStart >= DWELL_MS) {
                dwellFired = true;
                Panel pressed = panels.get(hit);
                onPanelPressed(pressed); // puede reconstruir la lista; no volver a usar índices
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handLandmarker != null) handLandmarker.close();
        if (telemetrySocket != null) telemetrySocket.close();
        analysisExecutor.shutdown();
        netExecutor.shutdownNow();
    }
}
