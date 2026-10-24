$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$dir=Join-Path $root 'backups'
if(-not (Test-Path $dir)){Write-Host 'No existe carpeta backups.' -ForegroundColor Yellow;exit 0}
$zips=@(Get-ChildItem $dir -Filter 'ng_tintoreria_full_*.zip' | Sort-Object LastWriteTime -Descending)
$legacy=@(Get-ChildItem $dir -Filter '*.sql' | Sort-Object LastWriteTime -Descending)
if(-not $zips -and -not $legacy){Write-Host 'No hay backups disponibles.' -ForegroundColor Yellow;exit 0}
$bad=0
foreach($f in $zips){
  $state='SIN HUELLA';$hash=(Get-FileHash -Algorithm SHA256 $f.FullName).Hash.ToLower();$side=$f.FullName+'.sha256'
  if(Test-Path $side){$expected=((Get-Content $side -First 1)-split '\s+')[0].ToLower();if($expected -eq $hash){$state='OK'}else{$state='ALTERADO';$bad++}}
  if($state -eq 'OK'){
    $tmp=Join-Path $env:TEMP ('verify_'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $tmp|Out-Null
    try{Expand-Archive -Path $f.FullName -DestinationPath $tmp -Force;if(-not (Test-Path (Join-Path $tmp 'database.sql')) -or -not (Test-Path (Join-Path $tmp 'files.tar.gz')) -or -not (Test-Path (Join-Path $tmp 'manifest.txt'))){$state='INCOMPLETO';$bad++}elseif((Get-Item (Join-Path $tmp 'database.sql')).Length -lt 1000){$state='INVALIDO';$bad++}}catch{$state='INVALIDO';$bad++}finally{Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue}
  }
  Write-Host ("{0,-12} {1,12:N0} bytes  {2}" -f $state,$f.Length,$f.Name) -ForegroundColor $(if($state -eq 'OK'){'Green'}elseif($state -eq 'SIN HUELLA'){'Yellow'}else{'Red'})
}
if($legacy){
  Write-Host "`nBackups antiguos (SOLO base de datos; no incluyen archivos adjuntos/logo):" -ForegroundColor Yellow
  foreach($f in $legacy){
    $hash=(Get-FileHash -Algorithm SHA256 $f.FullName).Hash.ToLower();$side=$f.FullName+'.sha256';$state='LEGACY'
    if(Test-Path $side){$expected=((Get-Content $side -First 1)-split '\s+')[0].ToLower();if($expected -ne $hash){$state='ALTERADO';$bad++}}
    if($f.Length -lt 1000){$state='INVALIDO';$bad++}
    Write-Host ("{0,-12} {1,12:N0} bytes  {2}" -f $state,$f.Length,$f.Name) -ForegroundColor $(if($state -eq 'LEGACY'){'Yellow'}else{'Red'})
  }
}
if($bad -gt 0){Write-Host "`nHay $bad backup(s) con problemas de integridad." -ForegroundColor Red;exit 2}
Write-Host "`nVerificacion terminada. Para recuperacion completa use preferentemente un backup ng_tintoreria_full_*.zip." -ForegroundColor Green
