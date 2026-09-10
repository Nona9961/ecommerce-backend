package com.nona.inf.persistence.repository;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.inf.context.TenantContextAccessor;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.converters.InventoryItemConvertor;
import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import com.nona.inf.persistence.repository.jpa.InventoryItemJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存仓储分页扩展场景测试（WU-55 红阶段契约：WU-47 商家库存列表页
 * 查询面——店铺全集分页 + 计数；InventoryLogRepository.listBySkuPaged
 * 同款形状）。
 * <p>
 * happy——listPaged 按当前店铺全集分页返回映射列表（主键升序由排序
 * 参数承载）、count 透传；critical——limit=0/offset&lt;0 fail-closed
 * 拒绝、空页返回空列表；fail——offset 超界返回空列表（fail-safe，
 * 不抛异常）。
 * <p>
 * 装配纪律：依赖全 mock（租户过滤面由 Hibernate 过滤器在容器内承载，
 * 本单元层以 mock 呈现——跨店 fail-closed 的真库断言归冒烟清单），
 * 无容器；被测仓储 @BeforeEach 重建；行为桩 lenient 豁免 UOE 挡道
 * （绿实现后收回精确桩）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class InventoryItemRepositoryImplPagingUnitTest {

    @Mock
    private InventoryItemJpaRepository jpaRepository;

    @Mock
    private InventoryItemConvertor convertor;

    @Mock
    private ChangeTrackerProvider changeTrackerProvider;

    @Mock
    private TenantContextAccessor tenantContextAccessor;

    /**
     * 被测仓储（setUp 重建）
     */
    private InventoryItemRepositoryImpl repository;

    /**
     * 每用例前重建被测仓储（mock 桩在各用例内布置）。
     */
    @BeforeEach
    void setUp() {
        repository = new InventoryItemRepositoryImpl(jpaRepository, convertor,
                changeTrackerProvider, tenantContextAccessor,
                new TenantPrivilege(java.util.List.of(), null));
    }

    @Test
    @DisplayName("happy：店铺库存分页返回映射列表（分页参数按 offset/limit 换算）")
    void listPaged_returnsMappedList() {
        final InventoryItemPO po = new InventoryItemPO();
        when(jpaRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(po)));
        final InventoryItem item = org.mockito.Mockito.mock(InventoryItem.class);
        when(convertor.convertToRoot(any(InventoryItemPO.class), isNull()))
                .thenReturn(item);

        final List<InventoryItem> result = repository.listPaged(0, 20);

        assertThat(result).hasSize(1);
        verify(jpaRepository).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("critical：空页返回空列表（fail-safe）")
    void listPaged_emptyPage_returnsEmpty() {
        when(jpaRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        assertThat(repository.listPaged(0, 20)).isEmpty();
    }

    @Test
    @DisplayName("critical：limit=0 非法分页参数 fail-closed 拒绝（IAE）")
    void listPaged_limitZero_rejected() {
        assertThatThrownBy(() -> repository.listPaged(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("critical：offset<0 非法分页参数 fail-closed 拒绝（IAE）")
    void listPaged_offsetNegative_rejected() {
        assertThatThrownBy(() -> repository.listPaged(-1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
    }

    @Test
    @DisplayName("fail：offset 超界返回空列表（fail-safe，不抛异常）")
    void listPaged_offsetBeyondScope_returnsEmpty() {
        when(jpaRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        assertThat(repository.listPaged(10_000, 20)).isEmpty();
    }

    @Test
    @DisplayName("happy：店铺库存总数透传（分页 total 用）")
    void count_returnsTotal() {
        when(jpaRepository.count()).thenReturn(42L);

        assertThat(repository.count()).isEqualTo(42L);
        verify(jpaRepository).count();
    }
}