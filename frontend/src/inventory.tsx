import React,{useEffect,useMemo,useState} from 'react';
import {codeText,digitsOnly,emailText,personText,validDigits,validEmail,validPerson} from './input-validation';

type Props={
  canMove:boolean; canCreateItem:boolean; canReserve:boolean; canRequestRead:boolean; canRequest:boolean; canApprove:boolean;
  canPurchaseRead:boolean; canPurchaseWrite:boolean; canPurchaseApprove:boolean; canReceive:boolean; canReportsExport:boolean; canSupplierRead:boolean; canSupplierWrite:boolean;
  onError:(message:string)=>void;
};

const API='/api';
const token=()=>localStorage.getItem('ngerp_token')||'';

async function api(path:string,opts:RequestInit={}){
  const headers:Record<string,string>={'Content-Type':'application/json'};
  if(token())headers.Authorization=`Bearer ${token()}`;
  const response=await fetch(API+path,{...opts,headers});
  if(response.status===401){localStorage.removeItem('ngerp_token');throw new Error('La sesión venció. Ingrese nuevamente.')}
  const text=await response.text();let body:any=null;
  try{body=text?JSON.parse(text):null}catch{body=text}
  if(!response.ok)throw new Error(body?.message||body?.error||text||`HTTP ${response.status}`);
  return body;
}

async function file(path:string,open=false){
  const headers:Record<string,string>={};if(token())headers.Authorization=`Bearer ${token()}`;
  const response=await fetch(API+path,{headers});
  if(!response.ok){const text=await response.text();throw new Error(text||`HTTP ${response.status}`)}
  const blob=await response.blob();
  const cd=response.headers.get('content-disposition')||'';
  const utf=cd.match(/filename\*=UTF-8''([^;]+)/i);const simple=cd.match(/filename="?([^";]+)"?/i);
  const name=utf?decodeURIComponent(utf[1]):simple?.[1]||'reporte';
  const url=URL.createObjectURL(blob);
  if(open){window.open(url,'_blank','noopener,noreferrer');setTimeout(()=>URL.revokeObjectURL(url),60000);return}
  const a=document.createElement('a');a.href=url;a.download=name;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1500);
}

const q=(v:any,d=3)=>new Intl.NumberFormat('es-PE',{minimumFractionDigits:0,maximumFractionDigits:d}).format(Number(v||0));
const money=(v:any,c='PEN')=>new Intl.NumberFormat('es-PE',{style:'currency',currency:c==='USD'?'USD':'PEN'}).format(Number(v||0));
const movementNames:Record<string,string>={INITIAL_STOCK:'Stock inicial',PURCHASE_RECEIPT:'Recepción de compra',ADJUSTMENT_IN:'Ajuste de entrada',ADJUSTMENT_OUT:'Ajuste de salida',PRODUCTION_ISSUE:'Salida a producción',PRODUCTION_RETURN:'Devolución de producción',TRANSFER_IN:'Transferencia entrada',TRANSFER_OUT:'Transferencia salida'};
const requestNames:Record<string,string>={DRAFT:'Borrador',REQUESTED:'Solicitada',APPROVED:'Aprobada',CANCELLED:'Cancelada'};
const orderNames:Record<string,string>={DRAFT:'Borrador',ISSUED:'Emitida',SENT:'Enviada',PARTIALLY_RECEIVED:'Recepción parcial',RECEIVED:'Recibida',CLOSED:'Cerrada',CANCELLED:'Cancelada'};
const stockNames:Record<string,string>={NORMAL:'Normal',LOW:'Stock bajo',NO_STOCK:'Sin stock'};
const pill=(s:string)=>['NORMAL','APPROVED','ACTIVE','RECEIVED','CLOSED'].includes(s)?'statuspill good':['NO_STOCK','CANCELLED'].includes(s)?'statuspill bad':['LOW','DRAFT','REQUESTED','PARTIALLY_RECEIVED'].includes(s)?'statuspill warn':'statuspill';

function Modal({title,onClose,children,wide=false}:{title:string;onClose:()=>void;children:React.ReactNode;wide?:boolean}){
  return <div className="modalback" onMouseDown={e=>{if(e.target===e.currentTarget)onClose()}}><div className={`modal inventory-modal ${wide?'inventory-modal-wide':''}`}><div className="panelhead"><h3>{title}</h3><button onClick={onClose}>✕</button></div>{children}</div></div>
}

function ExportButtons({base,onError}:{base:string;onError:(m:string)=>void}){
  const run=(ext:string,open=false)=>file(`/reports/${base}.${ext}`,open).catch((e:any)=>onError(e.message));
  return <div className="exportbar"><button onClick={()=>run('xlsx')}>Excel</button><button onClick={()=>run('pdf')}>PDF</button><button onClick={()=>run('pdf',true)}>Imprimir</button></div>
}

