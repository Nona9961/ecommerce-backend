package com.nona.application.support;

import com.nona.api.auth.LoginRequest;
import com.nona.api.auth.LoginResponse;
import com.nona.api.auth.Portal;
import com.nona.api.auth.RegisterRequest;
import com.nona.api.auth.RegisterResponse;
import com.nona.api.common.ErrorCode;
import com.nona.domain.identity.entity.Account;
import com.nona.domain.identity.entity.AccountStatus;
import com.nona.domain.identity.entity.AccountType;
import com.nona.domain.identity.factory.AccountFactory;
import com.nona.domain.identity.ports.CredentialService;
import com.nona.domain.identity.ports.IssuedToken;
import com.nona.domain.identity.ports.TokenService;
import com.nona.domain.identity.repo.AccountRepository;
import com.nona.exceptions.BusinessException;
import com.nona.inf.persistence.po.identity.AccountShopRelPO;
import com.nona.inf.persistence.repository.jpa.AccountShopRelJpaRepository;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.AuthUserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 认证用例（跨端共用）：注册 / 登录 / 登出编排。
 * <p>
 * 登录编排：按门户定向查单表账号（type 匹配）→ 凭证校验（BCrypt，失败统一消息
 * 防账号存在性泄露）→ 账号状态校验（BANNED 拒绝登录，COMMON_FORBIDDEN → HTTP 403，
 * C11）→ 读取 M10 关联组装 shopIds（U5，一期空列表合法）→ 回填 Redis 用户上下文
 * （status/roles/shopIds）→ 签发 JWT {uid, portal, exp}。平台运营（admin）账号
 * 无落点（RBAC 属 WU-10/Phase-II），portal=ADMIN 拒绝（fail-closed）。
 * 事务边界 = 本用例方法。
 *
 * @author nona9961
 */
@Service
public class AuthUseCase {

    /**
     * 凭证失败统一消息（查无账号 / 密码错误 / 门户不匹配同一消息，防存在性泄露）
     */
    private static final String INVALID_CREDENTIALS = "用户名或密码错误";

    /**
     * 账号仓储（单表 account）
     */
    private final AccountRepository accountRepository;

    /**
     * 账号-店铺关联 JPA 仓储（M10，登录组装 shopIds）
     */
    private final AccountShopRelJpaRepository accountShopRelRepository;

    /**
     * 账号聚合工厂
     */
    private final AccountFactory accountFactory;

    /**
     * 凭证端口（BCrypt）
     */
    private final CredentialService credentialService;

    /**
     * 令牌签发端口（JWT）
     */
    private final TokenService tokenService;

    /**
     * 用户上下文缓存（登录回填）
     */
    private final AuthUserCache authUserCache;

    /**
     * 构造认证用例。
     *
     * @param accountRepository       账号仓储
     * @param accountShopRelRepository 账号-店铺关联仓储
     * @param accountFactory           账号聚合工厂
     * @param credentialService        凭证端口
     * @param tokenService             令牌签发端口
     * @param authUserCache            用户上下文缓存
     */
    public AuthUseCase(AccountRepository accountRepository,
                       AccountShopRelJpaRepository accountShopRelRepository,
                       AccountFactory accountFactory,
                       CredentialService credentialService,
                       TokenService tokenService,
                       AuthUserCache authUserCache) {
        this.accountRepository = accountRepository;
        this.accountShopRelRepository = accountShopRelRepository;
        this.accountFactory = accountFactory;
        this.credentialService = credentialService;
        this.tokenService = tokenService;
        this.authUserCache = authUserCache;
    }

    /**
     * 注册：门户映射账号类型（MALL→BUYER、SELLER→SELLER；ADMIN 拒绝注册——
     * 平台员工由平台创建）；同 type 用户名重复即业务拒绝；凭证经 BCrypt 加密后
     * 由工厂创建账号并落库（买家即时生效；商家仅建账号，开店走入驻审核）。
     *
     * @param request 注册请求
     * @return 新建账号的用户 ID
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        final AccountType type = resolveTypeForRegister(request.portal());
        if (accountRepository.findByTypeAndUsername(type, request.username()).isPresent()) {
            throw new BusinessException("用户名已存在");
        }
        final Account account = accountFactory.createAccount(
                type, request.username(), request.password(), credentialService);
        accountRepository.save(account);
        return new RegisterResponse(account.getId());
    }

    /**
     * 登录：定向查单表（type 匹配，查无即统一失败消息）→ BCrypt 凭证校验
     * （失败统一消息防存在性泄露）→ BANNED 拒绝（COMMON_FORBIDDEN → HTTP 403，
     * C11）→ 读取 M10 关联组装 shopIds → 回填用户上下文缓存 → 签发 JWT。
     *
     * @param request 登录请求
     * @return 登录响应（token/过期时间/店铺 ID 列表）
     */
    public LoginResponse login(LoginRequest request) {
        final AccountType type = resolveTypeForLogin(request.portal());
        final Account account = accountRepository.findByTypeAndUsername(type, request.username())
                .orElseThrow(() -> new BusinessException(INVALID_CREDENTIALS));
        if (!credentialService.matches(request.password(), account.getPasswordHash())) {
            throw new BusinessException(INVALID_CREDENTIALS);
        }
        if (account.getStatus() == AccountStatus.BANNED) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN.defaultMessage(),
                    ErrorCode.COMMON_FORBIDDEN.code());
        }
        final List<Long> shopIds = accountShopRelRepository.findByAccountId(account.getId()).stream()
                .map(AccountShopRelPO::getShopId)
                .toList();
        authUserCache.put(account.getId(), new AuthUserContext(
                com.nona.inf.security.AccountStatus.ACTIVE,
                List.of(account.getType().name()),
                shopIds));
        final IssuedToken issued = tokenService.issueToken(account.getId(), request.portal());
        return new LoginResponse(issued.token(), issued.expiresAt().toString(), shopIds);
    }

    /**
     * 登出：无状态 JWT 语义下服务端无会话可销毁，登出即客户端丢弃 token；
     * 本方法为幂等收口（恒成功，无服务端状态可清理）。
     *
     * @param uid 当前登录用户 ID
     */
    public void logout(Long uid) {
    }

    /**
     * 注册门户 → 账号类型映射；ADMIN 拒绝注册（平台员工由平台创建）。
     *
     * @param portal 注册门户
     * @return 账号类型
     */
    private static AccountType resolveTypeForRegister(Portal portal) {
        return switch (portal) {
            case MALL -> AccountType.BUYER;
            case SELLER -> AccountType.SELLER;
            case ADMIN -> throw new BusinessException("平台账号不接受注册");
        };
    }

    /**
     * 登录门户 → 账号类型映射；ADMIN 无落点，统一失败消息（fail-closed 防泄露）。
     *
     * @param portal 登录门户
     * @return 账号类型
     */
    private static AccountType resolveTypeForLogin(Portal portal) {
        return switch (portal) {
            case MALL -> AccountType.BUYER;
            case SELLER -> AccountType.SELLER;
            case ADMIN -> throw new BusinessException(INVALID_CREDENTIALS);
        };
    }
}
