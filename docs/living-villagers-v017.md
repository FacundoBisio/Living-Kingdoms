# Living Villagers 0.17.0

Rama `codex/living-villagers-v017`, sobre el main estable local 0.16.1 (`48a2a4c`). Conserva el pulido de diálogo/HUD/Quest Board y los commits preparatorios de economía/edificios (`d46445c`, `6b5bdc5`). La entrega solicitada cambió de 0.20.0 a 0.17.0: comercio/diplomacia, gestión central completa y capítulo ampliado quedan para Kingdoms Alive. No se modificaron main ni las versiones Minecraft 1.21.1, NeoForge 21.1.252 y Java 21.

## Ciclo de ciudadanos

Identidad, viviendas, edificios, asignación y carreras se reutilizan. Desde **Alcalde → Habitantes → Profesión** se asignan las seis profesiones; el selector pagina en GUI pequeñas. Cada ciudadano muestra profesión, nivel/XP, vivienda, lugar de trabajo y actividad. **Construcción → Elegir edificio** incorpora almacén, cabaña, mina y herrería, con los mismos requisitos, reservas de terreno, etapas y trabajadores del sistema anterior.

| Profesión | Estación | Consecuencia real |
|---|---|---|
| Farmer | Farm | Cosecha/replanta cultivos reales, produce comida y gana XP; respeta protección y límites de parcela. |
| Builder | Town Hall / administración convertida | Construye proyectos financiados y repara daños estructurales expresamente encargados, gastando madera/piedra y ganando XP. |
| Guard | Barracks | Patrulla edificios defensivos, combate amenazas locales y progresa por daño real. Puede recibir una pechera fabricada. |
| Lumberjack | Cabaña con mesa de trabajo intacta | Consume 1 comida y produce 4–8 madera según nivel. |
| Miner | Mina con cortapiedras intacto | Consume 2 comida + 1 madera y produce 4–8 piedra + 1 hierro. |
| Blacksmith | Herrería con mesa de herrería intacta | Consume 2 comida + 2 madera + 8 hierro y fabrica una pechera de hierro; también repara equipo sostenido con coste y espera. |

Leñador/minero/herrero caminan a su estación usando navegación vanilla. Extracción abstracta evita talar/minar terrenos de jugadores; tiene entradas pagadas, capacidad, producción/XP contabilizados y espera persistida. La espera inicial por defecto es 1.200 ticks, con bonificaciones acotadas de nivel y mínimo de 200 ticks. Quitar, reasignar o recargar no reinicia una espera pendiente.

Trabajo económico/agricultura: día 1.000–11.000. Builder trabaja antes de 12.000. Por la noche regresan a su vivienda y pueden usar una cama real propia. Guard descansa entre 16.000–22.000 si no hay amenaza; continúa buscando enemigos y despierta para combatir. Ciudadanos civiles buscan refugio ante alerta o daño reciente. El alcalde conserva su función de punto de interacción. Caminos usan el pathfinder nativo, sin teletransportes ni red vial nueva; obstáculos producen espera/reintentos acotados.

## Recursos, construcción y mantenimiento

Comida conserva el almacén y contador de cosechas anteriores. Materiales/equipo usan un SavedData adicional con límites y transacciones en el hilo servidor. Aportes no se cuentan como producción. Una entrada de equipo equivale a una pechera de hierro; puede retirarse al inventario. La madera se almacena como recurso fungible y se retira como troncos de roble.

El consumo por defecto ocurre cada 2.400 ticks: una comida por cada cuatro habitantes, redondeando hacia arriba. Comida insuficiente mantiene el modificador de inmigración y solicitudes existentes. El almacén y sus mejoras aumentan capacidad; mejoras de edificios aumentan puestos o reducen esperas, y watchtowers mejoradas amplían detección. Se conserva la expansión controlada ya implementada, con costes y rechazo de proyectos pendientes/amenazas/solapamientos.

Comandos de supervivencia, dentro de un reino incorporado:

```text
/kingdom stock
/kingdom stock deposit food|wood|stone|iron
/kingdom stock withdraw food|wood|stone|iron|equipment
/kingdom development
/kingdom development plan warehouse|lumberjack_hut|mine|blacksmith
/kingdom development fund
/kingdom development repair <buildingUUID>
/kingdom development repair_equipment
/kingdom development equip_guard <citizenUUID>
/kingdom development upgrade <buildingUUID>
/kingdom development expand
```

El aporte/retiro por comando mueve hasta 16 unidades (un equipo). Financiación usa materiales reales del almacén. Reparar equipo requiere sostener una herramienta/armadura/escudo dañado, un herrero cargado junto a su estación y 1 hierro + 1 comida; resta 40 de daño a nivel 1, sin alterar componentes. El equipo fabricado para un guardia se gasta una vez y conserva el contrato de equipo no transferible/sin drops. Por ahora estas acciones avanzadas usan comandos; `/kingdom development` muestra edificios y trabajadores con UUID copiable.

Reparaciones estructurales: una orden explícita por edificio; solo huecos AIR que correspondan a bloques estructurales del módulo original. No sobrescribe cambios intencionales, contenedores, camas, estaciones ni bloques de mineral. Escanea hasta 32 entradas por oportunidad y coloca un bloque cada 100 ticks gastando un material; respeta mobGriefing y EntityPlaceEvent. Una cancelación restaura el bloque y espera antes de reintentar. El cursor transitorio puede reiniciarse al cargar; bloques colocados y gastos ya persistidos no se duplican.