export function Inventory(props:Props){
 const {canMove,canCreateItem,canRequestRead,canRequest,canApprove,canPurchaseRead,canPurchaseWrite,canPurchaseApprove,canReceive,canReportsExport,canSupplierRead,canSupplierWrite,onError}=props;
 const [summary,setSummary]=useState<any>({}),[items,setItems]=useState<any[]>([]),[movements,setMovements]=useState<any[]>([]),[reservations,setReservations]=useState<any[]>([]),[requests,setRequests]=useState<any[]>([]),[orders,setOrders]=useState<any[]>([]),[suppliers,setSuppliers]=useState<any[]>([]);
 const [view,setView]=useState('STOCK'),[search,setSearch]=useState(''),[itemEdit,setItemEdit]=useState<any|null>(null),[moveEdit,setMoveEdit]=useState<any|null>(null),[requestDetail,setRequestDetail]=useState<any|null>(null),[orderDetail,setOrderDetail]=useState<any|null>(null),[manualOrder,setManualOrder]=useState<any|null>(null),[manualRequest,setManualRequest]=useState<any|null>(null),[busy,setBusy]=useState(false);

 const load=()=>Promise.all([
   api('/inventory/summary'),api('/inventory/items'),api('/inventory/movements?limit=250'),api('/inventory/reservations'),
   canRequestRead?api('/purchase-requests'):Promise.resolve([]),canPurchaseRead?api('/purchase-orders'):Promise.resolve([]),canSupplierRead?api('/suppliers'):Promise.resolve([])
 ]).then(([s,i,m,r,p,o,su])=>{setSummary(s);setItems(i);setMovements(m);setReservations(r);setRequests(p);setOrders(o);setSuppliers(su)}).catch((e:any)=>onError(e.message));
 useEffect(()=>{void load()},[]);
 const visible=useMemo(()=>items.filter(x=>`${x.code} ${x.name}`.toLowerCase().includes(search.toLowerCase())),[items,search]);

 async function saveItem(){try{setBusy(true);await api('/inventory/items',{method:'POST',body:JSON.stringify(itemEdit)});setItemEdit(null);await load()}catch(e:any){onError(e.message)}finally{setBusy(false)}}
 async function saveMovement(){try{setBusy(true);await api('/inventory/movements',{method:'POST',body:JSON.stringify({...moveEdit,quantity:Number(moveEdit.quantity),unitCost:moveEdit.unitCost===''?null:Number(moveEdit.unitCost)})});setMoveEdit(null);await load()}catch(e:any){onError(e.message)}finally{setBusy(false)}}
 async function openRequest(id:string){try{setRequestDetail(await api('/purchase-requests/'+id))}catch(e:any){onError(e.message)}}
 async function requestStatus(id:string,status:string){try{setBusy(true);const out=await api(`/purchase-requests/${id}/status`,{method:'POST',body:JSON.stringify({status})});setRequestDetail(out);await load()}catch(e:any){onError(e.message)}finally{setBusy(false)}}
 async function saveRequestDraft(id:string,notes:string,lines:any[]){try{setBusy(true);const out=await api(`/purchase-requests/${id}`,{method:'PUT',body:JSON.stringify({notes,lines:lines.map(x=>({id:x.id,requestedQuantity:Number(x.requested_quantity)}))})});setRequestDetail(out);await load()}catch(e:any){onError(e.message)}finally{setBusy(false)}}
 async function createOrderFromRequest(id:string){try{setBusy(true);const out=await api(`/purchase-orders/from-request/${id}`,{method:'POST'});setRequestDetail(null);setOrderDetail(out);setView('ORDERS');await load()}catch(e:any){onError(e.message)}finally{setBusy(false)}}
 async function openOrder(id:string){try{setOrderDetail(await api('/purchase-orders/'+id))}catch(e:any){onError(e.message)}}
 async function saveOrder(id:string,draft:any,lines:any[]){try{setBusy(true);const body={...draft,discount:Number(draft.discount||0),igvRate:Number(draft.igv_rate??draft.igvRate??18),lines:lines.map(x=>({id:x.id,inventoryItemId:x.inventory_item_id,quantity:Number(x.quantity),unit:x.unit,unitPrice:Number(x.unit_price||0),lineDiscount:Number(x.line_discount||0)}))};const out=await api(`/purchase-orders/${id}`,{method:'PUT',body:JSON.stringify(body)});setOrderDetail(out);await load()}catch(e:any){onError(e.message)}finally{setBusy(false)}}
 async function orderAction(id:string,action:string){try{setBusy(true);const out=await api(`/purchase-orders/${id}/action`,{method:'POST',body:JSON.stringify({action})});setOrderDetail(out);await load()}catch(e:any){onError(e.message)}finally{setBusy(false)}}
 async function receiveOrder(id:string,body:any){try{setBusy(true);const out=await api(`/purchase-orders/${id}/receipts`,{method:'POST',body:JSON.stringify(body)});setOrderDetail(out);await load();return true}catch(e:any){onError(e.message);return false}finally{setBusy(false)}}
 async function createSupplier(data:any){
  if(!canSupplierWrite)throw new Error('No tiene permiso para registrar proveedores.');
  const saved=await api('/suppliers',{method:'POST',body:JSON.stringify({...data,active:true})});
  setSuppliers((prev:any[])=>[...prev,saved].sort((x,y)=>String(x.name).localeCompare(String(y.name),'es')));
  return saved;
 }
 async function createQuickItem(data:any){
  if(!canCreateItem)throw new Error('No tiene permiso para dar de alta insumos.');
  const saved=await api('/inventory/items/quick',{method:'POST',body:JSON.stringify({
   code:data.code,name:data.name,baseUnit:data.baseUnit,
   minimumStock:0,safetyStock:0,standardCost:null,active:true
  })});
  setItems((prev:any[])=>[...prev.filter(x=>x.id!==saved.id),saved]
    .sort((a,b)=>String(a.name).localeCompare(String(b.name),'es')));
  setSummary((prev:any)=>({...prev,items:Number(prev.items||0)+1,noStock:Number(prev.noStock||0)+1}));
  return saved;
 }
 async function createManualRequest(draft:any){
   try{
    setBusy(true);
    const out=await api('/purchase-requests/manual',{method:'POST',body:JSON.stringify({
     priority:draft.priority,notes:String(draft.notes||'').trim(),
     lines:draft.lines.map((x:any)=>({inventoryItemId:x.inventoryItemId,quantity:Number(x.quantity)}))
    })});
    setManualRequest(null);setRequestDetail(out);setView('REQUESTS');await load();
   }finally{setBusy(false)}
  }
  async function createManualOrder(draft:any){try{setBusy(true);const out=await api('/purchase-orders/manual',{method:'POST',body:JSON.stringify({...draft,discount:Number(draft.discount||0),igvRate:Number(draft.igvRate||18),lines:draft.lines.map((x:any)=>({inventoryItemId:x.inventoryItemId,quantity:Number(x.quantity),unit:x.unit,unitPrice:Number(x.unitPrice||0),lineDiscount:Number(x.lineDiscount||0)}))})});setManualOrder(null);setOrderDetail(out);setView('ORDERS');await load()}catch(e:any){onError(e.message)}finally{setBusy(false)}}

 return <>
  <div className="sales-head"><div><h2>Almacén, Kardex y Compras</h2><p>Existencias reales, reservas por OP, solicitudes, órdenes de compra, recepciones y reportes.</p></div><div className="actionbar">{canRequest&&<button className="primary" onClick={()=>{setView('REQUESTS');setManualRequest({priority:'NORMAL',notes:'',lines:[{inventoryItemId:'',quantity:''}]})}}>＋ Solicitar insumos</button>}{canPurchaseWrite&&<button onClick={()=>setManualOrder({supplierId:'',expectedDate:'',currency:'PEN',paymentTerms:'',deliveryAddress:'',notes:'',discount:0,igvRate:18,lines:[{inventoryItemId:items[0]?.id||'',quantity:'',unit:items[0]?.baseUnit||'KG',unitPrice:'',lineDiscount:0}]})}>＋ Orden de compra</button>}{canMove&&<button onClick={()=>setItemEdit({code:'',name:'',baseUnit:'KG',minimumStock:0,safetyStock:0,standardCost:'',active:true})}>＋ Insumo</button>}{canMove&&<button className="primary" onClick={()=>setMoveEdit({inventoryItemId:items[0]?.id||'',movementType:'INITIAL_STOCK',quantity:'',lotCode:'GENERAL',unitCost:'',comment:''})}>＋ Movimiento</button>}</div></div>
  <div className="grid sales-kpis"><div className="card"><small>Insumos</small><strong>{summary.items||0}</strong><span>Activos en almacén</span></div><div className="card"><small>Stock bajo</small><strong>{summary.lowStock||0}</strong><span>Requieren reposición</span></div><div className="card"><small>Sin stock</small><strong>{summary.noStock||0}</strong><span>Existencia física cero</span></div><div className="card"><small>OP con faltantes</small><strong>{summary.productionShortages||0}</strong><span>Producción bloqueada</span></div></div>
  <div className="tabs production-tabs"><button className={view==='STOCK'?'active':''} onClick={()=>setView('STOCK')}>Existencias</button><button className={view==='KARDEX'?'active':''} onClick={()=>setView('KARDEX')}>Kardex</button><button className={view==='RESERVATIONS'?'active':''} onClick={()=>setView('RESERVATIONS')}>Reservas OP</button>{canRequestRead&&<button className={view==='REQUESTS'?'active':''} onClick={()=>setView('REQUESTS')}>Solicitudes de compra</button>}{canPurchaseRead&&<button className={view==='ORDERS'?'active':''} onClick={()=>setView('ORDERS')}>Órdenes de compra</button>}</div>

  {view==='STOCK'&&<div className="panel"><div className="panelhead"><div className="sales-toolbar"><input className="search" placeholder="Buscar código o insumo" value={search} onChange={e=>setSearch(e.target.value)}/><span>{visible.length} insumo(s)</span></div>{canReportsExport&&<ExportButtons base="inventory/stock" onError={onError}/>}</div><div className="tablewrap"><table><thead><tr><th>Código / insumo</th><th>Físico</th><th>Reservado</th><th>Disponible</th><th>En compra</th><th>Mínimo</th><th>Costo ref.</th><th>Estado</th><th/></tr></thead><tbody>{visible.map(x=><tr key={x.id}><td><b>{x.code}</b><small className="cell-sub">{x.name} · {x.baseUnit}</small></td><td>{q(x.physicalQuantity,6)}</td><td>{q(x.reservedQuantity,6)}</td><td><b>{q(x.availableQuantity,6)}</b></td><td>{q(x.inPurchaseQuantity,6)}</td><td>{q(x.minimumStock,6)}</td><td>{x.standardCost==null?'—':money(x.standardCost,'PEN')}</td><td><span className={pill(x.stockStatus)}>{stockNames[x.stockStatus]||x.stockStatus}</span></td><td>{canMove&&<button onClick={()=>setItemEdit({...x})}>Editar</button>}</td></tr>)}</tbody></table></div></div>}
  {view==='KARDEX'&&<div className="panel"><div className="panelhead"><div><h3>Kardex de movimientos</h3><p>Las entradas son positivas y las salidas negativas. El saldo queda grabado después de cada movimiento.</p></div>{canReportsExport&&<ExportButtons base="inventory/kardex" onError={onError}/>}</div><div className="tablewrap"><table><thead><tr><th>Fecha</th><th>Insumo / lote</th><th>Movimiento</th><th>Cantidad</th><th>Saldo</th><th>Referencia</th><th>Usuario</th></tr></thead><tbody>{movements.map(x=><tr key={x.id}><td>{new Date(x.createdAt).toLocaleString('es-PE')}</td><td><b>{x.itemCode} · {x.itemName}</b><small className="cell-sub">Lote {x.lotCode||'—'} · {x.warehouseName}</small></td><td>{movementNames[x.movementType]||x.movementType}</td><td className={Number(x.quantity)<0?'qty-out':'qty-in'}>{q(x.quantity,6)}</td><td><b>{q(x.balanceAfter,6)}</b></td><td>{x.referenceNumber||x.referenceType||'—'}<small className="cell-sub">{x.comment||''}</small></td><td>{x.actor}</td></tr>)}</tbody></table></div></div>}
  {view==='RESERVATIONS'&&<div className="panel"><div className="panelhead"><div><h3>Reservas por orden de producción</h3><p>Reservar no descuenta físicamente el Kardex; evita que otra OP utilice el mismo stock.</p></div>{canReportsExport&&<ExportButtons base="inventory/reservations" onError={onError}/>}</div><div className="tablewrap"><table><thead><tr><th>OP / lote</th><th>Insumo</th><th>Reservado</th><th>Requerido</th><th>Estado</th><th>Fecha</th></tr></thead><tbody>{reservations.map(x=><tr key={x.id}><td><b>{x.productionNumber}</b><small className="cell-sub">{x.batchNumber} · {x.priority}</small></td><td>{x.itemCode} · {x.itemName}</td><td><b>{q(x.quantity,6)} kg</b></td><td>{q(x.plannedQuantityKg,6)} kg</td><td><span className={pill(x.status)}>{x.status==='ACTIVE'?'Activa':x.status==='RELEASED'?'Liberada':'Consumida'}</span></td><td>{new Date(x.createdAt).toLocaleString('es-PE')}</td></tr>)}</tbody></table></div></div>}
  {view==='REQUESTS'&&<div className="panel"><div className="panelhead"><div><h3>Solicitudes de compra</h3><p>Las solicitudes aprobadas pueden convertirse directamente en una orden de compra trazable.</p></div>{canReportsExport&&<ExportButtons base="purchase-requests" onError={onError}/>}</div><div className="tablewrap"><table><thead><tr><th>Solicitud</th><th>Origen</th><th>Cliente</th><th>Líneas</th><th>Prioridad</th><th>Estado</th><th>Fecha</th><th/></tr></thead><tbody>{requests.map(x=><tr key={x.id}><td><b>{x.requestNumber}</b></td><td>{x.productionNumber||'Manual'}<small className="cell-sub">{x.batchNumber||''}</small></td><td>{x.clientName||'—'}</td><td>{x.lineCount}</td><td>{x.priority}</td><td><span className={pill(x.status)}>{requestNames[x.status]||x.status}</span></td><td>{new Date(x.requestedAt).toLocaleString('es-PE')}</td><td><button onClick={()=>openRequest(x.id)}>Ver</button></td></tr>)}</tbody></table></div></div>}
  {view==='ORDERS'&&<div className="panel"><div className="panelhead"><div><h3>Órdenes de compra</h3><p>Emisión, PDF para proveedor, recepción parcial/total y actualización automática del Kardex.</p></div>{canReportsExport&&<ExportButtons base="purchase-orders" onError={onError}/>}</div><div className="tablewrap"><table><thead><tr><th>OC</th><th>Proveedor</th><th>Origen</th><th>Fecha / entrega</th><th>Estado</th><th>Total</th><th>Recepción</th><th/></tr></thead><tbody>{orders.map(x=><tr key={x.id}><td><b>{x.order_number}</b></td><td>{x.supplier_name||'Pendiente'}<small className="cell-sub">{x.supplier_ruc||''}</small></td><td>{x.request_number||'Reposición / manual'}</td><td>{x.order_date||'—'}<small className="cell-sub">Entrega: {x.expected_date||'—'}</small></td><td><span className={pill(x.status)}>{orderNames[x.status]||x.status}</span></td><td><b>{money(x.total,x.currency)}</b></td><td>{q(x.received_quantity,3)} / {q(x.ordered_quantity,3)}</td><td><button onClick={()=>openOrder(x.id)}>Ver</button></td></tr>)}</tbody></table></div></div>}

  {itemEdit&&<Modal title={itemEdit.id?'Editar insumo':'Nuevo insumo'} onClose={()=>setItemEdit(null)}><div className="formgrid"><label>Código<input maxLength={40} value={itemEdit.code||''} onChange={e=>setItemEdit({...itemEdit,code:codeText(e.target.value,40)})}/></label><label>Nombre<input value={itemEdit.name||''} onChange={e=>setItemEdit({...itemEdit,name:e.target.value})}/></label><label>Unidad base<input maxLength={20} value={itemEdit.baseUnit||'KG'} onChange={e=>setItemEdit({...itemEdit,baseUnit:codeText(e.target.value,20)})}/></label><label>Stock mínimo<input type="number" min="0" step="0.001" value={itemEdit.minimumStock??0} onChange={e=>setItemEdit({...itemEdit,minimumStock:Number(e.target.value)})}/></label><label>Stock seguridad<input type="number" min="0" step="0.001" value={itemEdit.safetyStock??0} onChange={e=>setItemEdit({...itemEdit,safetyStock:Number(e.target.value)})}/></label><label>Costo estándar / referencia (PEN por unidad)<input type="number" min="0" step="0.0001" value={itemEdit.standardCost??''} onChange={e=>setItemEdit({...itemEdit,standardCost:e.target.value===''?null:Number(e.target.value)})}/></label><label>Estado<select value={String(itemEdit.active!==false)} onChange={e=>setItemEdit({...itemEdit,active:e.target.value==='true'})}><option value="true">Activo</option><option value="false">Inactivo</option></select></label></div><div className="modal-actions"><button onClick={()=>setItemEdit(null)}>Cancelar</button><button className="primary" disabled={busy||!itemEdit.code||!itemEdit.name} onClick={saveItem}>Guardar</button></div></Modal>}
  {moveEdit&&<Modal title="Registrar movimiento de almacén" onClose={()=>setMoveEdit(null)}><div className="formgrid"><label>Insumo<select value={moveEdit.inventoryItemId||''} onChange={e=>setMoveEdit({...moveEdit,inventoryItemId:e.target.value})}><option value="">Seleccione…</option>{items.filter(x=>x.active!==false).map(x=><option key={x.id} value={x.id}>{x.code} · {x.name}</option>)}</select></label><label>Movimiento<select value={moveEdit.movementType} onChange={e=>setMoveEdit({...moveEdit,movementType:e.target.value})}><option value="INITIAL_STOCK">Stock inicial</option><option value="PURCHASE_RECEIPT">Recepción de compra manual</option><option value="ADJUSTMENT_IN">Ajuste de entrada</option><option value="ADJUSTMENT_OUT">Ajuste de salida</option><option value="PRODUCTION_RETURN">Devolución de producción</option></select></label><label>Cantidad<input type="number" min="0.000001" step="0.001" value={moveEdit.quantity} onChange={e=>setMoveEdit({...moveEdit,quantity:e.target.value})}/></label><label>Lote<input maxLength={80} value={moveEdit.lotCode||''} onChange={e=>setMoveEdit({...moveEdit,lotCode:codeText(e.target.value,80)})}/></label><label>Costo unitario opcional<input type="number" min="0" step="0.0001" value={moveEdit.unitCost} onChange={e=>setMoveEdit({...moveEdit,unitCost:e.target.value})}/></label><label>Referencia<input value={moveEdit.referenceNumber||''} onChange={e=>setMoveEdit({...moveEdit,referenceNumber:e.target.value})} placeholder="OC, guía, ajuste…"/></label></div><label>Comentario<textarea rows={3} value={moveEdit.comment||''} onChange={e=>setMoveEdit({...moveEdit,comment:e.target.value})}/></label><div className="modal-actions"><button onClick={()=>setMoveEdit(null)}>Cancelar</button><button className="primary" disabled={busy||!moveEdit.inventoryItemId||!moveEdit.quantity} onClick={saveMovement}>Registrar</button></div></Modal>}
  {manualRequest&&<ManualRequestModal draft={manualRequest} setDraft={setManualRequest} items={items} canCreateItem={canCreateItem} onCreateItem={createQuickItem} busy={busy} onClose={()=>setManualRequest(null)} onCreate={createManualRequest}/>}
   {requestDetail&&<RequestModal dto={requestDetail} canRequest={canRequest} canApprove={canApprove} canCreateOrder={canPurchaseWrite} busy={busy} onClose={()=>setRequestDetail(null)} onStatus={requestStatus} onSave={saveRequestDraft} onCreateOrder={createOrderFromRequest}/>} 
  {orderDetail&&<OrderModal dto={orderDetail} suppliers={suppliers} canWrite={canPurchaseWrite} canSupplierWrite={canSupplierWrite} onCreateSupplier={createSupplier} canApprove={canPurchaseApprove} canReceive={canReceive} busy={busy} onClose={()=>setOrderDetail(null)} onSave={saveOrder} onAction={orderAction} onReceive={receiveOrder} onError={onError}/>} 
  {manualOrder&&<ManualOrderModal draft={manualOrder} setDraft={setManualOrder} items={items} suppliers={suppliers} canCreateItem={canCreateItem} onCreateItem={createQuickItem} canSupplierWrite={canSupplierWrite} onCreateSupplier={createSupplier} busy={busy} onClose={()=>setManualOrder(null)} onCreate={createManualOrder}/>} 
 </>;
}


