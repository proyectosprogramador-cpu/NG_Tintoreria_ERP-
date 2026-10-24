$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Actualizacion segura v0.12.1 R1" -ForegroundColor Cyan
Write-Host " Mantenimiento de maquinas y bloqueo productivo" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not (Test-Path '.env')) { Fail "No se encontro .env. Copie esta version dentro de la carpeta activa del ERP." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop no esta instalado o docker no esta en PATH." }
try { docker info *> $null } catch { Fail "Abra Docker Desktop y espere a que el motor quede iniciado." }

Write-Host "1/7 Verificando PostgreSQL actual..." -ForegroundColor Yellow
& docker compose up -d db
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar PostgreSQL." }
$dbReady=$false
for($i=0;$i -lt 30;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_db 2>$null); if($h -eq 'healthy'){$dbReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $dbReady){Fail "PostgreSQL no alcanzo healthy."}

Write-Host "2/7 Creando backup integral antes de V20..." -ForegroundColor Yellow
try { & (Join-Path $root 'scripts\backup.ps1') } catch { Fail $_.Exception.Message }

Write-Host "3/7 Compilando backend y frontend v0.12.1 R1 SIN CACHE..." -ForegroundColor Yellow
& docker compose build --no-cache backend frontend
if($LASTEXITCODE -ne 0){Fail "La compilacion fallo. Sus datos no fueron modificados; conserve el backup integral."}

Write-Host "4/7 Recreando servicios y aplicando Flyway V20..." -ForegroundColor Yellow
& docker compose up -d --force-recreate backend frontend
if($LASTEXITCODE -ne 0){Fail "No se pudieron recrear los servicios."}
$backendReady=$false
for($i=0;$i -lt 75;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null); if($h -eq 'healthy'){$backendReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $backendReady){ & docker compose logs --tail=260 backend; Fail "El backend no alcanzo healthy." }

$cfg=@{}
Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}

Write-Host "5/7 Verificando Flyway V20 y estructura de mantenimiento..." -ForegroundColor Yellow
$v20=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from flyway_schema_history where version='20' and success=true;" 2>$null)
if(($v20|Out-String).Trim() -ne '1'){Fail "Flyway V20 no aparece aplicada. No continue."}
$tables=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from information_schema.tables where table_schema='public' and table_name in ('maintenance_work_orders','maintenance_evidences');" 2>$null)
if(($tables|Out-String).Trim() -ne '2'){Fail "No se encontraron las tablas de mantenimiento."}
$cols=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from information_schema.columns where table_schema='public' and table_name='machines' and column_name in ('current_meter_hours','maintenance_interval_hours','maintenance_interval_days','next_maintenance_at','next_maintenance_meter_hours');" 2>$null)
if(($cols|Out-String).Trim() -ne '5'){Fail "La ficha ampliada de maquinas no quedo aplicada."}
$perms=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from permissions where code in ('MAINTENANCE_READ','MAINTENANCE_WRITE','MAINTENANCE_EXECUTE','MAINTENANCE_CONFIG');" 2>$null)
if(($perms|Out-String).Trim() -ne '4'){Fail "No se encontraron los permisos de mantenimiento."}

Write-Host "6/7 Verificando version activa y frontend..." -ForegroundColor Yellow
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080};$url="http://localhost:$port";$ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio."}
try {
    $build = Invoke-RestMethod -Uri "$url/api/system/build" -Method Get -TimeoutSec 5
    if($build.version -ne '0.12.1-R1'){ Fail "El backend activo no es v0.12.1-R1. Version detectada: $($build.version)" }
    Write-Host "Backend confirmado: $($build.version)" -ForegroundColor Green
} catch { Fail "No se pudo confirmar /api/system/build: $($_.Exception.Message)" }

Write-Host "7/7 Actualizacion v0.12.1 R1 completada correctamente." -ForegroundColor Green
& docker compose ps
Write-Host "`nPRUEBAS RECOMENDADAS:" -ForegroundColor Yellow
Write-Host "- Cierre sesion y vuelva a ingresar para cargar permisos de Mantenimiento." -ForegroundColor White
Write-Host "- Operaciones > Mantenimiento de maquinas: configure horometro e intervalos de una maquina de prueba." -ForegroundColor White
Write-Host "- Programe una OT preventiva sobre una franja libre." -ForegroundColor White
Write-Host "- Intente programar una OP en esa misma franja: debe bloquearse por mantenimiento." -ForegroundColor White
Write-Host "- Inicie la OT: la maquina debe pasar a IN_MAINTENANCE y Produccion no debe poder iniciarla." -ForegroundColor White
Write-Host "- Complete la OT: registre costos, parada, horometro y estado final AVAILABLE." -ForegroundColor White
Write-Host "- Pruebe Excel/PDF y una evidencia JPG/PNG/PDF." -ForegroundColor White
Start-Process $url
Read-Host "Presione Enter para cerrar"
