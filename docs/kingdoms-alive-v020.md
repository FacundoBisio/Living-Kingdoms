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