function InventoryItemQuickForm({items,busy,onCancel,onSave}:any){
 const [data,setData]=useState({code:'',name:'',baseUnit:'KG'});
 const [saving,setSaving]=useState(false),[error,setError]=useState('');
 const code=data.code.trim(),name=data.name.trim(),unit=data.baseUnit.trim();
 const duplicated=items.find((item:any)=>String(item.code||'').trim().toLowerCase()===code.toLowerCase()
  ||String(item.name||'').trim().toLowerCase()===name.toLowerCase());
 const valid=!!code&&!!name&&!!unit&&name.length<=180&&!duplicated;
 const submit=async()=>{
  if(!code||!name||!unit){setError('Código, nombre y unidad son obligatorios.');return}
  if(duplicated){setError('El insumo ya existe en el catálogo. Selecciónelo en la lista.');return}
  try{setError('');setSaving(true);await onSave({code,name,baseUnit:unit})}
  catch(e:any){setError(e?.message||'No se pudo registrar el insumo.')}
  finally{setSaving(false)}
 };
 return <div className="nested-modal">
  <div className="panelhead"><div><h4>Nuevo insumo</h4><p>Se guardará en Almacén con stock inicial cero y se seleccionará en esta línea. La solicitud u OC se guardará por separado.</p></div></div>
  <div className="formgrid">
   <label>Código *<input maxLength={40} value={data.code} onChange={e=>{setError('');setData(old=>({...old,code:codeText(e.target.value,40)}))}} placeholder="INS-001"/></label>
   <label>Nombre del insumo *<input maxLength={180} value={data.name} onChange={e=>{setError('');setData(old=>({...old,name:e.target.value}))}} placeholder="Ej.: Ácido acético"/></label>
   <label>Unidad base *<input maxLength={20} value={data.baseUnit} onChange={e=>{setError('');setData(old=>({...old,baseUnit:codeText(e.target.value,20)}))}} placeholder="KG, L, UND…"/></label>
  </div>
  {duplicated&&code&&name&&<div className="form-alert" role="alert">Insumo similar existente: {duplicated.code} · {duplicated.name}. Utilice el insumo del catálogo.</div>}
  {error&&<div className="form-alert" role="alert">{error}</div>}
  <div className="modal-actions"><button type="button" disabled={busy||saving} onClick={onCancel}>Cancelar</button><button type="button" className="primary" disabled={busy||saving||!valid} onClick={submit}>{saving?'Registrando…':'Guardar y seleccionar'}</button></div>
 </div>;
}

