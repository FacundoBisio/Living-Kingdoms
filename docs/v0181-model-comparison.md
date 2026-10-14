# Living Kingdoms 0.18.1: comparación e integración de Argon 4

## Inspección previa

Se inspeccionó `livingkingdoms_citizen_assets.zip` antes de modificar el proyecto. SHA-256 del archivo: `6380d1c864396168d7df2d1007f83c3a6138cba60d083b801b3772a29b21b2bf`. La comprobación CRC del ZIP no detectó errores. Contiene nueve archivos: una geometría, un proyecto Blockbench, tres archivos de animaciones y cuatro PNG de 64×64; las carpetas de documentación, caras y ropa estaban vacías. No incluye scripts ejecutables ni instrucciones de instalación. El archivo proporcionado por el usuario es la fuente de estos recursos; atribuirlo a Argon 4 reproduce su descripción, sin afirmar una autoría o licencia externa que el archivo no documenta.

`docs/argon4-source/manifest.json` conserva el origen y SHA-256 de cada archivo importado. El proyecto `.bbmodel` y las dos texturas base permanecen como fuentes consultables en `docs/argon4-source/`. Los recursos originales de geometría, animaciones y las texturas externas de constructor y herrero se conservan sin alteraciones.

## Comparación

| Aspecto | Humanoide de 0.18.0 | Recursos externos e integración de 0.18.1 |
|---|---|---|
| Entidad y datos | `Villager` existente, identidad registrada y profesión sincronizada | La misma entidad, UUID, IA, navegación, inventario, profesión y persistencia |
| Modelo | `PlayerModel<Villager>` nativo con brazos y piernas independientes | Geometría Blockbench de 14 huesos y 24 cubos, adaptada a las partes nativas existentes |
| Apariencia | 80 skins originales: ocho variantes para diez roles | Las mismas 80 identidades y ropa base, con 80 texturas derivadas para las capas geométricas |
| Detalles | Pelo, barbas, prendas y circlet principalmente pintados | Pelo, barba, puños, botas, cuello, faldón, bolsa y banda con volumen; detalles activados según variante o profesión |
| Animación | Movimiento, ataques, sueño y equipo vanilla; acentos cortos de trabajo real | Nueve clips externos únicos disponibles; trabajo seleccionado por actividad real, conservando movimientos nativos cuando cubren más estados |
| Herramientas | Objetos de mano nativos, equipo real prioritario | Martillo externo separado para trabajo de constructor/herrero, conservando objetos reales y las otras herramientas existentes |
| Dependencias | Minecraft y NeoForge, sin GeckoLib | Sin dependencia nueva: adaptación acotada del formato exportado al renderer nativo |

La geometría externa aporta volumen y líneas de tiempo editables, pero no resulta superior en todos los aspectos. Las cuatro texturas suministradas no sustituyen la variedad de las 80 skins; tampoco hay un juego completo de texturas externas para todas las profesiones. Los clips no cubren todos los estados vanilla, ni garantizan por sí mismos herramientas, equipo, sueño, monturas o combate correctos. La integración conserva esos comportamientos del modelo anterior.

## Compatibilidad UV y huesos

Las caras principales de cabeza, torso, brazos y piernas usan las coordenadas estándar de un jugador de Minecraft de 64×64. Eso permite conservar todos los píxeles originales de identidad y ropa base. Los cubos externos adicionales leen zonas de capas secundarias que las skins de 0.18.0 dejaban transparentes; el generador de 0.18.1 llena esas zonas con colores derivados de cada skin, sin modificar las seis regiones principales.

No se puede aplicar el ZIP sin adaptación: la barba lee píxeles de la cara, la banda comparte UV con pelo y el martillo está incorporado permanentemente en el hueso `right_hand`. La adaptación remapea barba y banda a zonas auxiliares del atlas y controla su visibilidad. También eleva la banda dos píxeles y el pelo 0,75 píxeles de modelo para evitar que cubran los ojos; esta corrección geométrica requiere revisión visual dentro del juego. El martillo se excluye del cuerpo y se renderiza por separado cuando corresponde. Así un ciudadano que descansa, un comerciante o un guardia no recibe un martillo fijo.

