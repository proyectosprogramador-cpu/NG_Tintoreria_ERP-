package com.ngtintoreria.erp.procurement;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {
    private final SupplierRepository repo;
    private final AuditService audit;

    public SupplierController(SupplierRepository repo,AuditService audit){this.repo=repo;this.audit=audit;}

    @GetMapping
    @PreAuthorize("hasAuthority('SUPPLIERS_READ')")
    public List<Supplier> list(){
        return repo.findAll().stream().sorted(Comparator.comparing(Supplier::getName,String.CASE_INSENSITIVE_ORDER)).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SUPPLIERS_WRITE')")
    public Supplier create(@RequestBody Supplier s){
        normalizeAndValidate(s);
        if(repo.findByRuc(s.getRuc()).isPresent()) throw new IllegalArgumentException("El RUC "+s.getRuc()+" ya está registrado en otro proveedor.");
        Supplier out=repo.save(s);
        audit.record("PROVEEDOR_CREADO","Proveedores",out.getId().toString(),out.getName());
        return out;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPPLIERS_WRITE')")
    public Supplier update(@PathVariable UUID id,@RequestBody Supplier in){
        normalizeAndValidate(in);
        Supplier same=repo.findByRuc(in.getRuc()).orElse(null);
        if(same!=null&&!same.getId().equals(id)) throw new IllegalArgumentException("El RUC "+in.getRuc()+" ya está registrado en otro proveedor.");
        Supplier s=repo.findById(id).orElseThrow();
        s.setRuc(in.getRuc());s.setName(in.getName());s.setContactName(in.getContactName());s.setEmail(in.getEmail());s.setPhone(in.getPhone());s.setPaymentTerms(in.getPaymentTerms());s.setBankReference(in.getBankReference());s.setAddress(in.getAddress());s.setActive(in.isActive());
        s=repo.save(s);audit.record("PROVEEDOR_ACTUALIZADO","Proveedores",s.getId().toString(),s.getName());return s;
    }

    private static void normalizeAndValidate(Supplier s){
        String ruc=clean(s.getRuc()),name=clean(s.getName()),contact=clean(s.getContactName()),email=clean(s.getEmail()),phone=clean(s.getPhone());
        if(ruc==null||!ruc.matches("\\d{11}")) throw new IllegalArgumentException("El RUC del proveedor debe tener exactamente 11 dígitos.");
        if(name==null||name.isBlank()) throw new IllegalArgumentException("Ingrese la razón social del proveedor.");
        if(contact!=null&&!contact.isBlank()&&!contact.matches("[A-Za-zÁÉÍÓÚÜÑáéíóúüñ.' -]+")) throw new IllegalArgumentException("El nombre del contacto contiene caracteres no permitidos.");
        if(email!=null&&!email.isBlank()&&!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Ingrese un correo electrónico válido.");
        if(phone!=null&&!phone.isBlank()&&!phone.matches("\\d{7,15}")) throw new IllegalArgumentException("El teléfono debe contener entre 7 y 15 dígitos.");
        s.setRuc(ruc);s.setName(name);s.setContactName(contact);s.setEmail(email);s.setPhone(phone);s.setAddress(clean(s.getAddress()));s.setPaymentTerms(clean(s.getPaymentTerms()));s.setBankReference(clean(s.getBankReference()));
    }
    private static String clean(String v){return v==null?null:v.trim();}
}
