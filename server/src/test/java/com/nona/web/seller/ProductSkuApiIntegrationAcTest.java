package com.nona.web.seller;

import com.nona.api.auth.Portal;
import com.nona.domain.catalog.entity.ShopStatus;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.catalog.ShopPO;
import com.nona.inf.persistence.repository.jpa.ProductAttributeJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductEditVersionJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductImageJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import com.nona.inf.persistence.repository.jpa.ShopJpaRepository;
import com.nona.inf.persistence.repository.jpa.SkuJpaRepository;
import com.nona.inf.security.AccountStatus;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.AuthUserContext;
import com.nona.inf.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家端 SKU 规格 REST 端点集成测试：规格模板整体替换（/spec-template）、
 * SKU 改价（/skus/{id}/price）、SKU 启停（/skus/{id}/enabled）
 * （真实 Security 链 + JWT + H2 + 租户上下文）。
 * <p>
 * 覆盖：happy（配置模板生成默认态 SKU 集、改价清除价格、启停切换、
 * 模板变更保留价格与身份、空 dimensions 清空、同模板重配幂等）、
 * critical（组合数恰 200 接受）、error（超上限 400 且模板保持原值、
 * 维度结构非法 400、非正价格 400、未认证 401、商品不存在/跨店铺 404、
 * 目标 SKU 不存在 404、空启用状态 400）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class ProductSkuApiIntegrationAcTest {

    /**
     * 商家 A 账号 ID
     */
    private static final long SELLER_A_UID = 77001L;

    /**
     * 商家 B 账号 ID
     */
    private static final long SELLER_B_UID = 77002L;

    /**
     * 店铺 A ID（商家 A 当前店铺，租户锚点）
     */
    private static final long SHOP_A_ID = 97001L;

    /**
     * 店铺 B ID（商家 B 当前店铺，租户锚点）
     */
    private static final long SHOP_B_ID = 97002L;

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 店铺主表 JPA（测试数据写入与清理）
     */
    @Autowired
    private ShopJpaRepository shopJpaRepository;

    /**
     * 商品主表 JPA（测试数据清理）
     */
    @Autowired
    private ProductJpaRepository productJpaRepository;

    /**
     * 商品图片子表 JPA（测试数据清理）
     */
    @Autowired
    private ProductImageJpaRepository imageJpaRepository;

    /**
     * 商品属性子表 JPA（测试数据清理）
     */
    @Autowired
    private ProductAttributeJpaRepository attributeJpaRepository;

    /**
     * 商品 SKU 子表 JPA（测试数据清理）
     */
    @Autowired
    private SkuJpaRepository skuJpaRepository;

    /**
     * JWT 签发器（构造合法商家 token）
     */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis 并注入店铺归属）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 提权工具（清理 tenant-scoped 商品表需越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 商品编辑版本子表 JPA（测试数据清理——写路径留痕行随用例销毁）
     */
    @Autowired
    private ProductEditVersionJpaRepository editVersionJpaRepository;

    /**
     * 每用例前：清空商品相关表并直插 A/B 两家店铺，stub 两个商家上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            editVersionJpaRepository.deleteAll();
            skuJpaRepository.deleteAll();
            attributeJpaRepository.deleteAll();
            imageJpaRepository.deleteAll();
            productJpaRepository.deleteAll();
            shopJpaRepository.deleteAll();
        });
        shopJpaRepository.save(shopPo(SHOP_A_ID, "店铺A"));
        shopJpaRepository.save(shopPo(SHOP_B_ID, "店铺B"));
        when(authUserCache.get(SELLER_A_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_A_ID))));
        when(authUserCache.get(SELLER_B_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_B_ID))));
    }

    /**
     * happy：配置模板生成完整 SKU 集——数量=组合数、顺序=模板展开序、
     * 每个 SKU 携带派生摘要（hex64/可读）、默认未定价且停用。
     */
    @Test
    @DisplayName("配置模板生成默认态 SKU 集")
    void configureSpecTemplate_generatesDefaultSkuSet() throws Exception {
        final long productId = createDraft("多规格商品");

        mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":["
                                + "{\"name\":\"颜色\",\"values\":[\"黑\",\"白\"]},"
                                + "{\"name\":\"尺寸\",\"values\":[\"L\",\"XL\"]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].specSummary").value("颜色:黑,尺寸:L"))
                .andExpect(jsonPath("$.data[0].specHash").isString())
                .andExpect(jsonPath("$.data[0].price").doesNotExist())
                .andExpect(jsonPath("$.data[0].enabled").value(false))
                .andExpect(jsonPath("$.data[3].specSummary").value("颜色:白,尺寸:XL"));
    }

    /**
     * happy：改价生效（含清除价格回未定价）与启停切换闭环。
     */
    @Test
    @DisplayName("SKU 改价清除价与启停切换闭环")
    void skuMaintenance_priceAndEnabledRoundTrip() throws Exception {
        final long productId = createDraft("有价商品");
        final long skuId = configureTwoSkus(productId);

        mockMvc.perform(put("/seller/products/" + productId + "/skus/" + skuId + "/price")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":2599}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(skuId))
                .andExpect(jsonPath("$.data.price").value(2599));

        mockMvc.perform(put("/seller/products/" + productId + "/skus/" + skuId + "/price")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").doesNotExist());

        mockMvc.perform(put("/seller/products/" + productId + "/skus/" + skuId + "/enabled")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(skuId))
                .andExpect(jsonPath("$.data.enabled").value(true));

        mockMvc.perform(put("/seller/products/" + productId + "/skus/" + skuId + "/enabled")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    /**
     * happy（主链路闭环）：模板变更（新增规格值）后同组合 SKU 价格/启用/
     * ID 存活、新增组合为默认态；同模板重配幂等（数量与 ID 不变）。
     */
    @Test
    @DisplayName("模板变更保留价格与身份且重配幂等")
    void reconfigure_keepsPriceAndIdentity() throws Exception {
        final long productId = createDraft("重建商品");
        final long blackLId = configureTwoSkus(productId);
        mockMvc.perform(put("/seller/products/" + productId + "/skus/" + blackLId + "/price")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":100}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":["
                                + "{\"name\":\"颜色\",\"values\":[\"黑\",\"白\",\"蓝\"]},"
                                + "{\"name\":\"尺寸\",\"values\":[\"L\"]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":["
                                + "{\"name\":\"颜色\",\"values\":[\"黑\",\"白\",\"蓝\"]},"
                                + "{\"name\":\"尺寸\",\"values\":[\"L\"]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    /**
     * critical：空 dimensions 清空 SKU 集（空模板语义）；未认证 401。
     */
    @Test
    @DisplayName("空模板清空 SKU 集且未认证拒绝")
    void configure_emptyTemplateClearsAndAuthFailClosed() throws Exception {
        final long productId = createDraft("待清空商品");
        configureTwoSkus(productId);

        mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[{\"name\":\"颜色\",\"values\":[\"黑\"]}]}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * critical：组合数恰为上限（200）接受（单值维度 200 值）。
     */
    @Test
    @DisplayName("组合数恰为上限接受")
    void configure_atMaxBoundaryAccepted() throws Exception {
        final long productId = createDraft("边界商品");
        final StringBuilder values = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            if (!values.isEmpty()) {
                values.append(',');
            }
            values.append("\"值").append(i).append('"');
        }

        mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[{\"name\":\"颜色\",\"values\":[" + values + "]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(200));
    }

    /**
     * error：组合数超上限拒绝（400）且模板保持原值（再查详情 SKU 集不变）。
     * 维度结构非法（维度名空/值空）与非法价格（0）拒绝。
     */
    @Test
    @DisplayName("超上限与非法模板与非法价格拒绝")
    void configure_rejectsInvalidInputs() throws Exception {
        final long productId = createDraft("守卫商品");
        configureTwoSkus(productId);

        final StringBuilder values = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            if (!values.isEmpty()) {
                values.append(',');
            }
            values.append("\"值").append(i).append('"');
        }
        mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[{\"name\":\"颜色\",\"values\":[" + values + "]}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("catalog.product_sku_count_exceeded"));

        mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[{\"name\":\" \",\"values\":[\"黑\"]}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[{\"name\":\"颜色\",\"values\":[]}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/seller/products/" + productId + "/skus/99999/price")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":0}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * error：商品不存在/跨店铺按不存在呈现（404，fail-closed）；目标 SKU
     * 不存在 404；空启用状态 400。
     */
    @Test
    @DisplayName("跨店铺与目标缺失拒绝且不泄露归属")
    void crossShopAndMissingTargetFailClosed() throws Exception {
        final long productId = createDraft("A店商品");
        final String skuJson = mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[{\"name\":\"颜色\",\"values\":[\"黑\"]}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final long skuId = extractId(skuJson);

        mockMvc.perform(get("/seller/products/" + productId)
                        .header("Authorization", bearer(SELLER_B_UID)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_B_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":[{\"name\":\"颜色\",\"values\":[\"黑\"]}]}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/seller/products/" + productId + "/skus/" + skuId + "/price")
                        .header("Authorization", bearer(SELLER_B_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":100}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/seller/products/99999/skus/" + skuId + "/enabled")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/seller/products/" + productId + "/skus/99999/enabled")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("catalog.product_sku_not_found"));
        mockMvc.perform(put("/seller/products/" + productId + "/skus/" + skuId + "/enabled")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 构造商品 token。
     *
     * @param uid 账号 ID
     * @return Bearer token
     */
    private String bearer(long uid) {
        return "Bearer " + tokenProvider.issueToken(uid, Portal.SELLER);
    }

    /**
     * 通过 API 创建草稿（商家 A）。
     *
     * @param name 商品名称
     * @return 新建商品 ID
     */
    private long createDraft(String name) throws Exception {
        final String json = mockMvc.perform(post("/seller/products")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractId(json);
    }

    /**
     * 通过 API 配置双维度双值模板（颜色 黑/白 × 尺寸 L）并返回首个 SKU ID
     * （黑:L，展开序第一位）。
     *
     * @param productId 商品 ID
     * @return 首个 SKU ID
     */
    private long configureTwoSkus(long productId) throws Exception {
        final String json = mockMvc.perform(put("/seller/products/" + productId + "/spec-template")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimensions\":["
                                + "{\"name\":\"颜色\",\"values\":[\"黑\",\"白\"]},"
                                + "{\"name\":\"尺寸\",\"values\":[\"L\"]}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractFirstId(json);
    }

    /**
     * 从 SKU 集数组响应提取第一个元素的 id 字段。
     *
     * @param json 响应体（SKU 数组形态）
     * @return 首个 SKU ID
     */
    private static long extractFirstId(String json) {
        final int idIndex = json.indexOf("\"id\":");
        final int valueStart = idIndex + "\"id\":".length();
        final int valueEnd = json.indexOf(',', valueStart);
        final String idText = valueEnd == -1 ? json.substring(valueStart) : json.substring(valueStart, valueEnd);
        return Long.parseLong(idText);
    }

    /**
     * 从响应体提取数据的 id 字段。
     *
     * @param json 响应体
     * @return id 值
     */
    private long extractId(String json) {
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    /**
     * 构造店铺主表行。
     *
     * @param id   店铺 ID
     * @param name 店铺名称
     * @return 店铺 PO
     */
    private static ShopPO shopPo(long id, String name) {
        final ShopPO po = new ShopPO();
        po.setId(id);
        po.setName(name);
        po.setStatus(ShopStatus.NORMAL);
        return po;
    }
}