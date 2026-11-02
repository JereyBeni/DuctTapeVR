package com.jere.vrback.tv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * App de TV (paquete com.jere.vrback.tv).
 * Recibe por UDP (puerto 5005) la telemetría que manda el teléfono y la muestra:
 * orientación de la cabeza, posición del dedo, estado de la mano y de la GUI.
 */
public class TvActivity extends Activity {

    private static final String TAG = "VRBackTV";
    private static final int PORT = 5005;
    private static final long STALE_MS = 2000;      // sin paquetes por este tiempo = sin señal
    private static final float HEAD_RANGE_DEG = 70f; // ±grados que representa el panel de cabeza

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private DatagramSocket socket;
    private Thread receiver;

    private TelemetryView view;
    private Telemetry current;          // sólo se toca en el hilo de UI
    private long lastPacketMs = 0;
    private int packetCount = 0;
    private int packetsPerSecond = 0;
    private long rateWindowStart = 0;
    private String socketError = null;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            if (now - rateWindowStart >= 1000) {
                packetsPerSecond = packetCount;
                packetCount = 0;
                rateWindowStart = now;
            }
            view.invalidate();
            ui.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new TelemetryView(this);
        setContentView(view);
    }

    @Override
    protected void onStart() {
        super.onStart();
        startReceiver();
        rateWindowStart = SystemClock.elapsedRealtime();
        ui.post(ticker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(ticker);
        stopReceiver();
    }

    // ---------- Recepción UDP ----------

    private void startReceiver() {
        running = true;
        socketError = null;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(PORT));
        } catch (Exception e) {
            socketError = "No se pudo abrir el puerto " + PORT + ": " + e.getMessage();
            Log.e(TAG, socketError, e);
            return;
        }

        receiver = new Thread(() -> {
            byte[] buf = new byte[2048];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    Telemetry t = Telemetry.parse(text);
                    if (t != null) {
                        ui.post(() -> onTelemetry(t));
                    }
                } catch (Exception e) {
                    if (running) Log.w(TAG, "Error recibiendo UDP: " + e.getMessage());
                }
            }
        }, "telemetry-rx");
        receiver.start();
    }

    private void stopReceiver() {
        running = false;
        if (socket != null) {
            socket.close(); // desbloquea receive()
            socket = null;
        }
        receiver = null;
    }

    private void onTelemetry(Telemetry t) {
        current = t;
        lastPacketMs = SystemClock.elapsedRealtime();
        packetCount++;
        view.invalidate();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopReceiver();
    }

    // ---------- Modelo ----------

    static class Telemetry {
        float yaw, pitch, fx = -1f, fy = -1f;
        boolean hand, gui;
        String apps = "";

        static Telemetry parse(String s) {
            try {
                JSONObject o = new JSONObject(s);
                Telemetry t = new Telemetry();
                t.yaw = (float) o.optDouble("yaw", 0);
                t.pitch = (float) o.optDouble("pitch", 0);
                t.fx = (float) o.optDouble("fx", -1);
                t.fy = (float) o.optDouble("fy", -1);
                t.hand = o.optBoolean("hand", false);
                t.gui = o.optBoolean("gui", false);
                t.apps = o.optString("apps", "");
                return t;
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ---------- Vista ----------

    private class TelemetryView extends View {
        private final Paint bg = new Paint();
        private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TelemetryView(android.content.Context c) {
            super(c);
            bg.setColor(Color.rgb(16, 16, 20));
            panelPaint.setColor(Color.rgb(28, 30, 40));
            linePaint.setColor(Color.rgb(90, 95, 120));
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(3f);
            dotPaint.setColor(Color.rgb(255, 80, 80));
            textPaint.setColor(Color.WHITE);
            titlePaint.setColor(Color.rgb(120, 180, 255));
            titlePaint.setFakeBoldText(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            float u = h / 100f; // unidad relativa al alto de pantalla
            boolean stale = current == null
                    || SystemClock.elapsedRealtime() - lastPacketMs > STALE_MS;

            canvas.drawRect(0, 0, w, h, bg);

            textPaint.setTextSize(3.2f * u);
            titlePaint.setTextSize(4f * u);

            // Barra de estado
            String status;
            if (socketError != null) {
                status = socketError;
            } else if (stale) {
                status = "Sin señal · escuchando en el puerto " + PORT;
            } else {
                status = "Conectado · " + packetsPerSecond + " paquetes/s";
            }
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(status, 4 * u, 7 * u, textPaint);

            float top = 12 * u;
            float panelH = h - top - 6 * u;
            float half = (w - 12 * u) / 2f;

            // Panel izquierdo: orientación de la cabeza
            RectF headBox = new RectF(4 * u, top, 4 * u + half, top + panelH);
            drawPanel(canvas, headBox, u, "Cabeza");
            float cx = headBox.centerX();
            float cy = headBox.centerY();
            float range = Math.min(headBox.width(), headBox.height()) / 2f * 0.9f;
            linePaint.setColor(Color.rgb(90, 95, 120));
            canvas.drawLine(cx - range, cy, cx + range, cy, linePaint);
            canvas.drawLine(cx, cy - range, cx, cy + range, linePaint);

            if (!stale && current != null) {
                float px = cx + clamp(current.yaw / HEAD_RANGE_DEG, -1f, 1f) * range;
                float py = cy - clamp(current.pitch / HEAD_RANGE_DEG, -1f, 1f) * range;
                canvas.drawCircle(px, py, 1.6f * u, dotPaint);
                textPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(String.format("yaw %.1f°  pitch %.1f°", current.yaw, current.pitch),
                        headBox.left + 2 * u, headBox.bottom - 2 * u, textPaint);
            }

            // Panel derecho: mano y GUI
            RectF handBox = new RectF(headBox.right + 4 * u, top, w - 4 * u, top + panelH);
            drawPanel(canvas, handBox, u, "Mano y GUI");

            boolean hand = !stale && current != null && current.hand;
            boolean gui = !stale && current != null && current.gui;

            // Zona de dedo (mapa 0..1 del frame)
            RectF fingerArea = new RectF(handBox.left + 3 * u, handBox.top + 8 * u,
                    handBox.left + handBox.width() * 0.6f, handBox.bottom - 3 * u);
            linePaint.setColor(Color.rgb(60, 64, 84));
            canvas.drawRect(fingerArea, linePaint);
            if (hand) {
                float fx = fingerArea.left + current.fx * fingerArea.width();
                float fy = fingerArea.top + current.fy * fingerArea.height();
                canvas.drawCircle(fx, fy, 1.6f * u, dotPaint);
            }

            float tx = fingerArea.right + 3 * u;
            float ty = handBox.top + 12 * u;
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("Mano: " + (hand ? "detectada" : "no detectada"), tx, ty, textPaint);
            canvas.drawText("GUI: " + (gui ? "activa" : "desactivada"), tx, ty + 6 * u, textPaint);
            String apps = (current != null && !current.apps.isEmpty()) ? current.apps : "ninguna";
            canvas.drawText("Apps permitidas:", tx, ty + 14 * u, textPaint);
            canvas.drawText(apps, tx, ty + 20 * u, textPaint);

            if (!stale) {
                postInvalidateDelayed(100);
            }
        }

        private void drawPanel(Canvas c, RectF box, float u, String title) {
            c.drawRoundRect(box, 2 * u, 2 * u, panelPaint);
            titlePaint.setTextAlign(Paint.Align.LEFT);
            c.drawText(title, box.left + 2 * u, box.top + 5 * u, titlePaint);
        }

        private float clamp(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }
}
