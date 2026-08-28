package com.nona.inf.persistence.converters;

import com.nona.inf.persistence.po.BasePO;

import java.util.Collections;
import java.util.Map;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 组合转换器（聚合根，支持子表）
 * <p>
 * 用于处理聚合根及其子实体的转换。
 *
 * @param <Root>   聚合根类型
 * @param <MainPO> 主表 PO 类型
 */
@ScaffoldGenerated
public interface CompositePoConverter<Root, MainPO extends BasePO> {

    /**
     * 获取聚合根类型。
     *
     * @return 聚合根类型
     */
    Class<Root> rootClass();

    /**
     * 获取主表 PO 类型。
     *
     * @return 主表 PO 类型
     */
    Class<MainPO> mainPoClass();

    /**
     * 聚合根转主表 PO。
     *
     * @param root 聚合根
     * @return 主表 PO
     */
    MainPO toMainPO(Root root);

    /**
     * 主表 PO + 子表数据 转聚合根。
     *
     * @param mainPO    主表 PO
     * @param childData 子表数据，key 为属性路径，value 为单个对象或集合
     * @return 聚合根
     */
    Root toRoot(MainPO mainPO, Map<String, Object> childData);

    /**
     * 手动声明子实体映射（可选）
     * <p>
     * - 可覆盖自动扫描的映射
     * - 可新增自动扫描无法识别的映射（如泛型擦除、接口类型等）
     * <p>
     * 优先级：手动声明 > 自动扫描
     *
     * @return 属性路径 -> 转换器 的映射
     */
    default Map<String, PoConverter<?, ?>> declareChildConverters() {
        return Collections.emptyMap();
    }
}
