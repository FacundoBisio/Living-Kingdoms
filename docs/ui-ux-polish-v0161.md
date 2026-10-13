# Living Kingdoms 0.16.1: interfaz, Charter y validación

Minecraft 1.21.1, NeoForge 21.1.252 y Java 21. Rama `codex/ui-ux-polish-v0161`, creada desde `origin/main` en `9a969cc9a293f9c68860d37ded2f781ae76f60c4`. Se conservan la progresión, recompensas, mundos y sistemas de 0.16.0. Sin commit, merge ni push automáticos.

## Causa del blur y corrección

El arreglo previo documentado en el README existía en `VillageScreen`, pero no cubría `MayorOnboardingScreen`. Se comprobó el código exacto de `Screen` en `neoforge-21.1.252-sources.jar`: `Screen.render` llama a `renderBackground` antes de dibujar sus widgets; el fondo heredado ejecuta `gameRenderer.processBlurEffect` y el fondo de menú. La introducción dibujaba su panel y texto antes de llamar a `super.render`, de modo que esa segunda pasada podía desenfocar y cubrir contenido ya dibujado. Cambiar solamente colores no eliminaba la causa.

Ambas pantallas heredan ahora `KingdomScreen`, cuyo `renderBackground` final no ejecuta esa pasada. Dibujan una sola capa oscura sobre el mundo y paneles opacos, con texto nativo sin escalado fraccional ni sombra. No se cambia el ajuste global de desenfoque de Minecraft. `VillageTheme` ya empleaba colores opacos; los tooltips siguen siendo nativos. Esto elimina el camino identificado en el código; falta aceptar el resultado en una GPU real y comprobar posibles shaders o modificaciones de otros mods.

La introducción muestra hasta cinco líneas por página, menos cuando queda poco espacio. Continuar, Volver y Cerrar son controles nativos. Volver consulta páginas o etapas anteriores en el cliente sin retroceder el estado del servidor. El borrador del nombre se conserva al revisar páginas, redimensionar, recargar fuentes o recibir un rechazo. Solo confirmar la última página de la etapa actual envía una acción.

Los diálogos posteriores del alcalde también tienen páginas cortas, flechas para revisar y el control habitual de salida. Las estadísticas completas están en Información; ya no llenan todas las conversaciones. Una amenaza sigue apareciendo en el contexto. Si la ventana deja menos de 280×200 unidades GUI, se muestra una instrucción breve para ampliarla y un botón Cerrar, evitando superponer controles.

También se corrigieron secuencias de codificación dañadas que ya existían en ambos idiomas, incluidos acentos españoles y separadores ingleses. Las traducciones mantienen sus argumentos.

## HUD y misiones

Hay dos tarjetas independientes, con fondo oscuro semitransparente, texto opaco e íconos nativos de 8×8:

- Reino: nombre, reputación y población; seguridad cuando hay una amenaza. Se muestra únicamente dentro del territorio aliado elegido por el servicio existente.
- Misión seguida: título, objetivo breve, todos los recursos requeridos y su progreso, miembros derrotados del grupo concreto y estado listo para reclamar. Permanece fuera del reino; al estar lista indica el tablón de destino.

El progreso procede del snapshot del servidor, no de estimaciones del cliente. El servidor incluye el tamaño original del grupo para presentar derrotados/total, incluso durante una pelea. No cambian las reglas de contribución ni de recompensas. El modo expandido añade información de reputación, seguridad y destino. El texto largo se envuelve sin puntos suspensivos. Cuando no entra todo, se conservan las líneas en páginas con contador que rotan cada seis segundos visibles; las actualizaciones de progreso no reinician continuamente ese reloj.

H conserva el ajuste existente y su remapeo; K abre la administración local y J el diario remoto. El HUD se oculta al abrir pantallas/chat, F3 o F1. Evita el espacio de las boss bars que efectivamente lo cruzan. Otros overlays se acomodan mediante offsets; no hay detección automática de cada minimapa.

Configuración del cliente en `livingkingdoms-client.toml`:

| Clave | Predeterminado | Uso |
| --- | --- | --- |
| `hudEnabled` | `true` | Mostrar HUD; H lo alterna |
| `showKingdomInfo` | `true` | Tarjeta del reino |
| `showTrackedQuest` | `true` | Tarjeta de misión |
| `expandedHud` | `false` | Detalles adicionales |
| `hudX`, `hudY` | `6`, `6` | Offset en unidades GUI, acotado a pantalla |
| `hudWidth` | `200` | Ancho entre 160 y 320 |
| `hudBackgroundAlpha` | `204` | Opacidad entre 192 y 255 |

