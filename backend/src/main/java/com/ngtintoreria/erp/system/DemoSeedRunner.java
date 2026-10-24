package com.ngtintoreria.erp.system;

import com.ngtintoreria.erp.company.*;
import com.ngtintoreria.erp.crm.*;
import com.ngtintoreria.erp.masterdata.*;
import com.ngtintoreria.erp.procurement.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class DemoSeedRunner implements ApplicationRunner {
    private final boolean enabled;
    private final CompanyProfileRepository company;
    private final ClientRepository clients;
    private final SupplierRepository suppliers;
    private final FabricRepository fabrics;
    private final DyeColorRepository colors;
    private final ProcessTypeRepository processes;
    private final FinishTypeRepository finishes;
    private final MeasurementUnitRepository units;
    private final MachineRepository machines;

    public DemoSeedRunner(@Value("${app.demo-seed:false}") boolean enabled,
                          CompanyProfileRepository company, ClientRepository clients, SupplierRepository suppliers,
                          FabricRepository fabrics, DyeColorRepository colors, ProcessTypeRepository processes,
                          FinishTypeRepository finishes, MeasurementUnitRepository units, MachineRepository machines) {
        this.enabled=enabled; this.company=company; this.clients=clients; this.suppliers=suppliers;
        this.fabrics=fabrics; this.colors=colors; this.processes=processes; this.finishes=finishes; this.units=units; this.machines=machines;
    }

    @Override public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (company.count()==0) { CompanyProfile c=new CompanyProfile(); c.setTradeName("Tintorería Neul Guzmán"); c.setFiscalAddress("Lima, Perú"); company.save(c); }
        if (units.count()==0) {
            units.save(unit("KG","Kilogramo","kg","Masa")); units.save(unit("M","Metro","m","Longitud")); units.save(unit("L","Litro","L","Volumen")); units.save(unit("GL","Gramo por litro","g/L","Dosificación")); units.save(unit("OWF","Porcentaje sobre peso de fibra","% OWF","Dosificación"));
        }
        if (fabrics.count()==0) {
            Fabric a=new Fabric(); a.setCode("TEL-001"); a.setName("Jersey algodón 30/1"); a.setComposition("100% algodón"); a.setGsm(160d); a.setWidthCm(180d); a.setDefaultUnit("kg"); fabrics.save(a);
            Fabric b=new Fabric(); b.setCode("TEL-002"); b.setName("Piqué algodón"); b.setComposition("100% algodón"); b.setGsm(200d); b.setWidthCm(180d); b.setDefaultUnit("kg"); fabrics.save(b);
        }
        if (colors.count()==0) { colors.save(color("COL-001","Negro","Oscuro",10)); colors.save(color("COL-002","Azul marino","Oscuro",8)); colors.save(color("COL-003","Blanco","Claro",0)); }
        if (processes.count()==0) { processes.save(process("PROC-001","Teñido reactivo","4.50","100")); processes.save(process("PROC-002","Teñido disperso","5.20","100")); processes.save(process("PROC-003","Lavado","2.20","80")); }
        if (finishes.count()==0) { finishes.save(finish("ACA-001","Suavizado","0.45")); finishes.save(finish("ACA-002","Siliconado","0.65")); finishes.save(finish("ACA-003","Compactado","0.80")); }
        if (machines.count()==0) {
            Machine m=new Machine(); m.setCode("MAQ-001"); m.setName("Jet 01"); m.setMinCapacityKg(new BigDecimal("80")); m.setMaxCapacityKg(new BigDecimal("500")); m.setCompatibleProcesses("Teñido reactivo,Teñido disperso"); machines.save(m);
            Machine n=new Machine(); n.setCode("MAQ-002"); n.setName("Jet 02"); n.setMinCapacityKg(new BigDecimal("50")); n.setMaxCapacityKg(new BigDecimal("300")); n.setCompatibleProcesses("Teñido reactivo,Lavado"); machines.save(n);
        }
        if (clients.count()==0) { Client c=new Client(); c.setDocumentNumber("20123456789"); c.setName("Textiles Andinos S.A.C."); c.setContactName("Área de Compras"); c.setWhatsapp("+51 999 111 222"); c.setEmail("compras@textilesandinos.demo"); c.setAddress("Lima"); c.setCreditDays(30); clients.save(c); }
        if (suppliers.count()==0) { Supplier s=new Supplier(); s.setRuc("20987654321"); s.setName("Química Industrial Demo S.A.C."); s.setContactName("Ventas"); s.setEmail("ventas@quimica.demo"); s.setPhone("+51 999 333 444"); s.setPaymentTerms("Crédito 30 días"); s.setBankReference("BCP - referencia demo"); suppliers.save(s); }
    }
    private MeasurementUnit unit(String c,String n,String s,String d){MeasurementUnit x=new MeasurementUnit();x.setCode(c);x.setName(n);x.setSymbol(s);x.setDimension(d);return x;}
    private DyeColor color(String c,String n,String f,double p){DyeColor x=new DyeColor();x.setCode(c);x.setName(n);x.setFamily(f);x.setSurchargePct(p);return x;}
    private ProcessType process(String c,String n,String p,String lot){ProcessType x=new ProcessType();x.setCode(c);x.setName(n);x.setBillingUnit("kg");x.setBasePrice(new BigDecimal(p));x.setMinimumLot(new BigDecimal(lot));return x;}
    private FinishType finish(String c,String n,String p){FinishType x=new FinishType();x.setCode(c);x.setName(n);x.setBillingUnit("kg");x.setAdditionalPrice(new BigDecimal(p));return x;}
}
