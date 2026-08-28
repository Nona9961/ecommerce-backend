package com.nona.inf.persistence.tenant;

import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 租户隔离测试用的 global note PO。
 *
 * @author nona
 */
@Entity
@Table(name = "test_global_note")
@ScaffoldGenerated
public class TestGlobalNotePO extends BasePO {

    private String content;

    /**
     * 获取 note 内容。
     *
     * @return note 内容
     */
    public String getContent() {
        return content;
    }

    /**
     * 设置 note 内容。
     *
     * @param content note 内容
     */
    public void setContent(String content) {
        this.content = content;
    }
}