function ManualRequestModal({draft,setDraft,items,canCreateItem,onCreateItem,busy,onClose,onCreate}:any){
  const [addingItemLine,setAddingItemLine]=useState<number|null>(null);
  const [error,setError]=useState('');
  const activeItems=items.filter((item:any)=>item.active!==false);
  const patchLine=(idx:number,key:string,value:string)=>setDraft((old:any)=>({...old,lines:old.lines.map((x:any,i:number)=>i===idx?{...x,[key]:value}:x)}));
  const addLine=()=>setDraft((old:any)=>({...old,lines:[...old.lines,{inventoryItemId:'',quantity:''}]}));
  const removeLine=(idx:number)=>setDraft((old:any)=>({...old,lines:old.lines.filter((_:any,i:number)=>i!==idx)}));
  const ids=draft.lines.map((x:any)=>x.inventoryItemId).filter(Boolean);
  const duplicated=ids.length!==new Set(ids).size;
  const valid=String(draft.notes||'').trim().length>0&&String(draft.notes||'').length<=500
   &&draft.lines.length>0&&draft.lines.length<=50&&!duplicated
   &&draft.lines.every((x:any)=>x.inventoryItemId&&Number.isFinite(Number(x.quantity))&&Number(x.quantity)>0);
  const submit=async()=>{if(!valid){setError('Complete el motivo, los insumos y las cantidades positivas, sin duplicados.');return}
   try{setError('');await onCreate(draft)}catch(e:any){setError(e?.message||'No se pudo crear la solicitud')}}
  return <Modal title="Nueva solicitud de insumos / reposición" onClose={onClose} wide>
   <p className="inventory-note">Almacén registra la necesidad. Compras o Administración aprueba la solicitud antes de generar una orden de compra.</p>
   <div className="formgrid"><label>Prioridad *<select value={draft.priority} onChange={e=>setDraft((old:any)=>({...old,priority:e.target.value}))}><option value="NORMAL">Normal</option><option value="HIGH">Alta</option><option value="URGENT">Urgente</option></select></label>
   <label>Motivo de reposición *<textarea rows={2} maxLength={500} value={draft.notes} onChange={e=>setDraft((old:any)=>({...old,notes:e.target.value}))} placeholder="Ej.: reponer sal para mantener el stock mínimo"/></label></div>
   <div className="panelhead"><div><h4>Insumos solicitados</h4><p>Puede seleccionar varios insumos del maestro de Almacén.</p></div><button disabled={busy||draft.lines.length>=50} onClick={addLine}>＋ Línea</button></div>
   <div className="tablewrap"><table><thead><tr><th>Insumo</th><th>Disponible</th><th>Cantidad solicitada</th><th>Unidad</th><th/></tr></thead><tbody>{draft.lines.map((line:any,idx:number)=>{const item=activeItems.find((x:any)=>x.id===line.inventoryItemId);return <tr key={idx}>
    <td><div className="purchase-item-picker"><select value={line.inventoryItemId} onChange={e=>patchLine(idx,'inventoryItemId',e.target.value)}><option value="">Seleccione un insumo…</option>{activeItems.map((x:any)=><option key={x.id} value={x.id}>{x.code} · {x.name}</option>)}</select>{canCreateItem&&<button type="button" disabled={busy} onClick={()=>setAddingItemLine(idx)}>＋ Nuevo</button>}</div></td>
    <td>{item?q(item.availableQuantity,6):'—'}</td><td><input className="qty-input" type="number" min="0.000001" step="0.001" value={line.quantity} onChange={e=>patchLine(idx,'quantity',e.target.value)}/></td>
    <td>{item?.baseUnit||'—'}</td><td><button disabled={busy||draft.lines.length===1} onClick={()=>removeLine(idx)}>Quitar</button></td>
   </tr>})}</tbody></table></div>
   {addingItemLine!==null&&<InventoryItemQuickForm items={items} busy={busy} onCancel={()=>setAddingItemLine(null)} onSave={async (record:any)=>{
    const saved=await onCreateItem(record);
    setDraft((old:any)=>({...old,lines:old.lines.map((line:any,i:number)=>i===addingItemLine?{...line,inventoryItemId:saved.id}:line)}));
    setAddingItemLine(null);
   }}/>}
   {duplicated&&<div className="form-alert" role="alert">El mismo insumo aparece más de una vez.</div>}
   {error&&<div className="form-alert" role="alert">{error}</div>}
   {!activeItems.length&&<div className="form-alert" role="alert">{canCreateItem?'No hay insumos activos. Utilice «＋ Nuevo» para registrar el primero.':'No hay insumos activos. Solicite su registro a Almacén.'}</div>}
   <div className="modal-actions"><button disabled={busy} onClick={onClose}>Cancelar</button><button className="primary" disabled={busy||!valid} onClick={submit}>{busy?'Guardando…':'Crear solicitud en borrador'}</button></div>
  </Modal>
 }

 function RequestModal({dto,canRequest,canApprove,canCreateOrder,busy,onClose,onStatus,onSave,onCreateOrder}:any){
 const h=dto.header,[notes,setNotes]=useState(h.notes||''),[lines,setLines]=useState((dto.items||[]).map((x:any)=>({...x})));
 useEffect(()=>{setNotes(dto.header.notes||'');setLines((dto.items||[]).map((x:any)=>({...x})))},[dto]);
 const isManual=!h.production_order_id;
  function patchLine(id:string,value:string){setLines((current:any[])=>current.map(x=>x.id===id?{...x,requested_quantity:value}:x))}
 return <Modal title={`Solicitud ${h.request_number||h.requestNumber}`} onClose={onClose}><div className="inventory-request-head"><div><small>Origen</small><b>{h.production_number||'Solicitud manual'}</b><span>{h.batch_number||''}</span></div><div><small>Estado</small><b>{requestNames[h.status]||h.status}</b><span>{h.priority}</span></div><div><small>Solicitada por</small><b>{h.requested_by}</b><span>{new Date(h.requested_at).toLocaleString('es-PE')}</span></div></div><div className="tablewrap"><table><thead><tr><th>#</th><th>Insumo</th>{!isManual&&<><th>Requerido OP</th><th>Faltante</th></>}<th>Solicitado</th></tr></thead><tbody>{lines.map((x:any)=><tr key={x.id}><td>{x.line_no}</td><td><b>{x.material_code}</b><small className="cell-sub">{x.material_name}</small></td>{!isManual&&<><td>{q(x.required_quantity,6)} {x.unit}</td><td>{q(x.shortage_quantity,6)} {x.unit}</>}<td>{canRequest&&h.status==='DRAFT'?<input className="qty-input" type="number" min="0.000001" step="0.001" value={x.requested_quantity} onChange={e=>patchLine(x.id,e.target.value)}/>:<b>{q(x.requested_quantity,6)} {x.unit}</b>}</td></tr>)}</tbody></table></div>{canRequest&&h.status==='DRAFT'?<label>Notas / presentación de compra<textarea rows={3} value={notes} onChange={e=>setNotes(e.target.value)} placeholder="Ej.: comprar saco completo de 25 kg"/></label>:h.notes&&<p className="inventory-note">{h.notes}</p>}<div className="modal-actions">{canRequest&&h.status==='DRAFT'&&<button disabled={busy} onClick={()=>onSave(h.id,notes,lines)}>Guardar cantidades</button>}{canRequest&&h.status==='DRAFT'&&<button className="primary" disabled={busy} onClick={()=>onStatus(h.id,'REQUESTED')}>Enviar solicitud</button>}{canRequest&&['DRAFT','REQUESTED'].includes(h.status)&&<button className="danger-link" disabled={busy} onClick={()=>onStatus(h.id,'CANCELLED')}>Cancelar</button>}{canApprove&&h.status==='REQUESTED'&&<button className="primary" disabled={busy} onClick={()=>onStatus(h.id,'APPROVED')}>Aprobar</button>}{canCreateOrder&&h.status==='APPROVED'&&<button className="primary" disabled={busy} onClick={()=>onCreateOrder(h.id)}>Crear orden de compra</button>}<button onClick={onClose}>Cerrar</button></div></Modal>
}

