# NG Tintorería ERP

Sistema web de gestión para una tintorería industrial.

## Componentes del proyecto

- **Backend:** Java 21, Spring Boot 3.
- **Frontend:** React y TypeScript.
- **Base de datos:** PostgreSQL; migraciones con Flyway.
- **Entorno local:** Docker Compose.
- **Áreas funcionales:** clientes, cotizaciones, producción y programación, laboratorio de color, almacén, inventario, mantenimiento de máquinas, control de calidad y reportes.
- **Seguridad:** usuarios, roles y permisos.

## Estado del repositorio

Repositorio **privado** preparado para recibir la primera copia del código desde el entorno local de desarrollo. La creación de este archivo y de `.gitignore` **no constituye todavía un respaldo completo del ERP**.

## Flujo recomendado de versiones

1. Crear el respaldo inicial con el código de la versión validada que se esté ejecutando.
2. Realizar cambios en una rama de trabajo.
3. Ejecutar pruebas locales antes de fusionar en `main`.
4. Registrar cada cambio con un commit descriptivo.
5. Etiquetar los hitos validados, sin reutilizar una etiqueta para versiones distintas.

## Seguridad al subir código

**No subir**:

- `.env`, contraseñas, API keys, tokens, credenciales SUNAT ni certificados privados.
- Respaldos de PostgreSQL, volúmenes de Docker, bases de datos con información real.
- Evidencias, documentos y archivos subidos por usuarios que contengan información de clientes o empleados.
- Dependencias descargadas y compilaciones (`node_modules`, `target`, `dist`).

Conservar en el repositorio los **archivos de migración Flyway `.sql`** y las plantillas de configuración sin credenciales (por ejemplo `.env.example`).

Un `.gitignore` **no protege secretos escritos dentro de archivos que sí se suben**: revisar los cambios con `git diff --cached` antes de cada publicación.
