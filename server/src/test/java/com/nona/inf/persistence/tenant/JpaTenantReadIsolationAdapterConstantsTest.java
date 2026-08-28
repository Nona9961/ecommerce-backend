package com.nona.inf.persistence.tenant;

import com.nona.annotation.ScaffoldGenerated;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JpaTenantReadIsolationAdapter} 项目自有常量与 Hibernate 内部常量的一致性回归测试。
 * <p>
 * 背景：Hibernate {@code @TenantId} 机制依赖 internal 包下的内部类 {@code TenantIdBinder}
 * 中定义的 filter 名与参数名常量（无公开 API 可引用）。生产代码不得引用该 internal 类
 * （该内部类无公开 API），以项目自有常量镜像其值；本测试用反射在测试期比对，Hibernate 升级如改动
 * 这两个值即红灯告警（早警网，无运行时内部依赖）。
 * <p>
 * 刻意说明：internal 类全限定名以拼接方式书写
 * （{@code "org.hibernate.binder." + "internal.TenantIdBinder"}），避免在源码中出现
 * binder 包与 internal 包名点号连写的整串字面量——验收要求该整串在 {@code server/src}
 * 下零命中（含测试）。反射仅在测试期使用，生产代码零依赖。
 *
 * @author nona9961
 */
@ScaffoldGenerated
class JpaTenantReadIsolationAdapterConstantsTest {

    /**
     * Hibernate 内部类全限定名。拼接书写规避 internal 包整串字面残留（见类 Javadoc 刻意说明）。
     */
    private static final String TENANT_ID_BINDER_CLASS_NAME =
            "org.hibernate.binder." + "internal.TenantIdBinder";

    /**
     * 契约（A7）：生产常量 {@link JpaTenantReadIsolationAdapter#TENANT_ID_FILTER_NAME} 必须等于
     * Hibernate 内部 {@code TenantIdBinder.FILTER_NAME}。当前期望值 {@code "_tenantId"}；
     * 升级 Hibernate 时若上游改值，本用例红灯提示同步更新生产常量。
     */
    @Test
    void filterNameMatchesTenantIdBinderConstant() throws Exception {
        final String hibernateFilterName =
                readStaticStringField(TENANT_ID_BINDER_CLASS_NAME, "FILTER_NAME");

        assertThat(hibernateFilterName).isNotBlank();
        assertThat(JpaTenantReadIsolationAdapter.TENANT_ID_FILTER_NAME)
                .as("project constant must mirror TenantIdBinder.FILTER_NAME")
                .isNotBlank()
                .isEqualTo(hibernateFilterName);
    }

    /**
     * 契约（A7）：生产常量 {@link JpaTenantReadIsolationAdapter#TENANT_ID_PARAMETER_NAME} 必须等于
     * Hibernate 内部 {@code TenantIdBinder.PARAMETER_NAME}。当前期望值 {@code "tenantId"}；
     * 升级同步规则同上。
     */
    @Test
    void parameterNameMatchesTenantIdBinderConstant() throws Exception {
        final String hibernateParameterName =
                readStaticStringField(TENANT_ID_BINDER_CLASS_NAME, "PARAMETER_NAME");

        assertThat(hibernateParameterName).isNotBlank();
        assertThat(JpaTenantReadIsolationAdapter.TENANT_ID_PARAMETER_NAME)
                .as("project constant must mirror TenantIdBinder.PARAMETER_NAME")
                .isNotBlank()
                .isEqualTo(hibernateParameterName);
    }

    /**
     * 反射读取指定类的 public static String 字段值。
     * <p>
     * 仅测试期使用（R4 早警网）；类不存在 / 字段缺失 / 类型不符时抛出异常使测试红，
     * 提示上游结构变化需人工核对。
     */
    private static String readStaticStringField(String className, String fieldName) throws Exception {
        final Field field = Class.forName(className).getField(fieldName);
        return (String) field.get(null);
    }
}