function OrderModal({dto,suppliers,canWrite,canSupplierWrite,onCreateSupplier,canApprove,canReceive,busy,onClose,onSave,onAction,onReceive,onError}:any){
 const h=dto.header;
 const [draft,setDraft]=useState<any>({...h,supplierId:h.supplier_id||'',expectedDate:h.expected_date||'',currency:h.currency||'PEN',paymentTerms:h.payment_terms||'',deliveryAddress:h.delivery_address||'',notes:h.notes||'',discount:h.discount||0,igvRate:h.igv_rate??18});
 const [lines,setLines]=useState<any[]>((dto.items||[]).map((x:any)=>({...x})));
 const [receiving,setReceiving]=useState(false),[addingSupplier,setAddingSupplier]=useState(false);
 useEffect(()=>{const x=dto.header;setDraft({...x,supplierId:x.supplier_id||'',expectedDate:x.expected_date||'',currency:x.currency||'PEN',paymentTerms:x.payment_terms||'',deliveryAddress:x.delivery_address||'',notes:x.notes||'',discount:x.discount||0,igvRate:x.igv_rate??18});setLines((dto.items||[]).map((i:any)=>({...i})))},[dto]);
 const editable=canWrite&&h.status==='DRAFT';
 const patchLine=(id:string,key:string,value:any)=>setLines(xs=>xs.map(x=>x.id===id?{...x,[key]:value}:x));
 const chooseSupplier=(id:string)=>{const s=suppliers.find((x:any)=>x.id===id);setDraft({...draft,supplierId:id,paymentTerms:draft.paymentTerms||s?.paymentTerms||''})};
 const pdf=()=>file(`/reports/purchase-orders/${h.id}.pdf`).catch((e:any)=>onError(e.message));
 const print=()=>file(`/reports/purchase-orders/${h.id}.pdf`,true).catch((e:any)=>onError(e.message));
 return <Modal title={`Orden de compra ${h.order_number}`} onClose={onClose} wide><div className="inventory-request-head"><div><small>Estado</small><b>{orderNames[h.status]||h.status}</b><span>{h.request_number||'Reposición / manual'}</span></div><div><small>Proveedor</small><b>{h.supplier_name||'Pendiente'}</b><span>{h.supplier_ruc||''}</span></div><div><small>Total</small><b>{money(h.total,h.currency)}</b><span>{h.currency}</span></div></div>
 <div className="purchase-toolbar"><button onClick={pdf}>Descargar PDF</button><button onClick={print}>Imprimir</button>{canReceive&&['ISSUED','SENT','PARTIALLY_RECEIVED'].includes(h.status)&&<button className="primary" onClick={()=>setReceiving(true)}>Registrar recepción</button>}</div>
 <div className="formgrid"><label>Proveedor<select disabled={!editable} value={draft.supplierId} onChange={e=>chooseSupplier(e.target.value)}><option value="">Seleccione…</option>{suppliers.filter((s:any)=>s.active!==false).map((s:any)=><option key={s.id} value={s.id}>{s.ruc} · {s.name}</option>)}</select></label><label>Fecha esperada<input disabled={!editable} type="date" value={draft.expectedDate||''} onChange={e=>setDraft({...draft,expectedDate:e.target.value})}/></label><label>Moneda<select disabled={!editable} value={draft.currency} onChange={e=>setDraft({...draft,currency:e.target.value})}><option value="PEN">Soles (PEN)</option><option value="USD">Dólares (USD)</option></select></label><label>Condición de pago<input disabled={!editable} value={draft.paymentTerms||''} onChange={e=>setDraft({...draft,paymentTerms:e.target.value})}/></label><label>Lugar de entrega<input disabled={!editable} value={draft.deliveryAddress||''} onChange={e=>setDraft({...draft,deliveryAddress:e.target.value})}/></label><label>IGV %<input disabled={!editable} type="number" min="0" step="0.01" value={draft.igvRate} onChange={e=>setDraft({...draft,igvRate:e.target.value})}/></label></div>
 {editable&&canSupplierWrite&&<div className="purchase-toolbar"><button type="button" disabled={busy} onClick={()=>setAddingSupplier(v=>!v)}>{addingSupplier?'Ocultar alta de proveedor':'＋ Nuevo proveedor'}</button></div>}
  {addingSupplier&&<SupplierQuickForm busy={busy} onCancel={()=>setAddingSupplier(false)} onSave={async (record:any)=>{
   const saved=await onCreateSupplier(record);
   setDraft((old:any)=>({...old,supplierId:saved.id,paymentTerms:saved.paymentTerms||old.paymentTerms}));
   setAddingSupplier(false);
  }}/>}
  <div className="tablewrap"><table><thead><tr><th>#</th><th>Insumo</th><th>Cantidad</th><th>Und.</th><th>P. unitario</th><th>Descuento línea</th><th>Total</th><th>Recibido</th></tr></thead><tbody>{lines.map((x:any)=><tr key={x.id}><td>{x.line_no}</td><td><b>{x.item_code}</b><small className="cell-sub">{x.item_name}</small></td><td>{editable?<input className="qty-input" type="number" min="0.000001" step="0.001" value={x.quantity} onChange={e=>patchLine(x.id,'quantity',e.target.value)}/>:q(x.quantity,6)}</td><td>{editable?<input className="qty-input" value={x.unit} onChange={e=>patchLine(x.id,'unit',e.target.value)}/>:x.unit}</td><td>{editable?<input className="qty-input" type="number" min="0" step="0.0001" value={x.unit_price} onChange={e=>patchLine(x.id,'unit_price',e.target.value)}/>:money(x.unit_price,h.currency)}</td><td>{editable?<input className="qty-input" type="number" min="0" step="0.01" value={x.line_discount} onChange={e=>patchLine(x.id,'line_discount',e.target.value)}/>:money(x.line_discount,h.currency)}</td><td><b>{money(x.line_total,h.currency)}</b></td><td>{q(x.received_quantity,6)} / {q(x.quantity,6)}</td></tr>)}</tbody></table></div>
 <div className="purchase-totals"><span>Subtotal <b>{money(h.subtotal,h.currency)}</b></span><span>Descuento <b>{money(h.discount,h.currency)}</b></span><span>IGV <b>{money(h.igv_amount,h.currency)}</b></span><span>Total <strong>{money(h.total,h.currency)}</strong></span></div>
 <label>Observaciones<textarea disabled={!editable} rows={3} value={draft.notes||''} onChange={e=>setDraft({...draft,notes:e.target.value})}/></label>
 {(dto.receipts||[]).length>0&&<div className="purchase-receipts"><h4>Recepciones registradas</h4>{dto.receipts.map((r:any)=><div key={r.id}><b>{r.receipt_number}</b><span>{new Date(r.received_at).toLocaleString('es-PE')} · {q(r.total_quantity,6)} · {r.supplier_document_number||'sin documento'}</span></div>)}</div>}
 <div className="modal-actions">{editable&&<button disabled={busy} onClick={()=>onSave(h.id,draft,lines)}>Guardar borrador</button>}{canApprove&&h.status==='DRAFT'&&<button className="primary" disabled={busy} onClick={()=>onAction(h.id,'ISSUE')}>Emitir OC</button>}{canWrite&&h.status==='ISSUED'&&<button className="primary" disabled={busy} onClick={()=>onAction(h.id,'MARK_SENT')}>Marcar enviada</button>}{canWrite&&['DRAFT','ISSUED','SENT'].includes(h.status)&&<button className="danger-link" disabled={busy} onClick={()=>onAction(h.id,'CANCEL')}>Cancelar OC</button>}{canWrite&&h.status==='RECEIVED'&&<button className="primary" disabled={busy} onClick={()=>onAction(h.id,'CLOSE')}>Cerrar OC</button>}<button onClick={onClose}>Cerrar</button></div>
 {receiving&&<ReceiptModal order={dto} busy={busy} onClose={()=>setReceiving(false)} onReceive={(body:any)=>{onReceive(h.id,body).then?.((ok:boolean)=>{if(ok)setReceiving(false)})}}/>}
 </Modal>
}

