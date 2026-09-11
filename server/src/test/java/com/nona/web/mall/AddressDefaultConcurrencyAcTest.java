package com.nona.web.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.api.auth.Portal;
import com.nona.inf.persistence.po.identity.AddressBookPO;
import com.nona.inf.persistence.po.identity.AddressPO;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 默认地址唯一性并发测试：两个并发设置默认（不同地址）在买家维度锁下串行化，
 * 最终恰好一个默认；空簿并发新增带默认地址同样收敛为恰好一个默认。
 * <p>
 * 独立 H2 实例（LOCK_TIMEOUT 放宽，避免锁等待窗口触发超时打断串行化收敛）。
 */
@SpringBootTest(properties = {
        "management.health.redis.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:address-concurrency;LOCK_TIMEOUT=30000;DB_CLOSE_DELAY=-1",
        // 独立 H2 面的 Flyway/validate 豁免：MySQL 方言 V1/V1.1 迁移与 H2 mem 库
        // 不兼容（engine=InnoDB 等），本面回退 H2 时代 ddl-auto=create 语义
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create"
})
@AutoConfigureMockMvc
class AddressDefaultConcurrencyAcTest {

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 地址 JPA 仓储（直接断言库内默认行）
     */
    @Autowired
    private AddressJpaRepository addressRepository;

    /**
     * 地址簿主表 JPA 仓储（测试数据清理）
     */
    @Autowired
    private AddressBookJpaRepository addressBookRepository;

    /**
     * JWT 签发器
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
     * 每用例前清空地址表并 stub 缓存 miss。
     */
    @BeforeEach
    void setUp() {
        addressRepository.deleteAll();
        addressBookRepository.deleteAll();
        when(authUserCache.get(anyLong())).thenReturn(java.util.Optional.empty());
    }

    /**
     * critical：两线程并发把两个不同地址设为默认，串行化后库内恰好一个默认行。
     */
    @Test
    @DisplayName("并发设置默认最终唯一")
    void concurrentSetDefault_convergesToOneDefault() throws Exception {
        final String token = registerBuyer();
        final long firstId = insertAddress(token, 810001L);
        final long secondId = insertAddress(token, 810002L);

        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final AtomicInteger okCount = new AtomicInteger();
        try {
            pool.submit(() -> runSetDefault(token, firstId, start, okCount));
            pool.submit(() -> runSetDefault(token, secondId, start, okCount));
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(okCount.get()).as("两个并发设置默认请求都应成功（锁等待后串行执行）").isEqualTo(2);
        assertThat(defaultRows()).hasSize(1);
        assertThat(defaultRows().get(0)).isIn(firstId, secondId);
    }

    /**
     * critical：空簿并发新增两个带默认标记的地址，收敛为恰好一个默认。
     */
    @Test
    @DisplayName("空簿并发新增默认地址最终唯一")
    void concurrentAddWithDefault_convergesToOneDefault() throws Exception {
        final String token = registerBuyer();

        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final AtomicInteger okCount = new AtomicInteger();
        try {
            pool.submit(() -> runAddWithDefault(token, start, okCount));
            pool.submit(() -> runAddWithDefault(token, start, okCount));
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(okCount.get()).as("两个并发新增默认地址请求都应成功").isEqualTo(2);
        assertThat(addressRepository.count()).isEqualTo(2);
        assertThat(defaultRows()).hasSize(1);
    }

    /**
     * 注册买家并签发令牌。
     *
     * @return JWT
     */
    private String registerBuyer() throws Exception {
        final MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer-concurrency-" + System.nanoTime()
                                + "\",\"password\":\"secret123\",\"portal\":\"MALL\"}"))
                .andExpect(status().isOk())
                .andReturn();
        final long accountId = Long.parseLong(objectMapper.readTree(
                result.getResponse().getContentAsString()).path("data").path("userId").asText());
        return tokenProvider.issueToken(accountId, Portal.MALL);
    }

    /**
     * 并发任务：设置指定地址为默认。
     *
     * @param token    访问令牌
     * @param id       地址 ID
     * @param start    起跑闩
     * @param okCount  成功计数
     */
    private void runSetDefault(String token, long id, CountDownLatch start, AtomicInteger okCount) {
        try {
            start.await();
            mockMvc.perform(put("/mall/addresses/" + id + "/default")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            okCount.incrementAndGet();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 并发任务：新增带默认标记的地址。
     *
     * @param token   访问令牌
     * @param start   起跑闩
     * @param okCount 成功计数
     */
    private void runAddWithDefault(String token, CountDownLatch start, AtomicInteger okCount) {
        try {
            start.await();
            mockMvc.perform(post("/mall/addresses")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"recipient":"并发买家","phone":"13800138000","province":"浙江省",
                                     "city":"杭州市","district":"西湖区","detail":"文一西路 100 号","isDefault":true}"""))
                    .andExpect(status().isOk());
            okCount.incrementAndGet();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 直落地址行（绕过接口，预置非默认地址）。
     *
     * @param token 访问令牌（解析买家 ID）
     * @param id    指定地址 ID
     * @return 地址 ID
     */
    private long insertAddress(String token, long id) {
        final long accountId = tokenProvider.parse(token).orElseThrow().uid();
        // 确保地址簿根行存在（address_book 主表），地址行以 book_id（rootId）关联
        final AddressBookPO book = addressBookRepository.findByAccountId(accountId)
                .orElseGet(() -> {
                    final AddressBookPO po = new AddressBookPO();
                    po.setId(com.nona.util.IDUtils.generateID());
                    po.setAccountId(accountId);
                    addressBookRepository.save(po);
                    return po;
                });
        final AddressPO po = new AddressPO();
        po.setId(id);
        po.setBookId(book.getId());
        po.setRecipient("张三");
        po.setPhone("13800138000");
        po.setProvince("浙江省");
        po.setCity("杭州市");
        po.setDistrict("西湖区");
        po.setDetail("文一西路 100 号");
        po.setIsDefault(false);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());
        addressRepository.save(po);
        return id;
    }

    /**
     * 库内默认地址 ID 列表（恰一行断言用）。
     *
     * @return 默认地址 ID 列表
     */
    private List<Long> defaultRows() {
        return addressRepository.findAll().stream()
                .filter(po -> Boolean.TRUE.equals(po.getIsDefault()))
                .map(AddressPO::getId)
                .toList();
    }
}