## Notificaciones

Avisos personales del servidor distinguen objetivo listo, misión completada sin recompensa material y recompensa reclamada. Usan iconos vanilla, fondo oscuro, borde pixelado dorado/verde, texto envuelto y páginas si hace falta. La cola guarda ocho pendientes y deduplica los 128 eventos recientes; reclamar sustituye un aviso pendiente aún no mostrado de la misma misión. La animación/sonido y coordinación con otras notificaciones son los del sistema Toast nativo. Una sola tarjeta del mod entra en ese sistema a la vez y respeta la duración configurada en Minecraft y F1. `questNotifications` permite desactivarlas; H conserva el control independiente del HUD.

No hay sondeo de todas las misiones ni reenvío al conectar. Las transiciones existentes `objectiveSatisfied`/`COMPLETED` son los recibos persistidos. El antiguo Iron Shortage comunica sus términos aceptados reales y su espejo del capítulo no duplica avisos. Eventos mientras el jugador está desconectado no se reproducen después. HUD y Quest Board siguen usando las mismas misiones/estados, recompensas y capacidad de sesión. No se añaden misiones o recompensas nuevas en esta entrega.

## Costes y límites comprobables

- Comprobaciones económicas/Farmer/Builder/Guard escalonadas por entidad cada 20 ticks; civiles cada 40. Las metas vanilla mantienen animación/combate fluido y sus búsquedas/movimientos tienen esperas propias.
- Consumo comparte una comprobación por reino/segundo, activada por alcalde o ciudadano registrado cargado. Cache temporal de 2.048 reinos como máximo; recibo persistido evita cobros duplicados aunque se evacue una entrada.
- Plantillas nativas: cache de 64, invalidada al cambiar el resource manager. Camas: solo la parcela de la vivienda asignada, resultados positivos cacheados 2.400 ticks y verificados antes de usar; ausencia se reintenta después de 200.
- Chunks descargados pausan trabajo/consumo; no hay simulación remota, recuperación masiva de horas perdidas ni tickets de chunks del mod. El pathfinder de 1.21.1 consulta chunks disponibles; GameTests verifican ausencia de cargas forzadas.
- Detalles de habitantes/textos se cachean por snapshot, selección/idioma y ancho. El HUD existente solo envía datos cambiados una vez por segundo. Notificaciones envían un paquete por transición, no por frame/tick.
- Balance en `kingdomEconomy`, `professions`, Builder y Guard de `livingkingdoms-server.toml`: espera, puestos, consumo, capacidad y límites existentes. No hay nuevas dependencias. Las pruebas de límites/navegación no equivalen a una medición de TPS bajo carga real.

## Validación

**392 JUnit y 156 GameTests aprobados**, Gradle build y verificaciones de JAR/assets/diff aprobados. Registro actualizado de comandos/resultados en [AUTONOMOUS_PROGRESS.md](AUTONOMOUS_PROGRESS.md). JAR generado: `build/libs/livingkingdoms-0.17.0.jar`. Las corridas fallidas y correcciones se conservan en el registro; no se borraron mundos ni se desactivaron pruebas. Se intentó iniciar el cliente: falló en el controlador AMD (`atio6axx.dll`) antes de llegar al juego. La validación automatizada no aprueba legibilidad/blur/render dentro del cliente.

## Checklist manual pendiente

- Mundo nuevo y **copia** de partida 0.16.0/0.16.1: fundar y convertir una aldea con Charter; casas/habitantes/nombre/progreso anteriores deben conservarse. Confirmar mensajes con menos de dos aldeanos/camas.
- Alcalde, Quest Board, Main Quests/Requests e historial: objetivos, bloqueo, premio/destino, volver/cerrar y selección tras reclamar. Comparar inglés y español.
- 854×480, 1280×720, 1920×1080 y 4K; GUI 1–4/Auto y shaders habituales: diálogo nítido, texto sin recortes, selector paginado, HUD dentro/fuera, H, F1, boss bars/chat/minimapa y cola de avisos sin cubrir controles importantes.
- Asignar las seis profesiones en supervivencia; comprobar estaciones, cultivos, desplazamiento por puertas/caminos, obstrucción/reintento, noche/cama propia, despertar y refugio durante un ataque.
- Aportar recursos, observar consumo/escasez y lotes de producción; llenar almacén, quitar estación, descargar chunks y volver. Construir usando materiales de almacén, encargar reparación, proteger un bloque y conservar una edición del jugador.
- Herrero: fabricación, retiro de pechera, reparación de objetos encantados/componentes, mejora pagada de guardia y ausencia de drops duplicados al morir/remover.
- Dos jugadores: intentar misma asignación/último puesto, financiación/retirada concurrentes, equipo y reclamaciones; solamente el dueño recibe avisos y cada gasto/premio ocurre una vez.
- Guardar/salir/reabrir y reiniciar servidor: UUID, casas, carreras, esperas, stock/producción, órdenes y recompensas deben persistir; no deben reaparecer avisos.
- Perfil real con varios reinos cargados: medir TPS/MSPT, rutas fallidas, memoria y tráfico; descargar reinos y comprobar caída del trabajo. No hay benchmark de carga real aprobado todavía.
