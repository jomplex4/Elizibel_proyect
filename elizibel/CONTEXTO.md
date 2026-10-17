# CONTEXTO: ELIZIBEL v1.9

## 1. Resumen
App Android nativa de DigitalMinds (dueño: Redy; creador mostrado en "Acerca de": Joel Mamani Pauccara, no se discute) para aprender y practicar piano y flauta dulce soprano con canciones reales. Partitura que avanza, teclado de 88 teclas con notas que caen, flauta con digitación, metrónomo y afinador. v1.9 compila con kotlinc; **nada se ha probado en el teléfono** (Galaxy A03 Core, Android 13). Hermana de ART STAR (misma base de código).

## 2. Tecnología
- Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, JDK 17, compileSdk/targetSdk 34, minSdk 26. Una Activity, UI por código (sin XML ni Compose), sin librerías externas, sin internet.
- Release firmada con `app/elizibel.keystore` (alias `elizibel`, clave `elizibel123`). Cada versión se instala encima de la anterior.
- Compilación: GitHub Actions (`.github/workflows/build.yml` en la RAÍZ del repo `jomplex4/Elizibel_app`): `./gradlew assembleRelease`; un tag `v*` publica UN solo APK universal en Releases.
- Sonidos OGG decodificados al abrir con MediaCodec: piano Salamander Grand V3 (CC BY 3.0) y flauta dulce soprano VCSL (CC0). Paleta: fondo #000000, tarjetas #151517/#1F2023, rojo #DC0000/#FF2A1F (mano derecha), rosado #FF6FAE (mano izquierda), plomo #9A9CA4, fondo del ícono #BEBEBE. Fuentes: Space Grotesk, Michroma (títulos), Bravura (notación).

## 3. Estructura
- Raíz del ZIP: `.github/` y `elizibel/` (proyecto Gradle). Código en `app/src/main/java/com/digitalminds/elizibel/`:
- `Music.kt` modelo (Song, Ev, NoteH, Timeline con repeticiones). `Synth.kt` audio (48 voces, metrónomo) y banco de muestras. `Mic.kt` detector YIN (modo agudo para flauta). `MidiIn.kt` piano USB.
- `ScoreView.kt` partitura (claves, haces, ligaduras, 8va, dedos). `Instruments.kt` KeyboardView, FluteView y digitación barroca. `PlayerScreen.kt` reproductor y ajustes. `Screens.kt` Partituras, Aprender, explorador, escalas, símbolos, Acerca de, `BuildConfigLite.VERSION`. `Tools.kt` metrónomo y afinador. `Widgets.kt`, `Theme.kt`, `MainActivity.kt`.
- `assets/`: `songs/*.json` + `index.json`, `sounds/*.ogg` + `index.json`, `fonts/`, `splash_icon.png`, `dm_logo.png`.
- `tools/` (Python): `omr.py` lector de PDFs vectoriales de MuseScore; `export.py` a JSON; `opus.py` (Sibelius), `isn.py` (Interstellar), `czn.py` (Corazón, raster); `midi2song.py`+`listen.py` (MIDI); `txt2song.py` + `src/*.txt` (canciones a mano, valida cada compás); `make_*.py` generan canciones; `build_sounds.py`.
- Formato de canción: 480 ticks por negra; `ev=[tick,dur,staff,stem,beam,dots,tuplet,part,rest,notes[[midi,dia,alt,shown,tie,oct,dedo]],base]`; `measures=[num,den,key,start,len]`; `order` despliega repeticiones.

## 4. Funciones actuales
- Piano: pentagrama doble, teclado ajustado a la canción u 88 teclas, notas que caen (arrastre vertical), práctica por mano, rojo/rosado. Flauta: partitura a la izquierda, silueta blanca a la derecha con agujeros rojos, dedos y "Luego".
- Piano: menú "Suenan" (barra superior y Ajustes) para silenciar mano derecha, izquierda o ambas; la melodía (nota más aguda) suena más fuerte que el resto del acorde.
- Modos Libre, Espera y Evaluar (récord), bucles (2/4/8, hasta el final, toda la canción, acelerado), velocidades 40-150 %, cuenta previa On/Off, silenciar una parte (Piano 1/2, Flauta 1/2), volumen de la app y de mis teclas.
- Aprender: explorador (piano horizontal), tabla de digitación, escalas con dedos, símbolos, canciones; "?" con explicación en cada opción. Metrónomo (hasta seisillos, uno o dos sonidos), afinador flauta/piano con notas de referencia.
- Canciones: Listen to Your Heart, Icarus, Mariage d'Amour, Corazón de niño (66 de 80 compases), Interstellar, Experience (2 pianos), Someone You Loved (solo acompañamiento); flauta (Partituras): Titanic, We Wish You (2 flautas), Dulce Navidad, Comptine, Greensleeves; Aprender: Dulce Navidad (piano), Estrellita, Canon, Para Elisa, La mañana, y 5 de flauta.