El mínimo de opacidad conserva una relación de contraste de al menos 4,5:1 para todos los tonos sobre un fondo blanco en el modelo de composición probado. Esto no sustituye la lectura real, ni garantiza compatibilidad visual con todas las fuentes o shaders.

El Quest Board prioriza objetivo, progreso y condiciones de desbloqueo. Explica el motivo del encargo y dónde reclamar. El lector inicial muestra trabajo vigente y el siguiente paso bloqueado; Historial permite consultar el resto. No borra ni altera misiones. Las filas breves mantienen título completo en tooltip, narración y lector de detalles.

## Charter: sistema existente verificado

Se reutiliza `SettlementEstablishmentService` y `VillageSurvey`, sin otro sistema de conversión. Los mínimos predeterminados siguen siendo dos aldeanos vivos disponibles y dos camas completas. La campana no es obligatoria. La consulta ocurre al usar la Carta, sobre chunks ya cargados; no fuerza carga de mundo.

La conversión conserva casas, camas, inventarios y habitantes. Promueve un aldeano apropiado como alcalde cuando puede hacerlo, o crea el alcalde necesario, y coloca marcador y Quest Board en una plaza libre. El mensaje de éxito y las instrucciones siguientes quedan en el chat, con la ubicación del asentamiento y el paso de hablar con el alcalde. Cuando falta un mínimo, muestra actual/requerido de aldeanos y camas, explica cómo reintentar y conserva la Carta.

El flujo narrativo y el nombre usan el sistema de 0.16.0. La prueba nueva usa la interacción real del ítem, completa esa conversación y vuelve a leer los archivos guardados mediante `DimensionDataStorage`, verificando nombre, UUID, territorio y estado JOINED. Las pruebas existentes de fundación desde cero, proyectos iniciales, geometría y rollback se conservaron. Guardar y salir/reabrir un mundo completo desde el cliente todavía requiere la prueba manual indicada abajo.

## Costes y compatibilidad

El HUD sigue recibiendo snapshots comparados/deduplicados por el servicio existente; no agrega otra sincronización. En el render solo dibuja líneas, fondos e íconos. Su presentación y layout se reconstruyen ante datos, tamaño, configuración, boss bars o recursos nuevos. El lector usa una caché por pantalla de hasta 128 textos envueltos, invalidada al actualizar datos o fuentes. La introducción y los diálogos cachean páginas y orden visual. No se agregaron dependencias, barridos de entidades, IA ni trabajo del servidor por frame.

Los datos guardados mantienen sus esquemas. El registro de red pasa a versión 10: cliente y servidor deben usar el mismo JAR 0.16.1. Se conservan los JAR y mundos de desarrollo anteriores.

## Validación automática

- 374 pruebas JUnit, sin fallos ni omisiones; incluyen paginación, conservación de palabras, caché, contraste, paridad/UTF-8 de traducciones, límites GUI, historial, tarjetas independientes y progreso real.
- 145 GameTests aprobados en `runs/gametest-v0161-final-validation`. Dos nuevos verifican conversión/narrativa/nombre/lectura de disco y los dos mínimos incompletos sin consumo. Las comprobaciones previas de fundación, defensa, multiplayer y recompensas siguen presentes.
- Gradle build y comprobación del JAR: versión 0.16.1, Minecraft `[1.21.1]`, NeoForge `[21.1.252,21.2)`, idiomas actuales y ausencia del mod/clases de pruebas.
- Atlas HUD, filtrado sin blur y nueve recursos anteriores contrastados con los bytes del JAR. El auditor tolera finales CRLF de checkout en JSON y mantiene la comparación exacta de los bytes empaquetados.
- Validador de la skill de recursos sobre una copia temporal en `build`, con `pack.mcmeta` formato 34 comprobado contra `SharedConstants` de 1.21.1. El mod no necesita ese archivo de pack externo. Tres avisos de referencias vanilla se resuelven aparte contra los recursos de Minecraft; no se añaden archivos artificiales al mod.
- `git diff --check`.

