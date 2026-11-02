# agent.md — VR Back

Guía para agentes (y personas) que trabajen en este repo.

## Qué es

App Android de VR de cartón: el teléfono va pegado con cinta a la cabeza, con la **cámara trasera** mirando al frente. La app muestra la imagen de la cámara en pantalla dividida (un ojo por mitad), detecta la mano con MediaPipe y dibuja una GUI con botones que se activan al mantener el dedo índice sobre ellos.

Hay dos Activities:

- `MainActivityTutorial`: configuración inicial (dispositivo de salida TV/teléfono, GUI sí/no, conexión con otras apps sí/no). Guarda las respuestas en `SharedPreferences` (`vr_setup`).
- `MainActivity`: VR completo (cámara, sensor de cabeza, MediaPipe, GUI, telemetría UDP si la salida es TV).

## Stack

- Kotlin/Java: el código actual es **Java 17**.
- minSdk 26, targetSdk 34.
- CameraX 1.3.4 (`ImageAnalysis`, cámara trasera).
- MediaPipe `tasks-vision` 0.10.14 (`HandLandmarker`, modo `LIVE_STREAM`).
- `TYPE_ROTATION_VECTOR` para la orientación de cabeza.

## Estructura

```
app/src/main/
  AndroidManifest.xml
  java/com/jere/vrback/
    MainActivityTutorial.java
    MainActivity.java
  assets/
    hand_landmarker.task   <- NO versionado (descargar, ver abajo)
  res/values/
    strings.xml
    themes.xml
```

## Setup

1. Descargar `hand_landmarker.task` desde la sección **Models** de la guía oficial (https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker/android) y ponerlo en `app/src/main/assets/`.
2. Abrir en Android Studio y sincronizar Gradle.
3. Ejecutar en un teléfono físico (la cámara y los sensores no existen en el emulador).

## Convenciones

- Los nombres de extras del Intent viven como constantes en `MainActivityTutorial`. Si agregas una opción, añádela ahí.
- `MainActivity` extiende `ComponentActivity` (no `Activity`), porque CameraX necesita `LifecycleOwner`.
- La GUI se dibuja en `VrView` con `Canvas`; no usar layouts XML para la vista de VR.
- Los botones se definen en el arreglo `buttons` de `MainActivity` (coordenadas normalizadas 0..1 sobre el frame).

## Pendientes conocidos

- Ícono de la app: el manifest referencia `@mipmap/ic_launcher`, que aún no existe.
- El desplazamiento por yaw es una aproximación, no tracking 3D real.
- `proxy.toBitmap()` por frame es costoso; migrar a OpenGL si el FPS es bajo.
- Validación de conexión con otras apps: sólo hay lista blanca; falta el protocolo con la app de TV.
- Paquete de la app de TV: `com.jere.vrback.tv` (puerto UDP 5005).
- Código sin compilar en este entorno: revisar al abrir en Android Studio.

## Reglas para agentes

- No commitear `hand_landmarker.task` ni archivos de build (`build/`, `.gradle/`, `local.properties`).
- Cambios pequeños y enfocados; no mezclar refactor con features.
- Si cambias el comportamiento del tutorial o de la lista blanca, actualiza este archivo.
- 
