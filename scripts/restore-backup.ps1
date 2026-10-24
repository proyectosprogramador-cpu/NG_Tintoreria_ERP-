$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
function Fail($m){Write-Host "`nERROR: $m" -ForegroundColor Red;Read-Host 'Presione Enter para cerrar';exit 1}
function Get-FilesVolume {
  $v='';try{$v=(& docker inspect ng_tintoreria_backend --format '{{range .Mounts}}{{if eq .Destination "/app/data/files"}}{{.Name}}{{end}}{{end}}' 2>$null|Out-String).Trim()}catch{}
  if(-not $v){try{$v=(& docker volume ls --filter 'name=ng_tintoreria_files' --format '{{.Name}}' 2>$null|Select-Object -First 1|Out-String).Trim()}catch{}}
  if(-not $v){throw 'No se pudo localizar el volumen persistente de archivos.'};return $v
}
if(-not (Test-Path '.env')){Fail 'No existe .env.'}
try{docker info *> $null}catch{Fail 'Docker Desktop no esta iniciado.'}
$cfg=@{};Get-Content '.env'|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1]]=$matches[2]}}
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'};$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
$dir=Join-Path $root 'backups'
$zips=@(Get-ChildItem $dir -Filter 'ng_tintoreria_full_*.zip' -ErrorAction SilentlyContinue|Sort-Object LastWriteTime -Descending)
$legacy=@(Get-ChildItem $dir -Filter '*.sql' -ErrorAction SilentlyContinue|Sort-Object LastWriteTime -Descending)
$files=@($zips)+@($legacy)
if(-not $files){Fail 'No hay backups disponibles.'}
Write-Host 'BACKUPS DISPONIBLES' -ForegroundColor Cyan
for($i=0;$i -lt $files.Count;$i++){ $kind=if($files[$i].Extension -eq '.zip'){'COMPLETO'}else{'BD-LEGACY'};Write-Host ("[{0}] {1,-9} {2}  {3:N0} bytes  {4}" -f ($i+1),$kind,$files[$i].Name,$files[$i].Length,$files[$i].LastWriteTime) }
$n=Read-Host 'Numero de backup a restaurar';$idx=0;if(-not [int]::TryParse($n,[ref]$idx)-or $idx -lt 1-or $idx -gt $files.Count){Fail 'Seleccion invalida.'}
$target=$files[$idx-1]
$side=$target.FullName+'.sha256';if(Test-Path $side){$expected=((Get-Content $side -First 1)-split '\s+')[0].ToLower();$actual=(Get-FileHash -Algorithm SHA256 $target.FullName).Hash.ToLower();if($actual -ne $expected){Fail 'La huella SHA-256 no coincide. Restauracion cancelada.'}}
if($target.Extension -eq '.sql'){Write-Host "`nADVERTENCIA: este backup antiguo restaura SOLO PostgreSQL. Los documentos/evidencias/logo actuales no se modificaran." -ForegroundColor Yellow}
Write-Host "`nSe restaurara: $($target.Name)" -ForegroundColor Yellow
Write-Host 'Antes se creara automaticamente un BACKUP INTEGRAL del estado actual.' -ForegroundColor Yellow
if((Read-Host 'Escriba RESTAURAR para continuar') -ne 'RESTAURAR'){Write-Host 'Cancelado.';exit 0}
try{$pre=& (Join-Path $root 'scripts\backup.ps1') | Select-Object -Last 1}catch{Fail "No se pudo crear backup previo: $($_.Exception.Message)"}
Write-Host "Backup previo integral: $pre" -ForegroundColor Green
& docker compose stop backend frontend | Out-Null

function Invoke-CmdIsolated([string]$command,[switch]$ShowOutput){
  $id=[guid]::NewGuid().ToString('N')
  $cmdFile=Join-Path $env:TEMP ("ng_restore_"+$id+".cmd")
  $outFile=Join-Path $env:TEMP ("ng_restore_"+$id+".out")
  $errFile=Join-Path $env:TEMP ("ng_restore_"+$id+".err")
  try{
    @('@echo off',$command,'exit /b %errorlevel%') | Set-Content -Encoding ASCII $cmdFile
    $args='/d /s /c ""'+$cmdFile+'""'
    $p=Start-Process -FilePath $env:ComSpec -ArgumentList $args -Wait -PassThru -NoNewWindow -RedirectStandardOutput $outFile -RedirectStandardError $errFile
    $rc=$p.ExitCode
    if($ShowOutput -or $rc -ne 0){
      if(Test-Path $outFile){Get-Content $outFile -ErrorAction SilentlyContinue | ForEach-Object {Write-Host $_}}
      if(Test-Path $errFile){
        if($rc -eq 0){Get-Content $errFile -ErrorAction SilentlyContinue | ForEach-Object {Write-Host $_ -ForegroundColor DarkGray}}
        else{Get-Content $errFile -ErrorAction SilentlyContinue | ForEach-Object {Write-Host $_ -ForegroundColor Red}}
      }
    }
    return $rc
  } finally {
    Remove-Item $cmdFile,$outFile,$errFile -Force -ErrorAction SilentlyContinue
  }
}

