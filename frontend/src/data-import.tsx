import React,{useEffect,useMemo,useState} from 'react';

type Obj=Record<string,any>;
type Props={canWrite:boolean;onError:(m:string)=>void};
const API='/api';
const token=()=>localStorage.getItem('ngerp_token')||'';
async function api(path:string,opts:RequestInit={}){const h:any={'Content-Type':'application/json',...(opts.headers||{})};if(token())h.Authorization=`Bearer ${token()}`;const r=await fetch(API+path,{...opts,headers:h});const t=await r.text();let b:any=null;try{b=t?JSON.parse(t):null}catch{b=t}if(!r.ok)throw new Error(b?.message||b?.error||t||`HTTP ${r.status}`);return b}
async function upload(path:string,file:File){const fd=new FormData();fd.append('file',file);const h:any={};if(token())h.Authorization=`Bearer ${token()}`;const r=await fetch(API+path,{method:'POST',headers:h,body:fd});const t=await r.text();let b:any=null;try{b=t?JSON.parse(t):null}catch{b=t}if(!r.ok)throw new Error(b?.message||b?.error||t||`HTTP ${r.status}`);return b}
async function download(path:string,name:string){const h:any={};if(token())h.Authorization=`Bearer ${token()}`;const r=await fetch(API+path,{headers:h});if(!r.ok)throw new Error(await r.text()||`HTTP ${r.status}`);const b=await r.blob(),u=URL.createObjectURL(b),a=document.createElement('a');a.href=u;a.download=name;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(u),1500)}

const TYPES=[
 {code:'CLIENTS',title:'Clientes',desc:'Razón social, documento, contacto, crédito y dirección.',step:1},
 {code:'SUPPLIERS',title:'Proveedores',desc:'RUC, condiciones de pago, contacto y datos comerciales.',step:2},
 {code:'INVENTORY_ITEMS',title:'Insumos',desc:'Químicos/colorantes, unidad, mínimos, seguridad y costo estándar.',step:3},
 {code:'INITIAL_STOCK',title:'Stock inicial',desc:'Existencia física por insumo y lote. Requiere importar Insumos primero.',step:4},
 {code:'PRICE_LISTS',title:'Listas de precios',desc:'Tarifas generales o por cliente con reglas por tela/color/proceso.',step:5}
];
const labels:Record<string,string>={CLIENTS:'Clientes',SUPPLIERS:'Proveedores',INVENTORY_ITEMS:'Insumos',INITIAL_STOCK:'Stock inicial',PRICE_LISTS:'Listas de precios'};
const statusEs:Record<string,string>={PREVIEW:'Previsualización',COMPLETED:'Completado',FAILED:'Fallido',CANCELLED:'Cancelado',VALID:'Válido',INVALID:'Inválido',SKIPPED:'Omitido',IMPORTED:'Importado'};
const fmt=(x:any)=>x?new Date(x).toLocaleString():'—';

