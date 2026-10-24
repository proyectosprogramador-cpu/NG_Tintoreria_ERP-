$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Actualizacion segura v0.10.7" -ForegroundColor Cyan
Write-Host " Rediseño visual Corporate Industrial" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not (Test-Path '.env')) { Fail "No se encontro .env. Copie esta version dentro de la carpeta activa del ERP." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop no esta instalado o docker no esta en PATH." }
try { docker info *> $null } catch { Fail "Abra Docker Desktop y espere a que el motor quede iniciado." }

Write-Host "1/6 Verificando PostgreSQL actual..." -ForegroundColor Yellow
& docker compose up -d db
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar PostgreSQL." }
$dbReady=$false
for($i=0;$i -lt 30;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_db 2>$null); if($h -eq 'healthy'){$dbReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $dbReady){Fail "PostgreSQL no alcanzo healthy."}

Write-Host "2/6 Creando backup seguro antes del rediseño visual..." -ForegroundColor Yellow
try { & (Join-Path $root 'scripts\backup.ps1') } catch { Fail $_.Exception.Message }

Write-Host "3/6 Compilando backend y frontend v0.10.7 Corporate Industrial SIN CACHE..." -ForegroundColor Yellow
& docker compose build --no-cache backend frontend
if($LASTEXITCODE -ne 0){Fail "La compilacion fallo. Sus datos no fueron modificados; conserve el backup."}

Write-Host "4/6 Recreando servicios (sin cambios de esquema; Flyway permanece en V16)..." -ForegroundColor Yellow
& docker compose up -d --force-recreate backend frontend
if($LASTEXITCODE -ne 0){Fail "No se pudieron recrear los servicios."}
$backendReady=$false
for($i=0;$i -lt 75;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null); if($h -eq 'healthy'){$backendReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $backendReady){ & docker compose logs --tail=220 backend; Fail "El backend no alcanzo healthy." }

$cfg=@{}
Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}

Write-Host "5/6 Verificando seguridad V16 y version activa..." -ForegroundColor Yellow
$v16=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from flyway_schema_history where version='16' and success=true;" 2>$null)
if(($v16|Out-String).Trim() -ne '1'){Fail "Flyway V16 no aparece aplicada. No continue."}
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080};$url="http://localhost:$port";$ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio."}
try {
    $build = Invoke-RestMethod -Uri "$url/api/system/build" -Method Get -TimeoutSec 5
    if($build.version -ne '0.10.7'){ Fail "El backend activo no es v0.10.7. Version detectada: $($build.version)" }
    Write-Host "Backend confirmado: $($build.version)" -ForegroundColor Green
} catch { Fail "No se pudo confirmar /api/system/build: $($_.Exception.Message)" }

Write-Host "6/6 Actualizacion v0.10.7 completada correctamente." -ForegroundColor Green
& docker compose ps
Write-Host "`nPRUEBAS VISUALES Y RESPONSIVE RECOMENDADAS:" -ForegroundColor Yellow
Write-Host "- Pantalla 1366x768 / 14 pulgadas al 100% de zoom: menú completo y cierre de sesión visible." -ForegroundColor White
Write-Host "- Menú agrupado: Visión general, Comercial, Operaciones, Abastecimiento, Gestión y Administración." -ForegroundColor White
Write-Host "- Reducir ancho por debajo de 1050 px: el menú debe convertirse en panel hamburguesa sin perder el nuevo tema." -ForegroundColor White
Write-Host "- Revisar Dashboard, tablas, formularios, modales, estados y botones: deben usar el tema Corporate Industrial." -ForegroundColor White
Start-Process $url
Read-Host "Presione Enter para cerrar"
