$ErrorActionPreference='Continue'
$root=Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
$cfg=@{}; if(Test-Path '.env'){Get-Content '.env'|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1]]=$matches[2]}}}
$dbUser=if($cfg['POSTGRES_USER']){$cfg['POSTGRES_USER']}else{'ngerp'}
$dbName=if($cfg['POSTGRES_DB']){$cfg['POSTGRES_DB']}else{'ng_tintoreria'}
$port=if($cfg['ERP_PORT']){[int]$cfg['ERP_PORT']}else{8080}
Write-Host '=== DIAGNOSTICO NG COPILOTO v0.13.1 ===' -ForegroundColor Cyan
docker compose ps
Write-Host "`n--- Build activo ---" -ForegroundColor Yellow
try{Invoke-RestMethod -Uri "http://localhost:$port/api/system/build" -TimeoutSec 5|Format-List}catch{Write-Host $_.Exception.Message -ForegroundColor Red}
Write-Host "`n--- Flyway / tabla de alertas ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select version,description,success from flyway_schema_history order by installed_rank desc limit 3; select to_regclass('public.copilot_alert_user_state') alert_state_table;"
Write-Host "`n--- Condiciones detectables ---" -ForegroundColor Yellow
$sql=@"
with physical as (select inventory_item_id,sum(current_quantity) qty from inventory_lots where active=true group by inventory_item_id),
reserved as (select inventory_item_id,sum(quantity) qty from inventory_reservations where status='ACTIVE' group by inventory_item_id)
select 'STOCK_ZERO' tipo,count(*) cantidad from inventory_items i left join physical p on p.inventory_item_id=i.id left join reserved r on r.inventory_item_id=i.id where i.active=true and greatest(coalesce(p.qty,0)-coalesce(r.qty,0),0)=0
union all select 'PRODUCTION_ATTENTION',count(*) from production_orders where status in ('PLANNED','IN_PROCESS','PAUSED') and (status='PAUSED' or scheduled_end<now())
union all select 'QUALITY_ATTENTION',count(*) from production_orders where status in ('REPROCESS_REQUIRED','QUALITY_REJECTED') or (status='IN_QUALITY' and updated_at<now()-interval '2 hours')
union all select 'READY_DISPATCH',count(*) from production_orders where status='READY_FOR_DISPATCH'
union all select 'MACHINE_ATTENTION',count(*) from machines where active=true and operational_status<>'RETIRED' and (operational_status in ('IN_MAINTENANCE','OUT_OF_SERVICE') or (next_maintenance_at is not null and next_maintenance_at<=now()+interval '1 day') or (next_maintenance_meter_hours is not null and current_meter_hours>=next_maintenance_meter_hours));
"@
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c $sql
Write-Host "`n--- Estados de alerta por usuario ---" -ForegroundColor Yellow
docker exec ng_tintoreria_db psql -U $dbUser -d $dbName -c "select user_email,last_action,count(*) from copilot_alert_user_state group by user_email,last_action order by user_email,last_action;"
Read-Host 'Presione Enter para cerrar'
