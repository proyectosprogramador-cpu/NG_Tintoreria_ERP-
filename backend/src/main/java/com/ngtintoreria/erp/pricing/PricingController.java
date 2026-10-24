package com.ngtintoreria.erp.pricing;
import com.ngtintoreria.erp.audit.AuditService; import jakarta.transaction.Transactional; import org.springframework.security.access.prepost.PreAuthorize; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequestMapping("/api/price-lists")
public class PricingController {
 private final PriceListRepository lists; private final PriceRuleRepository rules; private final AuditService audit;
 public PricingController(PriceListRepository lists,PriceRuleRepository rules,AuditService audit){this.lists=lists;this.rules=rules;this.audit=audit;}
 public record PriceListRequest(PriceList header,List<PriceRule> rules){} public record PriceListDto(PriceList header,List<PriceRule> rules){}
 @GetMapping @PreAuthorize("hasAuthority('PRICING_READ')") public List<PriceListDto> all(){return lists.findAll().stream().sorted(Comparator.comparing(PriceList::getPriority).thenComparing(PriceList::getName)).map(this::dto).toList();}
 @PostMapping @Transactional @PreAuthorize("hasAuthority('PRICING_WRITE')") public PriceListDto create(@RequestBody PriceListRequest req){if(lists.existsByCode(req.header().getCode()))throw new IllegalArgumentException("Código de lista ya existe");PriceList h=lists.save(req.header());saveRules(h.getId(),req.rules());audit.record("LISTA_PRECIO_CREADA","Precios",h.getId().toString(),h.getCode());return dto(h);}
 @PutMapping("/{id}") @Transactional @PreAuthorize("hasAuthority('PRICING_WRITE')") public PriceListDto update(@PathVariable UUID id,@RequestBody PriceListRequest req){PriceList h=lists.findById(id).orElseThrow();PriceList in=req.header();h.setName(in.getName());h.setClientId(in.getClientId());h.setCurrency(in.getCurrency());h.setValidFrom(in.getValidFrom());h.setValidTo(in.getValidTo());h.setPriority(in.getPriority());h.setActive(in.isActive());h=lists.save(h);rules.deleteByPriceListId(id);saveRules(id,req.rules());audit.record("LISTA_PRECIO_ACTUALIZADA","Precios",id.toString(),h.getCode());return dto(h);}
 private void saveRules(UUID id,List<PriceRule> xs){if(xs==null)return;for(PriceRule r:xs){r.setPriceListId(id);rules.save(r);}}
 private PriceListDto dto(PriceList h){return new PriceListDto(h,rules.findByPriceListId(h.getId()));}
}
