$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Actualizacion segura v0.11.0" -ForegroundColor Cyan
Write-Host " Piloto productivo, checklist e incidencias" -ForegroundColor Cyan
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

Write-Host "2/7 Creando backup integral antes de V18..." -ForegroundColor Yellow
try { & (Join-Path $root 'scripts\backup.ps1') } catch { Fail $_.Exception.Message }

Write-Host "3/7 Compilando backend y frontend v0.11.0 SIN CACHE..." -ForegroundColor Yellow
& docker compose build --no-cache backend frontend
if($LASTEXITCODE -ne 0){Fail "La compilacion fallo. Sus datos no fueron modificados; conserve el backup integral."}

Write-Host "4/7 Recreando servicios y aplicando Flyway V18..." -ForegroundColor Yellow
& docker compose up -d --force-recreate backend frontend
if($LASTEXITCODE -ne 0){Fail "No se pudieron recrear los servicios."}
$backendReady=$false
for($i=0;$i -lt 75;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null); if($h -eq 'healthy'){$backendReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $backendReady){ & docker compose logs --tail=240 backend; Fail "El backend no alcanzo healthy." }

$cfg=@{}
Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}

Write-Host "5/7 Verificando Flyway V18 y estructura del piloto..." -ForegroundColor Yellow
$v18=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from flyway_schema_history where version='18' and success=true;" 2>$null)
if(($v18|Out-String).Trim() -ne '1'){Fail "Flyway V18 no aparece aplicada. No continue."}
$tables=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from information_schema.tables where table_schema='public' and table_name in ('pilot_cycles','pilot_checklist_items','pilot_incidents');" 2>$null)
if(($tables|Out-String).Trim() -ne '3'){Fail "No se encontraron las tres tablas del piloto productivo."}
$checks=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from pilot_checklist_items;" 2>$null)
if([int](($checks|Out-String).Trim()) -lt 20){Fail "El checklist inicial no fue cargado correctamente."}

Write-Host "6/7 Verificando version activa y frontend..." -ForegroundColor Yellow
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080};$url="http://localhost:$port";$ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio."}
try {
    $build = Invoke-RestMethod -Uri "$url/api/system/build" -Method Get -TimeoutSec 5
    if($build.version -ne '0.11.0'){ Fail "El backend activo no es v0.11.0. Version detectada: $($build.version)" }
    Write-Host "Backend confirmado: $($build.version)" -ForegroundColor Green
} catch { Fail "No se pudo confirmar /api/system/build: $($_.Exception.Message)" }

Write-Host "7/7 Actualizacion v0.11.0 completada correctamente." -ForegroundColor Green
& docker compose ps
Write-Host "`nPRUEBAS RECOMENDADAS:" -ForegroundColor Yellow
Write-Host "- Cierre sesion y vuelva a ingresar para cargar PILOT_READ / PILOT_WRITE / PILOT_VALIDATE." -ForegroundColor White
Write-Host "- Gestion > Piloto productivo: verificar checklist, avance y filtros." -ForegroundColor White
Write-Host "- Marque un punto En progreso y guarde responsable/evidencia." -ForegroundColor White
Write-Host "- Registre una incidencia MEDIA de prueba; resuelvala y verifiquela." -ForegroundColor White
Write-Host "- Centro de Reportes: exporte Checklist e Incidencias a Excel/PDF." -ForegroundColor White
Write-Host "- No declare GO-LIVE hasta cerrar incidencias CRITICAL/HIGH y completar puntos obligatorios." -ForegroundColor White
Start-Process $url
Read-Host "Presione Enter para cerrar"
