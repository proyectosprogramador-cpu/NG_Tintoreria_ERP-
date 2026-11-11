package com.ngtintoreria.erp.crm;

import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/clients")
public class ClientController {
    private final ClientRepository repo;
    private final AuditService audit;

    public ClientController(ClientRepository repo,AuditService audit){
        this.repo=repo;
        this.audit=audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CLIENTS_READ')")
    public List<Client> list(){
        return repo.findAll().stream()
            .sorted(Comparator.comparing(Client::getName,String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CLIENTS_WRITE')")
    public Client create(@RequestBody Client input){
        normalizeAndValidate(input);
        if(repo.findByDocumentNumber(input.getDocumentNumber()).isPresent()){
            throw new IllegalArgumentException("El RUC o documento "+input.getDocumentNumber()+" ya está registrado en otro cliente.");
        }
        Client out=repo.save(input);
        audit.record("CLIENTE_CREADO","Clientes",out.getId().toString(),out.getName());
        return out;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CLIENTS_WRITE')")
    public Client update(@PathVariable UUID id,@RequestBody Client input){
        normalizeAndValidate(input);
        Client existingWithDocument=repo.findByDocumentNumber(input.getDocumentNumber()).orElse(null);
        if(existingWithDocument!=null && !existingWithDocument.getId().equals(id)){
            throw new IllegalArgumentException("El RUC o documento "+input.getDocumentNumber()+" ya está registrado en otro cliente.");
        }

        Client c=repo.findById(id).orElseThrow();
        c.setDocumentNumber(input.getDocumentNumber());
        c.setName(input.getName());
        c.setContactName(clean(input.getContactName()));
        c.setWhatsapp(clean(input.getWhatsapp()));
        c.setEmail(clean(input.getEmail()));
        c.setAddress(clean(input.getAddress()));
        c.setCreditDays(input.getCreditDays());
        c.setActive(input.isActive());
        c=repo.save(c);
        audit.record("CLIENTE_ACTUALIZADO","Clientes",c.getId().toString(),c.getName());
        return c;
    }

    private static void normalizeAndValidate(Client c){
        String document=clean(c.getDocumentNumber());
        String name=clean(c.getName());
        String email=clean(c.getEmail());

        if(document==null || document.isBlank()){
            throw new IllegalArgumentException("Ingrese el RUC o documento del cliente.");
        }
        if(!document.matches("\\d+")){
            throw new IllegalArgumentException("El RUC o documento debe contener solo números.");
        }
        if(document.length()<8 || document.length()>11){
            throw new IllegalArgumentException("El RUC o documento debe tener entre 8 y 11 dígitos.");
        }
        if(name==null || name.isBlank()){
            throw new IllegalArgumentException("Ingrese la razón social o nombre del cliente.");
        }
        if(c.getCreditDays()<0){
            throw new IllegalArgumentException("Los días de crédito no pueden ser negativos.");
        }
        if(email!=null && !email.isBlank() && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")){
            throw new IllegalArgumentException("Ingrese un correo electrónico válido.");
        }

        c.setDocumentNumber(document);
        c.setName(name);
        c.setContactName(clean(c.getContactName()));
        c.setWhatsapp(clean(c.getWhatsapp()));
        c.setEmail(email);
        c.setAddress(clean(c.getAddress()));
    }

    private static String clean(String value){
        return value==null?null:value.trim();
    }
}
