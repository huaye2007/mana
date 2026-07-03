package com.github.huaye2007.mana.runtime.context;

public class Metadata {
    private short key;
    private long val;
    private String strVal;

    public short getKey() {
        return key;
    }

    public void setKey(short key) {
        this.key = key;
    }

    public long getVal() {
        return val;
    }

    public void setVal(long val) {
        this.val = val;
    }

    public String getStrVal() {
        return strVal;
    }

    public void setStrVal(String strVal) {
        this.strVal = strVal;
    }
}
