# ELIZIBEL 1.0

App Android nativa en Kotlin de DigitalMinds para aprender piano y flauta dulce con canciones reales. Sin internet, sin anuncios y sin librerías externas.

## Funciones
- Piano: pentagrama doble que avanza, teclado (rango de la canción u 88 teclas), notas que caen, práctica por mano y colores por mano.
- Piano digital por USB (OTG) con android.media.midi.
- Flauta dulce soprano barroca: silueta con la digitación de cada nota y micrófono en modo agudo.
- Modos Libre, Espera y Evaluar (con récord por canción), bucles de 2, 4, 8 compases o hasta el final, subida automática de velocidad, cuenta previa, metrónomo dentro de la canción, saltos de 5 a 30 s y arrastre sobre la partitura.
- Aprender: explorador de notas, tabla de digitación, escalas y acordes, símbolos y canciones para aprender.
- Metrónomo con subdivisiones y afinador para flauta (nota escrita) y piano.

## Canciones
Piano: Listen to Your Heart (desde el MIDI, con banda), Icarus, Mariage d'Amour, Interstellar, Experience (dos pianos), Someone You Loved (acompañamiento).
Flauta: Comptine d'un autre été, Greensleeves.
Aprender: Estrellita, Himno a la alegría, Canon de Pachelbel, Para Elisa (inicio), La mañana; en flauta María tenía un corderito, Estrellita, Au clair de la lune, Himno a la alegría y Cuando los santos marchan.

## Herramientas (carpeta tools)
- `omr.py`: lee PDFs vectoriales de MuseScore símbolo por símbolo (cabezas, plicas, haces, puntillos, alteraciones, armaduras, ligaduras, tresillos, 8va/15ma, claves con octava) y valida cada compás.
- `make_piano.py` + `export.py`: generan los JSON de piano. `fix_exp.py`: corrige el compás 17 de Experience (cabeza faltante en el PDF).
- `midi2song.py`: Listen to Your Heart desde el MIDI.
- `txt2song.py`: canciones escritas en texto (`tools/src`), con suma de cada compás validada.
- `build_sounds.py`: recorta, afina y comprime las muestras a OGG.

## Sonidos
Piano: Salamander Grand Piano V3 (Alexander Holm, CC BY 3.0). Flauta dulce: VCSL (Versilian Studios, CC0).

## Compilar
`./gradlew assembleRelease` o GitHub Actions (el flujo está en la raíz del repositorio).
