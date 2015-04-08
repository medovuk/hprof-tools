package com.badoo.hprof.cruncher;

import com.badoo.bmd.BmdTag;
import com.badoo.bmd.DataWriter;
import com.badoo.bmd.model.BmdBasicType;
import com.badoo.hprof.cruncher.util.CodingUtil;
import com.badoo.hprof.library.HprofReader;
import com.badoo.hprof.library.Tag;
import com.badoo.hprof.library.heap.HeapDumpReader;
import com.badoo.hprof.library.heap.HeapTag;
import com.badoo.hprof.library.heap.processor.HeapDumpDiscardProcessor;
import com.badoo.hprof.library.model.BasicType;
import com.badoo.hprof.library.model.ClassDefinition;
import com.badoo.hprof.library.model.ConstantField;
import com.badoo.hprof.library.model.HprofString;
import com.badoo.hprof.library.model.Instance;
import com.badoo.hprof.library.model.InstanceField;
import com.badoo.hprof.library.model.StaticField;
import com.badoo.hprof.library.processor.DiscardProcessor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.badoo.hprof.library.util.StreamUtil.read;
import static com.badoo.hprof.library.util.StreamUtil.readInt;
import static com.badoo.hprof.library.util.StreamUtil.skip;

/**
 * Processor for reading a HPROF file and outputting a BMD file. Operates in two stages:
 * <p/>
 * 1. Read all class definitions and write them to the BMD file.
 * 2. Read all instance dumps and write them to the BMD file.
 * <p/>
 * The reason why it's being done in two steps is that in HPROF files the class definition is not guaranteed to come
 * before the instance dump. In order to process it in one pass you must keep all the class definitions and some instance dumps in memory
 * until all class dependencies can be resolved.
 * <p/>
 * Created by Erik Andre on 22/10/14.
 */
public class CrunchProcessor extends DiscardProcessor {

    private static final int FIRST_ID = 1; // Skipping 0 since this is used as a (null) marker in some cases

    private final CrunchBdmWriter writer;
    private int nextStringId = FIRST_ID;
    private Map<Integer, Integer> stringIds = new HashMap<Integer, Integer>(); // Maps original to updated string ids
    private int nextObjectId = FIRST_ID;
    private Map<Integer, Integer> objectIds = new HashMap<Integer, Integer>(); // Maps original to updated object/class ids
    private Map<Integer, ClassDefinition> classesByOriginalId = new HashMap<Integer, ClassDefinition>(); // Maps original class id to the class definition
    private boolean readObjects;
    private List<Integer> rootObjectIds = new ArrayList<Integer>();

    public CrunchProcessor(OutputStream out) {
        this.writer = new CrunchBdmWriter(out);
    }

    /**
     * Must be called after the first pass (where class data is processed) is finished, before the second pass is started.
     */
    public void startSecondPass() {
        readObjects = true;
    }

    /**
     * Call after the second has has finished to write any remaining BMD data to the output stream and finish the conversion process.
     */
    public void finishAndWriteOutput() throws IOException {
        // Write roots
        writer.writeRootObjects(rootObjectIds);
    }

    @Override
    public void onRecord(int tag, int timestamp, int length, HprofReader reader) throws IOException {
        if (!readObjects) { // 1st pass: read class definitions and strings
            switch (tag) {
                case Tag.STRING:
                    HprofString string = reader.readStringRecord(length, timestamp);
                    // We replace the original string id with one starting from 0 as these are more efficient to store
                    stringIds.put(string.getId(), nextStringId); // Save the original id so we can update references later
                    string.setId(nextStringId);
                    nextStringId++;
                    boolean hashed = !keepString(string.getValue());
                    writer.writeString(string, hashed);
                    break;
                case Tag.LOAD_CLASS:
                    ClassDefinition classDef = reader.readLoadClassRecord();
                    classesByOriginalId.put(classDef.getObjectId(), classDef);
                    break;
                case Tag.HEAP_DUMP:
                case Tag.HEAP_DUMP_SEGMENT:
                    ClassDumpProcessor dumpProcessor = new ClassDumpProcessor();
                    HeapDumpReader dumpReader = new HeapDumpReader(reader.getInputStream(), length, dumpProcessor);
                    while (dumpReader.hasNext()) {
                        dumpReader.next();
                    }
                    break;
                case Tag.UNLOAD_CLASS:
                case Tag.HEAP_DUMP_END:
                    super.onRecord(tag, timestamp, length, reader); // These records can be discarded
                    break;
                default:
                    byte[] data = read(reader.getInputStream(), length);
                    writer.writeLegacyRecord(tag, data);
                    break;
            }
        }
        else { // 2nd pass: read object dumps
            switch (tag) {
                case Tag.HEAP_DUMP:
                case Tag.HEAP_DUMP_SEGMENT:
                    ObjectDumpProcessor dumpProcessor = new ObjectDumpProcessor();
                    HeapDumpReader dumpReader = new HeapDumpReader(reader.getInputStream(), length, dumpProcessor);
                    while (dumpReader.hasNext()) {
                        dumpReader.next();
                    }
                    break;
                default:
                    super.onRecord(tag, timestamp, length, reader); // Skip record
            }
        }
    }

