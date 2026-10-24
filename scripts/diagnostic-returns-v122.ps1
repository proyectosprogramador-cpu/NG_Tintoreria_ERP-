$ErrorActionPreference='Continue'
$root=Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path);Set-Location $root
Write-Host '=== DIAGNOSTICO DEVOLUCIONES v0.12.2 ===' -ForegroundColor Cyan
& docker compose ps
$cfg=@{};if(Test-Path '.env'){Get-Content '.env'|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1]]=$matches[2]}}}
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'};$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'};$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080}
Write-Host "`n--- Backend build publico ---" -ForegroundColor Yellow
try{Invoke-RestMethod -Uri "http://localhost:$port/api/system/build" -TimeoutSec 5|Format-List}catch{Write-Host $_.Exception.Message -ForegroundColor Red}
Write-Host "`n--- Flyway V21 ---" -ForegroundColor Yellow
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select installed_rank,version,description,success from flyway_schema_history where version='21';"
Write-Host "`n--- Estructura ---" -ForegroundColor Yellow
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select table_name from information_schema.tables where table_schema='public' and table_name like 'customer_return%' order by table_name;"
Write-Host "`n--- Devoluciones por estado ---" -ForegroundColor Yellow
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select status,count(*) from customer_returns group by status order by status;"
Write-Host "`n--- Ultimas devoluciones ---" -ForegroundColor Yellow
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select return_number,status,decision,total_weight_kg,reprocess_production_order_id,created_at from customer_returns order by created_at desc limit 10;"
Write-Host "`n--- OP originadas por devolucion ---" -ForegroundColor Yellow
& docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select production_number,status,source_return_id,material_status,scheduled_start from production_orders where source_return_id is not null order by created_at desc limit 10;"
Write-Host "`n--- Ultimos logs backend ---" -ForegroundColor Yellow
& docker compose logs backend --tail=180
Read-Host 'Presione Enter para cerrar'
