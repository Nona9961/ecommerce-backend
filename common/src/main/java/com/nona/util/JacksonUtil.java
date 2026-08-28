package com.nona.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nona.exceptions.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.Objects;
import com.nona.annotation.ScaffoldGenerated;

/**
 * JSON 序列化 / 反序列化工具，基于 Jackson。
 * <p>
 * 提供线程安全的默认 {@link ObjectMapper}（已注册 {@link JavaTimeModule}），
 * 并封装了对象 ↔ JSON / JsonNode 的双向转换；转换失败统一抛出 {@link BusinessException}。
 */
@Slf4j
@ScaffoldGenerated
public class JacksonUtil {

    /**
     * 默认线程安全 ObjectMapper（注册 JavaTimeModule，忽略未知字段与空 bean）
     */
    public static final ObjectMapper DEFAULT_MAPPER = init();

    /**
     * 初始化默认 ObjectMapper。
     *
     * @return 配置好的 ObjectMapper
     */
    private static ObjectMapper init() {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        return mapper;
    }

    /**
     * 对象转 JSON 字符串（使用默认 mapper）。
     *
     * @param object 待转换对象
     * @return JSON 字符串；输入为 null 时返回 null
     */
    public static String toJsonString(Object object) {
        return toJsonString(object, DEFAULT_MAPPER);
    }

    /**
     * 对象转 JsonNode（使用默认 mapper）。
     *
     * @param object 待转换对象
     * @return JsonNode；输入为 null 时返回 null
     */
    public static JsonNode toJsonNode(Object object) {
        return toJsonNode(object, DEFAULT_MAPPER);
    }

    /**
     * 对象转 ObjectNode（使用默认 mapper）。
     *
     * @param object 待转换对象
     * @return ObjectNode
     * @throws IllegalArgumentException 转换结果不是对象节点时抛出
     */
    public static ObjectNode toObjectNode(Object object) {
        return toObjectNode(object, DEFAULT_MAPPER);
    }

    /**
     * 对象转 ArrayNode（使用默认 mapper）。
     *
     * @param object 待转换对象
     * @return ArrayNode
     * @throws IllegalArgumentException 转换结果不是数组节点时抛出
     */
    public static ArrayNode toArrayNode(Object object) {
        return toArrayNode(object, DEFAULT_MAPPER);
    }

    /**
     * JSON 字符串转 JsonNode（使用默认 mapper）。
     *
     * @param json JSON 字符串
     * @return JsonNode；输入为空时返回 null
     */
    public static JsonNode jsonToNode(String json) {
        return jsonToNode(json, DEFAULT_MAPPER);
    }

    /**
     * JSON 字符串转 ObjectNode（使用默认 mapper）。
     *
     * @param json JSON 字符串
     * @return ObjectNode
     * @throws IllegalArgumentException JSON 不是对象时抛出
     */
    public static ObjectNode jsonToObjNode(String json) {
        return jsonToObjNode(json, DEFAULT_MAPPER);
    }

    /**
     * JSON 字符串转 ArrayNode（使用默认 mapper）。
     *
     * @param json JSON 字符串
     * @return ArrayNode
     * @throws IllegalArgumentException JSON 不是数组时抛出
     */
    public static ArrayNode jsonToArrayNode(String json) {
        return jsonToArrayNode(json, DEFAULT_MAPPER);
    }

    /**
     * JSON 字符串转对象（使用默认 mapper）。
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @return 目标对象；输入为空时返回 null
     * @param <T> 目标类型参数
     */
    public static <T> T fromJsonString(String json, Class<T> clazz) {
        return fromJsonString(json, clazz, DEFAULT_MAPPER);
    }

    /**
     * JsonNode 转对象（使用默认 mapper）。
     *
     * @param jsonNode JSON 节点
     * @param clazz    目标类型
     * @return 目标对象；输入为 null 时返回 null
     * @param <T> 目标类型参数
     */
    public static <T> T fromJsonNode(JsonNode jsonNode, Class<T> clazz) {
        return fromJsonNode(jsonNode, clazz, DEFAULT_MAPPER);
    }