function ReceiptModal({order,busy,onClose,onReceive}:any){
 const h=order.header;
 const [docType,setDocType]=useState('GUIA'),[docNumber,setDocNumber]=useState(''),[notes,setNotes]=useState('');
 const [lines,setLines]=useState((order.items||[]).map((x:any)=>({purchaseOrderItemId:x.id,item_name:x.item_name,pending:Number(x.pending_quantity||0),quantity:'',lotCode:'',unitCost:x.unit_price})));
 const patch=(id:string,key:string,value:any)=>setLines((xs:any[])=>xs.map(x=>x.purchaseOrderItemId===id?{...x,[key]:value}:x));
 const submit=()=>onReceive({supplierDocumentType:docType,supplierDocumentNumber:docNumber,notes,lines:lines.filter((x:any)=>Number(x.quantity)>0).map((x:any)=>({purchaseOrderItemId:x.purchaseOrderItemId,quantity:Number(x.quantity),lotCode:x.lotCode,unitCost:Number(x.unitCost||0)}))});
 return <div className="nested-modal"><div className="panelhead"><div><h4>Recepción contra {h.order_number}</h4><p>El ingreso actualizará Kardex y volverá a calcular la reserva de la OP vinculada.</p></div><button onClick={onClose}>✕</button></div><div className="formgrid"><label>Documento proveedor<select value={docType} onChange={e=>setDocType(e.target.value)}><option value="GUIA">Guía</option><option value="FACTURA">Factura</option><option value="BOLETA">Boleta</option><option value="OTRO">Otro</option></select></label><label>Número<input value={docNumber} onChange={e=>setDocNumber(e.target.value)}/></label></div><div className="tablewrap"><table><thead><tr><th>Insumo</th><th>Pendiente</th><th>Recibir ahora</th><th>Lote</th><th>Costo unit.</th></tr></thead><tbody>{lines.map((x:any)=><tr key={x.purchaseOrderItemId}><td>{x.item_name}</td><td>{q(x.pending,6)}</td><td><input className="qty-input" type="number" min="0" max={x.pending} step="0.001" value={x.quantity} onChange={e=>patch(x.purchaseOrderItemId,'quantity',e.target.value)}/></td><td><input className="qty-input" value={x.lotCode} onChange={e=>patch(x.purchaseOrderItemId,'lotCode',e.target.value)}/></td><td><input className="qty-input" type="number" min="0" step="0.0001" value={x.unitCost} onChange={e=>patch(x.purchaseOrderItemId,'unitCost',e.target.value)}/></td></tr>)}</tbody></table></div><label>Observaciones<textarea rows={2} value={notes} onChange={e=>setNotes(e.target.value)}/></label><div className="modal-actions"><button onClick={onClose}>Cancelar</button><button className="primary" disabled={busy||!lines.some((x:any)=>Number(x.quantity)>0)} onClick={submit}>Registrar recepción</button></div></div>
}


