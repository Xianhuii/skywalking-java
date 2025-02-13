/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.util;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Init a class's static fields by a {@link Properties}, including static fields and static inner classes.
 * <p>
 */
public class ConfigInitializer {

    public static void initialize(Properties properties, Class<?> rootConfigType) throws IllegalAccessException {
        initNextLevel(properties, rootConfigType, new ConfigDesc());
    }

    private static void initNextLevel(Properties properties, Class<?> recentConfigType,
                                      ConfigDesc parentDesc) throws IllegalArgumentException, IllegalAccessException {
        for (Field field : recentConfigType.getFields()) {
            if (Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers())) { // public static属性
                String configKey = (parentDesc + "." + field.getName()).toLowerCase(); // 拼接前缀的key
                Class<?> type = field.getType();
                if (Map.class.isAssignableFrom(type)) { // Map类型
                    /*
                     * Map config format is, config_key[map_key]=map_value, such as plugin.opgroup.resttemplate.rule[abc]=/url/path
                     * "config_key[]=" will generate an empty Map , user could use this mechanism to set an empty Map
                     */
                    ParameterizedType genericType = (ParameterizedType) field.getGenericType();
                    Type[] argumentTypes = genericType.getActualTypeArguments();
                    Type keyType = argumentTypes[0];
                    Type valueType = argumentTypes[1];
                    // A chance to set an empty map
                    if (properties.containsKey(configKey + "[]")) {
                        Map currentValue = (Map) field.get(null);
                        if (currentValue != null && !currentValue.isEmpty()) {
                            field.set(null, initEmptyMap(type));
                        }
                    } else {
                        // Set the map from config key and properties
                        Map map = readMapType(type, configKey, properties, keyType, valueType);
                        if (map.size() != 0) {
                            field.set(null, map);
                        }
                    }
                } else if (properties.containsKey(configKey)) {
                    //In order to guarantee the default value could be reset as empty , we parse the value even if it's blank
                    String propertyValue = properties.getProperty(configKey, "");
                    if (Collection.class.isAssignableFrom(type)) { // Collection类型
                        ParameterizedType genericType = (ParameterizedType) field.getGenericType();
                        Type argumentType = genericType.getActualTypeArguments()[0];
                        Collection collection = convertToCollection(argumentType, type, propertyValue);
                        field.set(null, collection);
                    } else { // 其他基本类型
                        // Convert the value into real type
                        final Length lengthDefine = field.getAnnotation(Length.class);
                        if (lengthDefine != null) {
                            int lengthLimited = lengthDefine.value();
                            String lengthKey = String.format("%s#length", configKey);
                            if (properties.containsKey(lengthKey)) {
                                try {
                                    lengthLimited = Integer.valueOf(properties.getProperty(lengthKey));
                                } catch (NumberFormatException ex) {
                                    System.err.printf("The length config (%s=%s) is invalid. The value can not be cast to number.", lengthKey, properties.getProperty(lengthKey));
                                }
                            }
                            if (propertyValue.length() > lengthLimited) {
                                propertyValue = StringUtil.cut(propertyValue, lengthLimited);
                                System.err.printf("The config value will be truncated , because the length max than %d : %s -> %s%n", lengthDefine.value(), configKey, propertyValue);
                            }
                        }
                        Object convertedValue = convertToTypicalType(type, propertyValue);
                        if (convertedValue != null) {
                            field.set(null, convertedValue);
                        }
                    }
                }

            }
        }
        // 递归设置内部类的静态变量
        for (Class<?> innerConfiguration : recentConfigType.getClasses()) {
            parentDesc.append(innerConfiguration.getSimpleName());
            initNextLevel(properties, innerConfiguration, parentDesc);
            parentDesc.removeLastDesc();
        }
    }

    private static Collection<Object> convertToCollection(Type argumentType, Class<?> type, String propertyValue) {
        Collection<Object> collection;
        if (type.equals(Set.class) || type.equals(HashSet.class)) {
            collection = new HashSet<>();
        } else if (type.equals(TreeSet.class)) {
            collection = new TreeSet<>();
        } else if (type.equals(List.class) || type.equals(LinkedList.class)) {
            collection = new LinkedList<>();
        } else if (type.equals(ArrayList.class)) {
            collection = new ArrayList<>();
        } else {
            throw new UnsupportedOperationException("Config parameter type support Set,HashSet,TreeSet,List,LinkedList,ArrayList");
        }
        if (StringUtil.isBlank(propertyValue)) {
            return collection;
        }
        Arrays.stream(propertyValue.split(","))
                .map(v -> convertToTypicalType(argumentType, v))
                .forEach(collection::add);
        return collection;
    }

    /**
     * Convert string value to typical type.
     *
     * @param type  type to convert
     * @param value string value to be converted
     * @return converted value or null
     */
    private static Object convertToTypicalType(Type type, String value) {
        if (StringUtil.isBlank(value)) {
            return null;
        }
        Object result = null;
        if (String.class.equals(type)) {
            result = value;
        } else if (int.class.equals(type) || Integer.class.equals(type)) {
            result = Integer.valueOf(value);
        } else if (long.class.equals(type) || Long.class.equals(type)) {
            result = Long.valueOf(value);
        } else if (boolean.class.equals(type) || Boolean.class.equals(type)) {
            result = Boolean.valueOf(value);
        } else if (float.class.equals(type) || Float.class.equals(type)) {
            result = Float.valueOf(value);
        } else if (double.class.equals(type) || Double.class.equals(type)) {
            result = Double.valueOf(value);
        } else if (type instanceof Class) {
            Class<?> clazz = (Class<?>) type;
            if (clazz.isEnum()) {
                result = Enum.valueOf((Class<Enum>) type, value.toUpperCase());
            }
        }
        return result;
    }

    /**
     * Set map items.
     *
     * @param type       the filed type
     * @param configKey  config key must not be null
     * @param properties properties must not be null
     * @param keyType    key type of the map
     * @param valueType  value type of the map
     */
    private static Map readMapType(Class<?> type,
                                   String configKey,
                                   Properties properties,
                                   final Type keyType,
                                   final Type valueType) {

        Objects.requireNonNull(configKey);
        Objects.requireNonNull(properties);
        Map<Object, Object> map = initEmptyMap(type);
        String prefix = configKey + "[";
        String suffix = "]";
        properties.forEach((propertyKey, propertyValue) -> {
            String propertyStringKey = propertyKey.toString();
            if (propertyStringKey.startsWith(prefix) && propertyStringKey.endsWith(suffix)) {
                String itemKey = propertyStringKey.substring(
                        prefix.length(), propertyStringKey.length() - suffix.length());
                Object keyObj;
                Object valueObj;

                keyObj = convertToTypicalType(keyType, itemKey);
                valueObj = convertToTypicalType(valueType, propertyValue.toString());

                if (keyObj == null) {
                    keyObj = itemKey;
                }

                if (valueObj == null) {
                    valueObj = propertyValue;
                }
                map.put(keyObj, valueObj);
            }
        });
        return map;
    }

    private static Map<Object, Object> initEmptyMap(Class<?> type) {
        if (type.equals(Map.class) || type.equals(HashMap.class)) {
            return new HashMap<>();
        } else if (type.equals(TreeMap.class)) {
            return new TreeMap<>();
        } else {
            throw new UnsupportedOperationException("Config parameter type support Map,HashMap,TreeMap");
        }
    }
}

class ConfigDesc {
    private LinkedList<String> descs = new LinkedList<>();

    void append(String currentDesc) {
        if (StringUtil.isNotEmpty(currentDesc)) {
            descs.addLast(currentDesc);
        }
    }

    void removeLastDesc() {
        descs.removeLast();
    }

    @Override
    public String toString() {
        return String.join(".", descs);
    }
}
