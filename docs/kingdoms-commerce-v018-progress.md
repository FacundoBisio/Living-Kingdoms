# Kingdoms & Commerce 0.18.0 — progreso

Base: main/origin/main `203a976`, confirmado con fetch el 13/10/2026; incluye toda la entrega 0.17.0. Rama `codex/kingdoms-commerce-v018`. Minecraft 1.21.1, NeoForge 21.1.252, Java 21. Commits locales autorizados; no merge/push. El usuario hará la validación visual: no abrir navegador ni cliente Minecraft.

## Contratos y responsables
- Principal: bloque/menú de almacén sobre KingdomEconomy, administración de edificios/guardias/reparaciones, alertas/historial/advancements, integración y pruebas.
- commerce_v018: profesión Merchant, mercado y transacciones; ProfessionService/ProfessionSavedData/FunctionalBuilding/CitizenRoutine y paquetes commerce. Bootstrap/locales se integran centralmente.
- citizen_visuals_v018: presentación nativa humanoide solo para ciudadanos/alcalde registrados, skins originales, etiquetas y animaciones ligadas a trabajo real. Paquetes y payload propios; conserva entidades vanilla/UUID/AI.
- preview_v018: proyección NBT acotada, rotación y confirmación validada; ConstructionService y SettlementLayoutPlanner. Bootstrap/UI general centralizados.

## Fases terminadas
1. Almacén físico: saldo único, menú nativo, depósito de pila seleccionada, reservas de obra/comida; romper el bloque conserva stock.
2. Mercado: Merchant operativo, inventario/esmeraldas reales, tesorería/cuotas persistidas, precios/config y ofertas de uso único.
3–4. Humanoides: renderizador nativo, 80 skins, herramientas/animación ligadas al progreso, equipo real, etiquetas/ficha/config.
5. Preview: NBT, rotación/movimiento, límites/entrada/costes y confirmación con nueva validación, mediante capacidad local del alcalde.
6. Administración: niveles 1–3, viviendas/puestos/capacidad/eficiencia, reparación, equipo, expansión e historial por GUI.
7. Alertas y seis avances: después de transiciones reales, recibos y cola/historial limitados; sin repetición de login.
8. Integración validada. Cambios arquitectónicos permanentes de edificios y simulación económica avanzada no forman parte de esta entrega.

## Evidencia final
- 428 JUnit, sin fallos/errores/omisiones; test/build exitosos: build/v018-final-release-build.log.
- 174 GameTests aprobados; servidor/Gradle exit 0: runs/gametest-v018-integrated-b, build/v018-integrated-gametest-b.log. Incluye nueve SavedData reales copiados del mundo sintético 0.17.0, recarga de entidades/equipo y permisos/transacciones de dos actores simulados.
- Primera corrida 170/171: fixture de alertas compartía ubicación; aislada. Snapshot pasó a GameTest por necesidad de tags de puertas cargadas, sin retirar comprobaciones. Casas ahora mejorables: expectativa de 0.17 actualizada, controles de coste/límites conservados.
- Static/asset/JAR checks aprobados: 14 módulos, 80 skins, almacén, HUD/cívicos/locales. Resource validator aprobado (tres referencias vanilla). git diff --check aprobado.
- Artefacto build/libs/livingkingdoms-0.18.0.jar, 1.283.077 bytes; SHA256 43af39f1dd941e022d21c18f24379e51b4493cff99a4bccf18b27a4ed90f42c3.
- Commits: 7c65d1e almacén; d18cdd2 comercio; 3469cf1 ciudadanos; 6bcb8c1 preview; b29c977 alertas/avances; b8b7f6e administración/UI. QA/documentación final comprobadas y listas para entrega. Sin merge/push, main 203a976 intacto.
- Usuario valida gráficos y cliente. No runClient ni navegador; no cambios de drivers. Pendiente manual: multiplayer real, copias de partidas completas, shaders/escalas, FPS/TPS/MSPT. No se mantiene el bloqueo gráfico antiguo como excusa para detener el desarrollo.

Detalles y checklist: [kingdoms-commerce-v018.md](kingdoms-commerce-v018.md).
