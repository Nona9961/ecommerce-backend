package com.nona.inf.persistence.po;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.TenantId;
import com.nona.annotation.ScaffoldGenerated;

/**
 * Tenant-scoped 的 rdb po 基类
 *
 * @author nona
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@ScaffoldGenerated
public abstract class TenantScopedBasePO extends BasePO {

    /**
     * 租户 ID（tenant-scoped 数据的隔离键）
     */
    @Column(nullable = false, length = 64, name = "tenant_id")
    @TenantId
    protected String tenantID;
}
