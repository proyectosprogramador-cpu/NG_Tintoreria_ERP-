package com.ngtintoreria.erp.procurement;
import com.ngtintoreria.erp.audit.AuditService; import org.springframework.security.access.prepost.PreAuthorize; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequestMapping("/api/suppliers")
public class SupplierController {
 private final SupplierRepository repo; private final AuditService audit;
 public SupplierController(SupplierRepository repo,AuditService audit){this.repo=repo;this.audit=audit;}
 @GetMapping @PreAuthorize("hasAuthority('SUPPLIERS_READ')") public List<Supplier> list(){return repo.findAll().stream().sorted(Comparator.comparing(Supplier::getName,String.CASE_INSENSITIVE_ORDER)).toList();}
 @PostMapping @PreAuthorize("hasAuthority('SUPPLIERS_WRITE')") public Supplier create(@RequestBody Supplier s){if(repo.findByRuc(s.getRuc()).isPresent())throw new IllegalArgumentException("RUC de proveedor ya registrado");Supplier out=repo.save(s);audit.record("PROVEEDOR_CREADO","Proveedores",out.getId().toString(),out.getName());return out;}
 @PutMapping("/{id}") @PreAuthorize("hasAuthority('SUPPLIERS_WRITE')") public Supplier update(@PathVariable UUID id,@RequestBody Supplier in){Supplier s=repo.findById(id).orElseThrow();s.setRuc(in.getRuc());s.setName(in.getName());s.setContactName(in.getContactName());s.setEmail(in.getEmail());s.setPhone(in.getPhone());s.setPaymentTerms(in.getPaymentTerms());s.setBankReference(in.getBankReference());s.setAddress(in.getAddress());s.setActive(in.isActive());s=repo.save(s);audit.record("PROVEEDOR_ACTUALIZADO","Proveedores",s.getId().toString(),s.getName());return s;}
}
