$ErrorActionPreference='Continue'
Set-Location (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
Write-Host "=== DIAGNOSTICO DASHBOARD v0.10.4 R3 ===" -ForegroundColor Cyan
docker compose ps
$cfg=@{};Get-Content '.env'|%{if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1]]=$matches[2]}}
$u=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'};$d=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'};$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080};$url="http://localhost:$port"
Write-Host "`n--- Backend build publico ---" -ForegroundColor Yellow
try{Invoke-RestMethod -Uri "$url/api/system/build" -TimeoutSec 5 | Format-List}catch{Write-Host $_.Exception.Message -ForegroundColor Red}
Write-Host "`n--- Flyway V13/V14/V15 ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $u -d $d -c "select installed_rank,version,description,success from flyway_schema_history where version in ('13','14','15') order by installed_rank;"
Write-Host "`n--- Correlativos actuales ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $u -d $d -c "select 'quotation' tipo,last_value,is_called from quotation_number_seq union all select 'sales_order',last_value,is_called from sales_order_number_seq union all select 'production_order',last_value,is_called from production_order_number_seq;"
Write-Host "`n--- Maximos usados ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $u -d $d -c "select (select max(quote_number) from quotations) max_cotizacion,(select max(order_number) from sales_orders) max_pedido,(select max(production_number) from production_orders) max_op;"
Write-Host "`n--- Usuario ADMIN y DASHBOARD_READ ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $u -d $d -c "select au.email,r.code role,p.code permission from app_users au join user_roles ur on ur.user_id=au.id join roles r on r.id=ur.role_id left join role_permissions rp on rp.role_id=r.id left join permissions p on p.id=rp.permission_id and p.code='DASHBOARD_READ' where r.code='ADMIN';"
Write-Host "`n--- Ultimos logs backend ---" -ForegroundColor Yellow
docker compose logs --tail=160 backend
Read-Host "Presione Enter para cerrar"
