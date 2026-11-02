package com.jere.vrback;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * Activity 1: tutorial de configuración.
 * Pregunta dispositivo de salida, GUI o sin GUI, y conexión con otras apps.
 * Guarda las respuestas en SharedPreferences y abre MainActivity.
 */
public class MainActivityTutorial extends Activity {

    public static final String PREFS = "vr_setup";
    public static final String KEY_OUTPUT = "output";        // "tv" | "phone"
    public static final String KEY_GUI = "gui";              // true | false
    public static final String KEY_EXTERNAL = "external";    // true | false
    public static final String KEY_ALLOWED_APPS = "allowed_apps";

    // Paquetes con los que se permite hablar (permisos restringidos).
    private static final String[] ALLOWED_PACKAGES = {
            "com.jere.vrback.tv"
    };

    private final String[] outputs = {"TV", "Este teléfono"};
    private final String[] guiOptions = {"GUI", "Sin GUI"};
    private final String[] externalOptions = {"Sí, con permisos restringidos", "No"};

    private int outputChoice = 0;
    private int guiChoice = 0;
    private int externalChoice = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("setup_done", false)) {
            launchVr(prefs);
            return;
        }
        askOutput();
    }

    private void askOutput() {
        new AlertDialog.Builder(this)
                .setTitle("¿A qué dispositivo te vas a conectar?")
                .setSingleChoiceItems(outputs, 0, (d, which) -> outputChoice = which)
                .setPositiveButton("Siguiente", (d, w) -> askGui())
                .setCancelable(false)
                .show();
    }

    private void askGui() {
        new AlertDialog.Builder(this)
                .setTitle("¿Con GUI o sin GUI?")
                .setSingleChoiceItems(guiOptions, 0, (d, which) -> guiChoice = which)
                .setPositiveButton("Siguiente", (d, w) -> askExternal())
                .setCancelable(false)
                .show();
    }

    private void askExternal() {
        new AlertDialog.Builder(this)
                .setTitle("¿Conectar con otras apps?")
                .setMessage("Solo se permitirán los paquetes de la lista blanca. Cada conexión pedirá confirmación.")
                .setSingleChoiceItems(externalOptions, 0, (d, which) -> externalChoice = which)
                .setPositiveButton("Terminar", (d, w) -> finishSetup())
                .setCancelable(false)
                .show();
    }

    private void finishSetup() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putString(KEY_OUTPUT, outputChoice == 0 ? "tv" : "phone");
        e.putBoolean(KEY_GUI, guiChoice == 0);
        e.putBoolean(KEY_EXTERNAL, externalChoice == 0);
        e.putString(KEY_ALLOWED_APPS, String.join(",", ALLOWED_PACKAGES));
        e.putBoolean("setup_done", true);
        e.apply();

        launchVr(getSharedPreferences(PREFS, MODE_PRIVATE));
    }

    private void launchVr(SharedPreferences prefs) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(KEY_OUTPUT, prefs.getString(KEY_OUTPUT, "phone"));
        i.putExtra(KEY_GUI, prefs.getBoolean(KEY_GUI, true));
        i.putExtra(KEY_EXTERNAL, prefs.getBoolean(KEY_EXTERNAL, false));
        i.putExtra(KEY_ALLOWED_APPS, prefs.getString(KEY_ALLOWED_APPS, ""));
        startActivity(i);
        finish();
    }
}
