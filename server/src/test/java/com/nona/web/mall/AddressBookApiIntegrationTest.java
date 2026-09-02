package com.nona.web.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.api.auth.Portal;
import com.nona.inf.persistence.repository.jpa.AccountJpaRepository;
import com.nona.inf.persistence.repository.jpa.AddressBookJpaRepository;
import com.nona.inf.persistence.repository.jpa.AddressJpaRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 买家地址簿 REST 端点集成测试：地址 CRUD + 默认地址唯一（真实 Security 链 + H2 address 表）。
 * <p>
 * 无真实 Redis（用户上下文缓存以 mock 替代）；买家身份经注册落库 + JWT 签发获得，
 * 过滤器缓存 miss 后由真实账号状态 SPI 回填。覆盖：happy（新增/列表/编辑/删除/设置默认）、
 * critical（无默认簿、幂等设置、删默认自动提升、覆盖式新增默认）、error（未认证 401、
 * 必填校验 400、目标不存在 404、跨买家归属隔离 404）。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class AddressBookApiIntegrationTest {

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
     * 地址 JPA 仓储（测试数据清理与直接断言）
     */
    @Autowired
    private AddressJpaRepository addressRepository;

    /**
     * 地址簿主表 JPA 仓储（测试数据清理）
     */
    @Autowired
    private AddressBookJpaRepository addressBookRepository;

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
     * 每用例前清空数据并 stub 缓存 miss（过滤器走真实账号状态回填）。
     */
    @BeforeEach
    void setUp() {
        addressRepository.deleteAll();
        addressBookRepository.deleteAll();
        accountRepository.deleteAll();
        when(authUserCache.get(anyLong())).thenReturn(java.util.Optional.empty());
    }

    /**
     * happy：新增地址（带默认标记）成功，返回分配的 ID 与默认标记。
     */
    @Test
    @DisplayName("新增地址成功")
    void add_address_returnsAssignedId() throws Exception {
        final String token = registerBuyer();

        mockMvc.perform(post("/mall/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("张三", "13800138000", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.recipient").value("张三"))
                .andExpect(jsonPath("$.data.isDefault").value(true));
    }

    /**
     * happy：列表返回全部地址（含默认标记）。
     */
    @Test
    @DisplayName("地址列表返回簿内全部")
    void list_returnsAllAddresses() throws Exception {
        final String token = registerBuyer();
        addAddress(token, "张三", false);
        final long secondId = addAddress(token, "李四", false);

        mockMvc.perform(get("/mall/addresses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].id").value(secondId));
    }

    /**
     * happy：编辑地址后字段更新（默认标记保持）。
     */
    @Test
    @DisplayName("编辑地址字段更新")
    void update_changesFields() throws Exception {
        final String token = registerBuyer();
        final long id = addAddress(token, "张三", true);

        mockMvc.perform(put("/mall/addresses/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("张三丰", "13900139000", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipient").value("张三丰"))
                .andExpect(jsonPath("$.data.phone").value("13900139000"))
                .andExpect(jsonPath("$.data.isDefault").value(true));
    }

    /**
     * happy：删除地址后列表移除。
     */
    @Test
    @DisplayName("删除地址后列表移除")
    void delete_removesFromList() throws Exception {
        final String token = registerBuyer();
        final long id = addAddress(token, "张三", false);

        mockMvc.perform(delete("/mall/addresses/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/mall/addresses").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /**
     * happy：设置默认让位旧默认（同买家默认唯一，列表恰一个默认）。
     */
    @Test
    @DisplayName("设置默认让位旧默认")
    void setDefault_migratesPreviousDefault() throws Exception {
        final String token = registerBuyer();
        final long firstId = addAddress(token, "张三", true);
        final long secondId = addAddress(token, "李四", false);

        mockMvc.perform(put("/mall/addresses/" + secondId + "/default")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/mall/addresses").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(firstId))
                .andExpect(jsonPath("$.data[0].isDefault").value(false))
                .andExpect(jsonPath("$.data[1].id").value(secondId))
                .andExpect(jsonPath("$.data[1].isDefault").value(true));
    }

    /**
     * critical：新增带默认标记的地址时旧默认让位（覆盖式新增默认）。
     */
    @Test
    @DisplayName("新增带默认标记覆盖旧默认")
    void add_withDefault_overwritesPrevious() throws Exception {
        final String token = registerBuyer();
        addAddress(token, "张三", true);
        final long secondId = addAddress(token, "李四", true);

        mockMvc.perform(get("/mall/addresses").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].isDefault").value(false))
                .andExpect(jsonPath("$.data[1].id").value(secondId))
                .andExpect(jsonPath("$.data[1].isDefault").value(true));
    }

    /**
     * critical：无默认簿合法（全部非默认），设置默认后唯一。
     */
    @Test
    @DisplayName("无默认簿可设置首个默认")
    void setDefault_firstDefaultInNonDefaultBook() throws Exception {
        final String token = registerBuyer();
        final long id = addAddress(token, "张三", false);

        mockMvc.perform(put("/mall/addresses/" + id + "/default")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/mall/addresses").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].isDefault").value(true));
    }

    /**
     * critical：设置默认幂等（已是默认再次设置无变化）。
     */
    @Test
    @DisplayName("重复设置默认幂等")
    void setDefault_idempotent() throws Exception {
        final String token = registerBuyer();
        final long id = addAddress(token, "张三", true);

        mockMvc.perform(put("/mall/addresses/" + id + "/default")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/mall/addresses").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].isDefault").value(true));
    }

    /**
     * critical：删除默认地址后自动提升最早一条为默认。
     */
    @Test
    @DisplayName("删除默认地址自动提升")
    void deleteDefault_promotesFirst() throws Exception {
        final String token = registerBuyer();
        final long firstId = addAddress(token, "张三", false);
        final long defaultId = addAddress(token, "李四", true);

        mockMvc.perform(delete("/mall/addresses/" + defaultId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/mall/addresses").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(firstId))
                .andExpect(jsonPath("$.data[0].isDefault").value(true));
    }

    /**
     * critical：删除最后一个地址后地址簿为空。
     */
    @Test
    @DisplayName("删除最后一条地址簿为空")
    void deleteLast_leavesEmptyBook() throws Exception {
        final String token = registerBuyer();
        final long id = addAddress(token, "张三", true);

        mockMvc.perform(delete("/mall/addresses/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/mall/addresses").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(0));
        assertThat(addressRepository.count()).isZero();
    }

    /**
     * error：未认证访问地址接口 → 401（/mall 受保护，买家角色）。
     */
    @Test
    @DisplayName("未认证访问返回 401")
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/mall/addresses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }

    /**
     * error：新增缺必填字段 → 400 参数校验失败。
     */
    @Test
    @DisplayName("必填字段缺失返回 400")
    void add_missingFields_returnsValidationError() throws Exception {
        final String token = registerBuyer();

        mockMvc.perform(post("/mall/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipient":"","phone":"13800138000","province":"浙江省",
                                 "city":"杭州市","district":"西湖区","detail":"文一西路 100 号"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"))
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * error：编辑不存在的地址 → 404（地址业务码）。
     */
    @Test
    @DisplayName("编辑不存在的地址返回 404")
    void update_missingAddress_returns404() throws Exception {
        final String token = registerBuyer();

        mockMvc.perform(put("/mall/addresses/999999")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("张三", "13800138000", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("identity.address_not_found"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("地址不存在")));
    }

    /**
     * error：删除不存在的地址 → 404。
     */
    @Test
    @DisplayName("删除不存在的地址返回 404")
    void delete_missingAddress_returns404() throws Exception {
        final String token = registerBuyer();

        mockMvc.perform(delete("/mall/addresses/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("identity.address_not_found"));
    }

    /**
     * error：设置不存在的地址为默认 → 404。
     */
    @Test
    @DisplayName("设置不存在的地址为默认返回 404")
    void setDefault_missingAddress_returns404() throws Exception {
        final String token = registerBuyer();

        mockMvc.perform(put("/mall/addresses/999999/default")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("identity.address_not_found"));
    }

    /**
     * error：跨买家归属隔离——买家 B 操作买家 A 的地址一律 404（不泄露归属）。
     */
    @Test
    @DisplayName("跨买家操作他人地址返回 404")
    void crossBuyer_operationsOnForeignAddress_returns404() throws Exception {
        final long ownerId = registerBuyerId();
        final String ownerToken = tokenFor(ownerId);
        final long addressId = addAddress(ownerToken, "张三", true);
        final String strangerToken = registerBuyer();

        mockMvc.perform(put("/mall/addresses/" + addressId)
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody("路人", "13700137000", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("identity.address_not_found"));

        mockMvc.perform(delete("/mall/addresses/" + addressId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/mall/addresses/" + addressId + "/default")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/mall/addresses").header("Authorization", "Bearer " + strangerToken))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /**
     * error：列表接口对空簿买家返回空列表（200 而非 404）。
     */
    @Test
    @DisplayName("空地址簿列表为空")
    void list_withoutAddresses_returnsEmpty() throws Exception {
        final String token = registerBuyer();

        mockMvc.perform(get("/mall/addresses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /**
     * 注册买家并返回其访问令牌（注册落库，账号状态回填走真实 DB）。
     *
     * @return JWT
     */
    private String registerBuyer() throws Exception {
        return tokenFor(registerBuyerId());
    }

    /**
     * 注册买家并返回账号 ID。
     *
     * @return 买家账号 ID
     */
    private long registerBuyerId() throws Exception {
        final MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer-" + System.nanoTime()
                                + "\",\"password\":\"secret123\",\"portal\":\"MALL\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("userId").asText());
    }

    /**
     * 为账号签发访问令牌（真实 JWT，过滤器缓存 miss 后回填真实状态）。
     *
     * @param accountId 账号 ID
     * @return JWT
     */
    private String tokenFor(long accountId) {
        return tokenProvider.issueToken(accountId, Portal.MALL);
    }

    /**
     * 通过接口新增地址并返回地址 ID。
     *
     * @param token     访问令牌
     * @param recipient 收件人
     * @param isDefault 默认标记
     * @return 地址 ID
     */
    private long addAddress(String token, String recipient, boolean isDefault) throws Exception {
        final MvcResult result = mockMvc.perform(post("/mall/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(recipient, "13800138000", isDefault)))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText());
    }

    /**
     * 构造合法地址请求体。
     *
     * @param recipient 收件人
     * @param phone     电话
     * @param isDefault 默认标记（可为 null）
     * @return 请求体 JSON
     */
    private static String validBody(String recipient, String phone, Boolean isDefault) {
        final StringBuilder body = new StringBuilder();
        body.append("{\"recipient\":\"").append(recipient)
                .append("\",\"phone\":\"").append(phone)
                .append("\",\"province\":\"浙江省\",\"city\":\"杭州市\",\"district\":\"西湖区\"")
                .append(",\"detail\":\"文一西路 100 号\"");
        if (isDefault != null) {
            body.append(",\"isDefault\":").append(isDefault);
        }
        body.append("}");
        return body.toString();
    }
}