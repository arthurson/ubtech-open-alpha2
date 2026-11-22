package com.ubtechinc.alpha.hardware.policy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 純 Java 最小 ONNX 推論器（只做前饋 MLP 子集，單 batch、float32）。
 *
 * <p>點解唔用 onnxruntime-android：呢個 APK 要裝到 API 19 (Android 4.4) 同 armeabi-v7a
 * 機，ORT AAR 要 minSdk 21 而且每個 ABI 十幾 MB；Alpha2 嘅 policy 係百幾 KB 嘅 MLP，
 * 自己讀 protobuf＋做幾個算子更細更穩。代價係<b>只支援下面呢啲算子</b>，遇到唔識嘅
 * 會喺 {@link #load} 即 throw（列出算子名），唔會靜靜地跑錯。LSTM／Conv 等 recurrent／
 * 卷積 policy（例如 Microduck 嘅 h_in/c_in 版）暫不支援。</p>
 *
 * <p>支援算子：Gemm（alpha/beta/transA/transB）、MatMul、Add、Sub、Mul、Div、Neg、Relu、
 * LeakyRelu、Elu、Tanh、Sigmoid、Clip（opset ≥ 11 輸入式 min/max，或 opset &lt; 11 屬性式）、
 * Identity、Flatten、Reshape（常量 shape）、Cast（只准 → float）、Constant、Concat（axis=-1/1，二維）。
 * 權重只讀 float32（raw_data 或 float_data）；int64 常量（Reshape shape／Flatten）亦讀。
 * 不支援 external data、sparse、subgraph。</p>
 *
 * <p>Observation normalizer 如果焗咗入 ONNX 圖（Microduck 做法：Sub/Div 常量），自然會被
 * 執行，runtime <b>唔可以再 normalize 一次</b>。</p>
 */
public final class OnnxMlp {

    /** 單一張量元素上限（4M float＝16MB）：MLP policy 遠細過呢個數；防畸形 shape 觸發 OOM（Error，唔會被 catch Exception 接住）。 */
    private static final long MAX_TENSOR_ELEMS = 4L * 1024 * 1024;

    /** 張量：float 資料＋形狀（最多二維夠用，仍保留任意 rank 嘅 dims）。 */
    private static final class T {
        final float[] d;
        final int[] shape;
        T(float[] d, int[] shape) { this.d = d; this.shape = shape; }
        int rank() { return shape.length; }
        static int numel(int[] s) {
            long n = 1;
            for (int x : s) {
                if (x < 0) throw new IllegalArgumentException("negative tensor dim");
                n = Math.multiplyExact(n, (long) x);
                if (n > MAX_TENSOR_ELEMS) throw new IllegalArgumentException("tensor too large");
            }
            return (int) n;
        }
    }

    private static final class Node {
        String op = "";
        final List<String> in = new ArrayList<String>();
        final List<String> out = new ArrayList<String>();
        final Map<String, Object> attr = new HashMap<String, Object>(); // Long / Float / long[] / float[] / T
    }

    private final List<Node> nodes = new ArrayList<Node>();
    private final Map<String, T> consts = new HashMap<String, T>();
    private String inputName = "";
    private String outputName = "";
    private final List<String> outputNames = new ArrayList<String>();
    private final Map<String, Integer> outputDims = new HashMap<String, Integer>();
    private int inputDim;
    private int outputDim;
    private int opset = 13;

    private OnnxMlp() { }

    public int inputDim() { return inputDim; }
    public int outputDim() { return outputDim; }
    public String inputName() { return inputName; }
    public String outputName() { return outputName; }
    /** 所有輸出名（多輸出 policy，例如統一格式嘅 action＋delta）。 */
    public List<String> outputNames() { return new ArrayList<String>(outputNames); }
    public boolean hasOutput(String name) { return outputDims.containsKey(name); }
    /** 指定輸出嘅維度；冇呢個輸出回 -1。 */
    public int outputDim(String name) { Integer d = outputDims.get(name); return d == null ? -1 : d; }

    // ---------------------------------------------------------------- 讀 protobuf

    /** Protobuf wire reader（只做呢個用途嘅最小子集）。 */
    private static final class Pb {
        final byte[] b;
        int p;
        final int end;
        Pb(byte[] b, int p, int end) { this.b = b; this.p = p; this.end = end; }
        boolean more() { return p < end; }
        long varint() {
            long r = 0;
            int s = 0;
            while (true) {
                if (p >= end) throw new IllegalArgumentException("truncated varint");
                int c = b[p++] & 0xFF;
                r |= (long) (c & 0x7F) << s;
                if ((c & 0x80) == 0) return r;
                s += 7;
                if (s > 63) throw new IllegalArgumentException("varint too long");
            }
        }
        /** 讀 length-delimited 欄位，回 [start,end)。 */
        int[] bytes() {
            long len = varint();
            if (len < 0 || p + len > end) throw new IllegalArgumentException("bad length " + len);
            int s = p;
            p += (int) len;
            return new int[] {s, p};
        }
        int fixed32() {
            if (p + 4 > end) throw new IllegalArgumentException("truncated fixed32");
            int v = (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 | (b[p + 2] & 0xFF) << 16 | (b[p + 3] & 0xFF) << 24;
            p += 4;
            return v;
        }
        void skip(int wire) {
            switch (wire) {
                case 0: varint(); break;
                case 1: p += 8; break;
                case 2: bytes(); break;
                case 5: p += 4; break;
                default: throw new IllegalArgumentException("unsupported wire type " + wire);
            }
            if (p > end) throw new IllegalArgumentException("skip past end");
        }
    }

    private static String str(byte[] b, int[] r) {
        try {
            return new String(b, r[0], r[1] - r[0], "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 載入 .onnx 位元組。唔支援嘅算子／結構會 throw IllegalArgumentException（訊息列出原因）。 */
    public static OnnxMlp load(byte[] model) {
        OnnxMlp m = new OnnxMlp();
        Pb pb = new Pb(model, 0, model.length);
        int[] graph = null;
        while (pb.more()) {
            int tag = (int) pb.varint();
            int f = tag >>> 3, w = tag & 7;
            if (f == 7 && w == 2) graph = pb.bytes();            // ModelProto.graph
            else if (f == 8 && w == 2) {                          // opset_import
                int[] r = pb.bytes();
                Pb o = new Pb(model, r[0], r[1]);
                String domain = "";
                long ver = 0;
                while (o.more()) {
                    int t2 = (int) o.varint();
                    if ((t2 >>> 3) == 1 && (t2 & 7) == 2) domain = str(model, o.bytes());
                    else if ((t2 >>> 3) == 2 && (t2 & 7) == 0) ver = o.varint();
                    else o.skip(t2 & 7);
                }
                if (domain.isEmpty() || "ai.onnx".equals(domain)) m.opset = (int) ver;
            } else pb.skip(w);
        }
        if (graph == null) throw new IllegalArgumentException("not an ONNX model (no graph)");
        m.readGraph(model, graph[0], graph[1]);
        m.validate();
        return m;
    }

    private void readGraph(byte[] b, int s, int e) {
        Pb g = new Pb(b, s, e);
        Set<String> inits = new LinkedHashSet<String>();
        List<String> inputs = new ArrayList<String>();
        List<int[]> inputDims = new ArrayList<int[]>();
        List<String> outputs = new ArrayList<String>();
        List<int[]> outputDimsL = new ArrayList<int[]>();
        while (g.more()) {
            int tag = (int) g.varint();
            int f = tag >>> 3, w = tag & 7;
            if (w != 2) { g.skip(w); continue; }
            int[] r = g.bytes();
            switch (f) {
                case 1: nodes.add(readNode(b, r[0], r[1])); break;
                case 5: {                                         // initializer
                    String[] nm = new String[1];
                    T t = readTensor(b, r[0], r[1], nm);
                    consts.put(nm[0], t);
                    inits.add(nm[0]);
                    break;
                }
                case 11: { String[] nm = new String[1]; int[] dims = readValueInfo(b, r[0], r[1], nm);
                    inputs.add(nm[0]); inputDims.add(dims); break; }
                case 12: { String[] nm = new String[1]; int[] dims = readValueInfo(b, r[0], r[1], nm);
                    outputs.add(nm[0]); outputDimsL.add(dims); break; }
                default: break;
            }
        }
        // 真輸入＝graph.input 去走 initializer（舊 exporter 會將權重都列入 input）。
        int idx = -1;
        for (int i = 0; i < inputs.size(); i++) {
            if (!inits.contains(inputs.get(i))) {
                if (idx >= 0) throw new IllegalArgumentException(
                        "model has more than one runtime input (recurrent/stateful policy not supported): "
                                + inputs.get(idx) + ", " + inputs.get(i));
                idx = i;
            }
        }
        if (idx < 0) throw new IllegalArgumentException("model has no runtime input");
        inputName = inputs.get(idx);
        int[] id = inputDims.get(idx);
        inputDim = id.length > 0 ? id[id.length - 1] : 0;
        if (outputs.isEmpty()) throw new IllegalArgumentException("model has no output");
        // 一個輸出：照舊。多輸出：必須有一個叫 "action"（主輸出）——統一格式嘅 action＋delta。
        int pi = 0;
        if (outputs.size() > 1) {
            pi = outputs.indexOf("action");
            if (pi < 0) throw new IllegalArgumentException("model has " + outputs.size() + " outputs but none is named 'action' (exactly one output, or one named 'action', is required)");
        }
        for (int i = 0; i < outputs.size(); i++) {
            int[] od = outputDimsL.get(i);
            outputNames.add(outputs.get(i));
            outputDims.put(outputs.get(i), od.length > 0 ? od[od.length - 1] : 0);
        }
        outputName = outputs.get(pi);
        outputDim = outputDims.get(outputName);
    }

    /** 回 dims（動態維度＝-1）；name 放入 nm[0]。 */
    private static int[] readValueInfo(byte[] b, int s, int e, String[] nm) {
        Pb v = new Pb(b, s, e);
        int[] dims = new int[0];
        nm[0] = "";
        while (v.more()) {
            int tag = (int) v.varint();
            int f = tag >>> 3, w = tag & 7;
            if (f == 1 && w == 2) nm[0] = str(b, v.bytes());
            else if (f == 2 && w == 2) {                          // type -> tensor_type(1) -> shape(2) -> dim(1)
                int[] ty = v.bytes();
                Pb t = new Pb(b, ty[0], ty[1]);
                while (t.more()) {
                    int t1 = (int) t.varint();
                    if ((t1 >>> 3) == 1 && (t1 & 7) == 2) {
                        int[] tt = t.bytes();
                        Pb x = new Pb(b, tt[0], tt[1]);
                        while (x.more()) {
                            int t2 = (int) x.varint();
                            if ((t2 >>> 3) == 2 && (t2 & 7) == 2) {
                                int[] sh = x.bytes();
                                Pb d = new Pb(b, sh[0], sh[1]);
                                List<Integer> dl = new ArrayList<Integer>();
                                while (d.more()) {
                                    int t3 = (int) d.varint();
                                    if ((t3 >>> 3) == 1 && (t3 & 7) == 2) {
                                        int[] dd = d.bytes();
                                        Pb q = new Pb(b, dd[0], dd[1]);
                                        int val = -1;
                                        while (q.more()) {
                                            int t4 = (int) q.varint();
                                            if ((t4 >>> 3) == 1 && (t4 & 7) == 0) val = (int) q.varint();
                                            else q.skip(t4 & 7);
                                        }
                                        dl.add(val);
                                    } else d.skip(t3 & 7);
                                }
                                dims = new int[dl.size()];
                                for (int i = 0; i < dims.length; i++) dims[i] = dl.get(i);
                            } else x.skip(t2 & 7);
                        }
                    } else t.skip(t1 & 7);
                }
            } else v.skip(w);
        }
        return dims;
    }

    private Node readNode(byte[] b, int s, int e) {
        Node n = new Node();
        Pb p = new Pb(b, s, e);
        while (p.more()) {
            int tag = (int) p.varint();
            int f = tag >>> 3, w = tag & 7;
            if (w != 2) { p.skip(w); continue; }
            int[] r = p.bytes();
            switch (f) {
                case 1: n.in.add(str(b, r)); break;
                case 2: n.out.add(str(b, r)); break;
                case 4: n.op = str(b, r); break;
                case 5: readAttr(b, r[0], r[1], n); break;
                default: break;
            }
        }
        return n;
    }

    private void readAttr(byte[] b, int s, int e, Node n) {
        Pb p = new Pb(b, s, e);
        String name = "";
        Object val = null;
        List<Long> ints = new ArrayList<Long>();
        List<Float> floats = new ArrayList<Float>();
        while (p.more()) {
            int tag = (int) p.varint();
            int f = tag >>> 3, w = tag & 7;
            if (f == 1 && w == 2) name = str(b, p.bytes());
            else if (f == 2 && w == 5) val = Float.valueOf(Float.intBitsToFloat(p.fixed32()));
            else if (f == 3 && w == 0) val = Long.valueOf(p.varint());
            else if (f == 5 && w == 2) { int[] r = p.bytes(); val = readTensor(b, r[0], r[1], new String[1]); }
            else if (f == 7 && w == 5) floats.add(Float.valueOf(Float.intBitsToFloat(p.fixed32())));
            else if (f == 7 && w == 2) {                           // packed floats
                int[] r = p.bytes();
                for (int i = r[0]; i + 4 <= r[1]; i += 4) {
                    floats.add(Float.valueOf(Float.intBitsToFloat(
                            (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16 | (b[i + 3] & 0xFF) << 24)));
                }
            } else if (f == 8 && w == 0) ints.add(Long.valueOf(p.varint()));
            else if (f == 8 && w == 2) {                           // packed ints
                int[] r = p.bytes();
                Pb q = new Pb(b, r[0], r[1]);
                while (q.more()) ints.add(Long.valueOf(q.varint()));
            } else p.skip(w);
        }
        if (val == null && !ints.isEmpty()) {
            long[] a = new long[ints.size()];
            for (int i = 0; i < a.length; i++) a[i] = ints.get(i);
            val = a;
        } else if (val == null && !floats.isEmpty()) {
            float[] a = new float[floats.size()];
            for (int i = 0; i < a.length; i++) a[i] = floats.get(i);
            val = a;
        }
        if (val != null) n.attr.put(name, val);
    }

    /** TensorProto → T。只收 float32(1)／int64(7)／int32(6)／double(11，降精度)；int 類轉 float 存。 */
    private static T readTensor(byte[] b, int s, int e, String[] nm) {
        Pb p = new Pb(b, s, e);
        List<Long> dims = new ArrayList<Long>();
        int dtype = 0;
        int[] raw = null;
        List<Float> fl = new ArrayList<Float>();
        List<Long> il = new ArrayList<Long>();
        nm[0] = "";
        while (p.more()) {
            int tag = (int) p.varint();
            int f = tag >>> 3, w = tag & 7;
            if (f == 1 && w == 0) dims.add(Long.valueOf(p.varint()));
            else if (f == 1 && w == 2) { int[] r = p.bytes(); Pb q = new Pb(b, r[0], r[1]); while (q.more()) dims.add(Long.valueOf(q.varint())); }
            else if (f == 2 && w == 0) dtype = (int) p.varint();
            else if (f == 4 && w == 2) { int[] r = p.bytes(); for (int i = r[0]; i + 4 <= r[1]; i += 4) fl.add(Float.valueOf(Float.intBitsToFloat(le32(b, i)))); }
            else if (f == 4 && w == 5) fl.add(Float.valueOf(Float.intBitsToFloat(p.fixed32())));
            else if ((f == 5 || f == 7) && w == 2) { int[] r = p.bytes(); Pb q = new Pb(b, r[0], r[1]); while (q.more()) il.add(Long.valueOf(q.varint())); }
            else if ((f == 5 || f == 7) && w == 0) il.add(Long.valueOf(p.varint()));
            else if (f == 8 && w == 2) nm[0] = str(b, p.bytes());
            else if (f == 9 && w == 2) raw = p.bytes();
            else if (f == 13 && w == 2) throw new IllegalArgumentException("external tensor data not supported");
            else p.skip(w);
        }
        int[] shape = new int[dims.size()];
        for (int i = 0; i < shape.length; i++) shape[i] = (int) (long) dims.get(i);
        int n = T.numel(shape);
        float[] d = new float[n];
        if (raw != null) {
            int len = raw[1] - raw[0];
            if (dtype == 1) {
                if (len != (long) n * 4) throw new IllegalArgumentException("float tensor size mismatch for " + nm[0]);
                for (int i = 0; i < n; i++) d[i] = Float.intBitsToFloat(le32(b, raw[0] + 4 * i));
            } else if (dtype == 7) {
                if (len != (long) n * 8) throw new IllegalArgumentException("int64 tensor size mismatch for " + nm[0]);
                for (int i = 0; i < n; i++) d[i] = (float) le64(b, raw[0] + 8 * i);
            } else if (dtype == 6) {
                if (len != (long) n * 4) throw new IllegalArgumentException("int32 tensor size mismatch for " + nm[0]);
                for (int i = 0; i < n; i++) d[i] = (float) le32(b, raw[0] + 4 * i);
            } else if (dtype == 11) {
                if (len != (long) n * 8) throw new IllegalArgumentException("double tensor size mismatch for " + nm[0]);
                for (int i = 0; i < n; i++) d[i] = (float) Double.longBitsToDouble(le64(b, raw[0] + 8 * i));
            } else {
                throw new IllegalArgumentException("unsupported tensor dtype " + dtype + " (" + nm[0] + ")");
            }
        } else if (!fl.isEmpty()) {
            if (fl.size() != n) throw new IllegalArgumentException("float_data size mismatch for " + nm[0]);
            for (int i = 0; i < n; i++) d[i] = fl.get(i);
        } else if (!il.isEmpty()) {
            if (il.size() != n) throw new IllegalArgumentException("int_data size mismatch for " + nm[0]);
            for (int i = 0; i < n; i++) d[i] = il.get(i);
        } else if (n != 0) {
            throw new IllegalArgumentException("tensor " + nm[0] + " has no data");
        }
        return new T(d, shape);
    }

    private static int le32(byte[] b, int i) {
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16 | (b[i + 3] & 0xFF) << 24;
    }

    private static long le64(byte[] b, int i) {
        return (le32(b, i) & 0xFFFFFFFFL) | ((long) le32(b, i + 4) << 32);
    }

    // ---------------------------------------------------------------- 驗證

    private static final Set<String> SUPPORTED = new LinkedHashSet<String>();
    static {
        String[] ops = {"Gemm", "MatMul", "Add", "Sub", "Mul", "Div", "Neg", "Relu", "LeakyRelu", "Elu", "Tanh",
                "Sigmoid", "Clip", "Identity", "Flatten", "Reshape", "Cast", "Constant", "Concat"};
        for (String o : ops) SUPPORTED.add(o);
    }

    private void validate() {
        Set<String> bad = new LinkedHashSet<String>();
        for (Node n : nodes) if (!SUPPORTED.contains(n.op)) bad.add(n.op);
        if (!bad.isEmpty()) {
            throw new IllegalArgumentException("unsupported ONNX op(s): " + bad
                    + " (supported: " + SUPPORTED + ")");
        }
        if (inputDim <= 0) throw new IllegalArgumentException("input dim must be static, got " + inputDim);
        if (outputDim <= 0) throw new IllegalArgumentException("output dim must be static, got " + outputDim);
        for (String on : outputNames) {
            if (outputDims.get(on) <= 0) throw new IllegalArgumentException("output '" + on + "' dim must be static, got " + outputDims.get(on));
        }
        // 試跑一次零輸入，儘早抓結構錯（形狀不符、缺張量）。
        run(new float[inputDim]);
    }

    // ---------------------------------------------------------------- 執行

    /** 單次推論。輸入長度必須等於 {@link #inputDim()}；回新陣列（長度 {@link #outputDim()}）。 */
    public float[] run(float[] obs) {
        Map<String, T> env = exec(obs);
        return take(env, outputName, outputDim);
    }

    /** 單次推論，回全部輸出（名 → 值，次序同 graph.output）。統一格式 policy 用：{@code action}＋{@code delta}。 */
    public Map<String, float[]> runAll(float[] obs) {
        Map<String, T> env = exec(obs);
        Map<String, float[]> r = new java.util.LinkedHashMap<String, float[]>();
        for (String on : outputNames) r.put(on, take(env, on, outputDims.get(on)));
        return r;
    }

    private Map<String, T> exec(float[] obs) {
        if (obs == null || obs.length != inputDim) {
            throw new IllegalArgumentException("obs length " + (obs == null ? -1 : obs.length)
                    + " != model input " + inputDim);
        }
        Map<String, T> env = new HashMap<String, T>(consts);
        env.put(inputName, new T(obs.clone(), new int[] {1, inputDim}));
        for (Node n : nodes) exec(n, env);
        return env;
    }

    private static float[] take(Map<String, T> env, String name, int dim) {
        T out = env.get(name);
        if (out == null) throw new IllegalStateException("output tensor not produced: " + name);
        if (out.d.length != dim) throw new IllegalStateException("output '" + name + "' length " + out.d.length + " != " + dim);
        return out.d.clone();
    }

    private static T get(Map<String, T> env, String name) {
        T t = env.get(name);
        if (t == null) throw new IllegalStateException("missing tensor: " + name);
        return t;
    }

    private static float attrF(Node n, String k, float def) {
        Object o = n.attr.get(k);
        return o instanceof Float ? ((Float) o).floatValue() : def;
    }

    private static long attrI(Node n, String k, long def) {
        Object o = n.attr.get(k);
        return o instanceof Long ? ((Long) o).longValue() : def;
    }

    private void exec(Node n, Map<String, T> env) {
        String op = n.op;
        String o0 = n.out.get(0);
        if ("Constant".equals(op)) {
            Object v = n.attr.get("value");
            if (v instanceof T) { env.put(o0, (T) v); return; }
            if (n.attr.get("value_float") instanceof Float) {
                env.put(o0, new T(new float[] {((Float) n.attr.get("value_float")).floatValue()}, new int[0]));
                return;
            }
            if (n.attr.get("value_int") instanceof Long) {
                env.put(o0, new T(new float[] {((Long) n.attr.get("value_int")).longValue()}, new int[0]));
                return;
            }
            throw new IllegalStateException("Constant without supported value attribute");
        }
        T a = get(env, n.in.get(0));
        if ("Gemm".equals(op)) { env.put(o0, gemm(n, a, get(env, n.in.get(1)), n.in.size() > 2 ? get(env, n.in.get(2)) : null)); return; }
        if ("MatMul".equals(op)) { env.put(o0, matmul(a, get(env, n.in.get(1)))); return; }
        if ("Add".equals(op) || "Sub".equals(op) || "Mul".equals(op) || "Div".equals(op)) {
            env.put(o0, binary(op, a, get(env, n.in.get(1)))); return;
        }
        if ("Concat".equals(op)) {
            long ax = attrI(n, "axis", 1);
            List<T> ts = new ArrayList<T>();
            for (String nm : n.in) ts.add(get(env, nm));
            env.put(o0, concat(ts, ax)); return;
        }
        if ("Reshape".equals(op)) {
            T sh = get(env, n.in.get(1));
            int[] ns = new int[sh.d.length];
            int unk = -1, known = 1;
            for (int i = 0; i < ns.length; i++) {
                int v = (int) sh.d[i];
                if (v == 0 && i < a.shape.length) v = a.shape[i];
                if (v == -1) unk = i; else known *= v;
                ns[i] = v;
            }
            if (unk >= 0) ns[unk] = known == 0 ? 0 : a.d.length / known;
            if (T.numel(ns) != a.d.length) throw new IllegalStateException("Reshape size mismatch");
            env.put(o0, new T(a.d, ns)); return;
        }
        float[] y = new float[a.d.length];
        float[] x = a.d;
        if ("Relu".equals(op)) { for (int i = 0; i < y.length; i++) y[i] = x[i] > 0 ? x[i] : 0; }
        else if ("LeakyRelu".equals(op)) { float al = attrF(n, "alpha", 0.01f); for (int i = 0; i < y.length; i++) y[i] = x[i] >= 0 ? x[i] : al * x[i]; }
        else if ("Elu".equals(op)) { float al = attrF(n, "alpha", 1.0f); for (int i = 0; i < y.length; i++) y[i] = x[i] >= 0 ? x[i] : al * ((float) Math.exp(x[i]) - 1f); }
        else if ("Tanh".equals(op)) { for (int i = 0; i < y.length; i++) y[i] = (float) Math.tanh(x[i]); }
        else if ("Sigmoid".equals(op)) { for (int i = 0; i < y.length; i++) y[i] = (float) (1.0 / (1.0 + Math.exp(-x[i]))); }
        else if ("Neg".equals(op)) { for (int i = 0; i < y.length; i++) y[i] = -x[i]; }
        else if ("Clip".equals(op)) {
            float lo = Float.NEGATIVE_INFINITY, hi = Float.POSITIVE_INFINITY;
            if (opset >= 11) {
                if (n.in.size() > 1 && !n.in.get(1).isEmpty()) lo = get(env, n.in.get(1)).d[0];
                if (n.in.size() > 2 && !n.in.get(2).isEmpty()) hi = get(env, n.in.get(2)).d[0];
            } else {
                lo = attrF(n, "min", lo);
                hi = attrF(n, "max", hi);
            }
            for (int i = 0; i < y.length; i++) y[i] = x[i] < lo ? lo : (x[i] > hi ? hi : x[i]);
        } else if ("Cast".equals(op)) {
            long to = attrI(n, "to", 1);
            if (to != 1) throw new IllegalStateException("Cast to dtype " + to + " not supported (only float)");
            System.arraycopy(x, 0, y, 0, y.length);
        } else if ("Identity".equals(op)) {
            System.arraycopy(x, 0, y, 0, y.length);
        } else if ("Flatten".equals(op)) {
            long ax = attrI(n, "axis", 1);
            int r = a.rank();
            if (ax < 0) ax += r;
            int d0 = 1, d1 = 1;
            for (int i = 0; i < r; i++) { if (i < ax) d0 *= a.shape[i]; else d1 *= a.shape[i]; }
            env.put(o0, new T(x, new int[] {d0, d1})); return;
        } else {
            throw new IllegalStateException("op not implemented: " + op);
        }
        env.put(o0, new T(y, a.shape));
    }

    private static T gemm(Node n, T a, T b, T c) {
        float alpha = attrF(n, "alpha", 1f), beta = attrF(n, "beta", 1f);
        boolean ta = attrI(n, "transA", 0) != 0, tb = attrI(n, "transB", 0) != 0;
        if (a.rank() != 2 || b.rank() != 2) throw new IllegalStateException("Gemm needs 2-D inputs");
        int m = ta ? a.shape[1] : a.shape[0], k = ta ? a.shape[0] : a.shape[1];
        int kb = tb ? b.shape[1] : b.shape[0], nn = tb ? b.shape[0] : b.shape[1];
        if (k != kb) throw new IllegalStateException("Gemm inner dim mismatch " + k + " vs " + kb);
        float[] y = new float[m * nn];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < nn; j++) {
                double s = 0;
                for (int t = 0; t < k; t++) {
                    float av = ta ? a.d[t * a.shape[1] + i] : a.d[i * a.shape[1] + t];
                    float bv = tb ? b.d[j * b.shape[1] + t] : b.d[t * b.shape[1] + j];
                    s += (double) av * bv;
                }
                float v = alpha * (float) s;
                if (c != null) v += beta * bcast(c, i, j, m, nn);
                y[i * nn + j] = v;
            }
        }
        return new T(y, new int[] {m, nn});
    }

    /** C 廣播到 (m,nn)：支援 scalar、(nn)、(1,nn)、(m,1)、(m,nn)。 */
    private static float bcast(T c, int i, int j, int m, int nn) {
        if (c.d.length == 1) return c.d[0];
        if (c.rank() == 1 && c.d.length == nn) return c.d[j];
        if (c.rank() == 2) {
            int r = c.shape[0] == 1 ? 0 : i, q = c.shape[1] == 1 ? 0 : j;
            return c.d[r * c.shape[1] + q];
        }
        throw new IllegalStateException("Gemm bias shape not broadcastable");
    }

    private static T matmul(T a, T b) {
        if (a.rank() != 2 || b.rank() != 2) throw new IllegalStateException("MatMul needs 2-D inputs");
        int m = a.shape[0], k = a.shape[1], nn = b.shape[1];
        if (b.shape[0] != k) throw new IllegalStateException("MatMul inner dim mismatch");
        float[] y = new float[m * nn];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < nn; j++) {
                double s = 0;
                for (int t = 0; t < k; t++) s += (double) a.d[i * k + t] * b.d[t * nn + j];
                y[i * nn + j] = (float) s;
            }
        }
        return new T(y, new int[] {m, nn});
    }

    /** 逐元素二元運算；支援 NumPy 風廣播（右對齊）。 */
    private static T binary(String op, T a, T b) {
        int r = Math.max(a.rank(), b.rank());
        int[] os = new int[r];
        for (int i = 0; i < r; i++) {
            int da = i - (r - a.rank()) >= 0 ? a.shape[i - (r - a.rank())] : 1;
            int db = i - (r - b.rank()) >= 0 ? b.shape[i - (r - b.rank())] : 1;
            if (da != db && da != 1 && db != 1) throw new IllegalStateException("broadcast mismatch");
            os[i] = Math.max(da, db);
        }
        int n = T.numel(os);
        float[] y = new float[n];
        int[] idx = new int[r];
        for (int k = 0; k < n; k++) {
            int ia = 0, ib = 0;
            for (int i = 0; i < r; i++) {
                int ai = i - (r - a.rank()), bi = i - (r - b.rank());
                if (ai >= 0) ia = ia * a.shape[ai] + (a.shape[ai] == 1 ? 0 : idx[i]);
                if (bi >= 0) ib = ib * b.shape[bi] + (b.shape[bi] == 1 ? 0 : idx[i]);
            }
            float u = a.d[ia], v = b.d[ib];
            y[k] = "Add".equals(op) ? u + v : "Sub".equals(op) ? u - v : "Mul".equals(op) ? u * v : u / v;
            for (int i = r - 1; i >= 0; i--) {
                if (++idx[i] < os[i]) break;
                idx[i] = 0;
            }
        }
        return new T(y, os);
    }

    private static T concat(List<T> ts, long axis) {
        T first = ts.get(0);
        int r = first.rank();
        if (r != 2) throw new IllegalStateException("Concat only supports 2-D");
        int ax = (int) (axis < 0 ? axis + r : axis);
        if (ax != 1) throw new IllegalStateException("Concat only supports last axis");
        int rows = first.shape[0], cols = 0;
        for (T t : ts) {
            if (t.rank() != 2 || t.shape[0] != rows) throw new IllegalStateException("Concat shape mismatch");
            cols += t.shape[1];
        }
        float[] y = new float[rows * cols];
        int off = 0;
        for (T t : ts) {
            for (int i = 0; i < rows; i++) System.arraycopy(t.d, i * t.shape[1], y, i * cols + off, t.shape[1]);
            off += t.shape[1];
        }
        return new T(y, new int[] {rows, cols});
    }
}
