package com.badoo.hprof.unobfuscator;

import com.badoo.hprof.library.Tag;
import com.badoo.hprof.library.heap.HeapDumpReader;
import com.badoo.hprof.library.heap.HeapTag;
import com.badoo.hprof.library.heap.processor.HeapDumpDiscardProcessor;
import com.badoo.hprof.library.model.ClassDefinition;
import com.badoo.hprof.library.model.InstanceField;
import com.badoo.hprof.library.model.NamedField;
import com.badoo.hprof.library.model.StaticField;
import com.badoo.hprof.library.processor.CopyProcessor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.badoo.hprof.library.IoUtil.copy;
import static com.badoo.hprof.library.IoUtil.readInt;
import static com.badoo.hprof.library.IoUtil.writeInt;

/**
 * Created by Erik Andre on 13/08/2014.
 */
public class UnobfuscatingProcessor extends CopyProcessor {

    class ClassDumpProcessor extends HeapDumpDiscardProcessor {

        @Override
        public void onHeapRecord(int tag, InputStream in) throws IOException {
            if (tag == HeapTag.CLASS_DUMP) {
                int objectId = readInt(in);
                ClassDefinition classDef = classes.get(objectId);
                if (classDef == null) {
                    throw new IllegalStateException("Class with id " + objectId + " no loaded before reading class dump!");
                }
                classDef.populateFromClassDump(in);
                // Since the names of obfuscated fields are shared between classes we need to deduplicate the references, otherwise we cannot deobfuscate them independently
                deduplicateStrings(classDef);
            }
            else {
                super.onHeapRecord(tag, in);
            }
        }
    }

    private ClassDumpProcessor classDumpProcessor = new ClassDumpProcessor();
    private Map<Integer, String> strings = new HashMap<Integer, String>();
    private Map<Integer, ClassDefinition> classes = new HashMap<Integer, ClassDefinition>();
    private int lastStringId = 0;
    private Set<Integer> referencedStringIds = new HashSet<Integer>();

    public UnobfuscatingProcessor(OutputStream out) {
        super(out);
    }

    public Map<Integer, String> getStrings() {
        return strings;
    }

    public Map<Integer, ClassDefinition> getClasses() {
        return classes;
    }

    @Override
    public void onRecord(int tag, int timestamp, int length, InputStream in) throws IOException {
        if (tag == Tag.HEAP_DUMP || tag == Tag.HEAP_DUMP_SEGMENT) {
            // Write the record to output but keep a copy to process
            byte[] record = new byte[length];
            in.read(record);
            writer.writeRecordHeader(tag, timestamp, length);
            out.write(record);
            readHeapDump(record);
        }
        else if (tag == Tag.STRING) {
            writer.writeRecordHeader(tag, timestamp, length);
            int stringId = readInt(in);
            writeInt(out, stringId);
            byte[] data = copy(in, out, length - 4);
            String string = new String(data);
            strings.put(stringId, string);
        }
        else if (tag == Tag.LOAD_CLASS) {
            writer.writeRecordHeader(tag, timestamp, length);
            byte[] data = copy(in, out, length);
            ClassDefinition classDef = ClassDefinition.createFromLoadClassData(new ByteArrayInputStream(data));
            classes.put(classDef.getObjectId(), classDef);
        }
        else {
            super.onRecord(tag, timestamp, length, in); // Discard
        }
    }

    private void readHeapDump(byte[] data) throws IOException {
        HeapDumpReader reader = new HeapDumpReader(new ByteArrayInputStream(data), data.length, classDumpProcessor);
        while (reader.hasNext()) {
            reader.next();
        }
    }

    private void deduplicateStrings(ClassDefinition classDef) {
        for (StaticField field : classDef.getStaticFields()) {
            deduplicateFieldName(field);
        }
        for (InstanceField field : classDef.getInstanceFields()) {
            deduplicateFieldName(field);
        }
    }

    private void deduplicateFieldName(NamedField field) {
        if (referencedStringIds.contains(field.getFieldNameId())) {
            // Create an alias for this string
            System.out.println("Created alias for duplicate string " + field.getFieldNameId() + " (" + strings.get(field.getFieldNameId()) + ")");
            String value = strings.get(field.getFieldNameId());
            int newId = createNewStringId();
            field.setFieldNameId(newId);
            strings.put(newId, value);
            referencedStringIds.add(newId); // Just in case
        } else {
            referencedStringIds.add(field.getFieldNameId());
        }
    }

    private int createNewStringId() {
        lastStringId++;
        return lastStringId;
    }


}