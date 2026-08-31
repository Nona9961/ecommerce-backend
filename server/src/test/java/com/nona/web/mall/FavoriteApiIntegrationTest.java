package com.nona.web.mall;

import com.nona.api.auth.Portal;
import com.nona.api.mall.FavoriteRequest;
import com.nona.application.mall.FavoriteUseCase;
import com.nona.domain.identity.entity.FavoriteType;
import com.nona.inf.persistence.po.identity.FavoritePO;
import com.nona.inf.persistence.repository.jpa.FavoriteJpaRepository;
import com.nona.inf.security.AccountStatus;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.AuthUserContext;
import com.nona.inf.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 收藏 REST 端点集成测试：商品 / 店铺收藏、取消收藏、收藏列表（真实 Security 链 + JWT + H2）。
 * <p>
 * 覆盖：happy（收藏商品/店铺 → 列表可见）、critical（重复收藏 / 重复取消 / 取消不存在的收藏
 * 幂等、分页、类型过滤、唯一约束兜底）、error（未认证 401、参数校验 400、非法类型 400）。
 * 当前买家身份从认证上下文取（JWT uid），不来自请求体。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class FavoriteApiIntegrationTest {

    /**
     * 测试买家 ID（JWT 主体）
     */
    private static final long BUYER_ID = 81001L;

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 收藏 JPA 仓储（测试数据写入与清理）
     */
    @Autowired
    private FavoriteJpaRepository favoriteRepository;

    /**
     * 收藏用例（并发幂等测试直接调用）
     */
    @Autowired
    private FavoriteUseCase favoriteUseCase;

    /**
     * JWT 签发器（构造合法买家 token）
     */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 每用例前清空收藏表并 stub 买家上下文（BUYER 角色）。
     */
    @BeforeEach
    void setUp() {
        favoriteRepository.deleteAll();
        when(authUserCache.get(anyLong())).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("BUYER"), List.of())));
    }

    /**
     * happy：收藏商品 → 成功；列表可见该条目（类型 PRODUCT、目标 ID 匹配）。
     */
    @Test
    void favorite_product_succeedsAndListed() throws Exception {
        mockMvc.perform(post("/mall/favorites")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"PRODUCT\",\"targetId\":2001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/mall/favorites").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].targetType").value("PRODUCT"))
                .andExpect(jsonPath("$.data.records[0].targetId").value(2001))
                .andExpect(jsonPath("$.data.records[0].createTime").isNotEmpty());
    }

    /**
     * happy：收藏店铺 → 成功；按类型过滤列表可见该条目。
     */
    @Test
    void favorite_shop_succeedsAndListed() throws Exception {
        mockMvc.perform(post("/mall/favorites")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"SHOP\",\"targetId\":3001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/mall/favorites?targetType=SHOP").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].targetType").value("SHOP"))
                .andExpect(jsonPath("$.data.records[0].targetId").value(3001));
    }

    /**
     * critical：重复收藏同一目标 → 幂等成功（第二次不报错），列表不重复（仍 1 条）。
     */
    @Test
    void favorite_duplicate_isIdempotent() throws Exception {
        final String body = "{\"targetType\":\"PRODUCT\",\"targetId\":2001}";
        mockMvc.perform(post("/mall/favorites").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/mall/favorites").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/mall/favorites").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    /**
     * critical：重复取消 → 第一次取消成功，第二次取消仍成功（幂等），列表为空。
     */
    @Test
    void unfavorite_twice_isIdempotent() throws Exception {
        mockMvc.perform(post("/mall/favorites").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"PRODUCT\",\"targetId\":2001}"))
                .andExpect(status().isOk());

        final String body = "{\"targetType\":\"PRODUCT\",\"targetId\":2001}";
        mockMvc.perform(delete("/mall/favorites").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(delete("/mall/favorites").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/mall/favorites").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    /**
     * critical：取消不存在的收藏 → 幂等成功（不报错），列表保持为空。
     */
    @Test
    void unfavorite_nonExisting_isIdempotent() throws Exception {
        mockMvc.perform(delete("/mall/favorites").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"PRODUCT\",\"targetId\":9999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /**
     * critical：收藏后取消 → 列表回到空（增删闭环）。
     */
    @Test
    void favorite_thenUnfavorite_listEmpty() throws Exception {
        mockMvc.perform(post("/mall/favorites").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"PRODUCT\",\"targetId\":2001}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/mall/favorites").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"PRODUCT\",\"targetId\":2001}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/mall/favorites").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    /**
     * critical：12 条收藏分页 → 第一页 10 条 / total 12 / 第二页 2 条。
     */
    @Test
    void list_paginated_returnsPages() throws Exception {
        for (long i = 1; i <= 12; i++) {
            favoriteRepository.save(newFavoritePo(81000L + i, BUYER_ID, FavoriteType.PRODUCT, 9000L + i));
        }

        mockMvc.perform(get("/mall/favorites?pageNum=1&pageSize=10").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(12))
                .andExpect(jsonPath("$.data.records.length()").value(10))
                .andExpect(jsonPath("$.data.pageNum").value(1));

        mockMvc.perform(get("/mall/favorites?pageNum=2&pageSize=10").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(12))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.pageNum").value(2));
    }

    /**
     * critical：混合类型收藏 → 不带类型过滤返回全部；带类型过滤只返回对应类型。
     */
    @Test
    void list_mixedTypes_filtersByType() throws Exception {
        favoriteRepository.save(newFavoritePo(82001L, BUYER_ID, FavoriteType.PRODUCT, 2001L));
        favoriteRepository.save(newFavoritePo(82002L, BUYER_ID, FavoriteType.PRODUCT, 2002L));
        favoriteRepository.save(newFavoritePo(82003L, BUYER_ID, FavoriteType.SHOP, 3001L));

        mockMvc.perform(get("/mall/favorites").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3));

        mockMvc.perform(get("/mall/favorites?targetType=SHOP").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].targetId").value(3001));

        mockMvc.perform(get("/mall/favorites?targetType=PRODUCT").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    /**
     * critical：空收藏 → 分页响应为空数组、total 0。
     */
    @Test
    void list_empty_returnsEmptyPage() throws Exception {
        mockMvc.perform(get("/mall/favorites").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records.length()").value(0));
    }

    /**
     * critical：不同买家收藏互不可见（收藏归属各自账号）。
     */
    @Test
    void list_otherBuyerFavorites_notVisible() throws Exception {
        favoriteRepository.save(newFavoritePo(83001L, 81002L, FavoriteType.PRODUCT, 2001L));

        mockMvc.perform(get("/mall/favorites").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    /**
     * critical：数据库唯一约束兜底——绕过查重直接插入重复条目 → 约束冲突。
     */
    @Test
    void favorite_duplicateInsert_violatesUniqueConstraint() {
        favoriteRepository.save(newFavoritePo(84001L, BUYER_ID, FavoriteType.PRODUCT, 2001L));
        assertThatThrownBy(() ->
                favoriteRepository.save(newFavoritePo(84002L, BUYER_ID, FavoriteType.PRODUCT, 2001L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * critical：并发重复收藏同一目标 → 两个请求均成功（一个插入、一个
     * 唯一约束兜底视为已存在），收藏表仍只有一行（B3 幂等语义并发展开）。
     */
    @Test
    void concurrent_favorite_sameTarget_bothSucceed() throws Exception {
        final FavoriteRequest request = new FavoriteRequest(com.nona.api.mall.FavoriteType.PRODUCT, 2001L);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        favoriteUseCase.favorite(BUYER_ID, request);
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            assertThat(errors).isEmpty();
        } finally {
            pool.shutdownNow();
        }
        assertThat(favoriteRepository.count()).isEqualTo(1);
    }

    /**
     * error：未认证访问收藏列表 → 401。
     */
    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/mall/favorites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }

    /**
     * error：未认证收藏 → 401。
     */
    @Test
    void favorite_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/mall/favorites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"PRODUCT\",\"targetId\":2001}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }

    /**
     * error：请求体缺 targetId → 参数校验失败（400 generic.validation_failed）。
     */
    @Test
    void favorite_missingTargetId_rejectsByValidation() throws Exception {
        mockMvc.perform(post("/mall/favorites").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"PRODUCT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"))
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * error：请求体非法收藏类型 → 反序列化拒绝（400 generic.validation_failed）。
     */
    @Test
    void favorite_unknownType_rejectsByValidation() throws Exception {
        mockMvc.perform(post("/mall/favorites").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"FOO\",\"targetId\":2001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"))
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * error：列表查询参数非法收藏类型 → 显式解析拒绝（400 generic.validation_failed）。
     */
    @Test
    void list_unknownTypeParam_rejectsByValidation() throws Exception {
        mockMvc.perform(get("/mall/favorites?targetType=FOO").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"))
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * 构造买家 Bearer token。
     *
     * @return Authorization 头值
     */
    private String bearer() {
        return "Bearer " + tokenProvider.issueToken(BUYER_ID, Portal.MALL);
    }

    /**
     * 构造收藏 PO（favorite 表行）。
     *
     * @param id        条目 ID
     * @param accountId 买家账号 ID
     * @param type      收藏类型
     * @param targetId  收藏目标 ID
     * @return PO
     */
    private static FavoritePO newFavoritePo(Long id, Long accountId, FavoriteType type, Long targetId) {
        final FavoritePO po = new FavoritePO();
        po.setId(id);
        po.setAccountId(accountId);
        po.setFavoriteType(type);
        po.setTargetId(targetId);
        return po;
    }
}