    /**
     * JSON 字符串转泛型对象（使用默认 mapper，支持 TypeReference）。
     *
     * @param json          JSON 字符串
     * @param typeReference 泛型类型引用
     * @return 目标对象；输入为空时返回 null
     * @param <T> 目标类型参数
     */
    public static <T> T fromJsonString(String json, TypeReference<T> typeReference) {
        return fromJsonString(json, typeReference, DEFAULT_MAPPER);
    }

    /**
     * JsonNode 转泛型对象（使用默认 mapper，支持 TypeReference）。
     *
     * @param jsonNode      JSON 节点
     * @param typeReference 泛型类型引用
     * @return 目标对象；输入为 null 时返回 null
     * @param <T> 目标类型参数
     */
    public static <T> T fromJsonNode(JsonNode jsonNode, TypeReference<T> typeReference) {
        return fromJsonNode(jsonNode, typeReference, DEFAULT_MAPPER);
    }

    /**
     * 校验 JSON 字符串的起始字符，用于快速判断 JSON 类型。
     *
     * @param json        JSON 字符串
     * @param checkObject true 校验对象起始符 '{'；false 校验数组起始符 '['
     * @return 起始字符匹配返回 true；输入为空时返回 false
     */
    public static boolean startTokenCheck(String json, boolean checkObject) {
        if (StringUtils.isBlank(json)) {
            return false;
        }
        final String trim = json.trim();
        return checkObject ? trim.startsWith("{") : trim.startsWith("[");
    }