La primera ejecución registró dos fallos por una colisión de nombres en el auxiliar nuevo que capturaba mensajes; se corrigió sin alterar las comprobaciones antiguas y se repitió toda la suite en otro mundo. Ambos mundos se conservaron. Los logs de rollback provocado son parte de las pruebas de rechazo, no errores de la ejecución final.

## Prueba manual pendiente en Minecraft

Usar una copia del mundo habitual y el JAR ordinario `build/libs/livingkingdoms-0.16.1.jar`, no el `-sources.jar`. Registrar GPU, resolución, escala GUI, idioma, fuente/resource pack y shaders. No hay captura gráfica aceptada de 0.16.1 en esta entrega.

| Resolución | Escalas que probar, cuando estén disponibles |
| --- | --- |
| 854×480 | 1, 2, Auto |
| 1280×720 | 1, 2, 3, Auto |
| 1920×1080 | 1, 2, 3, 4, Auto |
| 2560×1440 | 2, 3, 4, Auto |
| 3840×2160 | 3, 4, Auto |

1. En EN y ES, abrir todas las etapas iniciales del alcalde, incluyendo Nombre. Comparar desenfoque de menú en cero y su máximo, frente a mundo de día/noche y terreno claro. El texto debe conservar bordes nítidos y contraste. Volver, Continuar y Cerrar deben quedar separados del texto y del campo.
2. Escribir un nombre con acentos; volver a páginas anteriores, redimensionar y ejecutar F3+T. El borrador debe seguir intacto. Confirmar un nombre inválido o duplicado, corregirlo y registrar; no debe avanzar dos veces ni perder caracteres. Cerrar mientras espera respuesta no debe reabrir la pantalla después.
3. Abrir el diálogo normal, recorrer sus flechas y entrar a Información, Construcción, Inmigración y Ciudadanos. Probar tooltips y tabulación. No debe reaparecer blur al dibujar controles ni solaparse el retrato con las flechas. Reducir mucho la ventana: debe aparecer el aviso y poder cerrarse.
4. Seguir una misión en el tablón o diario. Dentro del territorio comprobar las dos tarjetas; fuera comprobar que desaparece solo el reino. Cambiar H y remapearlo; comprobar J/K y que chat, inventario, F1 y F3 respetan la visibilidad.
5. Para entrega de varios recursos, cambiar cantidades en inventario/mano secundaria y verificar todos los contadores. Para combate real, derrotar miembros del grupo vinculado y comprobar derrotados/total. Al terminar, debe indicar lista para reclamar y el tablón correcto; reclamar una vez debe conservar la recompensa habitual.
6. Probar nombres de reino largos, títulos largos, modo expandido y ancho 160. Si hay páginas, leer el contenido completo durante su ciclo, también mientras se actualiza progreso. Añadir una y varias boss bars; comprobar los offsets con el minimapa usado normalmente. No debe recortarse ni solaparse texto.
7. En el Quest Board, comprobar que aparecen el motivo, objetivo, requisito de bloqueo y destino de recompensa. Cambiar Historial, aceptar, seguir, reclamar y revisar el siguiente paso. Los valores de progresión y recompensa deben coincidir con 0.16.0.
8. Convertir una aldea vanilla real con dos aldeanos y dos camas completas. Antes/después registrar casas, camas, habitantes e inventarios; encontrar alcalde y Quest Board, terminar introducción y nombrar. Repetir con una cama y luego con un aldeano: deben aparecer los mínimos y no consumirse la Carta.
9. Fundar con Carta en terreno vacío sin señales de aldea, abastecer el Ayuntamiento y primera Casa. Probar conversión forzada agachado fuera de aldea: debe rechazar sin fundar. No habilitar cheats para estos flujos de supervivencia.
10. Guardar y salir, cerrar/reabrir Minecraft y volver al mundo. Verificar nombre, alcalde, tablero, habitantes, progreso, misión seguida y preferencias H. Repetir la incorporación con dos jugadores: un nombre compartido, conversaciones personales y ninguna recompensa duplicada.

Aceptar visualmente solo después de completar estas comprobaciones. Fuentes de terceros pueden cambiar anchos; F3+T invalida las cachés. Muchas boss bars en una ventana pequeña pueden ocupar todo el espacio seguro y ocultar temporalmente las tarjetas; usar offsets, menor escala GUI o un panel solo. La adaptación automática a overlays de todos los mods queda fuera de esta corrección.