function SupplierQuickForm({busy,onCancel,onSave}:any){
 const [data,setData]=useState<any>({ruc:'',name:'',contactName:'',phone:'',email:'',address:'',paymentTerms:'',bankReference:''});
 const [saving,setSaving]=useState(false),[error,setError]=useState('');
 const update=(key:string,value:string)=>{
  let v=value;
  if(key==='ruc')v=digitsOnly(value,11);
  if(key==='phone')v=digitsOnly(value,15);
  if(key==='contactName')v=personText(value,180);
  if(key==='email')v=emailText(value,160);
  const lengths:Record<string,number>={name:180,address:250,paymentTerms:120,bankReference:250};
  if(lengths[key])v=v.slice(0,lengths[key]);
  setError('');setData((current:any)=>({...current,[key]:v}));
 };
 const submit=async()=>{
  if(!validDigits(data.ruc,11,11)){setError('El RUC debe contener exactamente 11 dígitos.');return}
  if(!String(data.name||'').trim()){setError('Ingrese la razón social del proveedor.');return}
  if(data.contactName&&!validPerson(data.contactName)){setError('Revise el nombre del contacto.');return}
  if(data.phone&&!validDigits(data.phone,7,15)){setError('El teléfono debe tener de 7 a 15 dígitos.');return}
  if(!validEmail(data.email)){setError('Ingrese un correo electrónico válido.');return}
  try{setSaving(true);await onSave(data)}
  catch(e:any){setError(e.message||'No se pudo guardar el proveedor.')}
  finally{setSaving(false)}
 };
 return <div className="nested-modal">
  <div className="panelhead"><div><h4>Nuevo proveedor</h4><p>Se registrará en el maestro y quedará seleccionado en la OC.</p></div></div>
  <div className="formgrid">
   <label>RUC *<input inputMode="numeric" pattern="[0-9]*" maxLength={11} value={data.ruc} onChange={e=>update('ruc',e.target.value)}/></label>
   <label>Razón social *<input maxLength={180} value={data.name} onChange={e=>update('name',e.target.value)}/></label>
   <label>Contacto<input maxLength={180} value={data.contactName} onChange={e=>update('contactName',e.target.value)}/></label>
   <label>Teléfono<input inputMode="numeric" pattern="[0-9]*" maxLength={15} value={data.phone} onChange={e=>update('phone',e.target.value)}/></label>
   <label>Correo<input type="email" maxLength={160} value={data.email} onChange={e=>update('email',e.target.value)}/></label>
   <label>Dirección<input maxLength={250} value={data.address} onChange={e=>update('address',e.target.value)}/></label>
   <label>Condición de pago<input maxLength={120} value={data.paymentTerms} onChange={e=>update('paymentTerms',e.target.value)} placeholder="Contado, crédito 30 días…"/></label>
   <label>Referencia bancaria<input maxLength={250} value={data.bankReference} onChange={e=>update('bankReference',e.target.value)}/></label>
  </div>
  {error&&<div className="form-alert" role="alert">{error}</div>}
  <div className="modal-actions"><button disabled={busy||saving} onClick={onCancel}>Cancelar</button><button className="primary" disabled={busy||saving||!validDigits(data.ruc,11,11)||!String(data.name||'').trim()} onClick={submit}>{saving?'Registrando…':'Guardar y seleccionar'}</button></div>
 </div>;
}