    /**
     * 判断 JSON 字符串是否为合法对象。
     *
     * @param json JSON 字符串
     * @return 可解析为对象返回 true，否则返回 false
     */
    public static boolean isObject(String json) {
        try {
            jsonToObjNode(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 对象转 ObjectNode。
     *
     * @param object 待转换对象
     * @param mapper 使用的 ObjectMapper
     * @return ObjectNode
     * @throws IllegalArgumentException 转换结果不是对象节点时抛出
     */
    public static ObjectNode toObjectNode(Object object, ObjectMapper mapper) {
        final JsonNode node = toJsonNode(object, mapper);
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        throw new IllegalArgumentException("Cannot convert object to ObjectNode: " + object);
    }

    /**
     * 对象转 ArrayNode。
     *
     * @param object 待转换对象
     * @param mapper 使用的 ObjectMapper
     * @return ArrayNode
     * @throws IllegalArgumentException 转换结果不是数组节点时抛出
     */
    public static ArrayNode toArrayNode(Object object, ObjectMapper mapper) {
        final JsonNode node = toJsonNode(object, mapper);
        if (node instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        throw new IllegalArgumentException("Cannot convert object to ArrayNode: " + object);
    }

    /**
     * JSON 字符串转 ObjectNode。
     *
     * @param json   JSON 字符串
     * @param mapper 使用的 ObjectMapper
     * @return ObjectNode
     * @throws IllegalArgumentException JSON 不是对象时抛出
     */
    public static ObjectNode jsonToObjNode(String json, ObjectMapper mapper) {
        final JsonNode node = jsonToNode(json, mapper);
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        throw new IllegalArgumentException("JSON is not an object: " + json);
    }

    /**
     * JSON 字符串转 ArrayNode。
     *
     * @param json   JSON 字符串
     * @param mapper 使用的 ObjectMapper
     * @return ArrayNode
     * @throws IllegalArgumentException JSON 不是数组时抛出
     */
    public static ArrayNode jsonToArrayNode(String json, ObjectMapper mapper) {
        final JsonNode node = jsonToNode(json, mapper);
        if (node instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        throw new IllegalArgumentException("JSON is not an array: " + json);
    }

    /**
     * 对象转 JSON 字符串。
     *
     * @param object 待转换对象
     * @param mapper 使用的 ObjectMapper
     * @return JSON 字符串；输入为 null 时返回 null
     */
    public static String toJsonString(Object object, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "ObjectMapper must not be null");
        if (object == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            handleException(e, object);
            return null;
        }
    }

    /**
     * 对象转 JsonNode。
     *
     * @param object 待转换对象
     * @param mapper 使用的 ObjectMapper
     * @return JsonNode；输入为 null 时返回 null
     */
    public static JsonNode toJsonNode(Object object, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "ObjectMapper must not be null");
        if (object == null) {
            return null;
        }
        try {
            return mapper.valueToTree(object);
        } catch (IllegalArgumentException e) {
            handleException(e, object);
            return null;
        }
    }

    /**
     * JSON 字符串转 JsonNode。
     *
     * @param json   JSON 字符串
     * @param mapper 使用的 ObjectMapper
     * @return JsonNode；输入为空时返回 null
     */
    public static JsonNode jsonToNode(String json, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "Object Mapper must not be null");
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            handleException(e, json);
            return null;
        }
    }

    /**
     * JSON 字符串转对象。
     *
     * @param json   JSON 字符串
     * @param clazz  目标类型
     * @param mapper 使用的 ObjectMapper
     * @return 目标对象；输入为空时返回 null
     * @param <T> 目标类型参数
     */
    public static <T> T fromJsonString(String json, Class<T> clazz, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "Object Mapper must not be null");
        Objects.requireNonNull(clazz, "Target class must not be null");
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return mapper.readValue(json, clazz);
        } catch (IOException e) {
            handleException(e, json);
            return null;
        }
    }

    /**
     * JsonNode 转对象。
     *
     * @param jsonNode JSON 节点
     * @param clazz    目标类型
     * @param mapper   使用的 ObjectMapper
     * @return 目标对象；输入为 null 时返回 null
     * @param <T> 目标类型参数
     */
    public static <T> T fromJsonNode(JsonNode jsonNode, Class<T> clazz, ObjectMapper mapper) {
        Objects.requireNonNull(clazz, "Target class must not be null");
        Objects.requireNonNull(mapper, "Object Mapper must not be null");
        if (jsonNode == null) {
            return null;
        }
        try {
            return mapper.treeToValue(jsonNode, clazz);
        } catch (IOException e) {
            handleException(e, jsonNode);
            return null;
        }
    }

    /**
     * JSON 字符串转泛型对象。
     *
     * @param json          JSON 字符串
     * @param typeReference 泛型类型引用
     * @param mapper        使用的 ObjectMapper
     * @return 目标对象；输入为空时返回 null
     * @param <T> 目标类型参数
     */
    public static <T> T fromJsonString(String json, TypeReference<T> typeReference, ObjectMapper mapper) {
        Objects.requireNonNull(typeReference, "TypeReference must not be null");
        Objects.requireNonNull(mapper, "Object Mapper must not be null");
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return mapper.readValue(json, typeReference);
        } catch (IOException e) {
            handleException(e, json);
            return null;
        }
    }

    /**
     * JsonNode 转泛型对象。
     *
     * @param jsonNode      JSON 节点
     * @param typeReference 泛型类型引用
     * @param mapper        使用的 ObjectMapper
     * @return 目标对象；输入为 null 时返回 null
     * @param <T> 目标类型参数
     */
    public static <T> T fromJsonNode(JsonNode jsonNode, TypeReference<T> typeReference, ObjectMapper mapper) {
        Objects.requireNonNull(typeReference, "TypeReference must not be null");
        Objects.requireNonNull(mapper, "Object Mapper must not be null");
        if (jsonNode == null) {
            return null;
        }
        final JavaType javaType = mapper.getTypeFactory().constructType(typeReference);
        return mapper.convertValue(jsonNode, javaType);
    }

    /**
     * 统一异常处理：记录日志并抛出业务异常，不向调用方泄露内部细节。
     *
     * @param e     转换异常
     * @param input 导致失败的输入
     */
    private static void handleException(Exception e, Object input) {
        log.error("Jackson operation failed for input: {}", input, e);
        throw new BusinessException("internal error");
    }
}
