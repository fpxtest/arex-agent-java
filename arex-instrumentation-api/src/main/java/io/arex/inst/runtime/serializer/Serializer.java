package io.arex.inst.runtime.serializer;

import io.arex.agent.bootstrap.util.ArrayUtils;
import io.arex.agent.bootstrap.util.CollectionUtil;
import io.arex.agent.bootstrap.util.ReflectUtil;
import io.arex.agent.bootstrap.util.StringUtil;
import io.arex.inst.runtime.log.LogManager;
import io.arex.inst.runtime.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.*;

public class Serializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(Serializer.class);

    private static Serializer INSTANCE;

    public static Builder builder(StringSerializable defaultSerializer) {
        return new Builder(defaultSerializer);
    }

    public static Builder builder(List<StringSerializable> serializableList) {
        return new Builder(serializableList);
    }

    public static final String EMPTY_LIST_JSON = "[]";
    private static final String HASH_MAP_VALUES_CLASS = "java.util.HashMap$Values";
    private static final String ARRAY_LIST_CLASS = "java.util.ArrayList";
    public static final String SERIALIZE_SEPARATOR = "A@R#E$X";
    private static final String NULL_STRING = "null";
    private final StringSerializable defaultSerializer;
    private final Map<String, StringSerializable> serializers;

    /**
     * serialize throw throwable
     */
    public static String serializeWithException(Object object, String serializer) throws Throwable {
        if (object == null || INSTANCE == null) {
            return null;
        }

        Collection<Collection<?>> nestedCollection = TypeUtil.toNestedCollection(object);
        if (nestedCollection != null) {
            return serializeNestedCollection(serializer, nestedCollection);
        }

        return INSTANCE.getSerializer(serializer).serialize(object);
    }

    private static String serializeNestedCollection(String serializer, Collection<Collection<?>> nestedCollection) throws Throwable {
        StringBuilder jsonBuilder = new StringBuilder();
        Iterator<Collection<?>> collectionIterator = nestedCollection.iterator();
        while (collectionIterator.hasNext()) {
            Collection<?> collection = collectionIterator.next();
            if (collection == null) {
                jsonBuilder.append(NULL_STRING);
            } else if (collection.isEmpty()) {
                jsonBuilder.append(EMPTY_LIST_JSON);
            } else {
                jsonBuilder.append(serializeWithException(collection, serializer));
            }
            if (collectionIterator.hasNext()) {
                jsonBuilder.append(SERIALIZE_SEPARATOR);
            }
        }
        return jsonBuilder.toString();
    }

    /**
     * Serialize to string
     *
     * @param object object to be serialized
     * @return result string
     */
    public static String serialize(Object object) {
        if (object instanceof Throwable) {
            return serialize(object, "gson");
        }
        return serialize(object, null);
    }

    public static String serialize(Object object, String serializer) {
        try {
            return serializeWithException(object, serializer);
        } catch (Throwable ex) {
            LogManager.warn("serializer-serialize", StringUtil.format("can not serialize object: %s, cause: %s", TypeUtil.errorSerializeToString(object), ex.toString()));
            return null;
        }
    }

    /**
     * Deserialize by Class
     *
     * @param value String to be deserialized
     * @param clazz class to deserialize, example: com.xxx.xxxClass
     * @return T
     */
    public static <T> T deserialize(String value, Class<T> clazz) {
        if (StringUtil.isEmpty(value) || clazz == null) {
            return null;
        }

        try {
            return INSTANCE.getSerializer().deserialize(value, clazz);
        } catch (Throwable ex) {
            LogManager.warn("serializer-deserialize", StringUtil.format("can not deserialize value %s to class %s, cause: %s", value, clazz.getName(), ex.toString()));
            return null;
        }
    }

    /**
     * Deserialize by parameterized type
     *
     * @param value String to be deserialized
     * @param type Class type, example: {@code List<com.xxx.XXXType>}
     * @return T
     */
    public static <T> T deserialize(String value, Type type, String serializer) {
        if (StringUtil.isEmpty(value) || type == null) {
            return null;
        }

        try {
            return INSTANCE.getSerializer(serializer).deserialize(value, type);
        } catch (Throwable ex) {
            LogManager.warn("serializer-deserialize-type", StringUtil.format("can not deserialize value %s to type %s, cause: %s", value, type.getTypeName(), ex.toString()));
            return null;
        }
    }

    public static <T> T deserialize(String value, Type type) {
        return deserialize(value, type, null);
    }

    /**
     * Deserialization through type name mainly solves the two-level nesting of List {@code List<List<Object>>}
     *
     * @param value String to be deserialized
     * @param typeName Complex type name, example: java.util.ArrayList-java.util.ArrayList,com.xxx.XXXType
     * @return T
     */
    public static <T> T deserialize(String value, String typeName, String serializer) {
        if (StringUtil.isEmpty(value) || StringUtil.isEmpty(typeName)) {
            return null;
        }

        if (typeName.endsWith("Exception")) {
            serializer = "gson";
        }

        if (typeName.startsWith(HASH_MAP_VALUES_CLASS)) {
            return (T) restoreHashMapValues(value, typeName, serializer);
        }

        String[] typeNames = StringUtil.split(typeName, '-');
        if (ArrayUtils.isNotEmpty(typeNames) && typeNames.length > 1 && TypeUtil.isCollection(typeNames[0])) {
            String[] innerTypeNames = StringUtil.split(typeNames[1], ',');
            if (ArrayUtils.isNotEmpty(innerTypeNames) && TypeUtil.isCollection(innerTypeNames[0])) {
                return (T) deserializeNestedCollection(value, typeNames[0], innerTypeNames, serializer);
            }
        }

        return deserialize(value, TypeUtil.forName(typeName), serializer);
    }

    public static <T> T deserialize(String value, String typeName) {
        return deserialize(value, typeName, null);
    }

    /**
     * Deserialize nested collection
     * @param json json string
     * @param collectionType type name eg: java.util.HashSet-java.util.HashSet,java.lang.String,java.lang.String
     * @param serializer serializer
     */
    private static <T> Collection<Collection<T>> deserializeNestedCollection(String json, String collectionType,
        String[] innerCollectionType, String serializer) {
        Collection<Collection<T>> collection = ReflectUtil.getCollectionInstance(collectionType);
        if (collection == null) {
            return null;
        }

        if (ArrayUtils.isEmpty(innerCollectionType)) {
            return collection;
        }

        // Divide the json string according to the object separator added during serialization
        String[] jsonArray = StringUtil.splitByWholeSeparator(json, SERIALIZE_SEPARATOR);

        int elementIndex = 1;
        StringBuilder builder = new StringBuilder();
        for (String innerJson : jsonArray) {
            if (EMPTY_LIST_JSON.equals(innerJson)) {
                collection.add(ReflectUtil.getCollectionInstance(innerCollectionType[0]));
                continue;
            }

            if (StringUtil.isNullWord(innerJson)) {
                collection.add(null);
                continue;
            }

            if (innerCollectionType.length > elementIndex) {
                // The intercepted value and TypeName are deserialized in one-to-one correspondence
                builder.append(innerCollectionType[0]).append('-').append(innerCollectionType[elementIndex]);
                collection.add(deserialize(innerJson, TypeUtil.forName(builder.toString()), serializer));
                builder.setLength(0);
                elementIndex++;
            }
        }

        return collection;
    }

    private static Collection<?> restoreHashMapValues(String value, String typeName, String serializer) {
        String replacedTypeName = StringUtil.replace(typeName, HASH_MAP_VALUES_CLASS, ARRAY_LIST_CLASS);
        Collection<Object> collections =  deserialize(value, TypeUtil.forName(replacedTypeName), serializer);
        if (collections == null) {
            return CollectionUtil.emptyList();
        }
        Map<Integer, Object> map = new HashMap<>((int) (collections.size() / 0.75F + 1.0F));
        int count = 0;

        for (Object element : collections) {
            map.put(count++, element);
        }
        return map.values();
    }

    public static Serializer getINSTANCE() {
        return INSTANCE;
    }

    public Map<String, StringSerializable> getSerializers() {
        return serializers;
    }

    public StringSerializable getSerializer() {
        return defaultSerializer;
    }

    public StringSerializable getSerializer(String name) {
        if (name == null) {
            return defaultSerializer;
        }
        return serializers.get(name);
    }

    Serializer(StringSerializable defaultSerializer, Map<String, StringSerializable> serializers) {
        this.defaultSerializer = defaultSerializer;
        this.serializers = serializers;
    }

    public static class Builder {
        private StringSerializable defaultSerializer;
        private Map<String, StringSerializable> serializers = new HashMap<>();

        public Builder(StringSerializable defaultSerializer) {
            this.defaultSerializer = defaultSerializer;
        }

        public Builder(List<StringSerializable> serializableList) {
            for (StringSerializable serializable : serializableList) {
                if (serializable.isDefault()) {
                    this.defaultSerializer = serializable;
                    continue;
                }
                this.serializers.put(serializable.name(), serializable);
            }
        }

        public Builder addSerializer(String name, StringSerializable serializable) {
            serializers.put(name, serializable);
            return this;
        }

        public void build() {
            if (defaultSerializer == null) {
                LOGGER.error("Default serializer is not set");
                return;
            }
            Serializer.INSTANCE = new Serializer(defaultSerializer, Collections.unmodifiableMap(serializers));
        }
    }
}
