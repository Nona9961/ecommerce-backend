package com.nona.web.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.api.auth.Portal;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.mall.SearchCard;
import com.nona.application.mall.ProductSearchUseCase;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.persistence.repository.jpa.AccountJpaRepository;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.JwtTokenProvider;
import com.nona.util.JacksonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品搜索端点契约测试（WU-59：GET /mall/search ——路径/查询参数绑定/
 * 卡片形状，与前端 searchApi.ts 约定逐字段锁定；复用 WU-41
 * ProductSearchService 领域契约，web 面本 WU 回注）。
 * <p>
 * 装配策略：搜索用例 bean 以 {@code @MockitoBean} 替换为 mock（容器仅
 * 验证路由/MVC 装配与形状投影——搜索真实链（PG 镜像/product_search_view）
 * 受 test 库无真视图约束归 walkthrough 分支（见红报告未决 4 决策标准）；
 * 认证沿用 AddressBookApiIntegrationAcTest 先例。
 * <p>
 * 红阶段状态：controller 方法体为 UOE 契约占位——本类全部用例红
 * （500 generic 兜底；auth-1 为恒绿锚点）；绿阶段实现 controller
 * 委托后按本矩阵转绿（mock 形状不变，断言面不变）。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class SearchApiContractAcTest {

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 账号 JPA 仓储（测试数据清理）
     */
    @Autowired
    private AccountJpaRepository accountRepository;

    /**
     * JWT 签发器（构造买家访问令牌）
     */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * JSON 序列化器（项目统一静态实例）
     */
    private final ObjectMapper objectMapper = JacksonUtil.DEFAULT_MAPPER;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 商品搜索用例（mock——卡片形状投影断言面）
     */
    @MockitoBean
    private ProductSearchUseCase searchUseCase;

    /**
     * 每用例前清理账号数据并 stub 缓存 miss（过滤器走真实账号状态回填）。
     */
    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        when(authUserCache.get(anyLong())).thenReturn(java.util.Optional.empty());
    }

    /**
     * 搜索卡片形状基线（金额分：minPrice=19900 分 = 199 元；与前端
     * SearchCardWire 逐字段对齐——卡片上线为分，api-client 换算元）。
     */
    private static SearchCard searchCard() {
        return new SearchCard(900L, "加厚毛衣", "http://img.example/cover.jpg",
                19900L, 1200L, 5001L, "店铺甲", "暖冬品牌");
    }

    /**
     * 注册买家并返回其访问令牌（注册落库，账号状态回填走真实 DB）。
     *
     * @return JWT
     */
    private String registerBuyer() throws Exception {
        final MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer-" + System.nanoTime()
                                + "\",\"password\":\"secret123\",\"portal\":\"MALL\"}"))
                .andExpect(status().isOk())
                .andReturn();
        final long accountId = Long.parseLong(objectMapper.readTree(
                result.getResponse().getContentAsString()).path("data").path("userId").asText());
        return tokenProvider.issueToken(accountId, Portal.MALL);
    }

    @Test
    @DisplayName("search-1 形状：PageResult 包裹 + 卡片 8 字段（金额分直传）")
    void search_shape_contractFrozen() throws Exception {
        final String token = registerBuyer();
        when(searchUseCase.search(any(), any(), any(), any(), any(), any(), any(),
                any(), anyLong())).thenReturn(PageResult.of(List.of(searchCard()), 1L,
                new PageQuery(1, 10)));

        mockMvc.perform(get("/mall/search")
                        .header("Authorization", "Bearer " + token)
                        .param("keyword", "毛衣")
                        .param("minPrice", "10000")
                        .param("maxPrice", "20000")
                        .param("sort", "PRICE_ASC")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.records[0].productId").value(900))
                .andExpect(jsonPath("$.data.records[0].name").value("加厚毛衣"))
                .andExpect(jsonPath("$.data.records[0].coverImageUrl").value("http://img.example/cover.jpg"))
                .andExpect(jsonPath("$.data.records[0].minPrice").value(19900))
                .andExpect(jsonPath("$.data.records[0].salesTotal").value(1200))
                .andExpect(jsonPath("$.data.records[0].shopId").value(5001))
                .andExpect(jsonPath("$.data.records[0].shopName").value("店铺甲"))
                .andExpect(jsonPath("$.data.records[0].brandName").value("暖冬品牌"));
    }

    @Test
    @DisplayName("search-2 非法价格区间：契约码 search.invalid_price_range 400（服务校验位语义冻结）")
    void search_invalidPriceRange_returns400() throws Exception {
        final String token = registerBuyer();
        when(searchUseCase.search(any(), any(), any(), any(), any(), any(), any(),
                any(), anyLong())).thenThrow(new BusinessException(
                EcommerceBusinessCode.SEARCH_INVALID_PRICE_RANGE.code(), "价格区间非法"));

        mockMvc.perform(get("/mall/search")
                        .header("Authorization", "Bearer " + token)
                        .param("minPrice", "20000")
                        .param("maxPrice", "10000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("search.invalid_price_range"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("auth-1 未认证访问 → 401 auth.unauthorized（端点属 /mall/** 保护面，恒绿锚点）")
    void search_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/mall/search"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }
}