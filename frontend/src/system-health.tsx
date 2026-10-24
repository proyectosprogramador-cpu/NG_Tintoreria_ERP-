import React,{useEffect,useMemo,useState} from 'react';

const API='/api';
function token(){return localStorage.getItem('ngerp_token')||''}
async function req(path:string,opts:RequestInit={}){const h:any={'Content-Type':'application/json',...(opts.headers||{})};if(token())h.Authorization=`Bearer ${token()}`;const r=await fetch(API+path,{...opts,headers:h});const txt=await r.text();let body:any=null;try{body=txt?JSON.parse(txt):null}catch{body=txt}if(!r.ok)throw new Error(body?.message||body?.error||txt||`HTTP ${r.status}`);return body}
function bytes(v:any){const n=Number(v||0);if(n<1024)return `${n} B`;if(n<1024**2)return `${(n/1024).toFixed(1)} KB`;if(n<1024**3)return `${(n/1024**2).toFixed(1)} MB`;return `${(n/1024**3).toFixed(2)} GB`}
function date(v:any){return v?new Date(v).toLocaleString():'—'}
function num(v:any){return Number(v||0).toLocaleString()}
function statusLabel(v:string){return v==='PASS'?'Correcto':v==='WARN'?'Atención':'Bloqueo'}
function overallLabel(v:string){return v==='READY'?'LISTO':v==='ATTENTION'?'REQUIERE ATENCIÓN':'BLOQUEADO'}

