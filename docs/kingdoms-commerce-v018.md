# Kingdoms & Commerce 0.18.0

Rama `codex/kingdoms-commerce-v018`, basada en `main/origin/main` `203a976` (0.17.0 completa). Minecraft 1.21.1, NeoForge 21.1.252 y Java 21; sin nuevas dependencias. Se conservaron los sistemas de identidad, rutinas, economía, construcción, misiones, HUD y diálogos. Commits locales; main no fue modificado y no se hizo merge/push.

## Flujo jugable de supervivencia

1. Fundar o incorporar una aldea con Kingdom Charter y completar la incorporación con el alcalde.
2. Fabricar **Almacén del reino**: mesa de trabajo, patrón `LIL / LCL / LLL`, donde L = tablones, I = hierro y C = cofre. Colocarlo dentro del territorio. Clic derecho abre inventario y recursos del mismo KingdomEconomy que usan los ciudadanos.
3. Shift-clic en una pila del inventario aporta esa pila; clic en un recurso retira hasta 16 unidades físicas (una pechera), clic derecho retira una. Pasar el cursor muestra saldo/capacidad, materiales reservados y consumidos por obras y reservas alimentarias. Las reservas de obra ya se pagaron y permanecen en ConstructionProject: no se descuentan otra vez del saldo disponible. El equipo no acepta depósitos en el almacén; los herreros lo producen.
4. **Alcalde → Construcción → Elegir edificio → Previsualizar**. Elegir el tipo, girar con R, mover la esquina al terreno señalado con G, confirmar con Enter o cancelar con Retroceso. Teclas remapeables. El contorno verde/rojo corresponde al último estudio del servidor; azul indica la entrada. Confirmar crea el proyecto pendiente existente; después se aportan materiales con Mochila/Almacén y trabaja Builder. No se cobra ni coloca nada durante la vista previa. Es necesario hablar directamente con el alcalde para iniciar la sesión de parcela; el acceso rápido o un tablero no otorgan esa capacidad.
5. Construir **Market** mediante el mismo flujo, y asignar **Merchant** desde **Habitantes → Profesión**. Necesita hogar, puesto libre, estación intacta, horario de trabajo y seguridad. Interactuar con el comerciante compra o vende una unidad por oferta con esmeraldas. Shift + usar abre su ficha de actividad.
6. **Alcalde → Construcción → Edificios** abre administración: mejoras, reparaciones estructurales, expansión, equipo de guardias, reparación del objeto sostenido y registro histórico. Las acciones gastan recursos reales o crean órdenes de trabajo; no requieren comandos.

## Comercio y almacenamiento

Un almacén físico es un punto de acceso al saldo único del reino, sin inventario económico duplicado en el bloque. Romper/recolocar el bloque conserva el stock y no lo convierte en drops. Menú nativo, iconos de recursos de solo lectura, inventario normal y validaciones de territorio, incorporación, alcance, vida, permisos y capacidad. No se pueden extraer los iconos mediante clonado, intercambio de hotbar o lanzamiento.

El mercado comparte una tesorería y cuotas por reino; varios comerciantes no multiplican las existencias. La tesorería inicial configurable es 32 esmeraldas por reino. Reponer renueva cuotas, sin crear dinero ni bienes; el comerciante vende stock real del KingdomEconomy. Compras/ventas usan precios configurables con margen obligatorio de compra mayor que venta. Cada oferta tiene recibo de uso y revisiones de mercado, economía, comida y empleo. Una oferta desactualizada se renueva y debe confirmarse de nuevo. Inventario lleno, componentes modificados, reservas alimentarias, estación perdida, cambio de profesión o desconexión no producen intercambios parciales ni pagos duplicados. Configuración: `livingkingdoms-commerce.toml`.

Comida mantiene las unidades nutritivas de 0.17.0: por defecto 1 trigo = 2 unidades. Raciones saludables y alimentos comprometidos por misiones remuneradas quedan protegidos de retiros/ventas hasta su consumo por habitantes o trabajo. Madera sigue siendo fungible y se retira como troncos de roble. Las profesiones económicas mantienen entradas pagadas y esperas persistidas; no se agregaron talas o minas destructivas.

## Ciudadanos y presentación

