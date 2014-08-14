package com.badoo.hprof.library.model;

/**
 * Created by Erik Andre on 24/06/2014.
 */
public class ConstantField {

    private final short poolIndex;

    private final BasicType type;

    private final byte[] value;

    public ConstantField(short poolIndex, BasicType type, byte[] value) {
        this.poolIndex = poolIndex;
        this.type = type;
        this.value = value;
    }

    public short getPoolIndex() {
        return poolIndex;
    }

    public BasicType getType() {
        return type;
    }

    public byte[] getValue() {
        return value;
    }


}