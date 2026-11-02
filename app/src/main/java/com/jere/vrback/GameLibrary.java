package com.jere.vrback;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lista los juegos instalados en el dispositivo (los que tienen categoría de juego
 * o la marca FLAG_IS_GAME). Sólo cuenta apps con icono de lanzador.
 */
public final class GameLibrary {

    public static final class Game {
        public final String packageName;
        public final String label;
        public volatile Drawable icon;

        Game(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private GameLibrary() { }

    public static List<Game> loadInstalledGames(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);

        Set<String> seen = new HashSet<>();
        List<Game> out = new ArrayList<>();
        for (ResolveInfo ri : infos) {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if (!isGame(ai) || !seen.add(ai.packageName)) continue;
            out.add(new Game(ai.packageName, String.valueOf(ri.loadLabel(pm)), ri.loadIcon(pm)));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private static boolean isGame(ApplicationInfo ai) {
        return ai.category == ApplicationInfo.CATEGORY_GAME
                || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
    }
}