Las entidades siguen siendo Villager con los mismos UUID, inventarios, equipo, navegación y persistencia. Solo ciudadanos/alcaldes registrados reciben presentación humanoide; no se transforma al resto del mundo. Renderizador nativo, brazos separados, caminar/quietud/combate/descanso y 80 skins originales reproducibles. Armadura y armas reales se renderizan; herramientas cosméticas nunca entran en inventarios o drops. El gesto de trabajo se vincula al progreso real, y se oculta durante descanso/refugio o desplazamiento. Sonidos vanilla discretos y acotados, sin sonidos por frame.

Nombre, profesión traducida y nivel aparecen según distancia/configuración; ficha de actividad al interactuar con ciudadanos. Alcalde y Merchant conservan su interacción normal; Shift + usar permite inspeccionarlos. `livingkingdoms-citizen-visuals.toml` controla humanoides, etiquetas, distancias y acentos de trabajo. [Comparación nativo/GeckoLib y límites](v018-citizen-rendering.md).

## Mejoras verificables

Nuevas mejoras se limitan a niveles 1–3. Niveles históricos superiores no se eliminan. Costes del siguiente nivel: 32×nivel actual madera, 16×nivel piedra y 4×nivel hierro. Las solicitudes incluyen revisión de recursos; repetir un paquete anterior no vuelve a cobrar ni subir de nivel.

| Edificio | Beneficio real |
|---|---|
| Town Hall / Core | +1 puesto Builder por nivel; eleva desarrollo y habilita expansión desde nivel 2. |
| Warehouse | Capacidad adicional de materiales y comida según configuración por nivel. |
| Farm | +1 puesto Farmer por nivel para cosechas reales. |
| Barracks | +1 puesto Guard por nivel. |
| Watchtower | Mayor rango de detección existente. |
| Blacksmith / Mine / Lumberjack Hut | +1 puesto por nivel y reducción de espera productiva de 120 ticks por nivel; mínimo 200. |
| Market | Puestos Merchant y mayores cuotas al reponer. |
| Houses | +1 plaza habitacional por nivel; se necesitan camas para descanso físico. |

Las mejoras funcionales no sustituyen templates ni sobrescriben modificaciones del jugador. El nivel y beneficio se muestran en administración; los cambios arquitectónicos permanentes adicionales quedan fuera de esta entrega. Reparaciones solo cubren huecos estructurales seguros del template y respetan protección/eventos. Equipo de guardia consume una pechera fabricada, con la política existente de propiedad/sin drops. Reparar el objeto sostenido requiere herrero en su estación y paga 1 hierro + 1 comida. Expansión conserva identidad e índices; exige nivel 2, seguridad, sin obras pendientes y 64 madera + 32 piedra + 8 hierro.

## Alertas y avances

Hechos persistidos: muerte de ciudadano/guardia registrado, pérdida del último trabajador esencial, amenaza importante detectada, construcción y mejora completadas. Se muestran nombre/rol/reino conocidos, sin atribuir culpables o causas de ataque no comprobadas. Avisos personales para miembros incorporados conectados; no hay repetición al iniciar sesión. Historial desde Edificios → H: 128 hechos por reino; 512 recibos recientes, hasta 1024 reinos de historial. Evicción de recibos no permite repetir transiciones: los sistemas fuente conservan sus recibos duraderos. Cola de ocho avisos, 128 IDs recientes, integrada al Toast nativo. No hay sondeo del mundo para generar avisos.

Seis avances nuevos nativos: conversión de aldea, primer Warehouse, siete profesiones operativas, primera mejora, primer comercio y desarrollo 3. Conservan los avances anteriores de fundación, guardias, Builder y defensa. Se conceden tras eventos reales; no añaden pagos repetibles ni recompensas de inventario. Construcción/debug no concede los avisos/avances de producción normales.

## Rendimiento y compatibilidad comprobados

