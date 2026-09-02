package com.nona.util;

import com.nona.exceptions.BusinessCode;
import com.nona.exceptions.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.helpers.MessageFormatter;

import java.util.Objects;
import com.nona.annotation.ScaffoldGenerated;


/**
 * 常用校验并抛出业务异常的工具类。
 * <p>
 * 消息模板支持 {@code {}} 占位符，经 slf4j {@link MessageFormatter} 格式化。
 * 每个入口均提供两种形态：message-only（业务码为 null、HTTP 状态兜底 500）与
 * 带 businessCode（小写点分 {@code domain.reason}；未登记码经
 * {@link BusinessCode#defaultStatus(String)} 兜底 500，fail-closed）。
 */
@Slf4j
@ScaffoldGenerated
public class BusinessAssert {

    /**
     * 私有构造器，禁止实例化。
     *
     * @throws IllegalAccessException 总是抛出
     */
    private BusinessAssert() throws IllegalAccessException {
        throw new IllegalAccessException();
    }

    /**
     * 断言对象非空，否则抛出业务异常。
     *
     * @param object  待校验对象
     * @param message 异常信息模板，支持{}占位符格式
     * @param args    用于格式化消息模板的参数列表
     */
    public static void assertNonNull(Object object, String message, Object... args) {
        assertTrue(Objects.nonNull(object), message, args);
    }

    /**
     * 断言是true，如果不是，则抛出业务异常
     *
     * @param condition 判断表达式
     * @param message   异常信息模板，支持{}占位符格式
     * @param args      用于格式化消息模板的参数列表
     */
    public static void assertTrue(boolean condition, String message, Object... args) {
        if (!condition) {
            throwBusiness(message, args);
        }
    }

    /**
     * 抛出业务异常，支持使用占位符{}动态修改消息
     *
     * @param message 异常信息模板，支持{}占位符格式
     * @param args    用于格式化消息模板的参数列表
     */
    public static void throwBusiness(String message, Object... args) {
        throw generateExByMsg(message, args);
    }

    /**
     * 生成业务异常，支持使用占位符{}动态修改消息。
     *
     * @param message 异常信息模板，支持{}占位符格式
     * @param args    用于格式化消息模板的参数列表
     * @return 构造好的业务异常
     */
    public static BusinessException generateExByMsg(String message, Object... args) {
        if (ArrayUtils.isNotEmpty(args)) {
            message = MessageFormatter.arrayFormat(message, args).getMessage();
        }
        if (log.isDebugEnabled()) {
            log.debug(message);
        }
        return new BusinessException(message);
    }

    /**
     * 断言对象非空，否则抛出带业务码的业务异常。
     *
     * @param businessCode 业务码（小写点分 domain.reason；未登记码兜底 500）
     * @param object       待校验对象
     * @param message      异常信息模板，支持{}占位符格式
     * @param args         用于格式化消息模板的参数列表
     */
    public static void assertNonNull(String businessCode, Object object, String message, Object... args) {
        assertTrue(businessCode, Objects.nonNull(object), message, args);
    }

    /**
     * 断言是true，如果不是，则抛出带业务码的业务异常。
     *
     * @param businessCode 业务码（小写点分 domain.reason；未登记码兜底 500）
     * @param condition    判断表达式
     * @param message      异常信息模板，支持{}占位符格式
     * @param args         用于格式化消息模板的参数列表
     */
    public static void assertTrue(String businessCode, boolean condition, String message, Object... args) {
        if (!condition) {
            throwBusinessWithCode(businessCode, message, args);
        }
    }

    /**
     * 抛出带业务码的业务异常，支持使用占位符{}动态修改消息。
     *
     * @param businessCode 业务码（小写点分 domain.reason；未登记码兜底 500）
     * @param message      异常信息模板，支持{}占位符格式
     * @param args         用于格式化消息模板的参数列表
     */
    public static void throwBusinessWithCode(String businessCode, String message, Object... args) {
        throw generateExByMsgWithCode(businessCode, message, args);
    }

    /**
     * 生成带业务码的业务异常，支持使用占位符{}动态修改消息。
     *
     * @param businessCode 业务码（小写点分 domain.reason；未登记码经
     *                     {@link BusinessCode#defaultStatus(String)} 兜底 500，fail-closed）
     * @param message      异常信息模板，支持{}占位符格式
     * @param args         用于格式化消息模板的参数列表
     * @return 构造好的业务异常
     */
    public static BusinessException generateExByMsgWithCode(String businessCode, String message, Object... args) {
        if (ArrayUtils.isNotEmpty(args)) {
            message = MessageFormatter.arrayFormat(message, args).getMessage();
        }
        if (log.isDebugEnabled()) {
            log.debug(message);
        }
        return new BusinessException(businessCode, message);
    }

}
