package com.ngtintoreria.erp.company;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/company")
public class CompanyController {
 private final CompanyProfileRepository repo;
 private final AuditService audit;
 private final CompanyLogoStorageService logos;
 public CompanyController(CompanyProfileRepository repo,AuditService audit,CompanyLogoStorageService logos){this.repo=repo;this.audit=audit;this.logos=logos;}

 @GetMapping @PreAuthorize("hasAuthority('COMPANY_READ')")
 public CompanyProfile get(){return repo.findAll().stream().findFirst().orElseGet(()->repo.save(new CompanyProfile()));}

 @PutMapping @PreAuthorize("hasAuthority('COMPANY_WRITE')")
 public CompanyProfile update(@RequestBody CompanyProfile in){
   CompanyProfile c=get();
   c.setTradeName(in.getTradeName());c.setLegalName(in.getLegalName());c.setRuc(in.getRuc());c.setFiscalAddress(in.getFiscalAddress());
   c.setPhone(in.getPhone());c.setEmail(in.getEmail());c.setBaseCurrency(in.getBaseCurrency());c.setIgvRate(in.getIgvRate());
   // logoUrl se conserva por compatibilidad, pero el logo cargado localmente tiene prioridad en documentos y reportes.
   c.setLogoUrl(in.getLogoUrl());
   c=repo.save(c);audit.record("EMPRESA_ACTUALIZADA","Empresa",c.getId().toString(),c.getTradeName());return c;
 }

 @PostMapping(value="/logo",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 @PreAuthorize("hasAuthority('COMPANY_WRITE')")
 public CompanyProfile uploadLogo(@RequestPart("file") MultipartFile file, Authentication auth){
   CompanyProfile c=get();String old=c.getLogoStorageName();
   CompanyLogoStorageService.StoredLogo stored=logos.save(file);
   c.setLogoStorageName(stored.storageName());c.setLogoContentType(stored.contentType());c.setLogoOriginalName(stored.originalName());c.setLogoUpdatedAt(OffsetDateTime.now());
   c.setLogoUrl("/api/company/logo");c=repo.save(c);
   if(old!=null&&!old.equals(stored.storageName()))logos.delete(old);
   audit.record("LOGO_EMPRESA_ACTUALIZADO","Empresa",c.getId().toString(),stored.originalName()+" · "+auth.getName());
   return c;
 }

 @DeleteMapping("/logo") @PreAuthorize("hasAuthority('COMPANY_WRITE')")
 public CompanyProfile removeLogo(Authentication auth){
   CompanyProfile c=get();String old=c.getLogoStorageName();
   c.setLogoStorageName(null);c.setLogoContentType(null);c.setLogoOriginalName(null);c.setLogoUpdatedAt(null);c.setLogoUrl(null);c=repo.save(c);
   logos.delete(old);audit.record("LOGO_EMPRESA_ELIMINADO","Empresa",c.getId().toString(),auth.getName());return c;
 }

 @GetMapping("/logo") @PreAuthorize("isAuthenticated()")
 public ResponseEntity<Resource> logo(){
   CompanyProfile c=get();if(c.getLogoStorageName()==null)throw new NoSuchElementException("Logo no configurado");
   Resource r=logos.load(c.getLogoStorageName());
   MediaType type=MediaType.parseMediaType(c.getLogoContentType()==null?MediaType.APPLICATION_OCTET_STREAM_VALUE:c.getLogoContentType());
   return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noCache()).body(r);
 }

 @GetMapping("/branding") @PreAuthorize("isAuthenticated()")
 public Map<String,Object> branding(){
   CompanyProfile c=get();
   return Map.of(
      "tradeName",c.getTradeName()==null?"":c.getTradeName(),
      "legalName",c.getLegalName()==null?"":c.getLegalName(),
      "ruc",c.getRuc()==null?"":c.getRuc(),
      "fiscalAddress",c.getFiscalAddress()==null?"":c.getFiscalAddress(),
      "phone",c.getPhone()==null?"":c.getPhone(),
      "email",c.getEmail()==null?"":c.getEmail(),
      "hasLogo",c.getLogoStorageName()!=null,
      "logoUpdatedAt",c.getLogoUpdatedAt()==null?"":c.getLogoUpdatedAt().toString()
   );
 }
}