- Trabajo/seguimiento de ciudadanos escalonado por entidad cada 20 ticks. Estado visual enviado al cambiar o cada 200 ticks a sus observadores. Cache servidor 4096 y caches cliente 1024 entradas; no búsquedas globales por frame.
- Mercado bajo demanda: cinco ofertas y 37 slots físicos; cuotas/reposición perezosa sin recuperación multiplicada por tiempo desconectado. Sesiones acotadas.
- Proyección: una lista cacheada de hasta 1024 celdas no vacías; grandes módulos se simplifican visualmente conservando el blueprint completo del servidor. Rotación/movimiento validados como máximo dos veces por segundo. Chunks descargados no se fuerzan. Entidades de bloque animadas no soportadas conservan contorno y se omiten del dibujo especial.
- Almacén actualiza datos del menú como máximo cada 20 ticks o al transferir; el protocolo conserva cantidades de 32 bits. Administración usa snapshots al abrir, actualizar o actuar, sin sincronización por frame.
- Los nueve SavedData copiados del mundo sintético 0.17.0 se cargaron con registries/tags reales y conservaron todos los hechos persistidos al guardar y recargar. Se comprobaron aparte UUID, equipo, drops, AI y presentación tras recarga nativa. No se modificaron mundos originales.

**428 JUnit y 174 GameTests aprobados**, sin omisiones; Gradle build aprobado. Logs: `build/v018-final-release-build.log`, `build/v018-integrated-gametest-b.log`. La primera corrida nativa tuvo 170/171: se corrigió una colisión entre fixtures. La prueba de snapshot se movió de JUnit a GameTest para disponer de tags vanilla, conservando sus comprobaciones. No se desactivaron pruebas. La expectativa antigua que impedía mejoras de casas se actualizó para el beneficio nuevo, conservando los controles de costes/límites.

Verificaciones estáticas: 14 módulos/rotaciones/reproducibilidad, 80 skins, assets del almacén, ocho iconos HUD y nueve assets cívicos anteriores. Resource validator aprobado con tres referencias vanilla verificadas en los recursos del juego. JAR comprobado contra todas las clases compiladas y recursos actuales; pins/metadatos/Java 21 y locales concordantes, sin mod o fixtures de GameTest.

JAR: `build/libs/livingkingdoms-0.18.0.jar`, **1.283.077 bytes**. SHA-256: `43af39f1dd941e022d21c18f24379e51b4493cff99a4bccf18b27a4ed90f42c3`.

## Comprobaciones manuales del usuario

No se abrió ni controló navegador o cliente Minecraft. La apariencia gráfica y rendimiento real no se presentan como validados por tests de servidor.

- Copia de mundo 0.17.0: ciudadanos/nombres/carreras/XP, materiales, edificios, misiones/reputación/recibos e historial; guardar/reabrir. Mundo nuevo: fundación y conversión.
- Cofre: fabricar/colocar/romper/recolocar, pilas separadas, inventario lleno, reservas alimentarias y de obra. Dos clientes retirando último lote y otros bloques del mismo reino.
- Market: construir, asignar Merchant, día/noche, estación rota, amenaza, compra/venta, último bien/cuota/tesorería, esmeraldas y reposición tras reinicio.
- Ciudadanos: skins/seams, caminar, cama/orientación, herramienta y mano dominante, armadura/escudo, Farmer/Builder y las tres profesiones económicas, comercio y guardias en combate. Vanilla fuera del registro debe conservar su aspecto.
- Etiquetas y ficha: inglés/español, niveles y profesiones, grupos cercanos, invisibilidad, distancias, configuración y resource packs/shaders.
- Vista previa: nueve opciones del selector, cuatro giros, mover, terreno irregular, entrada/coste/contorno, proteger/modificar terreno entre estudio y confirmación; cancelar no debe cobrar ni colocar. Usar controles remapeados.
- Edificios: dos mejoras pagadas, capacidad/puestos/eficiencia reales, reparar estructura/objeto, equipar guardia, expansión, historial y avances únicos. Las casas deben tener camas suficientes para el descanso.
- Diálogo/HUD/Toast: 854×480, 1280×720, 1920×1080 y 4K, GUI 1–4/Auto; legibilidad, H/F1, chat, boss bars/minimapa y navegación con teclado.
- Perfil con varios reinos/NPC visibles: FPS, TPS/MSPT, memoria/red y rutas bloqueadas; comparar con chunks descargados. Las advertencias de retraso al preparar grandes fixtures no constituyen un benchmark del mod.
