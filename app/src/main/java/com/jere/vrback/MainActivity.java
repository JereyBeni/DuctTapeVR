package com.jere.vrback;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
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
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity 2: VR completo.
 * - Cámara trasera en pantalla dividida (un ojo por mitad).
 * - Orientación de cabeza con el sensor de rotación (con recentrado).
 * - Manos con MediaPipe HandLandmarker (modo LIVE_STREAM).
 * - GUI flotante: los paneles viven en "mundo" (yaw/pitch fijos) y se proyectan
 *   según la orientación de la cabeza, con un leve movimiento de flotación.
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
    private static final float PANEL_W_DEG = 12f;  // tamaño de cada panel en grados de visión
    private static final float PANEL_H_DEG = 7f;
    private static final long ACTION_TOAST_MS = 1500;

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
    private volatile Bitmap latestFrame;
    private volatile float fingerX = -1f, fingerY = -1f; // normalizado 0..1 sobre el frame
    private volatile boolean handVisible = false;

    private DatagramSocket telemetrySocket;

    private int dwellIndex = -1;
    private long dwellStart = 0;
    private boolean dwellFired = false;
    private String lastAction = null;
    private long lastActionTime = 0;

    // Paneles fijos en el "mundo", en grados respecto al centro de la vista.
    private static class Panel {
        final String label;
        final float yawDeg, pitchDeg;
        Panel(String label, float yawDeg, float pitchDeg) {
            this.label = label;
            this.yawDeg = yawDeg;
            this.pitchDeg = pitchDeg;
        }
    }

    private final Panel[] panels = {
            new Panel("Hola", -20f, 0f),
            new Panel("Ajustes", 0f, 0f),
            new Panel("Salir", 20f, 0f),
            new Panel("Centrar", 0f, -14f)
    };

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

    // ---------- Acciones de la GUI ----------

    private void onPanelPressed(int index) {
        String label = panels[index].label;
        Log.i(TAG, "Panel activado: " + label);
        lastAction = label;
        lastActionTime = System.currentTimeMillis();
        switch (label) {
            case "Salir":
                finish();
                break;
            case "Centrar":
                haveBase = false; // el próximo sensor fija un nuevo frente
                break;
            default:
                break;
        }
    }

    // ---------- Vista de VR ----------

    private class VrView extends View {
        private final Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dwellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        VrView(Context c) {
            super(c);
            panelPaint.setColor(Color.argb(190, 30, 120, 220));
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(36f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            dwellPaint.setColor(Color.argb(230, 255, 200, 0));
            dwellPaint.setStyle(Paint.Style.STROKE);
            dwellPaint.setStrokeWidth(8f);
            cursorPaint.setColor(Color.RED);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int eyeW = w / 2;
            float focal = focalPx(eyeW);
            long t = System.currentTimeMillis();

            canvas.drawColor(Color.BLACK);

            // El fondo se desplaza con la cabeza: al girar a un lado, la imagen se corre al contrario.
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
                    drawPanels(canvas, left, eyeW, h, focal, ipd, t);
                }
                canvas.restore();
            }

            // Mensaje de la última acción (centrado arriba, visible un momento).
            if (lastAction != null && t - lastActionTime < ACTION_TOAST_MS) {
                canvas.drawText("✓ " + lastAction, w / 2f, 60f, textPaint);
            }

            // Cursor del dedo, dibujado en ambos ojos.
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

            // Redibujar continuamente para que la flotación y la cámara se vean fluidas.
            postInvalidateOnAnimation();
        }

        private float focalPx(int eyeW) {
            return (eyeW / 2f) / (float) Math.tan(Math.toRadians(FOV_DEG / 2));
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

        private float panelWPx(float focal) {
            return focal * (float) Math.tan(Math.toRadians(PANEL_W_DEG));
        }

        private float panelHPx(float focal) {
            return focal * (float) Math.tan(Math.toRadians(PANEL_H_DEG));
        }

        private void drawPanels(Canvas c, int left, int eyeW, int h, float focal, float ipd, long t) {
            float bw = panelWPx(focal);
            float bh = panelHPx(focal);
            for (int i = 0; i < panels.length; i++) {
                float[] pos = project(panels[i], eyeW, h, focal, bobFor(i, t));
                if (pos == null) continue;
                RectF r = new RectF(left + pos[0] + ipd - bw / 2, pos[1] - bh / 2,
                        left + pos[0] + ipd + bw / 2, pos[1] + bh / 2);
                c.drawRoundRect(r, 24, 24, panelPaint);
                c.drawText(panels[i].label, r.centerX(),
                        r.centerY() + textPaint.getTextSize() / 3, textPaint);
                if (i == dwellIndex) {
                    c.drawRoundRect(r, 24, 24, dwellPaint);
                }
            }
        }

        // Hit-test en coordenadas del ojo izquierdo (sin separación entre ojos).
        private void checkDwell(float cx, float cy, int eyeW, int h, float focal, long t) {
            float bw = panelWPx(focal);
            float bh = panelHPx(focal);
            int hit = -1;
            for (int i = 0; i < panels.length; i++) {
                float[] pos = project(panels[i], eyeW, h, focal, bobFor(i, t));
                if (pos == null) continue;
                if (Math.abs(cx - pos[0]) < bw / 2 && Math.abs(cy - pos[1]) < bh / 2) {
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
                onPanelPressed(hit);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handLandmarker != null) handLandmarker.close();
        if (telemetrySocket != null) telemetrySocket.close();
        analysisExecutor.shutdown();
    }
}
