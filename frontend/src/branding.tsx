import React,{useEffect,useState} from 'react';

const API='/api';
const token=()=>localStorage.getItem('ngerp_token')||'';
export type CompanyBrand={tradeName:string;legalName:string;ruc:string;fiscalAddress:string;phone:string;email:string;hasLogo:boolean;logoUpdatedAt:string;logoObjectUrl?:string};
const fallback:CompanyBrand={tradeName:'NG Tintorería',legalName:'',ruc:'',fiscalAddress:'',phone:'',email:'',hasLogo:false,logoUpdatedAt:''};
let cached:CompanyBrand|null=null;
let loading:Promise<CompanyBrand>|null=null;

async function authFetch(path:string,opts:RequestInit={}){const headers:any={...(opts.headers||{})};if(token())headers.Authorization=`Bearer ${token()}`;const r=await fetch(API+path,{...opts,headers});if(!r.ok){const t=await r.text();throw new Error(t||`HTTP ${r.status}`)}return r}
function clearCompanyBrandCache(){if(cached?.logoObjectUrl)URL.revokeObjectURL(cached.logoObjectUrl);cached=null;loading=null}
export function invalidateCompanyBrand(){clearCompanyBrandCache();window.dispatchEvent(new Event('ng-company-brand-changed'))}
export async function loadCompanyBrand(force=false):Promise<CompanyBrand>{
 if(force)clearCompanyBrandCache();if(cached)return cached;if(loading)return loading;
 loading=(async()=>{try{const r=await authFetch('/company/branding');const b={...fallback,...await r.json()} as CompanyBrand;if(b.hasLogo){try{const lr=await authFetch('/company/logo');b.logoObjectUrl=URL.createObjectURL(await lr.blob())}catch{b.hasLogo=false}}cached=b;return b}catch{return fallback}finally{loading=null}})();return loading;
}
export function useCompanyBrand(){const [brand,setBrand]=useState<CompanyBrand>(cached||fallback);useEffect(()=>{let active=true;const run=()=>loadCompanyBrand().then(x=>{if(active)setBrand(x)});void run();const refresh=()=>{loadCompanyBrand(true).then(x=>{if(active)setBrand(x)})};window.addEventListener('ng-company-brand-changed',refresh);return()=>{active=false;window.removeEventListener('ng-company-brand-changed',refresh)}},[]);return brand}
function initials(name:string){return name.trim().split(/\s+/).filter(Boolean).slice(0,2).map(x=>x[0]?.toUpperCase()).join('')||'NG'}
export function DocumentBrand({subtitle}:{subtitle:string}){const b=useCompanyBrand();const contact=[b.phone,b.email].filter(Boolean).join(' · ');return <div className="document-brand-wrap"><div className="doc-brand company-doc-brand">{b.logoObjectUrl?<img src={b.logoObjectUrl} alt={`Logo ${b.tradeName}`}/>:<span>{initials(b.tradeName)}</span>}</div><div className="document-brand-text"><h2>{b.tradeName||'NG Tintorería'}</h2><p>{subtitle}</p>{(b.ruc||b.legalName)&&<small>{b.legalName||''}{b.legalName&&b.ruc?' · ':''}{b.ruc?`RUC ${b.ruc}`:''}</small>}{(b.fiscalAddress||contact)&&<small>{b.fiscalAddress||''}{b.fiscalAddress&&contact?' · ':''}{contact}</small>}</div></div>}

export async function uploadCompanyLogo(file:File){const data=new FormData();data.append('file',file);const r=await authFetch('/company/logo',{method:'POST',body:data});const body=await r.json();invalidateCompanyBrand();return body}
export async function removeCompanyLogo(){const r=await authFetch('/company/logo',{method:'DELETE'});const body=await r.json();invalidateCompanyBrand();return body}
