package cn.managame.core.write;

import java.util.*;

/**
 * Result of one physical-table batch.
 *
 * Successful items are implicit so the common all-success path only allocates this small object.
 * Problems are indexed against the submitted operation list.
 */
public final class BatchResult {
    private final int operationCount;
    private final List<BatchItemResult> problems;
    private final Map<Integer, BatchItemResult> byIndex;

    private BatchResult(int operationCount, List<BatchItemResult> problems) {
        if (operationCount < 0) throw new IllegalArgumentException("operationCount must be >= 0");
        this.operationCount = operationCount;
        this.problems = List.copyOf(problems);
        Map<Integer, BatchItemResult> map = new HashMap<>();
        for (BatchItemResult problem : this.problems) {
            if (problem.index() >= operationCount) {
                throw new IllegalArgumentException("problem index out of range: " + problem.index());
            }
            if (map.put(problem.index(), problem) != null) {
                throw new IllegalArgumentException("duplicate problem index: " + problem.index());
            }
        }
        this.byIndex = Map.copyOf(map);
    }

    public static BatchResult success(int operationCount) {
        return new BatchResult(operationCount, List.of());
    }

    public static BatchResult of(int operationCount, List<BatchItemResult> problems) {
        return new BatchResult(operationCount, problems);
    }

    public static BatchResult unknown(int operationCount, String message) {
        List<BatchItemResult> problems = new ArrayList<>(operationCount);
        for (int i = 0; i < operationCount; i++) {
            problems.add(new BatchItemResult(i, BatchItemState.UNKNOWN, message));
        }
        return new BatchResult(operationCount, problems);
    }

    public int operationCount() { return operationCount; }
    public List<BatchItemResult> problems() { return problems; }
    public boolean allSuccess() { return problems.isEmpty(); }
    public int successCount() { return operationCount - problems.size(); }

    public BatchItemState stateAt(int index) {
        checkIndex(index);
        BatchItemResult result = byIndex.get(index);
        return result == null ? BatchItemState.SUCCESS : result.state();
    }

    public String messageAt(int index) {
        checkIndex(index);
        BatchItemResult result = byIndex.get(index);
        return result == null ? "" : result.message();
    }

    public List<WriteOperation<?>> problemOperations(List<WriteOperation<?>> operations) {
        if (operations.size() != operationCount) {
            throw new IllegalArgumentException("operation size mismatch: expected=" + operationCount
                    + ", actual=" + operations.size());
        }
        List<WriteOperation<?>> result = new ArrayList<>(problems.size());
        for (BatchItemResult problem : problems) result.add(operations.get(problem.index()));
        return List.copyOf(result);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= operationCount) throw new IndexOutOfBoundsException(index);
    }
}
