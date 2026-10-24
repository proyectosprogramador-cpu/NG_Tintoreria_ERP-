package com.ngtintoreria.erp.audit;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name="audit_events")
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable=false,length=160) private String actor;
    @Column(nullable=false,length=100) private String action;
    @Column(nullable=false,length=100) private String module;
    @Column(length=120) private String referenceId;
    @Column(columnDefinition="text") private String detail;
    @Column(length=80) private String ipAddress;
    @Column(length=12) private String httpMethod;
    @Column(length=300) private String requestPath;
    @Column(nullable=false) private boolean success = true;
    @Column(nullable=false,updatable=false) private OffsetDateTime createdAt;

    protected AuditEvent() {}

    public AuditEvent(String actor,String action,String module,String referenceId,String detail,
                      String ipAddress,String httpMethod,String requestPath,boolean success) {
        this.actor=actor; this.action=action; this.module=module; this.referenceId=referenceId; this.detail=detail;
        this.ipAddress=ipAddress; this.httpMethod=httpMethod; this.requestPath=requestPath; this.success=success;
    }

    @PrePersist void pre(){createdAt=OffsetDateTime.now();}
    public UUID getId(){return id;}
    public String getActor(){return actor;}
    public String getAction(){return action;}
    public String getModule(){return module;}
    public String getReferenceId(){return referenceId;}
    public String getDetail(){return detail;}
    public String getIpAddress(){return ipAddress;}
    public String getHttpMethod(){return httpMethod;}
    public String getRequestPath(){return requestPath;}
    public boolean isSuccess(){return success;}
    public OffsetDateTime getCreatedAt(){return createdAt;}
}
