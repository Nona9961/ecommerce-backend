package com.nona.web.seller;

import com.nona.api.auth.Portal;
import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.ports.FreightCalculator;
import com.nona.exceptions.BusinessException;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.repository.jpa.FreightTemplateJpaRepository;
import com.nona.inf.security.AccountStatus;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.AuthUserContext;
import com.nona.inf.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家端运费模板 REST 端点集成测试：模板 CRUD、启用/停用与跨域领域守卫
 * （真实 Security 链 + JWT + H2 + 租户上下文）。
 * <p>
 * 覆盖：happy（三规则创建/列表/详情/更新/启停/删除）、
 * critical（停用后计算器拒绝——disabled 新建订单不可用；店铺间列表隔离）、
 * error（未认证 401、参数校验 400、不存在 404、跨店铺访问 404——fail-closed）。
 * 当前店铺从认证上下文取（JWT uid → 用户上下文 shopIds → 请求租户），
 * 不来自请求体。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class FreightTemplateApiIntegrationTest {

    /**
     * 商家 A 账号 ID
     */
    private static final long SELLER_A_UID = 71001L;

    /**
     * 商家 B 账号 ID
     */
    private static final long SELLER_B_UID = 71002L;

    /**
     * 店铺 A ID（商家 A 当前店铺，租户锚点）
     */
    private static final long SHOP_A_ID = 90001L;

    /**
     * 店铺 B ID（商家 B 当前店铺，租户锚点）
     */
    private static final long SHOP_B_ID = 90002L;

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 运费模板主表 JPA（测试数据清理）
     */
    @Autowired
    private FreightTemplateJpaRepository freightTemplateJpaRepository;

    /**
     * 运费计算器（跨域契约：catalog 提供、订单域消费，接口签名冻结）
     */
    @Autowired
    private FreightCalculator freightCalculator;

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
     * 提权工具（清理 tenant-scoped 模板表需越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 每用例前：清空模板表并 stub 两个商家上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> freightTemplateJpaRepository.deleteAll());
        when(authUserCache.get(SELLER_A_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_A_ID))));
        when(authUserCache.get(SELLER_B_UID)).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("SELLER"), List.of(SHOP_B_ID))));
    }

    /**
     * happy：创建包邮模板 → 返回详情（启用、无计费参数）。
     */
    @Test
    @DisplayName("创建包邮模板返回详情")
    void createFreeTemplate_returnsDetail() throws Exception {
        mockMvc.perform(post("/seller/freight-templates")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"全场包邮\",\"ruleType\":\"FREE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.shopId").value(SHOP_A_ID))
                .andExpect(jsonPath("$.data.name").value("全场包邮"))
                .andExpect(jsonPath("$.data.ruleType").value("FREE"))
                .andExpect(jsonPath("$.data.perItemPrice").doesNotExist())
                .andExpect(jsonPath("$.data.status").value("ENABLED"));
    }

    /**
     * happy：创建按件模板 → 单价保留。
     */
    @Test
    @DisplayName("创建按件模板保留单价")
    void createPerItemTemplate_keepsPrice() throws Exception {
        mockMvc.perform(post("/seller/freight-templates")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"按件计费\",\"ruleType\":\"PER_ITEM\",\"perItemPrice\":800}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ruleType").value("PER_ITEM"))
                .andExpect(jsonPath("$.data.perItemPrice").value(800))
                .andExpect(jsonPath("$.data.baseFreight").doesNotExist())
                .andExpect(jsonPath("$.data.freeThreshold").doesNotExist());
    }

    /**
     * happy：创建满额免邮模板 → 基础运费与阈值保留。
     */
    @Test
    @DisplayName("创建满额免邮模板保留参数")
    void createThresholdTemplate_keepsParams() throws Exception {
        mockMvc.perform(post("/seller/freight-templates")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"满99包邮\",\"ruleType\":\"THRESHOLD_FREE\","
                                + "\"baseFreight\":1000,\"freeThreshold\":9900}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ruleType").value("THRESHOLD_FREE"))
                .andExpect(jsonPath("$.data.baseFreight").value(1000))
                .andExpect(jsonPath("$.data.freeThreshold").value(9900));
    }

    /**
     * happy：列表返回本店模板（新模板在前），初始为空列表。
     */
    @Test
    @DisplayName("模板列表返回本店全部")
    void listTemplates_returnsOwnTemplates() throws Exception {
        final Long firstId = createTemplate(SELLER_A_UID, "模板一", "FREE", "");
        final Long secondId = createTemplate(SELLER_A_UID, "模板二", "PER_ITEM",
                ",\"perItemPrice\":800");

        mockMvc.perform(get("/seller/freight-templates").header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(secondId))
                .andExpect(jsonPath("$.data[0].name").value("模板二"))
                .andExpect(jsonPath("$.data[1].id").value(firstId))
                .andExpect(jsonPath("$.data[1].name").value("模板一"));
    }

    /**
     * happy：详情查询返回规则与状态。
     */
    @Test
    @DisplayName("详情查询返回规则与状态")
    void getTemplate_returnsDetail() throws Exception {
        final Long templateId = createTemplate(SELLER_A_UID, "模板一", "FREE", "");

        mockMvc.perform(get("/seller/freight-templates/" + templateId)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(templateId))
                .andExpect(jsonPath("$.data.shopId").value(SHOP_A_ID))
                .andExpect(jsonPath("$.data.name").value("模板一"))
                .andExpect(jsonPath("$.data.status").value("ENABLED"));
    }

    /**
     * happy：更新规则 → 名称/规则/参数整体替换，状态保持。
     */
    @Test
    @DisplayName("更新规则整体生效")
    void updateTemplate_replacesRules() throws Exception {
        final Long templateId = createTemplate(SELLER_A_UID, "按件模板", "PER_ITEM",
                ",\"perItemPrice\":800");

        mockMvc.perform(put("/seller/freight-templates/" + templateId)
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"满99包邮\",\"ruleType\":\"THRESHOLD_FREE\","
                                + "\"baseFreight\":1000,\"freeThreshold\":9900}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("满99包邮"))
                .andExpect(jsonPath("$.data.ruleType").value("THRESHOLD_FREE"))
                .andExpect(jsonPath("$.data.baseFreight").value(1000))
                .andExpect(jsonPath("$.data.freeThreshold").value(9900))
                .andExpect(jsonPath("$.data.perItemPrice").doesNotExist())
                .andExpect(jsonPath("$.data.status").value("ENABLED"));
    }

    /**
     * happy：停用模板 → 状态停用；重新启用 → 恢复可用。
     */
    @Test
    @DisplayName("停用与重新启用切换状态")
    void setStatus_flipsEnabled() throws Exception {
        final Long templateId = createTemplate(SELLER_A_UID, "模板一", "FREE", "");

        mockMvc.perform(put("/seller/freight-templates/" + templateId + "/status")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(put("/seller/freight-templates/" + templateId + "/status")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENABLED"));
    }

    /**
     * happy：删除模板 → 列表为空。
     */
    @Test
    @DisplayName("删除模板后列表为空")
    void deleteTemplate_removesFromList() throws Exception {
        final Long templateId = createTemplate(SELLER_A_UID, "模板一", "FREE", "");

        mockMvc.perform(delete("/seller/freight-templates/" + templateId)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/seller/freight-templates").header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /**
     * critical：停用后计算器拒绝计费（disabled 模板新建订单不可用——领域守卫，
     * 经真实仓储与计算器链路验证）。
     */
    @Test
    @DisplayName("停用模板经计算器拒绝（新订单领域守卫）")
    void disabledTemplate_rejectedByCalculator() throws Exception {
        final Long templateId = createTemplate(SELLER_A_UID, "按件模板", "PER_ITEM",
                ",\"perItemPrice\":800");

        mockMvc.perform(put("/seller/freight-templates/" + templateId + "/status")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        final FreightTemplate loaded = loadByJpa(templateId);
        assertThatThrownBy(() -> freightCalculator.calculate(loaded, 9900L, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("停用");
    }

    /**
     * error：未认证访问 → 401。
     */
    @Test
    @DisplayName("未认证访问模板接口拒绝")
    void unauthenticated_rejected() throws Exception {
        mockMvc.perform(get("/seller/freight-templates"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * error：创建空模板名称 → 400（契约校验）。
     */
    @Test
    @DisplayName("空模板名称创建拒绝")
    void createTemplate_blankNameRejected() throws Exception {
        mockMvc.perform(post("/seller/freight-templates")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"ruleType\":\"FREE\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * error：未知规则类型 → 400（领域校验兜底）。
     */
    @Test
    @DisplayName("未知规则类型创建拒绝")
    void createTemplate_unknownRuleTypeRejected() throws Exception {
        mockMvc.perform(post("/seller/freight-templates")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"模板\",\"ruleType\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * error：按件规则缺失单价 → 400（领域校验兜底）。
     */
    @Test
    @DisplayName("按件规则缺失单价拒绝")
    void createTemplate_missingPriceRejected() throws Exception {
        mockMvc.perform(post("/seller/freight-templates")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"按件\",\"ruleType\":\"PER_ITEM\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * error：状态请求缺失 enabled 字段 → 400（契约校验）。
     */
    @Test
    @DisplayName("状态请求缺enabled拒绝")
    void setStatus_missingEnabledRejected() throws Exception {
        final Long templateId = createTemplate(SELLER_A_UID, "模板一", "FREE", "");

        mockMvc.perform(put("/seller/freight-templates/" + templateId + "/status")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * error：操作不存在的模板 → 404。
     */
    @Test
    @DisplayName("操作不存在的模板返回404")
    void missingTemplate_returnsNotFound() throws Exception {
        mockMvc.perform(get("/seller/freight-templates/99999")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/seller/freight-templates/99999")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名字\",\"ruleType\":\"FREE\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/seller/freight-templates/99999/status")
                        .header("Authorization", bearer(SELLER_A_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/seller/freight-templates/99999")
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isNotFound());
    }

    /**
     * critical：商家 B 操作商家 A 的模板 → 404（跨店铺访问按不存在呈现，
     * fail-closed 隔离）。列表互不可见。
     */
    @Test
    @DisplayName("跨店铺访问模板不可见（fail-closed）")
    void crossShopTemplateAccess_notVisible() throws Exception {
        final Long templateId = createTemplate(SELLER_A_UID, "A店模板", "FREE", "");

        mockMvc.perform(get("/seller/freight-templates/" + templateId)
                        .header("Authorization", bearer(SELLER_B_UID)))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/seller/freight-templates/" + templateId + "/status")
                        .header("Authorization", bearer(SELLER_B_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/seller/freight-templates/" + templateId)
                        .header("Authorization", bearer(SELLER_B_UID)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/seller/freight-templates").header("Authorization", bearer(SELLER_B_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/seller/freight-templates/" + templateId)
                        .header("Authorization", bearer(SELLER_A_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("A店模板"));
    }

    /**
     * 创建模板并返回模板 ID（测试辅助）。
     *
     * @param uid       商家账号 ID
     * @param name      模板名称
     * @param ruleType  规则类型
     * @param extraArgs 附加 JSON 参数（形如 {@code ,"perItemPrice":800}；
     *                  无附加参数传空字符串）
     * @return 模板 ID（响应 data.id）
     * @throws Exception MockMvc 调用失败
     */
    private Long createTemplate(long uid, String name, String ruleType, String extraArgs) throws Exception {
        final String body = mockMvc.perform(post("/seller/freight-templates")
                        .header("Authorization", bearer(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"ruleType\":\"" + ruleType + "\"" + extraArgs + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    /**
     * 直读模板 PO（断言领域守卫链路的测试数据源；纯读放行——测试线程
     * 无请求租户上下文，读放行关闭租户过滤命中本店铺行）。
     *
     * @param templateId 模板 ID
     * @return 模板 PO 转换的领域对象
     */
    private FreightTemplate loadByJpa(Long templateId) {
        try {
            return tenantPrivilege.withReadBypass(() -> {
                final var po = freightTemplateJpaRepository.findById(templateId).orElseThrow();
                return new FreightTemplate(po.getId(), po.getShopId(), po.getName(), po.getRuleType(),
                        po.getPerItemPrice(), po.getBaseFreight(), po.getFreeThreshold(), po.getStatus());
            });
        } catch (Exception e) {
            throw new IllegalStateException("读取运费模板失败", e);
        }
    }

    /**
     * 构造商家 Bearer token。
     *
     * @param uid 账号 ID
     * @return Authorization 头值
     */
    private String bearer(long uid) {
        return "Bearer " + tokenProvider.issueToken(uid, Portal.SELLER);
    }
}