package com.omniforge.knowledge.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * all-MiniLM-L6-v2 专用翻译器（自研，替代 DJL 内置 TextEmbeddingTranslator）。
 *
 * <p>背景：DJL 0.36 的 {@code Encoding.toNDList} 不设置张量名，而
 * sentence-transformers 导出的 ONNX 模型输入为具名张量
 * {@code [input_ids, attention_mask, token_type_ids]}，名字匹配失败
 * （真机实测：Input mismatch）。本翻译器显式命名三个输入张量。</p>
 *
 * <p>输出：模型默认导出 last_hidden_state [1, seq, 384]，
 * 经 attention mask 加权的 mean pooling 得到句向量；若模型输出已池化
 * （[1, 384] / [384]）则直接使用。不做 L2 归一化——余弦相似度对缩放不敏感。</p>
 */
public final class MiniLmEmbeddingTranslator implements Translator<String, float[]> {

    private static final String ATTENTION_MASK_ATTACHMENT = "attentionMask";

    private final HuggingFaceTokenizer tokenizer;

    public MiniLmEmbeddingTranslator(HuggingFaceTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * 不叠加额外 batch 维：本翻译器输入已含 batch=1（[1, seq]），
     * 该 ONNX 模型输入为 rank-2，默认 STACK 批处理器会叠成 rank-3 导致
     * ORT_INVALID_ARGUMENT（Invalid rank: Got 3 Expected 2）。
     * DJL 0.36 无 IDENTITY 常量，自定义 no-op 批处理器。
     */
    private static final Batchifier NOOP_BATCHIFIER = new Batchifier() {
        @Override
        public NDList batchify(NDList[] inputs) {
            return inputs[0];
        }

        @Override
        public NDList[] unbatchify(NDList outputs) {
            return new NDList[]{outputs};
        }
    };

    @Override
    public Batchifier getBatchifier() {
        return NOOP_BATCHIFIER;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, String input) {
        Encoding encoding = tokenizer.encode(input);
        long[] ids = encoding.getIds();
        long[] mask = encoding.getAttentionMask();
        long[] typeIds = encoding.getTypeIds();
        NDManager manager = ctx.getNDManager();
        NDArray inputIds = manager.create(ids).reshape(new Shape(1, ids.length));
        inputIds.setName("input_ids");
        NDArray attentionMask = manager.create(mask).reshape(new Shape(1, mask.length));
        attentionMask.setName("attention_mask");
        NDArray tokenTypeIds = manager.create(typeIds).reshape(new Shape(1, typeIds.length));
        tokenTypeIds.setName("token_type_ids");
        ctx.setAttachment(ATTENTION_MASK_ATTACHMENT, attentionMask);
        return new NDList(inputIds, attentionMask, tokenTypeIds);
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList outputs) {
        NDArray lastHidden = outputs.get(0);
        if (lastHidden.getShape().dimension() == 3) {
            // [1, seq, 384]：attention mask 加权 mean pooling → [1, 384]
            NDArray mask = (NDArray) ctx.getAttachment(ATTENTION_MASK_ATTACHMENT);
            NDArray maskExpanded = mask.expandDims(2);            // [1, seq, 1]
            NDArray masked = lastHidden.mul(maskExpanded);
            NDArray sumEmbeddings = masked.sum(new int[]{1}, true); // [1, 1, 384]
            NDArray maskSum = maskExpanded.sum(new int[]{1}, true)
                    .add(1e-9);                                    // 防全零掩码除零
            return sumEmbeddings.div(maskSum).toFloatArray();      // 广播 → [1, 1, 384] → 384
        }
        // 已池化的 sentence_embedding（[1, 384] 或 [384]）
        return lastHidden.toFloatArray();
    }
}
