package com.ngtintoreria.erp.pricing;

import com.ngtintoreria.erp.masterdata.DyeColorRepository;
import com.ngtintoreria.erp.masterdata.FinishTypeRepository;
import com.ngtintoreria.erp.masterdata.ProcessType;
import com.ngtintoreria.erp.masterdata.ProcessTypeRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/pricing")
public class PriceRecommendationController {
    private final PriceListRepository lists;
    private final PriceRuleRepository rules;
    private final ProcessTypeRepository processes;
    private final FinishTypeRepository finishes;
    private final DyeColorRepository colors;

    public PriceRecommendationController(PriceListRepository lists, PriceRuleRepository rules,
                                         ProcessTypeRepository processes, FinishTypeRepository finishes,
                                         DyeColorRepository colors) {
        this.lists=lists; this.rules=rules; this.processes=processes; this.finishes=finishes; this.colors=colors;
    }

    public record Recommendation(String source, UUID priceListId, UUID priceRuleId, String currency,
                                 BigDecimal unitPrice, BigDecimal surchargePct, BigDecimal discountPct,
                                 BigDecimal minimumBillableQuantity, BigDecimal billableQuantity,
                                 String warning) {}
    private record Candidate(PriceList list, PriceRule rule, int specificity, boolean clientSpecific) {}

    @GetMapping("/recommendation")
    @PreAuthorize("hasAuthority('PRICING_READ')")
    public Recommendation recommendation(@RequestParam(required=false) UUID clientId,
                                         @RequestParam(required=false) UUID fabricId,
                                         @RequestParam(required=false) UUID colorId,
                                         @RequestParam UUID processId,
                                         @RequestParam(required=false) UUID finishId,
                                         @RequestParam(defaultValue="kg") String unit,
                                         @RequestParam BigDecimal quantity,
                                         @RequestParam(required=false) LocalDate date) {
        if(quantity==null || quantity.signum()<=0) throw new IllegalArgumentException("La cantidad debe ser mayor que cero");
        LocalDate effectiveDate=date==null?LocalDate.now():date;
        List<Candidate> candidates=new ArrayList<>();
        for(PriceList list:lists.findByActiveTrue()){
            if(list.getValidFrom()!=null && effectiveDate.isBefore(list.getValidFrom()))continue;
            if(list.getValidTo()!=null && effectiveDate.isAfter(list.getValidTo()))continue;
            if(list.getClientId()!=null && !Objects.equals(list.getClientId(),clientId))continue;
            for(PriceRule rule:rules.findByPriceListId(list.getId())){
                if(!rule.isActive() || !matches(rule,fabricId,colorId,processId,finishId,unit,quantity))continue;
                int specificity=(rule.getFabricId()!=null?1:0)+(rule.getColorId()!=null?1:0)+(rule.getProcessId()!=null?1:0)+(rule.getFinishId()!=null?1:0);
                candidates.add(new Candidate(list,rule,specificity,list.getClientId()!=null));
            }
        }
        candidates.sort(Comparator.comparing(Candidate::clientSpecific).reversed()
                .thenComparing(c->c.list().getPriority()).thenComparing(Candidate::specificity,Comparator.reverseOrder()));
        ProcessType process=processes.findById(processId).orElseThrow();
        BigDecimal minimum=nvl(process.getMinimumLot());
        if(!candidates.isEmpty()){
            Candidate c=candidates.get(0); PriceRule r=c.rule();
            if(r.getMinQty()!=null && r.getMinQty().compareTo(minimum)>0)minimum=r.getMinQty();
            BigDecimal billable=quantity.max(minimum);
            String warning=billable.compareTo(quantity)>0?"Cantidad menor al lote mínimo; se sugiere facturar "+billable.stripTrailingZeros().toPlainString()+" "+unit:"";
            return new Recommendation("Lista "+c.list().getCode()+" · "+c.list().getName(),c.list().getId(),r.getId(),c.list().getCurrency(),
                    money4(r.getUnitPrice()),nvl(r.getSurchargePct()),nvl(r.getDiscountPct()),minimum,billable,warning);
        }
        BigDecimal base=nvl(process.getBasePrice());
        if(finishId!=null) base=base.add(finishes.findById(finishId).map(x->nvl(x.getAdditionalPrice())).orElse(BigDecimal.ZERO));
        BigDecimal surcharge=colorId==null?BigDecimal.ZERO:colors.findById(colorId).map(x->BigDecimal.valueOf(x.getSurchargePct()==null?0:x.getSurchargePct())).orElse(BigDecimal.ZERO);
        BigDecimal billable=quantity.max(minimum);
        String warning=billable.compareTo(quantity)>0?"Cantidad menor al lote mínimo; se sugiere facturar "+billable.stripTrailingZeros().toPlainString()+" "+unit:"";
        return new Recommendation("Precio técnico base (sin lista vigente)",null,null,"PEN",money4(base),surcharge,BigDecimal.ZERO,minimum,billable,warning);
    }

    private boolean matches(PriceRule r,UUID fabricId,UUID colorId,UUID processId,UUID finishId,String unit,BigDecimal qty){
        if(r.getFabricId()!=null&&!Objects.equals(r.getFabricId(),fabricId))return false;
        if(r.getColorId()!=null&&!Objects.equals(r.getColorId(),colorId))return false;
        if(r.getProcessId()!=null&&!Objects.equals(r.getProcessId(),processId))return false;
        if(r.getFinishId()!=null&&!Objects.equals(r.getFinishId(),finishId))return false;
        if(r.getUnit()!=null&&!r.getUnit().isBlank()&&!r.getUnit().equalsIgnoreCase(unit))return false;
        if(r.getMinQty()!=null&&qty.compareTo(r.getMinQty())<0)return false;
        return r.getMaxQty()==null||qty.compareTo(r.getMaxQty())<=0;
    }
    private static BigDecimal nvl(BigDecimal x){return x==null?BigDecimal.ZERO:x;}
    private static BigDecimal money4(BigDecimal x){return nvl(x).setScale(4,RoundingMode.HALF_UP);}
}
