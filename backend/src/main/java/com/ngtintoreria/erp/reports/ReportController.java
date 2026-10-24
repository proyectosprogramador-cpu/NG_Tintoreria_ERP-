package com.ngtintoreria.erp.reports;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports){this.reports=reports;}

    @GetMapping("/dashboard/executive.{format}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or (hasAuthority('REPORTS_EXPORT') and hasAuthority('DASHBOARD_READ') and hasAuthority('COSTING_READ'))")
    public ResponseEntity<byte[]> executive(@PathVariable String format,@RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to){return file(reports.executiveDashboard(format,from,to));}

    @GetMapping("/commercial/quotations.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('QUOTES_READ')")
    public ResponseEntity<byte[]> quotations(@PathVariable String format,@RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to){return file(reports.commercialQuotations(format,from,to));}

    @GetMapping("/commercial/orders.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('ORDERS_READ')")
    public ResponseEntity<byte[]> salesOrders(@PathVariable String format,@RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to){return file(reports.commercialOrders(format,from,to));}

    @GetMapping("/receiving/receptions.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('RECEPTIONS_READ')")
    public ResponseEntity<byte[]> receptions(@PathVariable String format,@RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to){return file(reports.fabricReceptions(format,from,to));}

    @GetMapping("/laboratory/requests.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('LAB_REQUESTS_READ')")
    public ResponseEntity<byte[]> laboratory(@PathVariable String format,@RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to){return file(reports.laboratoryRequests(format,from,to));}

    @GetMapping("/admin/clients.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('CLIENTS_READ')")
    public ResponseEntity<byte[]> clients(@PathVariable String format){return file(reports.clients(format));}

    @GetMapping("/admin/suppliers.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('SUPPLIERS_READ')")
    public ResponseEntity<byte[]> suppliers(@PathVariable String format){return file(reports.suppliers(format));}

    @GetMapping("/admin/price-lists.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('PRICING_READ')")
    public ResponseEntity<byte[]> priceLists(@PathVariable String format){return file(reports.priceLists(format));}

    @GetMapping("/admin/formulas.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('FORMULAS_READ')")
    public ResponseEntity<byte[]> formulas(@PathVariable String format){return file(reports.formulas(format));}

    @GetMapping("/admin/masters.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('MASTERS_READ')")
    public ResponseEntity<byte[]> masters(@PathVariable String format){return file(reports.masterData(format));}

    @GetMapping("/admin/users.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('USERS_READ')")
    public ResponseEntity<byte[]> users(@PathVariable String format){return file(reports.users(format));}

    @GetMapping("/audit/events.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('AUDIT_READ')")
    public ResponseEntity<byte[]> audit(@PathVariable String format,@RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to){return file(reports.auditEvents(format,from,to));}

    @GetMapping("/inventory/stock.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('INVENTORY_READ')")
    public ResponseEntity<byte[]> stock(@PathVariable String format){return file(reports.inventoryStock(format));}

    @GetMapping("/inventory/kardex.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('INVENTORY_READ')")
    public ResponseEntity<byte[]> kardex(@PathVariable String format){return file(reports.inventoryKardex(format));}

    @GetMapping("/inventory/reservations.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('INVENTORY_READ')")
    public ResponseEntity<byte[]> reservations(@PathVariable String format){return file(reports.inventoryReservations(format));}

    @GetMapping("/purchase-requests.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('PURCHASE_REQUESTS_READ')")
    public ResponseEntity<byte[]> requests(@PathVariable String format){return file(reports.purchaseRequests(format));}

    @GetMapping("/purchase-orders.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('PURCHASE_ORDERS_READ')")
    public ResponseEntity<byte[]> orders(@PathVariable String format){return file(reports.purchaseOrderList(format));}

    @GetMapping("/production/orders.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('PRODUCTION_ORDERS_READ')")
    public ResponseEntity<byte[]> productionOrders(@PathVariable String format){return file(reports.productionOrders(format));}

    @GetMapping("/production/consumption.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('PRODUCTION_ORDERS_READ')")
    public ResponseEntity<byte[]> productionConsumption(@PathVariable String format){return file(reports.productionConsumption(format));}

    @GetMapping("/production/incidents.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('PRODUCTION_ORDERS_READ')")
    public ResponseEntity<byte[]> productionIncidents(@PathVariable String format){return file(reports.productionIncidents(format));}

    @GetMapping("/maintenance/machines.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('MAINTENANCE_READ')")
    public ResponseEntity<byte[]> maintenanceMachines(@PathVariable String format){return file(reports.maintenanceMachines(format));}

    @GetMapping("/maintenance/work-orders.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('MAINTENANCE_READ')")
    public ResponseEntity<byte[]> maintenanceWorkOrders(@PathVariable String format){return file(reports.maintenanceWorkOrders(format));}

    @GetMapping("/maintenance/work-orders/{id}.pdf")
    @PreAuthorize("hasAuthority('MAINTENANCE_READ')")
    public ResponseEntity<byte[]> maintenanceWorkOrderPdf(@PathVariable UUID id){return file(reports.maintenanceWorkOrderPdf(id));}

    @GetMapping("/quality/inspections.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('QUALITY_READ')")
    public ResponseEntity<byte[]> qualityInspections(@PathVariable String format){return file(reports.qualityInspections(format));}

    @GetMapping("/quality/checks.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('QUALITY_READ')")
    public ResponseEntity<byte[]> qualityChecks(@PathVariable String format){return file(reports.qualityChecks(format));}

    @GetMapping("/quality/inspections/{id}.pdf")
    @PreAuthorize("hasAuthority('QUALITY_READ')")
    public ResponseEntity<byte[]> qualityInspectionPdf(@PathVariable UUID id){return file(reports.qualityInspectionPdf(id));}

    @GetMapping("/dispatches.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('DISPATCH_READ')")
    public ResponseEntity<byte[]> dispatches(@PathVariable String format,@RequestParam(required=false) String status,@RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to){return file(reports.dispatchList(format,status,from,to));}

    @GetMapping("/dispatches/{id}.pdf")
    @PreAuthorize("hasAuthority('DISPATCH_READ')")
    public ResponseEntity<byte[]> dispatchPdf(@PathVariable UUID id){return file(reports.dispatchPdf(id));}

    @GetMapping("/returns.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('RETURNS_READ')")
    public ResponseEntity<byte[]> customerReturns(@PathVariable String format){return file(reports.customerReturns(format));}

    @GetMapping("/returns/{id}.pdf")
    @PreAuthorize("hasAuthority('RETURNS_READ')")
    public ResponseEntity<byte[]> customerReturnPdf(@PathVariable UUID id){return file(reports.customerReturnPdf(id));}

    @GetMapping("/costing/orders.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('COSTING_READ')")
    public ResponseEntity<byte[]> costingOrders(@PathVariable String format){return file(reports.costingOrders(format));}

    @GetMapping("/costing/materials.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT') and hasAuthority('COSTING_READ')")
    public ResponseEntity<byte[]> costingMaterials(@PathVariable String format){return file(reports.costingMaterials(format));}

    @GetMapping("/costing/orders/{id}.pdf")
    @PreAuthorize("hasAuthority('COSTING_READ')")
    public ResponseEntity<byte[]> costingOrderPdf(@PathVariable UUID id){return file(reports.costingOrderPdf(id));}

    @GetMapping("/pilot/checklist.{format}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or (hasAuthority('REPORTS_EXPORT') and hasAuthority('PILOT_READ'))")
    public ResponseEntity<byte[]> pilotChecklist(@PathVariable String format){return file(reports.pilotChecklist(format));}

    @GetMapping("/pilot/incidents.{format}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or (hasAuthority('REPORTS_EXPORT') and hasAuthority('PILOT_READ'))")
    public ResponseEntity<byte[]> pilotIncidents(@PathVariable String format){return file(reports.pilotIncidents(format));}

    @GetMapping("/documents/register.{format}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<byte[]> documentRegister(@PathVariable String format){return file(reports.documentRegister(format));}

    @GetMapping("/purchase-orders/{id}.pdf")
    @PreAuthorize("hasAuthority('PURCHASE_ORDERS_READ')")
    public ResponseEntity<byte[]> orderPdf(@PathVariable UUID id){return file(reports.purchaseOrderPdf(id));}

    private ResponseEntity<byte[]> file(ReportService.ReportFile f){
        ContentDisposition cd=ContentDisposition.attachment().filename(f.filename(),StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,cd.toString()).body(f.bytes());
    }
}
