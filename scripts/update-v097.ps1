$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; Read-Host "Presione Enter para cerrar"; exit 1 }

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " NG Tintoreria ERP - Actualizacion segura v0.9.7" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

if (-not (Test-Path '.env')) { Fail "No se encontro .env. Copie esta actualizacion dentro de la carpeta del ERP que ya utiliza." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop no esta instalado o docker no esta en PATH." }
try { docker info *> $null } catch { Fail "Abra Docker Desktop y espere a que el motor quede iniciado." }

Write-Host "1/5 Verificando la base de datos actual..." -ForegroundColor Yellow
& docker compose up -d db
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar PostgreSQL." }
$dbReady = $false
for ($i=0; $i -lt 30; $i++) {
  $health = (& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_db 2>$null)
  if ($health -eq 'healthy') { $dbReady = $true; break }
  Start-Sleep -Seconds 2
}
if (-not $dbReady) { Fail "PostgreSQL no alcanzo el estado healthy." }

Write-Host "2/5 Creando backup obligatorio..." -ForegroundColor Yellow
try { & (Join-Path $root 'scripts\backup.ps1') } catch { Fail $_.Exception.Message }

Write-Host "3/5 Compilando la nueva version sin tocar los datos..." -ForegroundColor Yellow
& docker compose build backend frontend
if ($LASTEXITCODE -ne 0) { Fail "La compilacion fallo. Sus datos no fueron modificados; conserve el backup creado." }

Write-Host "4/5 Aplicando migracion e iniciando servicios..." -ForegroundColor Yellow
& docker compose up -d
if ($LASTEXITCODE -ne 0) { Fail "No se pudo iniciar la version 0.9.7. Ejecute VER_ESTADO_WINDOWS.bat y conserve el backup." }

$cfg=@{}
Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080}
$url="http://localhost:$port"
$ready=$false
for($i=0;$i -lt 90;$i++){
  try{$r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2;if($r.StatusCode -eq 200){$ready=$true;break}}catch{}
  Start-Sleep -Seconds 2
}
if(-not $ready){Fail "El ERP no respondio a tiempo. Ejecute VER_ESTADO_WINDOWS.bat y envie una captura de los mensajes."}

Write-Host "5/5 Actualizacion completada." -ForegroundColor Green
& docker compose ps
Write-Host "`nCierre sesion e ingrese otra vez para cargar los nuevos permisos." -ForegroundColor Yellow
Write-Host "Abra Programacion / Produccion y complete VALIDACION_v097.txt antes de usar datos reales." -ForegroundColor Cyan
Start-Process $url
Read-Host "Presione Enter para cerrar"