Los clips se exportan en tres JSON con diez entradas y nueve nombres únicos. `animation.citizen.work_hammer` aparece dos veces con contenido idéntico. Los otros clips son `idle`, `walk`, `run`, `guard_patrol`, `guard_attack`, `guard_block`, `farm_plant` y `lumberjack_chop`, con el prefijo `animation.citizen.`. Se seleccionan la rotación de cabeza en reposo, los clips de martillo, cultivo y tala tras progreso confirmado, y el bloqueo cuando el ciudadano usa realmente un escudo. Miner y Merchant conservan sus acentos anteriores. Los clips de locomoción y ataque quedan conservados y validados como recursos, pero se mantienen las animaciones vanilla que ya coordinan pasos, equipo, monturas y sueño. Las pistas de posición corporal no se aplican para mantener alineados cuerpo, armadura y manos; por eso la adaptación no reproduce cada canal exactamente como lo haría un reproductor GeckoLib. Ninguna animación ejecuta acciones económicas o modifica la IA.

Se reutiliza el renderer delegado existente. Solo los ciudadanos registrados reciben la presentación humanoide; los aldeanos vanilla ajenos al sistema conservan su renderer habitual. Etiquetas, inspección, comercio, menú del alcalde y previsualización de construcciones siguen usando sus sistemas anteriores. No hay cambio de esquema persistente ni conversión de entidades.

## Verificaciones y límites

Las pruebas de recursos comprueban geometría finita, jerarquía sin ciclos, referencias de huesos, UV dentro de 64×64, los nueve clips y la igualdad de la entrada duplicada. Comprueban las 80 skins derivadas y sus regiones principales píxel a píxel frente a los originales. También verifican los SHA-256 de las cuatro texturas externas y que las dos texturas de profesión estén empaquetadas. El generador y la validación del artefacto complementan estas comprobaciones.

Estas pruebas no validan la apariencia dentro de Minecraft. El cliente gráfico no se ejecuta ni se controla durante esta tarea. La adaptación es específica de estos recursos: no pretende importar cualquier modelo o expresión Molang de GeckoLib. Los resultados de JUnit, GameTests y build deben consultarse en el registro de entrega, sin deducir que pasar pruebas de servidor equivale a validar renderizado.

## Comparación manual pendiente

Usar `/kingdommodels native` y `/kingdommodels argon4`, sobre el mismo ciudadano y mundo, para comparar el modelo anterior con Argon 4 sin reiniciar ni abrir otra partida. Estos comandos locales guardan la preferencia del cliente. También puede establecerse `citizenPresentation.model = "NATIVE"` o `"ARGON4"` en `config/livingkingdoms-citizen-visuals.toml`; `ARGON4` es la opción predeterminada. La selección no cambia recursos del reino, profesión ni entidad. Mantener una copia del mundo para las comprobaciones de compatibilidad.

1. Comparar ciudadanos de las diez profesiones y ocho variantes: cara, peinado, barba, cuello, botas, ropa y diferencias del alcalde. Revisar costuras de UV y superficies que se atraviesen.
2. Observar caminar, correr, girar, dormir, subir a una montura y descansar. Ningún martillo ni movimiento de trabajo debería aparecer durante el descanso.
3. Observar producción confirmada de Farmer, Lumberjack, Miner y Blacksmith, y una obra o reparación real de Builder. Comprobar que las manos, la herramienta y el objetivo coinciden visualmente; probar ciudadanos zurdos.
4. Comprobar guardias armados y desarmados, espada, arco, escudo en uso y armadura. El equipo debe coincidir con el inventario real. Probar invisibilidad y ocultar la interfaz.
5. Probar etiquetas a corta y larga distancia, veinte o más ciudadanos visibles y escalas de GUI distintas; revisar tiempos de frame con y sin el modelo nuevo.
6. Abrir comercio, almacén, alcalde e inspección; comenzar una previsualización 3D y cancelarla. Guardar y cargar una copia de un mundo anterior, comprobar nombres, profesiones y XP.
7. Con dos clientes, seleccionar modelos diferentes y comprobar que la actividad y el equipo observados siguen correspondiendo al mismo estado del servidor.
