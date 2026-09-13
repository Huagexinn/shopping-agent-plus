package com.example.service.ai.impl;

import com.example.ai.rag.KnowledgeReference;

import java.util.List;

/**
 * 流式增量校验的缓冲与结算器。
 */
final class AiStreamingGroundingGuard {
    /** 强边界符；刻意不含 {@code .}，因为它是小数点，「6,499.00」会被错误切断。 */
    private static final String BOUNDARIES = "。！？；\n";

    /**
     * 未结算缓冲的上限。模型不写标点时，缓冲会持续增长导致首字延迟回升，超过这个长度就强制结算。
     * 取 64 是在「首字延迟」和「误切风险」之间的折中：中文短句普遍在 20~40 字，64 字已能覆盖大多数
     * 完整语义；再大则无标点长回复的等待感明显。
     */
    static final int MAX_PENDING_CHARS = 64;

    private final StringBuilder buffer = new StringBuilder();
    private int settledLength;

    /**
     * 追加模型文本并尝试结算。
     *
     * @return 本次可发送的新增片段；未到结算点返回空片段；校验不通过返回 {@link Settlement#rejected}
     */
    Settlement accept(String chunk,
                      String userMessage,
                      List<Object> successfulToolResults,
                      List<KnowledgeReference> knowledgeReferences) {
        if (chunk == null || chunk.isEmpty()) {
            return Settlement.none();
        }
        buffer.append(chunk);
        int cut = chooseCut();
        if (cut <= settledLength) {
            return Settlement.none();
        }
        // 只校验 [0, cut)，即「已发送 + 本次结算」的累积窗口；空 buffer 永远走不到这里，
        // 因此不会出现 validate("") 返回 EMPTY_REPLY 的情况
        AiGroundingValidator.GroundingDecision decision = AiGroundingValidator.validate(
                buffer.substring(0, cut), userMessage, successfulToolResults, knowledgeReferences);
        if (!decision.accepted()) {
            return Settlement.rejected(decision.failureType());
        }
        String delta = buffer.substring(settledLength, cut);
        settledLength = cut;
        return Settlement.delta(delta);
    }

    /** 当前累积的全部模型文本；流结束时用它走一次完整校验。 */
    String text() {
        return buffer.toString();
    }

    private int chooseCut() {
        int lastBoundaryEnd = -1;
        for (int index = buffer.length() - 1; index >= settledLength; index--) {
            if (BOUNDARIES.indexOf(buffer.charAt(index)) >= 0) {
                lastBoundaryEnd = index + 1;
                break;
            }
        }
        if (lastBoundaryEnd > settledLength) {
            return lastBoundaryEnd;
        }
        if (buffer.length() - settledLength <= MAX_PENDING_CHARS) {
            return settledLength;
        }
        return forcedCut();
    }

    /**
     * 超长无标点时的强制结算点。
     *
     * <p>不能切在数字中间：缓冲以数字结尾时，下一个字符可能还是数字（模型还没写完 5999）。
     * 因此向前退到最近一个非数字字符之后；若退到已结算位置仍无法落点，就放弃本次强制结算，
     * 等下一个边界符，宁可多等也不误切。</p>
     */
    private int forcedCut() {
        int cut = Math.min(settledLength + MAX_PENDING_CHARS, buffer.length());
        while (cut > settledLength && isNumberChar(buffer.charAt(cut - 1))) {
            cut--;
        }
        return cut;
    }

    private boolean isNumberChar(char value) {
        return Character.isDigit(value) || value == '.' || value == ',';
    }

    /** 一次结算的结果。 */
    record Settlement(String delta, boolean rejected, String failureType) {
        static Settlement none() {
            return new Settlement("", false, null);
        }

        static Settlement delta(String delta) {
            return new Settlement(delta, false, null);
        }

        static Settlement rejected(String failureType) {
            return new Settlement("", true, failureType);
        }
    }
}
