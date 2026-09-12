package com.nona.api.mall;

/**
 * 订单收货地址快照视图（订单视图嵌套地址，WU-59 回注）。
 * <p>
 * <b>字段名契约（与地址簿 AddressResponse 的 recipient 不同名）</b>：
 * 订单消费面由前端 order.types.ts {@code AddressSnapshot} 冻结——
 * 收件人为 {@code receiverName}（OrderDetailPage 直接消费
 * order.address.receiverName），与 AddressResponse 的 {@code recipient}
 * 是地址簿面字段名；两消费面字段名不同源，本视图按订单面冻结形态
 * 命名，不得与 AddressResponse 混用。
 * <p>
 * 六段全部必填非空（AddressSnapshot 构造守卫，下单时固化不可变）。
 *
 * @param receiverName 收件人
 * @param phone        联系电话
 * @param province     省份
 * @param city         城市
 * @param district     区县
 * @param detail       详细地址
 * @author nona9961
 */
public record OrderAddress(
        String receiverName,
        String phone,
        String province,
        String city,
        String district,
        String detail
) {
}