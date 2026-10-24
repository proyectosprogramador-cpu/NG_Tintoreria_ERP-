$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($msg){throw $msg}
function Get-FilesVolume {
  $v=''
  try { $v=(& docker inspect ng_tintoreria_backend --format '{{range .Mounts}}{{if eq .Destination "/app/data/files"}}{{.Name}}{{end}}{{end}}' 2>$null | Out-String).Trim() } catch {}
  if(-not $v){ try { $v=(& docker volume ls --filter 'name=ng_tintoreria_files' --format '{{.Name}}' 2>$null | Select-Object -First 1 | Out-String).Trim() } catch {} }
  if(-not $v){ Fail 'No se pudo localizar el volumen persistente de archivos ng_tintoreria_files.' }
  return $v
}
if(-not (Test-Path '.env')){Fail 'No existe .env. Inicie primero el ERP.'}
if(-not (Get-Command docker -ErrorAction SilentlyContinue)){Fail 'Docker no esta disponible.'}
try{docker info *> $null}catch{Fail 'Docker Desktop no esta iniciado.'}
$cfg=@{}
Get-Content '.env' | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2] } }
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
& docker compose up -d db | Out-Null
# No certificar como VERIFIED una base vacia o incompleta. Esta comprobacion evita
# crear una falsa copia valida si el esquema productivo fue borrado accidentalmente.
$tableCount=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select count(*) from pg_tables where schemaname='public';" 2>$null | Out-String).Trim()
$flywayExists=(& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -tAc "select case when to_regclass('public.flyway_schema_history') is null then 0 else 1 end;" 2>$null | Out-String).Trim()
$tc=0; [void][int]::TryParse($tableCount,[ref]$tc)
if($flywayExists -ne '1' -or $tc -lt 20){Fail "La base activa no parece contener el esquema productivo completo (tablas public: $tableCount). Backup cancelado para evitar certificar una copia vacia."}
$backupDir=Join-Path $root 'backups'; New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$stamp=Get-Date -Format 'yyyyMMdd_HHmmss'
$stage=Join-Path $env:TEMP "ng_tintoreria_backup_$stamp"
New-Item -ItemType Directory -Force -Path $stage | Out-Null
$restartServices=$false
try {
  $backendRunning=((& docker inspect --format='{{.State.Running}}' ng_tintoreria_backend 2>$null | Out-String).Trim() -eq 'true')
  $frontendRunning=((& docker inspect --format='{{.State.Running}}' ng_tintoreria_frontend 2>$null | Out-String).Trim() -eq 'true')
  if($backendRunning -or $frontendRunning){
    Write-Host 'Pausando temporalmente backend/frontend para obtener una copia consistente...' -ForegroundColor Yellow
    & docker compose stop backend frontend | Out-Null
    $restartServices=$true
  }
  $sql=Join-Path $stage 'database.sql'
  Write-Host "Creando dump PostgreSQL..." -ForegroundColor Cyan
  $cmd="docker compose exec -T db pg_dump -U $dbUser $dbName > `"$sql`""
  cmd /c $cmd
  if($LASTEXITCODE -ne 0){Fail 'No se pudo crear el dump PostgreSQL.'}
  $sqlItem=Get-Item $sql
  if($sqlItem.Length -lt 1000){Fail 'El dump PostgreSQL se creo vacio o demasiado pequeno.'}
  $head=(Get-Content $sql -TotalCount 8 -ErrorAction Stop) -join "`n"
  if($head -notmatch 'PostgreSQL database dump'){Fail 'El archivo database.sql no parece un dump PostgreSQL valido.'}
  $hasFlyway=Select-String -Path $sql -Pattern 'flyway_schema_history' -Quiet
  $hasUsers=Select-String -Path $sql -Pattern 'app_users' -Quiet
  if(-not $hasFlyway -or -not $hasUsers){Fail 'El dump no contiene las tablas esenciales del ERP. Backup cancelado.'}

  $filesVolume=Get-FilesVolume
  Write-Host "Respaldando documentos/evidencias/logo desde volumen: $filesVolume" -ForegroundColor Cyan
  & docker run --rm -v "${filesVolume}:/source:ro" -v "${stage}:/backup" postgres:16-alpine sh -c 'cd /source && tar -czf /backup/files.tar.gz .'
  if($LASTEXITCODE -ne 0){Fail 'No se pudo respaldar el volumen de archivos.'}
  $filesArchive=Join-Path $stage 'files.tar.gz'
  if(-not (Test-Path $filesArchive)){Fail 'No se genero files.tar.gz.'}

  $sqlHash=(Get-FileHash -Algorithm SHA256 $sql).Hash.ToLower()
  $filesHash=(Get-FileHash -Algorithm SHA256 $filesArchive).Hash.ToLower()
  $manifest=@(
    'NG Tintoreria ERP - Backup integral',
    "created_at=$((Get-Date).ToString('o'))",
    "database=$dbName",
    "database_sha256=$sqlHash",
    "files_volume=$filesVolume",
    "files_sha256=$filesHash",
    'contents=database.sql,files.tar.gz'
  )
  $manifest | Set-Content -Encoding UTF8 (Join-Path $stage 'manifest.txt')

  $zip=Join-Path $backupDir "ng_tintoreria_full_$stamp.zip"
  Write-Host "Empaquetando backup integral: $zip" -ForegroundColor Cyan
  Compress-Archive -Path (Join-Path $stage 'database.sql'),(Join-Path $stage 'files.tar.gz'),(Join-Path $stage 'manifest.txt') -DestinationPath $zip -CompressionLevel Optimal -Force
  $item=Get-Item $zip
  if($item.Length -lt 1500){Fail 'El paquete integral se creo vacio o demasiado pequeno.'}
  $hash=(Get-FileHash -Algorithm SHA256 $zip).Hash.ToLower()
  "$hash  $($item.Name)" | Set-Content -Encoding ASCII ($zip+'.sha256')

  $createdBy=[Environment]::UserName.Replace("'","''")
  $name=$item.Name.Replace("'","''")
  $notes="Backup integral ERP v0.13.0: PostgreSQL + documentos/evidencias/logo".Replace("'","''")
  $sqlRegister="insert into system_backup_history(filename,size_bytes,sha256,status,created_by,created_at,verified_at,notes) values ('$name',$($item.Length),'$hash','VERIFIED','$createdBy',now(),now(),'$notes');"
  try{ & docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -c $sqlRegister *> $null }catch{ Write-Host 'Aviso: paquete creado, pero no se pudo registrar en el catalogo.' -ForegroundColor Yellow }
  Write-Host "Backup integral verificado correctamente: $zip" -ForegroundColor Green
  Write-Host "Incluye: base de datos + documentos/evidencias + logo corporativo." -ForegroundColor Green
  Write-Host "SHA-256: $hash" -ForegroundColor DarkGreen
  $zip
} finally {
  Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
  if($restartServices){
    Write-Host 'Reanudando ERP después del backup...' -ForegroundColor DarkCyan
    & docker compose up -d backend frontend | Out-Null
  }
}
