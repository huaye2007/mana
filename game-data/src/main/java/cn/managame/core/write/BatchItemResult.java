package cn.managame.core.write;

/** Non-successful operation result. Successful operations are implicit in BatchResult. */
public record BatchItemResult(int index, BatchItemState state, String message) {
    public BatchItemResult {
        if (index < 0) throw new IllegalArgumentException("index must be >= 0");
        if (state == null || state == BatchItemState.SUCCESS) {
            throw new IllegalArgumentException("BatchItemResult only stores non-success states");
        }
        message = message == null ? "" : message;
    }
}
