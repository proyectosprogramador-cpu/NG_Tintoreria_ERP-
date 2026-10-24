package com.ngtintoreria.erp.identity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "permissions")
public class Permission {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, unique = true, length = 80)
    private String code;
    @Column(nullable = false, length = 150)
    private String name;
    protected Permission() {}
    public Permission(String code, String name) { this.code = code; this.name = name; }
    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
}