export function DataImportCenter({canWrite,onError}:Props){
 const [selected,setSelected]=useState('CLIENTS'),[file,setFile]=useState<File|null>(null),[batch,setBatch]=useState<Obj|null>(null),[history,setHistory]=useState<Obj[]>([]),[busy,setBusy]=useState(false);
 const spec=useMemo(()=>TYPES.find(x=>x.code===selected)!,[selected]);
 async function loadHistory(){try{setHistory(await api('/data-import/batches?limit=50'))}catch(e:any){onError(e.message)}}
 useEffect(()=>{loadHistory()},[]);
 async function template(){try{await download(`/data-import/templates/${selected}`,`plantilla_${selected.toLowerCase()}.xlsx`)}catch(e:any){onError(e.message)}}
 async function preview(){if(!file)return onError('Seleccione primero un archivo Excel');try{setBusy(true);const b=await upload(`/data-import/preview/${selected}`,file);setBatch(b);await loadHistory()}catch(e:any){onError(e.message)}finally{setBusy(false)}}
 async function commit(){if(!batch)return;try{if(!confirm(`Se importarán ${batch.valid_rows??batch.validRows??0} registros válidos. Los omitidos no se modificarán. ¿Continuar?`))return;setBusy(true);const b=await api(`/data-import/batches/${batch.id}/commit`,{method:'POST'});setBatch(b);setFile(null);await loadHistory()}catch(e:any){onError(e.message)}finally{setBusy(false)}}
 async function cancel(){if(!batch)return;try{setBusy(true);await api(`/data-import/batches/${batch.id}/cancel`,{method:'POST'});setBatch(null);setFile(null);await loadHistory()}catch(e:any){onError(e.message)}finally{setBusy(false)}}
 async function openBatch(id:string){try{setBusy(true);setBatch(await api(`/data-import/batches/${id}?rows=500`))}catch(e:any){onError(e.message)}finally{setBusy(false)}}
 const rows=(batch?.rows||[]) as Obj[];
 const invalid=Number(batch?.invalid_rows??0), valid=Number(batch?.valid_rows??0), skipped=Number(batch?.skipped_rows??0), imported=Number(batch?.imported_rows??0);
 return <>
  <div className="hero"><div><h2>Carga inicial / Excel</h2><p>Importación masiva controlada con plantilla, previsualización, validación por fila y confirmación explícita.</p></div><div className="badge">PREPRODUCCIÓN</div></div>
  <div className="panel import-order"><div><b>Orden recomendado de carga</b><p>Clientes → Proveedores → Insumos → Stock inicial → Listas de precios. Esto evita referencias faltantes y mantiene la trazabilidad.</p></div><div className="import-steps">{TYPES.map(x=><button key={x.code} className={selected===x.code?'active':''} onClick={()=>{setSelected(x.code);setFile(null);setBatch(null)}}><span>{x.step}</span>{x.title}</button>)}</div></div>
  <div className="import-grid">{TYPES.map(x=><button key={x.code} className={`panel import-card ${selected===x.code?'selected':''}`} onClick={()=>{setSelected(x.code);setFile(null);setBatch(null)}}><small>Paso {x.step}</small><h3>{x.title}</h3><p>{x.desc}</p></button>)}</div>
  <div className="panel"><div className="panelhead"><div><h3>{spec.title}</h3><p>{spec.desc}</p></div><button onClick={template}>Descargar plantilla Excel</button></div><div className="import-upload"><label>Archivo Excel<input type="file" accept=".xlsx,.xls" disabled={!canWrite||busy} onChange={e=>{setFile(e.target.files?.[0]||null);setBatch(null)}}/></label><div><b>{file?.name||'Ningún archivo seleccionado'}</b><small>Máximo 5,000 filas. Los datos no se guardan durante la previsualización.</small></div>{canWrite&&<button className="primary" disabled={!file||busy} onClick={preview}>{busy?'Procesando…':'Previsualizar y validar'}</button>}</div></div>
  {batch&&<div className="panel"><div className="panelhead"><div><h3>Resultado de previsualización</h3><p>{labels[batch.import_type]||batch.import_type} · {batch.file_name} · creado {fmt(batch.created_at)}</p></div><span className={`badge ${batch.status==='COMPLETED'?'ok':''}`}>{statusEs[batch.status]||batch.status}</span></div>
   <div className="kpis import-kpis"><div className="kpi"><small>Total</small><b>{batch.total_rows||0}</b><span>filas detectadas</span></div><div className="kpi"><small>Válidas</small><b className="oktext">{valid}</b><span>listas para importar</span></div><div className="kpi"><small>Omitidas</small><b>{skipped}</b><span>ya existen; no se sobrescriben</span></div><div className="kpi"><small>Inválidas</small><b className={invalid?'text-danger':''}>{invalid}</b><span>deben corregirse en Excel</span></div><div className="kpi"><small>Importadas</small><b>{imported}</b><span>confirmadas en ERP</span></div></div>
   {invalid>0&&<div className="inline-note import-error"><b>No se puede confirmar todavía.</b> Corrija las filas inválidas en el Excel y vuelva a previsualizar. El ERP no realiza importaciones parciales con errores.</div>}
   {rows.length>0&&<div className="tablewrap"><table><thead><tr><th>Fila Excel</th><th>Estado</th><th>Acción</th><th>Datos</th><th>Validación</th></tr></thead><tbody>{rows.map(r=><tr key={r.id}><td>{r.row_no}</td><td><span className={`import-status st-${String(r.status).toLowerCase()}`}>{statusEs[r.status]||r.status}</span></td><td>{r.action==='SKIP'?'Omitir':'Crear'}</td><td><code className="import-json">{compact(r.normalized_data||r.raw_data)}</code></td><td className="wrapcell">{r.error_message||r.imported_reference||'Correcto'}</td></tr>)}</tbody></table></div>}
   {canWrite&&batch.status==='PREVIEW'&&<div className="import-actions"><button onClick={cancel} disabled={busy}>Cancelar previsualización</button><button className="primary" disabled={busy||invalid>0||valid===0} onClick={commit}>Importar {valid} registro(s)</button></div>}
  </div>}
  <div className="panel"><div className="panelhead"><div><h3>Historial de cargas</h3><p>Registro auditable de previsualizaciones, importaciones, errores y usuario responsable.</p></div><button onClick={loadHistory}>Actualizar</button></div><div className="tablewrap"><table><thead><tr><th>Fecha</th><th>Tipo</th><th>Archivo</th><th>Estado</th><th>Total</th><th>Importados</th><th>Usuario</th><th/></tr></thead><tbody>{history.map(x=><tr key={x.id}><td>{fmt(x.created_at)}</td><td>{labels[x.import_type]||x.import_type}</td><td>{x.file_name}</td><td>{statusEs[x.status]||x.status}</td><td>{x.total_rows}</td><td>{x.imported_rows}</td><td>{x.committed_by||x.created_by}</td><td><button onClick={()=>openBatch(x.id)}>Ver detalle</button></td></tr>)}{history.length===0&&<tr><td colSpan={8} className="empty">Aún no existen cargas masivas.</td></tr>}</tbody></table></div></div>
 </>
}
function compact(x:any){try{const o=typeof x==='string'?JSON.parse(x):x;return Object.entries(o||{}).filter(([,v])=>v!==null&&v!==''&&v!==undefined).slice(0,8).map(([k,v])=>`${k}: ${String(v)}`).join(' · ')}catch{return String(x||'')}}
