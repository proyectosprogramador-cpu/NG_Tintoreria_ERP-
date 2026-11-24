# Entornos de NG Tintorería ERP — configuración y aislamiento

## Código compartido, datos separados
Las ramas `cloud-full` (Render académico CIBERTEC) y `main` (fuente consolidada) pueden contener las mismas funcionalidades, pero **no comparten la misma base de datos, almacenamiento ni secretos**. No subir `.env`, archivos reales ni dumps a GitHub.

## Windows / Docker Compose — ERP LAN
La configuración de `docker-compose.yml` usa el contenedor `db` y el backend `backend:8080`, y monta `frontend/nginx.local.conf` en lugar del proxy de Render. El frontend escucha en puerto 80 dentro del contenedor y se publica en `localhost:8080` (o `ERP_PORT`).

Antes de ejecutar cambios en un ERP con datos:
1. Verifique su backup integral de PostgreSQL y documentos.
2. Ejecute `docker compose config` y compruebe que frontend monta `nginx.local.conf`, `PORT=80` y backend usa `DB_HOST=db`.
3. Confirme que no haya ningún proxy del frontend local hacia `ng-tintoreria-api.onrender.com`.
4. Use `docker compose up -d --build` solo tras probar la rama y confirmar el proyecto/volúmenes actuales (no usar `docker compose down -v`).

## Render académico
Render construye frontend y backend desde sus propios Dockerfiles/configuración del servicio, **sin utilizar el archivo `docker-compose.yml` del entorno LAN**. El `frontend/nginx.conf` original permanece destinado a Render y redirige `/api/` a la API académica. Antes de una fusión que implique Render, compruebe cómo está configurado realmente el servicio y las variables `PORT`, `DB_*`, `ERP_JWT_SECRET`, almacenamiento persistente y health checks.

## Flujo de Git y pruebas
1. Preparar cambios en una rama derivada de `cloud-full`.
2. Probar seguridad/roles, compilación y aislamiento de API/BD local vs Render.
3. Fusionar en `cloud-full` en despliegue controlado, conservando respaldos y evidencia de Render.
4. Integrar los mismos commits a `main` mediante PR de sincronización cuando las pruebas estén completas.
5. Mantener separados datos de demostración CIBERTEC y datos de empresa; Flyway no se deshace ni se sustituye por copiar datos.

**Esta documentación no implica que la modificación haya sido desplegada o validada en Docker/Render.**
