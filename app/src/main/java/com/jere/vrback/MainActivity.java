package com.jere.vrback;

import android.Manifest;
import androidx.activity.ComponentActivity;
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
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity 2: VR completo.
 * - Cámara trasera en pantalla dividida (un ojo por mitad).
 * - Orientación de cabeza con el sensor de rotación.
 * - Manos con MediaPipe HandLandmarker (modo LIVE_STREAM).
 * - GUI de botones que se activan al tocarlos con el índice.
 * - Si el dispositivo de salida es TV, envía telemetría por UDP.
 */
public class MainActivity extends ComponentActivity implements SensorEventListener {

    private static final String TAG = "VRBack";
    private static final int CAMERA_PERMISSION_REQ = 42;
    private static final int TV_TELEMETRY_PORT = 5005;
    private static final long DWELL_MS = 600;     // tiempo que hay que mantener el dedo sobre un botón
    private static final float YAW_SCALE = 900f;  // píxeles de desplazamiento de GUI por radián

    private VrView vrView;
    private boolean showGui;
    private boolean sendTelemetry;
    private String allowedApps;

    private SensorManager sensorManager;
    private float yaw = 0f, pitch = 0f;

    private HandLandmarker handLandmarker;
    private ExecutorService analysisExecutor;
    private volatile Bitmap latestFrame;
    private volatile float fingerX = -1f, fingerY = -1f; // normalizado 0..1 sobre el frame
    private volatile boolean handVisible = false;

    private DatagramSocket telemetrySocket;
    private long dwellStart = 0;
    private int dwellButton = -1;

    private final Button[] buttons = {
            new Button("Hola", 0.2f, 0.35f),
            new Button("Ajustes", 0.5f, 0.35f),
            new Button("Salir", 0.8f, 0.35f)
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

            vrView.postInvalidate();
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
        yaw = orientation[0];
        pitch = orientation[1];
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // ---------- Telemetría para el TV ----------

    private void sendTelemetryPacket() {
        if (telemetrySocket == null) return;
        String json = String.format(java.util.Locale.US,
                "{\"yaw\":%.3f,\"pitch\":%.3f,\"fx\":%.3f,\"fy\":%.3f,\"hand\":%b,\"gui\":%b,\"apps\":\"%s\"}",
                yaw, pitch, fingerX, fingerY, handVisible, showGui, allowedApps);
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

    // ---------- Vista de VR ----------

    private static class Button {
        final String label;
        final float nx, ny; // centro normalizado en el frame (0..1)
        Button(String label, float nx, float ny) {
            this.label = label;
            this.nx = nx;
            this.ny = ny;
        }
    }

    private class VrView extends View {
        private final Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dwellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private static final float IPD_PX = 24f;

        VrView(Context c) {
            super(c);
            buttonPaint.setColor(Color.argb(200, 30, 120, 220));
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(42f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            dwellPaint.setColor(Color.argb(220, 255, 200, 0));
            dwellPaint.setStyle(Paint.Style.STROKE);
            dwellPaint.setStrokeWidth(8f);
            cursorPaint.setColor(Color.RED);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int eyeW = w / 2;

            Bitmap frame = latestFrame;
            canvas.drawColor(Color.BLACK);

            // Desplazamiento horizontal de la imagen según la cabeza (simula mirar alrededor).
            float shift = yaw * YAW_SCALE;

            for (int eye = 0; eye < 2; eye++) {
                int left = eye * eyeW;
                canvas.save();
                canvas.clipRect(left, 0, left + eyeW, h);
                if (frame != null) {
                    RectF dst = new RectF(left + shift, 0, left + eyeW + shift, h);
                    canvas.drawBitmap(frame, null, dst, framePaint);
                }
                if (showGui) {
                    drawGui(canvas, left, eyeW, h, eye == 0 ? -IPD_PX / 2 : IPD_PX / 2, shift);
                }
                canvas.restore();
            }

            // Cursor del dedo (sólo en el ojo izquierdo para no duplicar el cálculo).
            if (handVisible && fingerX >= 0) {
                float cx = fingerX * eyeW;
                float cy = fingerY * h;
                canvas.drawCircle(cx, cy, 18f, cursorPaint);
                checkDwell(cx / eyeW, cy / h);
            } else {
                dwellButton = -1;
            }
        }

        private void drawGui(Canvas canvas, int left, int eyeW, int h, float ipdOffset, float shift) {
            float bw = eyeW * 0.22f;
            float bh = h * 0.12f;
            for (int i = 0; i < buttons.length; i++) {
                Button b = buttons[i];
                float cx = left + b.nx * eyeW + ipdOffset + shift * 0.3f;
                float cy = b.ny * h;
                RectF r = new RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
                canvas.drawRoundRect(r, 24, 24, buttonPaint);
                canvas.drawText(b.label, cx, cy + textPaint.getTextSize() / 3, textPaint);
                if (i == dwellButton) {
                    canvas.drawRoundRect(r, 24, 24, dwellPaint);
                }
            }
        }

        private void checkDwell(float nx, float ny) {
            int hit = -1;
            for (int i = 0; i < buttons.length; i++) {
                Button b = buttons[i];
                if (Math.abs(nx - b.nx) < 0.11f && Math.abs(ny - b.ny) < 0.06f) {
                    hit = i;
                    break;
                }
            }
            if (hit == -1) {
                dwellButton = -1;
                return;
            }
            long now = System.currentTimeMillis();
            if (dwellButton != hit) {
                dwellButton = hit;
                dwellStart = now;
            } else if (now - dwellStart >= DWELL_MS) {
                onButtonPressed(hit);
                dwellStart = now + 100000; // evita repetir el disparo
            }
        }
    }

    private void onButtonPressed(int index) {
        Log.i(TAG, "Botón presionado: " + buttons[index].label);
        if ("Salir".equals(buttons[index].label)) {
            finish();
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
