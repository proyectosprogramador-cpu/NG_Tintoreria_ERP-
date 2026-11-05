export const EMAIL_RE=/^[^\s@]+@[^\s@]+\.[^\s@]+$/;
export const PERSON_RE=/^[A-Za-zÁÉÍÓÚÜÑáéíóúüñ.' -]+$/;

export function digitsOnly(value:string,max=30){
  return String(value??'').replace(/\D/g,'').slice(0,max);
}
export function personText(value:string,max=180){
  return String(value??'').replace(/[^A-Za-zÁÉÍÓÚÜÑáéíóúüñ.' -]/g,'').slice(0,max);
}
export function codeText(value:string,max=60){
  return String(value??'').replace(/[^A-Za-z0-9._\-/]/g,'').toUpperCase().slice(0,max);
}
export function plateText(value:string,max=10){
  return String(value??'').replace(/[^A-Za-z0-9-]/g,'').toUpperCase().slice(0,max);
}
export function emailText(value:string,max=160){
  return String(value??'').replace(/\s/g,'').slice(0,max);
}
export function validEmail(value:any){
  const s=String(value??'').trim();
  return !s || EMAIL_RE.test(s);
}
export function validPerson(value:any){
  const s=String(value??'').trim();
  return !s || PERSON_RE.test(s);
}
export function validDigits(value:any,min:number,max:number){
  const s=String(value??'').trim();
  return /^\d+$/.test(s)&&s.length>=min&&s.length<=max;
}
export function preventInvalidNumberKey(e:any){
  const el=e.target as HTMLInputElement;
  if(!el || el.type!=='number') return;
  if(['e','E','+'].includes(e.key)) e.preventDefault();
  if(e.key==='-' && el.min!=='' && Number(el.min)>=0) e.preventDefault();
}
