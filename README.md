# OpenRoute

Primera iteración de una app Android local-first para:

- importar rutas GPX desde almacenamiento local
- visualizarlas sobre un mapa OpenStreetMap
- grabar recorridos propios con la localización del dispositivo
- guardar todo en local, sin backend ni login

## Decisión de mapas

Se ha elegido una primera iteración con `WebView + Leaflet + OpenStreetMap` por tres razones:

1. no requiere clave de Google Maps ni facturación
2. encaja con la idea de formatos y estándares abiertos
3. nos deja validar el flujo de producto antes de pasar a un SDK nativo más pesado

Para producción, conviene sustituir el tile server por un proveedor adecuado o infraestructura propia. Esta iteración usa el servidor estándar de OpenStreetMap solo como prototipo.

## Funcionalidad actual

- importación local de archivos `.gpx`
- autoimportación de archivos `.gpx` detectados en `Downloads`
- listado de rutas guardadas
- ocultación de rutas para que no reaparezcan al relanzar la app
- selección de ruta para resaltarla en el mapa
- grabación básica con foreground service mientras la ruta está activa
- persistencia local en JSON dentro del almacenamiento privado de la app

## Nota sobre Descargas

La autoimportación silenciosa desde `Downloads` usa acceso amplio a almacenamiento en Android 11+.
Tiene sentido para una app personal como esta, pero no es un enfoque adecuado para publicar en Google Play.

## Siguientes pasos naturales

- soporte para `GeoJSON` y `KML`
- nombres editables para rutas grabadas
- métricas de tiempo, velocidad y desnivel
- almacenamiento con Room
- sustitución del mapa embebido por MapLibre si queremos una capa nativa