## 5. Historial de avance
- v1.0 base de ART STAR adaptada, lector de PDF, sonidos, 18 canciones. v1.1 rediseño completo según capturas, solo piano y flauta. v1.2 firma fija, compatibilidad Android 17.
- v1.3 notas que caen arrastrables, silenciar partes, ajustes con "?", Titanic y We Wish. v1.4 Dulce Navidad y Corazón. v1.5 Interstellar (Pietschmann) leído de trazos dibujados. v1.6 silenciar manos, ligaduras huérfanas reparadas. v1.7 Mi corazón encantado (flauta, a ojo). v1.8 Mi corazón encantado piano (PDF MuseScore), reproductor sin barra arriba (abajo: volver, nombre, manos, controles, metrónomo, ajustes), Ajustes en filas con ventanita, cuenta inicial tan-tan-tin, barras del sistema ocultas solo en horizontal (reproductor y explorador), también al abrir menús; arrastrar pausa y al soltar continúa, explorador con zoom, íconos de instrumento en la cabecera.

## 6. Bugs resueltos
- APK firmado distinto cada vez, no se actualizaba: keystore fija en el repo.
- Afinador no volvía a escuchar al regresar a la app: `reactivate()` en `onStart`.
- Botón del metrónomo salía de pantalla: `pickGrid` en filas centradas.
- Gesto de borde movía la partitura: arrastre corto desde el borde se ignora.
- Listen to Your Heart sonaba con pausas: el MIDI baja el tempo a 60 en el compás 21; se fuerza 115 y se quita la banda.
- Notas ligadas sin salida quedaban mudas (2 en Corazón): `fix_ties.py` las repara.
- Lector: plicas, haces entre pentagramas, clave con octava (8vb/15mb) y espaciado irregular de líneas, corregidos en `omr.py`. Experience compás 17 (cabeza faltante en el PDF) se completa en `fix_exp.py`. Corazón compases 28, 78, 79 corregidos a mano.

## 7. Problemas conocidos o pendientes
- Nada probado en el teléfono: sonido, micrófono, USB MIDI, rendimiento y dibujo de piezas densas.
- Corazón: faltan compases 46 a 59 (PDF dañado) y la app numera 1 a 66, no como el impreso. El usuario oyó saltos bruscos agudo-grave en los compases 33 a 37 y 53 a 57 (partitura leída = PDF, sextas en la derecha, sin error hallado): pedir compás y descripción exacta si persiste.
- Final de las líneas 8va en Interstellar sin confirmar de oído. Dulce Navidad flauta: la hoja del usuario dice "re" donde el PDF tiene un silencio.
- Transcritas a ojo, verificar de oído: Greensleeves, Titanic, We Wish You, Para Elisa, La mañana, Canon, melodías infantiles. Someone You Loved es solo acompañamiento.
- Faltan por no tener partitura legible: Past Lives, Una mattina (original), Canon completo, Hedwig, Gymnopédie, One Summer's Day. Mi corazón encantado de flauta es lectura a ojo de un escaneo, un tono abajo.
- Interstellar 97-110: la izquierda cruza sobre la derecha (está así en la partitura).
- `make_piano.py` aún trae el Interstellar viejo: no ejecutarlo sin quitarlo (pisaría el nuevo). Los scripts leen PDFs de `/mnt/user-data/uploads`.

## 8. Decisiones importantes
- Solo piano y flauta: ningún otro instrumento ni banda. Flauta soprano barroca de plástico, una sola digitación; la nota escrita suena una octava arriba.
- targetSdk 34 a propósito: subirlo fuerza edge-to-edge en Android 15+ y tapa la barra superior.
- Precisión: nunca inventar notas; cada compás debe sumar exacto; tolerancia del usuario 1 %. Fuente: MIDI o PDF vectorial. MuseScore.com no es accesible desde el entorno.
- Un cambio de diseño aplica a todas las pantallas parecidas. Nombres de opciones cortos, con texto previo y "?" al lado.

## 9. Siguientes pasos
1. Probar en el teléfono y corregir lo que falle. 2. Completar Corazón (46 a 59) con otro PDF. 3. Agregar Past Lives y las canciones pendientes cuando haya PDF o MIDI. 4. Ajustar lo que el usuario note al oído en Interstellar e Icarus.

## 10. Instrucciones para el nuevo chat
- Español, directo y honesto sobre lo no hecho o no probado. Cero guiones largos (la raya larga) en texto y código. Usuario no técnico: comandos PowerShell en un solo bloque, un comando por línea.
- Entregar siempre el ZIP completo (`.github/` + `elizibel/`) con `present_files`; subir `versionCode`, `versionName` y `BuildConfigLite.VERSION`.
- Antes de entregar: compilar con kotlinc 2.0.21 contra android.jar 34, validar JSON de canciones (compases, rango de flauta 60 a 86). Lo que no se pida, no se toca.
- Subida: `Remove-Item` de `elizibel` y `.github`, `Expand-Archive -Force`, `git add -A`, `commit`, `push`, `tag vX.Y`, `push origin vX.Y`.