function ManualOrderModal({draft,setDraft,items,suppliers,canCreateItem,onCreateItem,canSupplierWrite,onCreateSupplier,busy,onClose,onCreate}:any){
  const [addingItemLine,setAddingItemLine]=useState<number|null>(null);
 const [addingSupplier,setAddingSupplier]=useState(false);
 const patchLine=(idx:number,key:string,value:any)=>setDraft({...draft,lines:draft.lines.map((x:any,i:number)=>i===idx?{...x,[key]:value}:x)});
 const add=()=>{const first=items.find((x:any)=>x.active!==false);setDraft((old:any)=>({...old,lines:[...old.lines,{inventoryItemId:first?.id||'',quantity:'',unit:first?.baseUnit||'KG',unitPrice:'',lineDiscount:0}]}))};
 const remove=(idx:number)=>setDraft({...draft,lines:draft.lines.filter((_:any,i:number)=>i!==idx)});
 const chooseItem=(idx:number,id:string)=>{const item=items.find((x:any)=>x.id===id);setDraft({...draft,lines:draft.lines.map((x:any,i:number)=>i===idx?{...x,inventoryItemId:id,unit:item?.baseUnit||'KG'}:x)})};
 const chooseSupplier=(id:string)=>{const s=suppliers.find((x:any)=>x.id===id);setDraft({...draft,supplierId:id,paymentTerms:s?.paymentTerms||draft.paymentTerms})};
 return <Modal title="Nueva orden de compra manual / reposición" onClose={onClose} wide><div className="formgrid"><label>Proveedor<select value={draft.supplierId} onChange={e=>chooseSupplier(e.target.value)}><option value="">Seleccione…</option>{suppliers.filter((s:any)=>s.active!==false).map((s:any)=><option key={s.id} value={s.id}>{s.ruc} · {s.name}</option>)}</select></label><label>Fecha esperada<input type="date" value={draft.expectedDate} onChange={e=>setDraft({...draft,expectedDate:e.target.value})}/></label><label>Moneda<select value={draft.currency} onChange={e=>setDraft({...draft,currency:e.target.value})}><option value="PEN">Soles</option><option value="USD">Dólares</option></select></label><label>Condición de pago<input value={draft.paymentTerms} onChange={e=>setDraft({...draft,paymentTerms:e.target.value})}/></label><label>Lugar entrega<input value={draft.deliveryAddress} onChange={e=>setDraft({...draft,deliveryAddress:e.target.value})}/></label><label>IGV %<input type="number" min="0" step="0.01" value={draft.igvRate} onChange={e=>setDraft({...draft,igvRate:e.target.value})}/></label></div>{true&&canSupplierWrite&&<div className="purchase-toolbar"><button type="button" disabled={busy} onClick={()=>setAddingSupplier(v=>!v)}>{addingSupplier?'Ocultar alta de proveedor':'＋ Nuevo proveedor'}</button></div>}
  {addingSupplier&&<SupplierQuickForm busy={busy} onCancel={()=>setAddingSupplier(false)} onSave={async (record:any)=>{
   const saved=await onCreateSupplier(record);
   setDraft((old:any)=>({...old,supplierId:saved.id,paymentTerms:saved.paymentTerms||old.paymentTerms}));
   setAddingSupplier(false);
  }}/>}
  <div className="panelhead"><h4>Insumos</h4><button onClick={add}>＋ Línea</button></div><div className="tablewrap"><table><thead><tr><th>Insumo</th><th>Cantidad</th><th>Und.</th><th>P. unitario</th><th>Desc. línea</th><th/></tr></thead><tbody>{draft.lines.map((x:any,idx:number)=><tr key={idx}><td><div className="purchase-item-picker"><select value={x.inventoryItemId} onChange={e=>chooseItem(idx,e.target.value)}><option value="">Seleccione un insumo…</option>{items.filter((i:any)=>i.active!==false).map((i:any)=><option key={i.id} value={i.id}>{i.code} · {i.name}</option>)}</select>{canCreateItem&&<button type="button" disabled={busy} onClick={()=>setAddingItemLine(idx)}>＋ Nuevo</button>}</div></td><td><input className="qty-input" type="number" min="0.000001" step="0.001" value={x.quantity} onChange={e=>patchLine(idx,'quantity',e.target.value)}/></td><td><input className="qty-input" value={x.unit} onChange={e=>patchLine(idx,'unit',e.target.value)}/></td><td><input className="qty-input" type="number" min="0" step="0.0001" value={x.unitPrice} onChange={e=>patchLine(idx,'unitPrice',e.target.value)}/></td><td><input className="qty-input" type="number" min="0" step="0.01" value={x.lineDiscount} onChange={e=>patchLine(idx,'lineDiscount',e.target.value)}/></td><td><button disabled={draft.lines.length===1} onClick={()=>remove(idx)}>Quitar</button></td></tr>)}</tbody></table></div>
  {addingItemLine!==null&&<InventoryItemQuickForm items={items} busy={busy} onCancel={()=>setAddingItemLine(null)} onSave={async (record:any)=>{
   const saved=await onCreateItem(record);
   setDraft((old:any)=>({...old,lines:old.lines.map((line:any,i:number)=>i===addingItemLine?{...line,inventoryItemId:saved.id,unit:saved.baseUnit||'KG'}:line)}));
   setAddingItemLine(null);
  }}/>}
  {!items.some((i:any)=>i.active!==false)&&<div className="form-alert" role="alert">{canCreateItem?'No hay insumos activos. Utilice «＋ Nuevo» para registrar el primero.':'No hay insumos activos. Solicite su registro a Almacén.'}</div>}
  <label>Observaciones<textarea rows={3} value={draft.notes} onChange={e=>setDraft({...draft,notes:e.target.value})}/></label><div className="modal-actions"><button onClick={onClose}>Cancelar</button><button className="primary" disabled={busy||!draft.supplierId||!draft.lines.length||draft.lines.some((x:any)=>!x.inventoryItemId||Number(x.quantity)<=0)} onClick={()=>onCreate(draft)}>Crear borrador de OC</button></div></Modal>
}
