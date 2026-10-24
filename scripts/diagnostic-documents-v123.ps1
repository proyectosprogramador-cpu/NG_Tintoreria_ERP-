$ErrorActionPreference='Continue'
$root=Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path);Set-Location $root
Write-Host '=== DIAGNOSTICO GESTION DOCUMENTAL v0.12.3 ===' -ForegroundColor Cyan
docker compose ps
$cfg=@{};Get-Content '.env' -ErrorAction SilentlyContinue|%{if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1]]=$matches[2]}}
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'};$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'};$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080}
Write-Host "`n--- Backend build ---" -ForegroundColor Yellow
try{Invoke-RestMethod -Uri "http://localhost:$port/api/system/build" -TimeoutSec 5|Format-List}catch{Write-Host $_.Exception.Message -ForegroundColor Red}
Write-Host "`n--- Flyway V22 ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select installed_rank,version,description,success from flyway_schema_history where version='22';"
Write-Host "`n--- Tabla y documentos ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select status,is_current,count(*) from business_documents group by status,is_current order by status,is_current;"
Write-Host "`n--- Permisos ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select code,name from permissions where code like 'DOCUMENTS_%' order by code;"
Write-Host "`n--- Ultimos documentos ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select entity_type,category,title,version_no,is_current,status,original_name,uploaded_by,created_at from business_documents order by created_at desc limit 15;"
Write-Host "`n--- Ultimos logs backend ---" -ForegroundColor Yellow
docker compose logs backend --tail=100
Read-Host 'Presione Enter para cerrar'