function RestoreSql($path){
  # PowerShell 5.1 convierte stderr de programas nativos en NativeCommandError.
  # Ejecutamos psql mediante Start-Process con stdout/stderr redirigidos y
  # decidimos el resultado exclusivamente por ExitCode.
  $dropCmd="docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -c `"DROP SCHEMA public CASCADE; CREATE SCHEMA public;`""
  $rc=Invoke-CmdIsolated $dropCmd
  if($rc -ne 0){return $rc}

  $restoreCmd="docker exec -i ng_tintoreria_db psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 < `"$path`""
  $rc=Invoke-CmdIsolated $restoreCmd
  return $rc
}
function RestoreZip($path){
  $tmp=Join-Path $env:TEMP ('ng_restore_'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $tmp|Out-Null
  try{
    Expand-Archive -Path $path -DestinationPath $tmp -Force
    $sql=Join-Path $tmp 'database.sql';$archive=Join-Path $tmp 'files.tar.gz'
    if(-not (Test-Path $sql) -or -not (Test-Path $archive)){return 91}
    $hasFlyway=Select-String -Path $sql -Pattern 'flyway_schema_history' -Quiet
    $hasUsers=Select-String -Path $sql -Pattern 'app_users' -Quiet
    if(-not $hasFlyway -or -not $hasUsers){Write-Host 'Backup rechazado: database.sql no contiene el esquema esencial del ERP.' -ForegroundColor Red;return 92}
    $rc=RestoreSql $sql;if($rc -ne 0){return $rc}
    $volume=Get-FilesVolume
    & docker run --rm -v "${volume}:/target" -v "${tmp}:/restore:ro" postgres:16-alpine sh -c 'find /target -mindepth 1 -maxdepth 1 -exec rm -rf {} + && tar -xzf /restore/files.tar.gz -C /target'
    return $LASTEXITCODE
  } finally {Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue}
}
function RestoreAny($path){if([IO.Path]::GetExtension($path) -eq '.zip'){return (RestoreZip $path)}else{return (RestoreSql $path)}}

Write-Host 'Restaurando backup seleccionado...' -ForegroundColor Cyan
$rc=RestoreAny $target.FullName
if($rc -ne 0){
  Write-Host 'La restauracion fallo. Intentando rollback automatico al backup integral previo...' -ForegroundColor Red
  if($pre -and (Test-Path $pre)){if((RestoreAny $pre)-ne 0){Fail 'Tambien fallo el rollback automatico. NO inicie operacion y conserve ambos backups.'}}
  & docker compose up -d backend frontend | Out-Null
  Fail 'La restauracion elegida fallo; se repuso el estado previo.'
}
Write-Host 'Levantando backend para aplicar migraciones pendientes...' -ForegroundColor Cyan
& docker compose up -d backend frontend | Out-Null
$ok=$false;for($i=0;$i -lt 75;$i++){$h=(& docker inspect --format='{{.State.Health.Status}}' ng_tintoreria_backend 2>$null);if($h -eq 'healthy'){$ok=$true;break};Start-Sleep 2}
if(-not $ok){
  & docker compose logs --tail=120 backend
  Write-Host 'Backend no quedo healthy. Intentando rollback automatico al backup previo...' -ForegroundColor Red
  & docker compose stop backend frontend | Out-Null
  if($pre -and (Test-Path $pre)){
    $rollbackRc=RestoreAny $pre
    & docker compose up -d backend frontend | Out-Null
    if($rollbackRc -ne 0){Fail 'El backend no quedo healthy y tambien fallo el rollback. NO inicie operacion.'}
    Fail 'La restauracion no arranco correctamente; se repuso el estado previo.'
  }
  Fail 'Datos restaurados, pero backend no quedo healthy y no se localizo backup de rollback.'
}
$name=$target.Name.Replace("'","''");$note=if($target.Extension -eq '.zip'){'Restauracion integral v0.13.2-R2: BD + archivos'}else{'Restauracion legacy v0.13.2-R2: solo BD'}
$registerSql="insert into system_backup_history(filename,size_bytes,status,created_by,created_at,verified_at,restored_at,notes) values ('$name',$($target.Length),'RESTORED','WINDOWS_RESTORE',now(),now(),now(),'$note');"
$registerCmd="docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -c `"$registerSql`""
cmd /d /s /c $registerCmd *> $null
if($LASTEXITCODE -ne 0){Write-Host 'ADVERTENCIA: la restauracion fue correcta, pero no pudo registrarse en system_backup_history.' -ForegroundColor Yellow}
Write-Host "`nRESTAURACION COMPLETADA Y ERP HEALTHY." -ForegroundColor Green
if($target.Extension -eq '.zip'){Write-Host 'Se restauraron PostgreSQL + documentos/evidencias + logo corporativo.' -ForegroundColor Green}
Read-Host 'Presione Enter para cerrar'

