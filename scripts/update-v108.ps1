$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Actualizacion segura v0.10.8" -ForegroundColor Cyan
Write-Host " Identidad documental, logo, reportes y backup integral" -ForegroundColor Cyan
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

Write-Host "2/7 Creando backup INTEGRAL antes de V17..." -ForegroundColor Yellow
try { & (Join-Path $root 'scripts\backup.ps1') } catch { Fail $_.Exception.Message }

Write-Host "3/7 Compilando backend y frontend v0.10.8 SIN CACHE..." -ForegroundColor Yellow
& docker compose build --no-cache backend frontend
if($LASTEXITCODE -ne 0){Fail "La compilacion fallo. Sus datos no fueron modificados; conserve el backup integral."}

Write-Host "4/7 Recreando servicios y aplicando Flyway V17..." -ForegroundColor Yellow
& docker compose up -d --force-recreate backend frontend
if($LASTEXITCODE -ne 0){Fail "No se pudieron recrear los servicios."}
$backendReady=$false
for($i=0;$i -lt 75;$i++){ $h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null); if($h -eq 'healthy'){$backendReady=$true;break}; Start-Sleep -Seconds 2 }
if(-not $backendReady){ & docker compose logs --tail=220 backend; Fail "El backend no alcanzo healthy." }

$cfg=@{}
Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}

Write-Host "5/7 Verificando Flyway V17 y estructura de branding..." -ForegroundColor Yellow
$v17=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from flyway_schema_history where version='17' and success=true;" 2>$null)
if(($v17|Out-String).Trim() -ne '1'){Fail "Flyway V17 no aparece aplicada. No continue."}
$cols=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from information_schema.columns where table_name='company_profile' and column_name in ('logo_storage_name','logo_content_type','logo_original_name','logo_updated_at');" 2>$null)
if(($cols|Out-String).Trim() -ne '4'){Fail "No se encontraron las cuatro columnas de identidad documental."}

Write-Host "6/7 Verificando version activa, frontend y almacenamiento persistente..." -ForegroundColor Yellow
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080};$url="http://localhost:$port";$ready=$false
for($i=0;$i -lt 60;$i++){try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 2}
if(-not $ready){Fail "El frontend no respondio."}
try {
    $build = Invoke-RestMethod -Uri "$url/api/system/build" -Method Get -TimeoutSec 5
    if($build.version -ne '0.10.8'){ Fail "El backend activo no es v0.10.8. Version detectada: $($build.version)" }
    Write-Host "Backend confirmado: $($build.version)" -ForegroundColor Green
} catch { Fail "No se pudo confirmar /api/system/build: $($_.Exception.Message)" }
$filesVolume=(& docker inspect ng_tintoreria_backend --format '{{range .Mounts}}{{if eq .Destination "/app/data/files"}}{{.Name}}{{end}}{{end}}' 2>$null | Out-String).Trim()
if(-not $filesVolume){Fail "El backend no tiene montado el volumen persistente /app/data/files."}
Write-Host "Almacenamiento persistente confirmado: $filesVolume" -ForegroundColor Green

Write-Host "7/7 Actualizacion v0.10.8 completada correctamente." -ForegroundColor Green
& docker compose ps
Write-Host "`nPRUEBAS RECOMENDADAS:" -ForegroundColor Yellow
Write-Host "- Empresa: subir logo PNG/JPG, guardar datos y verificar vista previa." -ForegroundColor White
Write-Host "- Reportes: descargar PDF y Excel; ambos deben mostrar logo y datos de empresa." -ForegroundColor White
Write-Host "- Documentos imprimibles: Proforma, Recepcion, Laboratorio y Hoja de produccion deben usar la identidad configurada." -ForegroundColor White
Write-Host "- Crear CREAR_BACKUP_SEGURO_WINDOWS.bat: ahora debe generar ng_tintoreria_full_*.zip (BD + archivos/logo)." -ForegroundColor White
Write-Host "- Centro de Reportes: comprobar nuevos reportes de Clientes, Proveedores, Listas, Formulas, Maestros y Usuarios." -ForegroundColor White
Start-Process $url
Read-Host "Presione Enter para cerrar"
