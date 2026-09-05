package com.nona.web.mall;

import com.nona.api.HttpResponse;
import com.nona.api.mall.AddressApi;
import com.nona.api.mall.AddressRequest;
import com.nona.api.mall.AddressResponse;
import com.nona.application.mall.AddressBookUseCase;
import com.nona.inf.context.TenantContextAccessor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家地址簿 REST 控制器（路由前缀 /mall/addresses，仅买家角色可访问）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380）+ 委托 {@link AddressBookUseCase}，不承载业务逻辑；
 * 当前买家账号 ID 从跟踪上下文 取（认证过滤器已填充，买家维度由此锚定）。
 *
 * @author nona9961
 */
@RestController
public class AddressController implements AddressApi {

    /**
     * 地址簿用例
     */
    private final AddressBookUseCase addressBookUseCase;

    /**
     * 请求上下文（取当前登录买家 ID）
     */
    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 构造地址簿控制器。
     *
     * @param addressBookUseCase 地址簿用例
     * @param threadContext      请求上下文
     */
    public AddressController(AddressBookUseCase addressBookUseCase, TenantContextAccessor tenantContextAccessor) {
        this.addressBookUseCase = addressBookUseCase;
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @GetMapping("/mall/addresses")
    public HttpResponse<List<AddressResponse>> list() {
        return HttpResponse.ok(addressBookUseCase.list(currentAccountId()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/mall/addresses")
    public HttpResponse<AddressResponse> add(@Valid @RequestBody AddressRequest request) {
        return HttpResponse.ok(addressBookUseCase.add(currentAccountId(), request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/mall/addresses/{id}")
    public HttpResponse<AddressResponse> update(@PathVariable("id") Long id, @Valid @RequestBody AddressRequest request) {
        return HttpResponse.ok(addressBookUseCase.update(currentAccountId(), id, request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @DeleteMapping("/mall/addresses/{id}")
    public HttpResponse<Void> delete(@PathVariable("id") Long id) {
        addressBookUseCase.delete(currentAccountId(), id);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/mall/addresses/{id}/default")
    public HttpResponse<Void> setDefault(@PathVariable("id") Long id) {
        addressBookUseCase.setDefault(currentAccountId(), id);
        return HttpResponse.ok();
    }

    /**
     * 当前登录买家账号 ID（认证过滤器填充）。
     *
     * @return 买家账号 ID
     */
    private Long currentAccountId() {
        return Long.valueOf(tenantContextAccessor.getIdentity());
    }
}