# Living Kingdoms 0.18.1 — progreso compacto

Base estable: `codex/kingdoms-commerce-v018`, commit `d9dd748`. Integración: `codex/citizen-models-v0181`. Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21, sin cambios de dependencias.

- Análisis previo completado: ZIP íntegro, 14 huesos, 24 cubos, cuatro texturas de 64×64, nueve clips distintos. Se detectaron UV auxiliares compartidos, martillo permanente y riesgo de tapar los ojos.
- Un único renderer delegado conserva el modelo nativo y permite seleccionar la adaptación Argon. Las entidades, IA, equipo real, paquetes y esquemas persistentes siguen siendo los de 0.18.0.
- 80 skins originales intactas; 80 variantes derivadas llenan UV auxiliares. Pelo y banda recolocados; barba según identidad; martillo separado del cuerpo y condicionado al trabajo real.
- Animaciones numéricas leídas una vez por recarga; interpolación acotada sin asignaciones por muestra. Trabajo vinculado a progreso confirmado; locomoción, combate y descanso nativos conservados.
- `/kingdommodels native` y `/kingdommodels argon4`: comparación local inmediata y persistente, sin privilegios ni mensajes al servidor.
- Recursos fuente y procedencia conservados en `docs/argon4-source/`; reproductibilidad: `tools/integrate_argon_assets.py --check`.

Validación final:

- `gradlew test build`: **469 JUnit, 0 fallos, 0 errores, 0 omitidos**; build correcto. Log: `build/v0181-final-release-build.log`.
- `gradlew runGameTestServer -PgameTestDirectory=runs/gametest-v0181-models-a`: **174 GameTests aprobados**, salida 0. Log: `build/v0181-gametest-a.log`. Mundo aislado nuevo; las nueve copias de datos 0.17.0 se incluyen en la suite existente.
- `integrate_argon_assets.py --check` y `generate_citizen_assets.py --check`: recursos fuente, nueve clips, UV y 80 skins originales/derivadas comprobados. Cuatro pruebas de malla CPU verifican pivotes, UV/normales de cabeza frente a Minecraft 1.21.1, separación del martillo y recursos inválidos. Las pruebas CPU no certifican el aspecto gráfico.
- `verify_release_artifact.py --version 0.18.1`: todas las clases y recursos del JAR coinciden con los archivos actuales; Java 21, pins, traducciones y ausencia de mod de pruebas verificados. `git diff --check` correcto.
- `build/libs/livingkingdoms-0.18.1.jar`: 1.406.929 bytes; SHA-256 `035fa624238d4876a8716e3228836679c953f591af06cd21eb66985dc08a1596`.
- El JAR estable 0.18.0 mantiene SHA-256 `43af39f1dd941e022d21c18f24379e51b4493cff99a4bccf18b27a4ed90f42c3`. No se alteraron mundos originales, main, dependencias ni esquemas persistentes.

Durante las verificaciones se corrigieron rutas relativas de fixtures de tests; la última ejecución completa pasa. El servidor de pruebas registró retrasos de tick al cargar sus muchos fixtures: no constituye una medición de rendimiento gráfico ni de una partida normal.

Pendiente manual: iniciar Minecraft por cuenta del usuario, comparar caras/UV, herramientas y armaduras, sueño, etiquetas, shaders y rendimiento con varios ciudadanos; observar dos clientes con modelos distintos y probar una copia de un mundo anterior. No se ejecutará ni controlará el cliente desde Codex.

Comparación y guía detallada: [v0181-model-comparison.md](v0181-model-comparison.md).

## Validación para publicar en GitHub

La comprobación completa de publicación detectó que el fixture visual registraba un guardia con un UUID de lugar de trabajo inexistente. Su dato inválido contaminaba el SavedData compartido y podía hacer fallar la recarga del test de Farmer según el orden de ejecución. Se corrigió únicamente el fixture: registra un cuartel válido, verifica la recarga completa del perfil y retira la profesión al finalizar. No se relajaron las validaciones ni se modificó el gameplay o el formato persistente.

Tras la corrección: **469 JUnit verificados, 174 GameTests aprobados y build correcto**, salida 0. Log final: `build/v0181-main-publish-final-validation.log`; mundo aislado: `runs/gametest-v0181-main-publish-final`. El log incluye una interrupción prolongada, por lo que su duración no se usa como medición de rendimiento. Los logs, mundos, cachés y JAR del mod permanecen excluidos de Git.

También se verificaron los 14 templates, el almacén, las 80 skins originales y sus 80 variantes adaptadas, los recursos Argon versionados y sus hashes, el contenido del JAR y `git diff --check`. El JAR reconstruido para publicación pesa 1.406.951 bytes y tiene SHA-256 `d49b98efe98fef73afde307e36b0c466777d73f0702d62f1b358acb5ac759ee7`; el hash anterior corresponde a la primera entrega local.
