package com.github.huaye2007.mana.jpa.docdb.cache;

import com.github.huaye2007.mana.jpa.cache.CacheCompositeKey;
import com.github.huaye2007.mana.jpa.cache.annotation.CacheKey;
import com.github.huaye2007.mana.jpa.core.metadata.FieldAccessor;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadata;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocFieldMetadata;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DocDB 缓存键元数据。
 * 启动时解析 @CacheKey 注解，按 order 排序，预构建 FieldAccessor 数组。
 * 运行时直接通过 accessor 高性能提取缓存键值。
 */
public class DocCacheKeyMeta {

    private final FieldAccessor[] accessors;
    private final String[] propertyNames;
    private final int roleIdIndex;

    private DocCacheKeyMeta(FieldAccessor[] accessors, String[] propertyNames, int roleIdIndex) {
        this.accessors = accessors;
        this.propertyNames = propertyNames;
        this.roleIdIndex = roleIdIndex;
    }

    /**
     * 从文档实体元数据解析 @CacheKey 注解
     */
    public static DocCacheKeyMeta resolve(DocEntityMetadata metadata) {
        List<Entry> entries = new ArrayList<>();

        for (DocFieldMetadata fieldMeta : metadata.fields()) {
            Field javaField = fieldMeta.javaField();
            CacheKey ann = javaField.getAnnotation(CacheKey.class);
            if (ann != null) {
                entries.add(new Entry(ann.order(), fieldMeta));
            }
        }

        if (entries.isEmpty()) {
            throw new IllegalStateException(
                    "Entity " + metadata.entityType().getName() + " has no @CacheKey fields. " +
                    "IDocMultiCacheRepository requires at least one @CacheKey annotated field.");
        }

        entries.sort(Comparator.comparingInt(e -> e.order));

        FieldAccessor[] accessors = new FieldAccessor[entries.size()];
        String[] propertyNames = new String[entries.size()];
        int roleIdIndex = -1;
        for (int i = 0; i < entries.size(); i++) {
            DocFieldMetadata fm = entries.get(i).fieldMeta;
            accessors[i] = fm.accessor();
            propertyNames[i] = fm.propertyName();
            if (fm.isRoleId()) {
                roleIdIndex = i;
            }
        }
        return new DocCacheKeyMeta(accessors, propertyNames, roleIdIndex);
    }

    /** 从实体实例中提取组合缓存键 */
    public CacheCompositeKey extractKey(Object entity) {
        Object[] values = new Object[accessors.length];
        for (int i = 0; i < accessors.length; i++) {
            values[i] = accessors[i].get(entity);
        }
        return CacheCompositeKey.of(values);
    }

    /** 缓存键字段的 Java 属性名（用于 DocQuerySpec 条件构建） */
    public String[] propertyNames() { return propertyNames; }

    public boolean hasRoleId() { return roleIdIndex >= 0; }

    public Object extractRoleId(CacheCompositeKey key) {
        return hasRoleId() ? key.part(roleIdIndex) : null;
    }

    private record Entry(int order, DocFieldMetadata fieldMeta) {}
}
