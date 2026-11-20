package cn.tianshang.commerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 回款分配建议：一笔到账的钱该记到哪几份合同上。只给建议，由登记的人确认或调整。
 *
 * <p>按把握从大到小依次尝试：
 * <ol>
 *   <li>INVOICE：金额正好等于某张发票，记到那张发票的合同；
 *   <li>EXACT：金额正好等于某一份或某几份（最多 4 份）合同的未收之和；
 *   <li>OLDEST：从最早到期的合同开始依次冲抵，剩下的留作待分配。
 * </ol>
 */
public final class ReceiptAllocator {

    private ReceiptAllocator() {}

    /** 还有未收的合同。due 为最早的应收截止日，date 为签订或下单日期，都可能为空。 */
    public record Open(String contractKey, BigDecimal outstanding, LocalDate due, LocalDate date) {}

    /** 一张未冲红的发票：金额与所属合同。 */
    public record Invoice(String contractKey, BigDecimal amount, LocalDate issuedOn) {}

    public record Share(String contractKey, BigDecimal amount) {}

    public record Suggestion(String rule, List<Share> shares, BigDecimal unallocated) {}

    /** 精确匹配最多组合的合同份数，以及尝试次数上限（合同很多时不做无谓的穷举）。 */
    private static final int MAX_COMBINATION = 4;
    private static final int MAX_STEPS = 200_000;

    public static Suggestion suggest(BigDecimal amount, List<Open> open, List<Invoice> invoices) {
        var sorted = open
            .stream()
            .filter(o -> o.outstanding().signum() > 0)
            .sorted(
                Comparator.comparing(Open::due, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Open::date, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Open::contractKey)
            )
            .toList();

        var byInvoice = invoices
            .stream()
            .filter(i -> i.amount().compareTo(amount) == 0)
            .sorted(Comparator.comparing(Invoice::issuedOn, Comparator.nullsLast(Comparator.naturalOrder())))
            .filter(i ->
                sorted
                    .stream()
                    .anyMatch(o -> o.contractKey().equals(i.contractKey()) && o.outstanding().compareTo(amount) >= 0)
            )
            .findFirst();
        if (byInvoice.isPresent()) return new Suggestion(
            "INVOICE",
            List.of(new Share(byInvoice.get().contractKey(), amount)),
            BigDecimal.ZERO
        );

        var exact = exactCombination(amount, sorted);
        if (exact != null) return new Suggestion(
            "EXACT",
            exact
                .stream()
                .map(o -> new Share(o.contractKey(), o.outstanding()))
                .toList(),
            BigDecimal.ZERO
        );

        var shares = new ArrayList<Share>();
        var left = amount;
        for (var o : sorted) {
            if (left.signum() <= 0) break;
            var take = left.min(o.outstanding());
            shares.add(new Share(o.contractKey(), take));
            left = left.subtract(take);
        }
        return new Suggestion("OLDEST", shares, left);
    }

    /** 未收之和正好等于 amount 的最小组合（先 1 份，再 2 份……），优先取靠前（更早到期）的合同。 */
    private static List<Open> exactCombination(BigDecimal amount, List<Open> sorted) {
        int[] steps = { 0 };
        for (int size = 1; size <= Math.min(MAX_COMBINATION, sorted.size()); size++) {
            var found = pick(amount, sorted, 0, size, new ArrayList<>(), steps);
            if (found != null) return found;
            if (steps[0] > MAX_STEPS) return null;
        }
        return null;
    }

    private static List<Open> pick(
        BigDecimal left,
        List<Open> sorted,
        int from,
        int need,
        List<Open> chosen,
        int[] steps
    ) {
        if (need == 0) return left.signum() == 0 ? new ArrayList<>(chosen) : null;
        for (int i = from; i <= sorted.size() - need; i++) {
            if (++steps[0] > MAX_STEPS) return null;
            var o = sorted.get(i);
            if (o.outstanding().compareTo(left) > 0) continue;
            chosen.add(o);
            var found = pick(left.subtract(o.outstanding()), sorted, i + 1, need - 1, chosen, steps);
            chosen.remove(chosen.size() - 1);
            if (found != null) return found;
        }
        return null;
    }
}
