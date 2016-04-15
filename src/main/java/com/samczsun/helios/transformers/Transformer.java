/*
 * Copyright 2016 Sam Sun <me@samczsun.com>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.samczsun.helios.transformers;

import com.samczsun.helios.Constants;
import com.samczsun.helios.Helios;
import com.samczsun.helios.Settings;
import com.samczsun.helios.gui.ClassData;
import com.samczsun.helios.gui.ClassManager;
import com.samczsun.helios.gui.ClickableSyntaxTextArea;
import com.samczsun.helios.tasks.DecompileTask;
import com.samczsun.helios.transformers.assemblers.Assembler;
import com.samczsun.helios.transformers.compilers.Compiler;
import com.samczsun.helios.transformers.decompilers.Decompiler;
import com.samczsun.helios.transformers.disassemblers.Disassembler;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import javax.swing.*;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public abstract class Transformer {
    private static final Pattern LEGAL_ID_PATTERN = Pattern.compile("[^0-9a-z\\-\\._]");
    // Only allow digits, lower case letters, and the following characters
    // - (HYPHEN)
    // . (PERIOD)
    // _ (UNDERSCORE)

    private static final Map<String, Transformer> BY_ID = new LinkedHashMap<>();
    private static final Map<String, Transformer> BY_NAME = new LinkedHashMap<>();

    static {
        Decompiler.getAllDecompilers();
        Disassembler.getAllDisassemblers();
        Assembler.getAllAssemblers();
        Compiler.getAllCompilers();
    }

    public static final Transformer HEX = new HexViewer().register();

    public static final Transformer TEXT = new TextViewer().register();

    protected final TransformerSettings settings = new TransformerSettings(this);

    private final String id;
    private final String name;

    protected Transformer(String id, String name) {
        this(id, name, null);
    }

    protected Transformer(String id, String name, Class<? extends TransformerSettings.Setting> settingsClass) {
        checkLegalId(id);
        checkLegalName(name);
        this.id = id;
        this.name = name;
        if (settingsClass != null) {
            if (settingsClass.isEnum()) {
                for (TransformerSettings.Setting setting : settingsClass.getEnumConstants()) {
                    getSettings().registerSetting(setting);
                }
            } else {
                throw new IllegalArgumentException("Settings must be an enum");
            }
        }
    }

    protected Transformer register() {
        if (BY_ID.containsKey(getId())) {
            throw new IllegalArgumentException(getId() + " already exists!");
        }
        if (BY_NAME.containsKey(getName())) {
            throw new IllegalArgumentException(getName() + " already exists!");
        }
        BY_ID.put(getId(), this);
        BY_NAME.put(getName(), this);
        return this;
    }


    public final TransformerSettings getSettings() {
        return this.settings;
    }

    public final String getId() {
        return this.id;
    }

    public final String getName() {
        return this.name;
    }

    public final boolean hasSettings() {
        return getSettings().size() > 0;
    }

    public TransformerType getType() {
        return TransformerType.OTHER;
    }

    public abstract boolean isApplicable(String className);

    protected String buildPath(File inputJar) {
        return Settings.RT_LOCATION.get().asString() + ";" + inputJar.getAbsolutePath() + (Settings.PATH
                .get()
                .asString()
                .isEmpty() ? "" : ";" + Settings.PATH.get().asString());
    }

    protected String parseException(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        e.printStackTrace();
        String exception = Constants.REPO_NAME + " version " + Constants.REPO_VERSION + "\n" + sw.toString();
        return "An exception occured while performing this task. Please open a GitHub issue with the details below.\n\n" + exception;
    }

    protected byte[] fixBytes(byte[] in) {
        ClassReader reader = new ClassReader(in);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    public abstract Object transform(Object... args);

    public JComponent open(ClassManager cm, ClassData data, String jumpTo) {
        ClickableSyntaxTextArea area = new ClickableSyntaxTextArea(cm, this, data.getFileName(), data.getClassName());
        area.getCaret().setSelectionVisible(true);
        area.setText("Decompiling... this may take a while");
        Helios.submitBackgroundTask(new DecompileTask(data.getFileName(), data.getClassName(), area, this, jumpTo));
        RTextScrollPane scrollPane = new RTextScrollPane(area);
        scrollPane.setLineNumbersEnabled(true);
        scrollPane.setFoldIndicatorEnabled(true);
        return scrollPane;
    }

    // Should be singletons
    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this;
    }

    public static Transformer getById(String id) {
        return BY_ID.get(id);
    }

    public static Transformer getByName(String name) {
        return BY_NAME.get(name);
    }

    public static Collection<Transformer> getAllTransformers() {
        return getAllTransformers(transformer -> true);
    }

    public static Collection<Transformer> getAllTransformers(Predicate<Transformer> filter) {
        return BY_ID.values().stream().filter(filter).collect(Collectors.toList());
    }

    public enum TransformerType {
        DECOMPILER("decompiler"),
        DISASSEMBLER("disassembler"),
        COMPILER("compiler"),
        ASSEMBLER("assembler"),
        CUSTOM("custom"),
        OTHER("other");

        private String id;

        TransformerType(String id) {
            this.id = id;
        }

        public String getId() {
            return this.id;
        }
    }

    private void checkLegalId(String request) {
        if (request == null || request.length() == 0) throw new IllegalArgumentException("ID must not be empty");
        Matcher matcher = LEGAL_ID_PATTERN.matcher(request);
        if (matcher.find()) {
            throw new IllegalArgumentException("ID must only be lowercase letters and numbers");
        }
    }

    private void checkLegalName(String request) {
        if (request == null || request.length() == 0) throw new IllegalArgumentException("Name must not be empty");
    }
}
