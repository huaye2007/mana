package cn.managame.data.meta;

import cn.managame.data.annotation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityMetaTest {
    static class Base { @Id private long id; }
    static class Child extends Base { int value; }
    static class DuplicateId extends Base { @Id long another; }
    static class DuplicateOrder extends Base {
        @GroupKey(order=1) long a; @GroupKey(order=1) long b; @MapKey long c;
    }
    static class MissingOrder extends Base {
        @GroupKey long a; @GroupKey long b; @MapKey long c;
    }
    static class FinalId { @Id final long id=0; }
    static class StaticId { @Id static long id; }
    static class NoConstructor { @Id long id; NoConstructor(long id) { this.id=id; } }
    @Test void privateInheritedFieldsUseCompiledAccessAndNoArgFactory() {
        var meta=new EntityMeta(Child.class,EntityMeta.Kind.SINGLE);
        var value=(Child)meta.create(); meta.idField().set(value,7L);
        assertEquals(7L,meta.getId(value));
    }
    @Test void rejectsAmbiguousOrUnwritableIdentitiesAndMissingConstructor() {
        for(Class<?> c : new Class<?>[]{DuplicateId.class,FinalId.class,StaticId.class,NoConstructor.class})
            assertThrows(IllegalArgumentException.class,()->new EntityMeta(c,EntityMeta.Kind.SINGLE));
        for(Class<?> c : new Class<?>[]{DuplicateOrder.class,MissingOrder.class})
            assertThrows(IllegalArgumentException.class,()->new EntityMeta(c,EntityMeta.Kind.GROUP));
    }
}
