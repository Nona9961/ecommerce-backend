/**
 * 支付渠道基础设施（inf.payment）：PaymentGateway 端口实现——一期唯一
 * 实现为 mock 渠道（三剧本仿真：成功/失败/不回调）。真实渠道接入时在
 * 本包新增实现类替换 mock（域与编排层零改动，与身份域令牌端口实现落
 * inf.security 同构）。
 */
package com.nona.inf.payment;