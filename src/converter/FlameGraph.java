/*
 * Copyright 2020 Andrei Pangin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public class FlameGraph {
    public static final byte FRAME_INTERPRETED = 0;
    public static final byte FRAME_JIT_COMPILED = 1;
    public static final byte FRAME_INLINED = 2;
    public static final byte FRAME_NATIVE = 3;
    public static final byte FRAME_CPP = 4;
    public static final byte FRAME_KERNEL = 5;
    public static final byte FRAME_C1_COMPILED = 6;

    public String title = "Flame Graph";
    public boolean reverse;
    public double minwidth;
    public int skip;
    public String input;
    public String output;

    private final Frame root = new Frame(FRAME_NATIVE);
    private int depth;
    private long mintotal;

    public FlameGraph(String... args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") && !arg.isEmpty()) {
                if (input == null) {
                    input = arg;
                } else {
                    output = arg;
                }
            } else if (arg.equals("--title")) {
                title = args[++i];
            } else if (arg.equals("--reverse")) {
                reverse = true;
            } else if (arg.equals("--minwidth")) {
                minwidth = Double.parseDouble(args[++i]);
            } else if (arg.equals("--skip")) {
                skip = Integer.parseInt(args[++i]);
            }
        }
    }

    public void parse() throws IOException {
        parse(new InputStreamReader(new FileInputStream(input), StandardCharsets.UTF_8));
    }

    public void parse(Reader in) throws IOException {
        try (BufferedReader br = new BufferedReader(in)) {
            for (String line; (line = br.readLine()) != null; ) {
                int space = line.lastIndexOf(' ');
                if (space <= 0) continue;

                String[] trace = line.substring(0, space).split(";");
                long ticks = Long.parseLong(line.substring(space + 1));
                addSample(trace, ticks);
            }
        }
    }

    public void addSample(String[] trace, long ticks) {
        Frame frame = root;
        if (reverse) {
            for (int i = trace.length; --i >= skip; ) {
                frame = frame.addChild(trace[i], ticks);
            }
        } else {
            for (int i = skip; i < trace.length; i++) {
                frame = frame.addChild(trace[i], ticks);
            }
        }
        frame.addLeaf(ticks);

        depth = Math.max(depth, trace.length);
    }

    public void dump() throws IOException {
        if (output == null) {
            dump(System.out);
        } else {
            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(output), 32768);
                 PrintStream out = new PrintStream(bos, false, "UTF-8")) {
                dump(out);
            }
        }
    }

    public void dump(PrintStream out) {
        mintotal = (long) (root.total * minwidth / 100);
        int depth = mintotal > 1 ? root.depth(mintotal) : this.depth + 1;

        String tail = getResource("/flame.html");

        tail = printTill(out, tail, "/*height:*/300");
        out.print(Math.min(depth * 16, 32767));

        tail = printTill(out, tail, "/*title:*/");
        out.print(title);

        tail = printTill(out, tail, "/*reverse:*/false");
        out.print(reverse);

        tail = printTill(out, tail, "/*depth:*/0");
        out.print(depth);

        tail = printTill(out, tail, "/*frames:*/");

        printFrame(out, "all", root, 0, 0);

        out.print(tail);
    }

    private String printTill(PrintStream out, String data, String till) {
        int index = data.indexOf(till);
        out.print(data.substring(0, index));
        return data.substring(index + till.length());
    }

    private void printFrame(PrintStream out, String title, Frame frame, int level, long x) {
        int type = frame.getType();
        if (type == FRAME_KERNEL) {
            title = stripSuffix(title);
        }
        if (title.indexOf('\\') >= 0) {
            title = title.replace("\\", "\\\\");
        }
        if (title.indexOf('\'') >= 0) {
            title = title.replace("'", "\\'");
        }

        if ((frame.inlined | frame.c1 | frame.interpreted) != 0 && frame.inlined < frame.total && frame.interpreted < frame.total) {
            out.println("f(" + level + "," + x + "," + frame.total + "," + type + ",'" + title + "'," +
                    frame.inlined + "," + frame.c1 + "," + frame.interpreted + ")");
        } else {
            out.println("f(" + level + "," + x + "," + frame.total + "," + type + ",'" + title + "')");
        }

        x += frame.self;
        for (Map.Entry<String, Frame> e : frame.entrySet()) {
            Frame child = e.getValue();
            if (child.total >= mintotal) {
                printFrame(out, e.getKey(), child, level + 1, x);
            }
            x += child.total;
        }
    }

    static String stripSuffix(String title) {
        return title.substring(0, title.length() - 4);
    }

    public static void main(String[] args) throws IOException {
        FlameGraph fg = new FlameGraph(args);
        if (fg.input == null) {
            System.out.println("Usage: java " + FlameGraph.class.getName() + " [options] input.collapsed [output.html]");
            System.out.println();
            System.out.println("Options:");
            System.out.println("  --title TITLE");
            System.out.println("  --reverse");
            System.out.println("  --minwidth PERCENT");
            System.out.println("  --skip FRAMES");
            System.exit(1);
        }

        fg.parse();
        fg.dump();
    }

    static class Frame extends TreeMap<String, Frame> {
        final byte type;
        long total;
        long self;
        long inlined, c1, interpreted;

        Frame(byte type) {
            this.type = type;
        }

        byte getType() {
            if (inlined * 3 >= total) {
                return FRAME_INLINED;
            } else if (c1 * 2 >= total) {
                return FRAME_C1_COMPILED;
            } else if (interpreted * 2 >= total) {
                return FRAME_INTERPRETED;
            } else {
                return type;
            }
        }

        private Frame getChild(String title, byte type) {
            Frame child = super.get(title);
            if (child == null) {
                super.put(title, child = new Frame(type));
            }
            return child;
        }

        Frame addChild(String title, long ticks) {
            total += ticks;

            Frame child;
            if (title.endsWith("_[j]")) {
                child = getChild(stripSuffix(title), FRAME_JIT_COMPILED);
            } else if (title.endsWith("_[i]")) {
                (child = getChild(stripSuffix(title), FRAME_JIT_COMPILED)).inlined += ticks;
            } else if (title.endsWith("_[k]")) {
                child = getChild(title, FRAME_KERNEL);
            } else if (title.endsWith("_[1]")) {
                (child = getChild(stripSuffix(title), FRAME_JIT_COMPILED)).c1 += ticks;
            } else if (title.contains("::") || title.startsWith("-[") || title.startsWith("+[")) {
                child = getChild(title, FRAME_CPP);
            } else if (title.indexOf('/') > 0 && title.charAt(0) != '['
                    || title.indexOf('.') > 0 && Character.isUpperCase(title.charAt(0))) {
                (child = getChild(title, FRAME_JIT_COMPILED)).interpreted += ticks;
            } else {
                child = getChild(title, FRAME_NATIVE);
            }
            return child;
        }

        void addLeaf(long ticks) {
            total += ticks;
            self += ticks;
        }

        int depth(long cutoff) {
            int depth = 0;
            if (size() > 0) {
                for (Frame child : values()) {
                    if (child.total >= cutoff) {
                        depth = Math.max(depth, child.depth(cutoff));
                    }
                }
            }
            return depth + 1;
        }
    }

    private static String getResource(String name) {
        try (InputStream stream = FlameGraph.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("No resource found");
            }

            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            for (int length; (length = stream.read(buffer)) != -1; ) {
                result.write(buffer, 0, length);
            }
            return result.toString("UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException("Can't load resource with name " + name);
        }
    }
}
