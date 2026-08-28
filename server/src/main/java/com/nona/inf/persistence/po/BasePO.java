package com.nona.inf.persistence.po;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;
import com.nona.annotation.ScaffoldGenerated;


/**
 * 所有rdb的po基类
 *
 * @author nona
 */
@Data
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
@ScaffoldGenerated
public abstract class BasePO {

    /**
     * 主键 ID（所有实体统一 Long 类型）
     */
    @Id
    protected Long id;

    /**
     * 创建时间（自动填充，不可更新）
     */
    @Column(nullable = false, updatable = false)
    @CreatedDate
    protected LocalDateTime createTime;

    /**
     * 更新时间（自动维护）
     */
    @Column(nullable = false)
    @LastModifiedDate
    protected LocalDateTime updateTime;


}