    private boolean keepString(String string) {
        // Keep the names of some core system classes (to avoid issues in MAT)
        return string.startsWith("java.lang") || "V".equals(string) || "boolean".equals(string) || "byte".equals(string)
            || "short".equals(string) || "char".equals(string) || "int".equals(string) || "long".equals(string)
            || "float".equals(string) || "double".equals(string);
    }

    @Override
    public void onHeader(String text, int idSize, int timeHigh, int timeLow) throws IOException {
        // The text of the HPROF header is written to the BMD header but the timestamp is discarded
        writer.writeHeader(1, text.getBytes());
    }

    private int mapObjectId(int id) {
        if (id == 0) {
            return 0; // Zero is a special case used when there is no value (null), do not map it to a new id
        }
        if (!objectIds.containsKey(id)) {
            objectIds.put(id, nextObjectId);
            nextObjectId++;
        }
        return objectIds.get(id);
    }

    @SuppressWarnings({"ForLoopReplaceableByForEach"})
    private class CrunchBdmWriter extends DataWriter {

        protected CrunchBdmWriter(OutputStream out) {
            super(out);
        }

        public void writeHeader(int version, byte[] metadata) throws IOException {
            writeInt32(version);
            writeByteArrayWithLength(metadata != null ? metadata : new byte[]{});
        }

        public void writeString(HprofString string, boolean hashed) throws IOException {
            writeInt32(hashed ? BmdTag.HASHED_STRING : BmdTag.STRING);
            writeInt32(string.getId());
            byte[] stringData = string.getValue().getBytes();
            if (hashed) {
                writeRawVarint32(stringData.length);
                writeInt32(string.getValue().hashCode());
            }
            else {
                writeByteArrayWithLength(stringData);
            }
        }

        public void writeLegacyRecord(int tag, byte[] data) throws IOException {
            writeInt32(BmdTag.LEGACY_HPROF_RECORD);
            writeInt32(tag);
            writeInt32(data.length);
            writeRawBytes(data);
        }

        public void writeClassDefinition(ClassDefinition classDef) throws IOException {
            writeInt32(BmdTag.CLASS_DEFINITION);
            writeInt32(mapObjectId(classDef.getObjectId()));
            writeInt32(mapObjectId(classDef.getSuperClassObjectId()));
            writeInt32(stringIds.get(classDef.getNameStringId()));
            // Write constants and static fields (not filtered)
            int constantFieldCount = classDef.getConstantFields().size();
            writeInt32(constantFieldCount);
            for (int i = 0; i < constantFieldCount; i++) {
                ConstantField field = classDef.getConstantFields().get(i);
                writeInt32(field.getPoolIndex());
                writeInt32(convertType(field.getType()).id);
                writeFieldValue(field.getType(), field.getValue());
            }
            int staticFieldCount = classDef.getStaticFields().size();
            writeInt32(staticFieldCount);
            for (int i = 0; i < staticFieldCount; i++) {
                StaticField field = classDef.getStaticFields().get(i);
                writeInt32(stringIds.get(field.getFieldNameId()));
                writeInt32(convertType(field.getType()).id);
                writeFieldValue(field.getType(), field.getValue());
            }
            // Filter instance fields before writing them
            int skippedFieldSize = 0;
            List<InstanceField> keptFields = new ArrayList<InstanceField>();
            int instanceFieldCount = classDef.getInstanceFields().size();
            for (int i = 0; i < instanceFieldCount; i++) {
                InstanceField field = classDef.getInstanceFields().get(i);
                if (field.getType() != BasicType.OBJECT) {
                    skippedFieldSize += field.getType().size;
                    continue;
                }
                else {
                    keptFields.add(field);
                }
            }
            int keptFieldCount = keptFields.size();
            writeInt32(keptFieldCount);
            for (int i = 0; i < keptFieldCount; i++) {
                InstanceField field = keptFields.get(i);
                writeInt32(stringIds.get(field.getFieldNameId()));
                writeInt32(convertType(field.getType()).id);
            }
            writeInt32(skippedFieldSize);
        }