export function SystemHealthCenter({canManage,onError}:{canManage:boolean,onError:(s:string)=>void}){
 const [data,setData]=useState<any>(null),[failures,setFailures]=useState<any[]>([]),[runs,setRuns]=useState<any[]>([]),[tab,setTab]=useState('health'),[busy,setBusy]=useState(false),[form,setForm]=useState<any>({deploymentStage:'PREPRODUCTION',plannedGoLive:'',responsible:'',notes:''});
 const health=data?.health||{};const env=data?.environment||{};
 const load=async()=>{try{const [a,b,c]=await Promise.all([req('/system-health/summary'),req('/system-health/failures?limit=50'),req('/system-health/runs?limit=30')]);setData(a);setFailures(b);setRuns(c);setForm({deploymentStage:a.environment?.deployment_stage||'PREPRODUCTION',plannedGoLive:a.environment?.planned_go_live||'',responsible:a.environment?.responsible||'',notes:a.environment?.notes||''})}catch(e:any){onError(e.message)}};
 useEffect(()=>{void load()},[]);
 const usedPct=useMemo(()=>{const s=health.storage||{};const total=Number(s.totalBytes||0),usable=Number(s.usableBytes||0);return total>0?Math.max(0,Math.min(100,((total-usable)/total)*100)):0},[health.storage]);
 async function runPreflight(){try{setBusy(true);const out=await req('/system-health/preflight',{method:'POST'});setData((d:any)=>({...d,health:out.health}));await load()}catch(e:any){onError(e.message)}finally{setBusy(false)}}
 async function saveEnvironment(){try{setBusy(true);await req('/system-health/environment',{method:'PUT',body:JSON.stringify(form)});await load()}catch(e:any){onError(e.message)}finally{setBusy(false)}}
 return <div>
  <div className="hero"><div><h2>Estado técnico y preparación productiva</h2><p>Salud de servicios, almacenamiento, backups, restauración, errores recientes y controles previos al uso productivo.</p></div><div className={`badge health-overall ${String(health.overallStatus||'').toLowerCase()}`}>{overallLabel(health.overallStatus||'BLOCKED')}</div></div>
  <div className="actionbar health-actions"><button onClick={load}>Actualizar</button>{canManage&&<button className="primary" disabled={busy} onClick={runPreflight}>{busy?'Procesando…':'Ejecutar preflight'}</button>}<button onClick={()=>window.print()}>Imprimir</button></div>
  <div className="tabs"><button className={tab==='health'?'active':''} onClick={()=>setTab('health')}>Salud técnica</button><button className={tab==='readiness'?'active':''} onClick={()=>setTab('readiness')}>Puesta en marcha</button><button className={tab==='errors'?'active':''} onClick={()=>setTab('errors')}>Errores recientes</button><button className={tab==='history'?'active':''} onClick={()=>setTab('history')}>Historial preflight</button></div>

  {tab==='health'&&<>
    <div className="kpis">
      <K label="Backend" value={health.backend||'...'} sub={`Versión ${data?.version||'—'} · perfil ${(health.activeProfiles||[]).join(', ')||'default'}`} tone={health.backend==='UP'?'ok':'bad'}/>
      <K label="PostgreSQL" value={health.database||'...'} sub={`BD ${bytes(health.databaseBytes)}`} tone={health.database==='UP'?'ok':'bad'}/>
      <K label="Flyway" value={`V${health.flywayVersion||'—'}`} sub="Esquema aplicado" tone={String(health.flywayVersion)==='23'?'ok':'bad'}/>
      <K label="Espacio libre" value={bytes(health.storage?.usableBytes)} sub={`${usedPct.toFixed(1)}% utilizado`} tone={Number(health.storage?.usableBytes||0)>5*1024**3?'ok':'warn'}/>
      <K label="Archivos" value={num(health.storage?.fileCount)} sub={bytes(health.storage?.filesBytes)} />
      <K label="Errores 24 h" value={num(health.failedEvents24h)} sub="Eventos auditados fallidos" tone={Number(health.failedEvents24h||0)===0?'ok':'warn'}/>
      <K label="Usuarios activos" value={num(health.activeUsers)} sub={`${num(health.lockedUsers)} bloqueados · ${num(health.temporaryPasswords)} claves temporales`} />
      <K label="Preflight" value={overallLabel(health.overallStatus||'BLOCKED')} sub={`${health.blockerCount||0} bloqueos · ${health.warningCount||0} avisos`} tone={health.overallStatus==='READY'?'ok':health.overallStatus==='ATTENTION'?'warn':'bad'}/>
    </div>
    <div className="health-grid">
      <div className="panel"><div className="panelhead"><div><h3>Almacenamiento persistente</h3><p>Volumen donde se conservan documentos, evidencias y logo.</p></div></div><div className="storage-meter"><span style={{width:`${usedPct}%`}}/></div><div className="formgrid compact-grid"><Info label="Capacidad" value={bytes(health.storage?.totalBytes)}/><Info label="Disponible" value={bytes(health.storage?.usableBytes)}/><Info label="Archivos" value={num(health.storage?.fileCount)}/><Info label="Contenido" value={bytes(health.storage?.filesBytes)}/><Info label="Escribible" value={health.storage?.writable?'Sí':'No'}/></div></div>
      <div className="panel"><div className="panelhead"><div><h3>Continuidad y recuperación</h3><p>Último respaldo integral y última restauración registrada.</p></div></div>{health.lastBackup?<div className="formgrid compact-grid"><Info label="Backup" value={health.lastBackup.filename}/><Info label="Estado" value={health.lastBackup.status}/><Info label="Creado" value={date(health.lastBackup.created_at)}/><Info label="Verificado" value={date(health.lastBackup.verified_at)}/><Info label="Tamaño" value={bytes(health.lastBackup.size_bytes)}/></div>:<p>No hay backup registrado.</p>}<hr/><Info label="Última restauración" value={date(health.restore?.last_restored_at)}/></div>
    </div>
  </>}

  {tab==='readiness'&&<>
    <div className="health-grid readiness-grid">
      <div className="panel"><div className="panelhead"><div><h3>Controles de salida</h3><p>Un bloqueo impide pasar a Producción. Una advertencia requiere revisión antes del cambio de etapa.</p></div></div><div className="check-table">{(health.checks||[]).map((c:any)=><div className="check-row" key={c.code}><span className={`health-pill ${String(c.status).toLowerCase()}`}>{statusLabel(c.status)}</span><div><b>{c.label}</b><small>{c.detail}</small></div></div>)}</div></div>
      <div className="panel"><div className="panelhead"><div><h3>Etapa de despliegue</h3><p>Este valor administrativo no cambia Docker ni Spring; documenta si la instalación está en preproducción o en operación real.</p></div></div><div className="formgrid"><label>Etapa<select disabled={!canManage} value={form.deploymentStage} onChange={e=>setForm({...form,deploymentStage:e.target.value})}><option value="PREPRODUCTION">Preproducción</option><option value="PRODUCTION">Producción</option></select></label><label>Fecha objetivo<input disabled={!canManage} type="date" value={form.plannedGoLive||''} onChange={e=>setForm({...form,plannedGoLive:e.target.value})}/></label><label>Responsable<input disabled={!canManage} value={form.responsible||''} onChange={e=>setForm({...form,responsible:e.target.value})}/></label></div><label>Notas<textarea disabled={!canManage} rows={5} value={form.notes||''} onChange={e=>setForm({...form,notes:e.target.value})}/></label>{canManage&&<button className="primary" disabled={busy} onClick={saveEnvironment}>Guardar etapa</button>}<div className="inline-note">Para pasar a <b>Producción</b>, el preflight debe estar completamente en estado <b>LISTO</b>.</div></div>
    </div>
    <div className="panel"><div className="panelhead"><div><h3>Resumen de piloto</h3><p>El preflight reutiliza la validación formal realizada en Piloto productivo.</p></div></div><div className="formgrid compact-grid"><Info label="Ciclo" value={health.pilot?.code||'—'}/><Info label="Estado" value={health.pilot?.status||'—'}/><Info label="Checklist" value={`${health.pilot?.mandatoryPassed||0}/${health.pilot?.mandatoryTotal||0}`}/><Info label="Bloqueados" value={health.pilot?.mandatoryBlocked||0}/><Info label="Incidencias críticas/altas" value={health.pilot?.blockingIncidents||0}/><Info label="Evaluación" value={health.pilot?.ready?'LISTO':'PENDIENTE'}/></div></div>
  </>}

  {tab==='errors'&&<div className="panel"><div className="panelhead"><div><h3>Últimos eventos fallidos</h3><p>Errores auditados por el ERP. No incluye mensajes técnicos sensibles del servidor.</p></div></div><div className="tablewrap"><table><thead><tr><th>Fecha</th><th>Usuario</th><th>Módulo</th><th>Acción</th><th>IP</th><th>Ruta</th><th>Detalle</th></tr></thead><tbody>{failures.map((r:any,i)=><tr key={i}><td>{date(r.created_at)}</td><td>{r.actor}</td><td>{r.module}</td><td>{r.action}</td><td>{r.ip_address||'—'}</td><td><small>{r.http_method||''} {r.request_path||''}</small></td><td className="wrapcell">{r.detail||'—'}</td></tr>)}{!failures.length&&<tr><td colSpan={7}>No hay eventos fallidos registrados.</td></tr>}</tbody></table></div></div>}

  {tab==='history'&&<div className="panel"><div className="panelhead"><div><h3>Historial de preflight</h3><p>Cada ejecución queda registrada para documentar la preparación previa al go-live.</p></div></div><div className="tablewrap"><table><thead><tr><th>Fecha</th><th>Resultado</th><th>Bloqueos</th><th>Advertencias</th><th>Ejecutado por</th></tr></thead><tbody>{runs.map((r:any)=><tr key={r.id}><td>{date(r.created_at)}</td><td><span className={`health-pill ${r.overall_status==='READY'?'pass':r.overall_status==='ATTENTION'?'warn':'block'}`}>{overallLabel(r.overall_status)}</span></td><td>{r.blocker_count}</td><td>{r.warning_count}</td><td>{r.run_by}</td></tr>)}{!runs.length&&<tr><td colSpan={5}>Todavía no se ha ejecutado un preflight formal.</td></tr>}</tbody></table></div></div>}
 </div>
}
function K({label,value,sub,tone}:{label:string,value:any,sub:string,tone?:string}){return <div className={`kpi ${tone?`kpi-${tone}`:''}`}><small>{label}</small><b>{value}</b><span>{sub}</span></div>}
function Info({label,value}:{label:string,value:any}){return <div><b>{label}</b><br/><span>{String(value??'—')}</span></div>}
