package com.ngtintoreria.erp.crm;
import com.ngtintoreria.erp.audit.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/clients")
public class ClientController {
 private final ClientRepository repo; private final AuditService audit;
 public ClientController(ClientRepository repo,AuditService audit){this.repo=repo;this.audit=audit;}
 @GetMapping @PreAuthorize("hasAuthority('CLIENTS_READ')") public List<Client> list(){return repo.findAll().stream().sorted(Comparator.comparing(Client::getName,String.CASE_INSENSITIVE_ORDER)).toList();}
 @PostMapping @PreAuthorize("hasAuthority('CLIENTS_WRITE')") public Client create(@RequestBody Client c){if(repo.findByDocumentNumber(c.getDocumentNumber()).isPresent())throw new IllegalArgumentException("Documento de cliente ya registrado");Client out=repo.save(c);audit.record("CLIENTE_CREADO","Clientes",out.getId().toString(),out.getName());return out;}
 @PutMapping("/{id}") @PreAuthorize("hasAuthority('CLIENTS_WRITE')") public Client update(@PathVariable UUID id,@RequestBody Client in){Client c=repo.findById(id).orElseThrow();c.setDocumentNumber(in.getDocumentNumber());c.setName(in.getName());c.setContactName(in.getContactName());c.setWhatsapp(in.getWhatsapp());c.setEmail(in.getEmail());c.setAddress(in.getAddress());c.setCreditDays(in.getCreditDays());c.setActive(in.isActive());c=repo.save(c);audit.record("CLIENTE_ACTUALIZADO","Clientes",c.getId().toString(),c.getName());return c;}
}
