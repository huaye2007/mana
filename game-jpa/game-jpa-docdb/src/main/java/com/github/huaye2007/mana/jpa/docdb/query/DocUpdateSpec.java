package com.github.huaye2007.mana.jpa.docdb.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文档局部更新规格。
 * 支持 set / unset / inc 操作。
 */
public class DocUpdateSpec {

    private final List<UpdateOp> operations = new ArrayList<>();

    public DocUpdateSpec set(String field, Object value) {
        operations.add(new UpdateOp(UpdateType.SET, field, value));
        return this;
    }

    public DocUpdateSpec unset(String field) {
        operations.add(new UpdateOp(UpdateType.UNSET, field, null));
        return this;
    }

    public DocUpdateSpec inc(String field, Number value) {
        operations.add(new UpdateOp(UpdateType.INC, field, value));
        return this;
    }

    public List<UpdateOp> operations() { return Collections.unmodifiableList(operations); }

    public enum UpdateType { SET, UNSET, INC }

    public record UpdateOp(UpdateType type, String field, Object value) {}
}
