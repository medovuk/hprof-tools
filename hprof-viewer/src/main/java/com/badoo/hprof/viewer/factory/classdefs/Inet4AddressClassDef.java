package com.badoo.hprof.viewer.factory.classdefs;

import com.badoo.hprof.library.model.BasicType;
import com.badoo.hprof.library.model.ClassDefinition;
import com.badoo.hprof.library.model.InstanceField;
import com.badoo.hprof.viewer.MemoryDump;

import javax.annotation.Nonnull;



/**
 * Class definition for accessing data of an instance dump of an Inet4Address
 *
 * Created by Erik Andre on 05/12/15.
 */
public class Inet4AddressClassDef extends BaseClassDef {

    public final ClassDefinition cls;
    public final InstanceField hostName;
    public final InstanceField ipaddress;

    public Inet4AddressClassDef(@Nonnull MemoryDump data) {
        cls = data.findClassByName("java.net.InetAddress");
        hostName = data.findFieldByName("hostName", BasicType.OBJECT, cls);
        ipaddress = data.findFieldByName("ipaddress", BasicType.OBJECT, cls);
    }
}