        public void writeInstanceDump(Instance instance) throws IOException {
            writeInt32(BmdTag.INSTANCE_DUMP);
            writeInt32(mapObjectId(instance.getObjectId()));
            writeInt32(mapObjectId(instance.getClassObjectId()));
            ClassDefinition currentClass = classesByOriginalId.get(instance.getClassObjectId());
            ByteArrayInputStream in = new ByteArrayInputStream(instance.getInstanceFieldData());
            while (currentClass != null) {
                int fieldCount = currentClass.getInstanceFields().size();
                for (int i = 0; i < fieldCount; i++) {
                    InstanceField field = currentClass.getInstanceFields().get(i);
                    BasicType type = field.getType();
                    if (type == BasicType.OBJECT) {
                        int id = readInt(in);
                        writeInt32(mapObjectId(id));
                    }
                    else { // Other fields are ignored
                        skip(in, type.size);
                    }
                }
                currentClass = classesByOriginalId.get(currentClass.getSuperClassObjectId());
            }
            if (in.available() != 0) {
                throw new IllegalStateException("Did not read the expected number of bytes. Available: " + in.available());
            }
        }

        public void writePrimitiveArray(int originalObjectId, BasicType type, int count) throws IOException {
            writeInt32(BmdTag.PRIMITIVE_ARRAY_PLACEHOLDER);
            writeInt32(mapObjectId(originalObjectId));
            writeInt32(convertType(type).id);
            writeInt32(count);
        }

        public void writeObjectArray(int originalObjectId, int originalClassId, int[] elements) throws IOException {
            writeInt32(BmdTag.OBJECT_ARRAY);
            writeInt32(mapObjectId(originalObjectId));
            writeInt32(mapObjectId(originalClassId));
            writeInt32(elements.length);
            for (int i = 0; i < elements.length; i++) {
                writeInt32(mapObjectId(elements[i]));
            }
        }

        public void writeRootObjects(List<Integer> roots) throws IOException {
            writeInt32(BmdTag.ROOT_OBJECTS);
            writeInt32(roots.size());
            for (int i = 0; i < roots.size(); i++) {
                writeInt32(mapObjectId(roots.get(i)));
            }
        }

        private void writeFieldValue(BasicType type, byte[] data) throws IOException {
            switch (type) {
                case OBJECT:
                    writeInt32(mapObjectId(CodingUtil.readInt(data)));
                    break;
                case SHORT:
                    writeInt32(CodingUtil.readShort(data));
                    break;
                case INT:
                    writeInt32(CodingUtil.readInt(data));
                    break;
                case LONG:
                    writeInt64(CodingUtil.readLong(data));
                    break;
                case FLOAT:
                    writeFloat(Float.intBitsToFloat(CodingUtil.readInt(data)));
                    break;
                case DOUBLE:
                    writeDouble(Double.longBitsToDouble(CodingUtil.readLong(data)));
                    break;
                case BOOLEAN:
                    writeRawBytes(data);
                    break;
                case BYTE:
                    writeRawBytes(data);
                    break;
                case CHAR:
                    writeRawBytes(data);
                    break;
            }
        }

        private BmdBasicType convertType(BasicType type) {
            switch (type) {
                case OBJECT:
                    return BmdBasicType.OBJECT;
                case BOOLEAN:
                    return BmdBasicType.BOOLEAN;
                case BYTE:
                    return BmdBasicType.BYTE;
                case CHAR:
                    return BmdBasicType.CHAR;
                case SHORT:
                    return BmdBasicType.SHORT;
                case INT:
                    return BmdBasicType.INT;
                case LONG:
                    return BmdBasicType.LONG;
                case FLOAT:
                    return BmdBasicType.FLOAT;
                case DOUBLE:
                    return BmdBasicType.DOUBLE;
                default:
                    throw new IllegalArgumentException("Invalid type:" + type);
            }
        }
    }

