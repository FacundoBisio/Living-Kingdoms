# Kingdoms Alive 0.20.0

Base: main local `48a2a4c`, que incluye 0.16.1; el remoto todavía está en 0.16.0. Rama única: `codex/kingdoms-alive-v020`. No se descartaron ni publicaron esos commits previos.

Auditoría inicial: Charter, incorporación narrativa/nombres, UI sin la pasada heredada de blur, HUD modular, identidad/viviendas de ciudadanos, Farmer/Builder/Guard, reservas y etapas de construcción, encuentros/defensa y recompensas ya existen. Se conservan esos sistemas. Faltan almacenamiento económico con consumo, tres profesiones productivas, edificios de apoyo, relaciones/comercio, gestión central y progresión que los conecte.

Implementación por commits temáticos:

1. Recursos compartidos y consumo, reutilizando FoodStock y los intercambios de inventario existentes.
2. Edificios funcionales, mejoras/expansión seguras y financiación desde almacén.
3. Lumberjack/Miner/Blacksmith, horarios/refugio y reparación, sobre ProfessionSavedData y navegación nativa.
4. Relaciones descubiertas y comercio real entre almacenes, con autoridad del servidor.
5. Gestión central y capítulo de progreso integrado, manteniendo las identidades/recompensas antiguas.
6. Pruebas integradas, rendimiento, migración y JAR final.

Se aprovecha el pulido UI de 0.16.1 desde el principio; el almacén precede a las nuevas profesiones para que su producción tenga un único destino. Los mínimos funcionales de economía y diplomacia usarán límites configurables, chunks cargados y transacciones, sin extracción destructiva, simulación ilimitada de reinos lejanos ni guerras. Cada decisión de alcance y la evidencia de pruebas se anotarán brevemente al completar su fase. La aceptación gráfica seguirá pendiente hasta probar el cliente Minecraft.

Estado: auditoría terminada; implementación en curso. **No es todavía una entrega 0.20.0 aprobada.**

## Fase 1: almacén y consumo

Se implementaron cuentas compartidas persistentes, intercambios reales con inventario, revisiones contra operaciones repetidas, límites de almacenamiento y consumo periódico por población. FoodStock sigue siendo la única fuente de comida; depositar trigo no incrementa las cosechas producidas. Solo el alcalde registrado y cargado activa el consumo, con comprobaciones escalonadas y sin simulación offline. Los datos nuevos corruptos/futuros se rechazan sin sobrescribir archivos.

Acceso de supervivencia provisional: `/kingdom stock deposit food|wood|stone|iron` y `/kingdom stock withdraw food|wood|stone|iron|equipment`. Requiere incorporación al reino y estar dentro del territorio. Mueve hasta 16 unidades (una armadura por retiro), sin consumir nada si no hay capacidad, recursos o espacio. Madera admite troncos de distintos tipos y retira unidades normalizadas como roble. La gestión gráfica y la producción de armaduras se conectarán en las fases siguientes.

Validación de esta fase: **379 JUnit y 147 GameTests aprobados**, compilación de Java 21 y `git diff --check`. Mundo aislado: `runs/gametest-v020-storage`. Las pruebas nuevas cubren inventario lleno, permisos, transacciones antiguas/multiplayer, consumo una vez y lectura real del archivo guardado. La versión de desarrollo se configuró en 0.20.0; esto no equivale a certificar la actualización completa.

## Fase 2: edificios y expansión

Cuatro plantillas nativas nuevas (Warehouse, Lumberjack Hut, Mine Workshop y Market) conservan intactos los diez módulos anteriores. Blacksmith reutiliza su plantilla existente; su coste de hierro incluye el valor del yunque para evitar obtener hierro gratis desmantelándolo. La mina usa piedra decorativa sin minerales extraíbles. Los nuevos edificios pasan por las mismas reservas, protección, terreno, marcadores y etapas con Builder.

El almacén financia proyectos reales. Las mejoras guardadas aumentan capacidad del Warehouse, plazas de trabajo y detección del Watchtower; el Ayuntamiento también aumenta el nivel del reino. Los niveles de los talleres se conectan con producción en la fase siguiente y los del Market con comercio. Las mejoras son funcionales, no reemplazan el modelo de la casa ni sus inventarios. Las viviendas crecen mediante casas adicionales, evitando cambiar silenciosamente ocupación/camas de partidas anteriores. La economía carga tanto su esquema inicial como el extendido con niveles.

Expansión: +16 de radio, hasta el límite configurable, con Ayuntamiento de nivel 2, seguridad, cola terminada, coste de recursos, comprobación de vecinos y borde del mundo. Cambia el territorio efectivo sin editar terrenos ni cargar chunks. Acceso provisional: `/kingdom development plan warehouse|lumberjack_hut|mine|blacksmith|market`, `fund`, `upgrade <building UUID>` y `expand`; la interfaz central reemplazará la necesidad de identificar edificios por UUID.

Validación: **383 JUnit y 149 GameTests aprobados**, módulos nativos en cuatro rotaciones, persistencia de niveles, migración económica, ampliación del índice territorial, aumento real de capacidad/plazas y pago único de proyectos. Mundo aislado: `runs/gametest-v020-buildings`. Auditor geométrico y generador verifican los catorce módulos sin partes desconectadas; aceptación gráfica pendiente.

El helper de worldgen JSON se ejecutó y reportó ausencia de archivos compatibles: aquí las estructuras son NBT colocadas por el planificador existente, no registros worldgen JSON. Se validaron mediante el auditor NBT y los codecs/colocaciones reales de Minecraft, sin añadir definiciones ficticias para satisfacer ese helper.
