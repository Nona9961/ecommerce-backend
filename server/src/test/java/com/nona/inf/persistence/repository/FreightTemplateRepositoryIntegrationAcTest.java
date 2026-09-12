package com.nona.inf.persistence.repository;

import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.factory.FreightTemplateFactory;
import com.nona.domain.catalog.repo.FreightTemplateRepository;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.catalog.FreightTemplatePO;
import com.nona.inf.persistence.repository.jpa.FreightTemplateJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运费模板仓储集成测试：主表（freight_template）存在性与变更集驱动落库、
 * 删除真实行数、列表按店铺、跨店铺租户隔离（fail-closed）。
 * <p>
 * 与 DDD 红线对应：运费模板为独立聚合根，根表 freight_template
 * （tenant=shopId）一行=一模板；仓储继承 DifferRepository（读 → track →
 * save → calculateChanges 变更集落库）；单表聚合无集合子实体；
 * 跨店铺加载在主键路径即被租户过滤拦截（fail-closed）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class FreightTemplateRepositoryIntegrationAcTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9001";

    /**
     * 测试店铺 B 的租户（隔离断言用）
     */
    private static final String TENANT_B = "9002";

    /**
     * 运费模板仓储（被测对象：DifferRepository 子类）
     */
    @Autowired
    private FreightTemplateRepository freightTemplateRepository;

    /**
     * 运费模板主表 JPA（断言根表行与清理）
     */
    @Autowired
    private FreightTemplateJpaRepository freightTemplateJpaRepository;

    /**
     * 运费模板聚合工厂
     */
    @Autowired
    private FreightTemplateFactory freightTemplateFactory;

    /**
     * 编程式事务模板（模拟用例层事务边界：写路径需在事务内）
     */
    @Autowired
    private TransactionTemplate tx;


    /**
     * 提权工具（测试数据清理需要越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 每用例前：提权清空模板表 + 建立请求作用域 + 商家 A 租户上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> freightTemplateJpaRepository.deleteAll());
    }

    /**
     * 新增：save 后根表存在一行，聚合可整体读回（三规则参数与状态一致）。
     */
    @Test
    @DisplayName("新增模板后根表存在且可读回")
    void save_insertsRootRow() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final FreightTemplate template = tx.execute(status -> {
                final FreightTemplate created = freightTemplateFactory.createFreightTemplate(
                        9001L, "按件模板", FreightRuleType.PER_ITEM, 800L, null, null);
                freightTemplateRepository.save(created);
                return created;
            });
            assertThat(template).isNotNull();

            assertThat(freightTemplateJpaRepository.existsById(template.getId())).isTrue();
            final FreightTemplatePO po = freightTemplateJpaRepository.findById(template.getId()).orElseThrow();
            assertThat(po.getTenantID()).isEqualTo(TENANT_A);
            assertThat(po.getShopId()).isEqualTo(9001L);
            assertThat(po.getName()).isEqualTo("按件模板");
            assertThat(po.getRuleType()).isEqualTo(FreightRuleType.PER_ITEM);
            assertThat(po.getPerItemPrice()).isEqualTo(800L);

            final FreightTemplate loaded = freightTemplateRepository.getByID(template.getId());
            assertThat(loaded).isNotNull();
            assertThat(loaded.getShopId()).isEqualTo(9001L);
            assertThat(loaded.getRuleType()).isEqualTo(FreightRuleType.PER_ITEM);
            assertThat(loaded.getPerItemPrice()).isEqualTo(800L);
            assertThat(loaded.isEnabled()).isTrue();
    
        });
}

    /**
     * 更新：快照基线后改规则，save 落库（名称/规则/参数整体替换）。
     */
    @Test
    @DisplayName("更新规则后落库")
    void save_updateAppliesRules() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final FreightTemplate template = freightTemplateFactory.createFreightTemplate(
                    9001L, "按件模板", FreightRuleType.PER_ITEM, 800L, null, null);
            freightTemplateRepository.save(template);

            final FreightTemplate loaded = freightTemplateRepository.getByID(template.getId());
            loaded.updateRules("满99包邮", FreightRuleType.THRESHOLD_FREE, 800L, 1000L, 9900L);
            freightTemplateRepository.save(loaded);

            final FreightTemplatePO po = freightTemplateJpaRepository.findById(template.getId()).orElseThrow();
            assertThat(po.getName()).isEqualTo("满99包邮");
            assertThat(po.getRuleType()).isEqualTo(FreightRuleType.THRESHOLD_FREE);
            assertThat(po.getPerItemPrice()).isNull();
            assertThat(po.getBaseFreight()).isEqualTo(1000L);
            assertThat(po.getFreeThreshold()).isEqualTo(9900L);
    
        });
}

    /**
     * 更新：停用/启用状态落库。
     */
    @Test
    @DisplayName("停用启用状态落库")
    void save_statusFlipsPersisted() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final FreightTemplate template = freightTemplateFactory.createFreightTemplate(
                    9001L, "包邮模板", FreightRuleType.FREE, null, null, null);
            freightTemplateRepository.save(template);

            final FreightTemplate loaded = freightTemplateRepository.getByID(template.getId());
            loaded.disable();
            freightTemplateRepository.save(loaded);
            assertThat(freightTemplateJpaRepository.findById(template.getId()).orElseThrow().getStatus().name())
                    .isEqualTo("DISABLED");

            final FreightTemplate enableAgain = freightTemplateRepository.getByID(template.getId());
            enableAgain.enable();
            freightTemplateRepository.save(enableAgain);
            assertThat(freightTemplateJpaRepository.findById(template.getId()).orElseThrow().getStatus().name())
                    .isEqualTo("ENABLED");
    
        });
}

    /**
     * 保存语义：无变更 save 返回 false（快照基线一致时无落库动作）。
     */
    @Test
    @DisplayName("无变更保存返回false")
    void save_withoutChangesReturnsFalse() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final FreightTemplate template = freightTemplateFactory.createFreightTemplate(
                    9001L, "包邮模板", FreightRuleType.FREE, null, null, null);
            freightTemplateRepository.save(template);

            final FreightTemplate loaded = freightTemplateRepository.getByID(template.getId());
            assertThat(freightTemplateRepository.save(loaded)).isFalse();
    
        });
}

    /**
     * 删除：deleteByID 返回真实删除条数（1）；不存在返回 0。
     */
    @Test
    @DisplayName("删除返回真实行数")
    void deleteByID_returnsRealRowCount() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final FreightTemplate template = freightTemplateFactory.createFreightTemplate(
                    9001L, "包邮模板", FreightRuleType.FREE, null, null, null);
            freightTemplateRepository.save(template);

            final int deleted = tx.execute(status -> freightTemplateRepository.deleteByID(template.getId()));
            assertThat(deleted).isEqualTo(1);
            assertThat(freightTemplateJpaRepository.existsById(template.getId())).isFalse();

            final int deletedAgain = tx.execute(status -> freightTemplateRepository.deleteByID(template.getId()));
            assertThat(deletedAgain).isZero();
    
        });
}

    /**
     * 列表：listByShopId 返回本店全部模板（新模板在前），不含他店数据。
     */
    @Test
    @DisplayName("列表返回本店模板不含他店")
    void listByShopId_returnsOwnTemplatesOnly() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final FreightTemplate first = freightTemplateFactory.createFreightTemplate(
                    9001L, "模板一", FreightRuleType.FREE, null, null, null);
            final FreightTemplate second = freightTemplateFactory.createFreightTemplate(
                    9001L, "模板二", FreightRuleType.PER_ITEM, 800L, null, null);
            freightTemplateRepository.save(first);
            freightTemplateRepository.save(second);

            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);
                final FreightTemplate other = freightTemplateFactory.createFreightTemplate(
                        9002L, "B店模板", FreightRuleType.FREE, null, null, null);
                freightTemplateRepository.save(other);

                TrackingContext.withScope(() -> {
                    TrackingContext.scope().setTenantID(TENANT_A);
                    final List<FreightTemplate> list = freightTemplateRepository.listByShopId(9001L);
                    assertThat(list).hasSize(2);
                    assertThat(list.get(0).getId()).isEqualTo(second.getId());
                    assertThat(list.get(1).getId()).isEqualTo(first.getId());
    
                });
            });
        });
}

    /**
     * 隔离：B 店铺租户上下文加载 A 店铺模板 → null（主键路径 fail-closed）。
     */
    @Test
    @DisplayName("跨店铺加载模板不可见（fail-closed）")
    void loadForeignTemplate_notVisible() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final FreightTemplate templateA = freightTemplateFactory.createFreightTemplate(
                    9001L, "A店模板", FreightRuleType.FREE, null, null, null);
            freightTemplateRepository.save(templateA);

            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);
                assertThat(freightTemplateRepository.getByID(templateA.getId())).isNull();
    
            });
        });
}

    /**
     * 归属：B 店铺租户上下文新增模板落库为 B 店归属（tenant 列=B），
     * 切回 A 上下文读不到 B 店模板。
     */
    @Test
    @DisplayName("模板归属写入当前店铺租户")
    void createUnderTenantB_writesTenantB() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);
                final FreightTemplate templateB = freightTemplateFactory.createFreightTemplate(
                        9002L, "B店模板", FreightRuleType.FREE, null, null, null);
                freightTemplateRepository.save(templateB);

                final FreightTemplatePO saved = freightTemplateJpaRepository.findById(templateB.getId()).orElseThrow();
                assertThat(saved.getTenantID()).isEqualTo(TENANT_B);

                TrackingContext.withScope(() -> {
                    TrackingContext.scope().setTenantID(TENANT_A);
                    assertThat(freightTemplateJpaRepository.findById(templateB.getId())).isEmpty();
    
                });
            });
        });
}

    /**
     * 删除语义：delete(domain) 委托 deleteByID 返回真实行数。
     */
    @Test
    @DisplayName("delete委托删除返回真实行数")
    void delete_delegatesToDeleteById() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final FreightTemplate template = freightTemplateFactory.createFreightTemplate(
                    9001L, "包邮模板", FreightRuleType.FREE, null, null, null);
            freightTemplateRepository.save(template);

            final int deleted = tx.execute(status -> freightTemplateRepository.delete(template));
            assertThat(deleted).isEqualTo(1);
            assertThat(freightTemplateJpaRepository.existsById(template.getId())).isFalse();
    
        });
}
}