    private class ClassDumpProcessor extends HeapDumpDiscardProcessor {

        @Override
        public void onHeapRecord(int tag, HeapDumpReader reader) throws IOException {
            switch (tag) {
                case HeapTag.CLASS_DUMP:
                    ClassDefinition def = reader.readClassDumpRecord(classesByOriginalId);
                    writer.writeClassDefinition(def);
                    break;
                default:
                    super.onHeapRecord(tag, reader);
            }
        }

    }

    private class ObjectDumpProcessor extends HeapDumpDiscardProcessor {

        @Override
        public void onHeapRecord(int tag, HeapDumpReader reader) throws IOException {
            InputStream in = reader.getInputStream();
            switch (tag) {
                case HeapTag.INSTANCE_DUMP:
                    Instance instance = reader.readInstanceDump();
                    writer.writeInstanceDump(instance);
                    break;
                case HeapTag.OBJECT_ARRAY_DUMP:
                    readObjectArray(in);
                    break;
                case HeapTag.PRIMITIVE_ARRAY_DUMP:
                    readPrimitiveArray(in);
                    break;
                // Roots
                case HeapTag.ROOT_UNKNOWN:
                    rootObjectIds.add(readInt(in));
                    break;
                case HeapTag.ROOT_JNI_GLOBAL:
                    rootObjectIds.add(readInt(in));
                    skip(in, 4); // JNI global ref
                    break;
                case HeapTag.ROOT_JNI_LOCAL:
                    rootObjectIds.add(readInt(in));
                    skip(in, 8); // Thread serial + frame number
                    break;
                case HeapTag.ROOT_JAVA_FRAME:
                    rootObjectIds.add(readInt(in));
                    skip(in, 8); // Thread serial + frame number
                    break;
                case HeapTag.ROOT_NATIVE_STACK:
                    rootObjectIds.add(readInt(in));
                    skip(in, 4); // Thread serial
                    break;
                case HeapTag.ROOT_STICKY_CLASS:
                    rootObjectIds.add(readInt(in));
                    break;
                case HeapTag.ROOT_THREAD_BLOCK:
                    rootObjectIds.add(readInt(in));
                    skip(in, 4); // Thread serial
                    break;
                case HeapTag.ROOT_MONITOR_USED:
                    rootObjectIds.add(readInt(in));
                    break;
                case HeapTag.ROOT_THREAD_OBJECT:
                    rootObjectIds.add(readInt(in));
                    skip(in, 8); // Thread serial + stack serial
                    break;
                case HeapTag.HPROF_ROOT_INTERNED_STRING:
                    rootObjectIds.add(readInt(in));
                    break;
                case HeapTag.HPROF_ROOT_FINALIZING:
                    rootObjectIds.add(readInt(in));
                    break;
                case HeapTag.HPROF_ROOT_DEBUGGER:
                    rootObjectIds.add(readInt(in));
                    break;
                case HeapTag.HPROF_ROOT_REFERENCE_CLEANUP:
                    rootObjectIds.add(readInt(in));
                    break;
                case HeapTag.HPROF_ROOT_VM_INTERNAL:
                    rootObjectIds.add(readInt(in));
                    break;
                case HeapTag.HPROF_ROOT_JNI_MONITOR:
                    rootObjectIds.add(readInt(in));
                    skip(in, 8); // Data
                    break;
                default:
                    super.onHeapRecord(tag, reader);
            }
        }

        private void readObjectArray(InputStream in) throws IOException {
            int originalObjectId = readInt(in);
            skip(in, 4); // Stack trace serial
            int count = readInt(in);
            int originalElementClassId = readInt(in);
            int[] elements = new int[count];
            for (int i = 0; i < count; i++) {
                elements[i] = readInt(in);
            }
            writer.writeObjectArray(originalObjectId, originalElementClassId, elements);
        }

        private void readPrimitiveArray(InputStream in) throws IOException {
            int originalObjectId = readInt(in);
            skip(in, 4); // Stack trace serial
            int count = readInt(in);
            BasicType type = BasicType.fromType(in.read());
            skip(in, count * type.size);
            writer.writePrimitiveArray(originalObjectId, type, count);
        }

    }
}
