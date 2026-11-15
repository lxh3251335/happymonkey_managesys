package cn.tianshang.commerce.quote;

/**
 * 报价所处的跟进阶段，由报价状态与门店的合同状态共同决定。
 *
 * <ul>
 *   <li>DRAFT 草稿、ISSUED 待客户确认、ACCEPTED 客户已确认待生成合同 —— 三者合称“跟进中”；
 *   <li>CONTRACTED 已转合同：门店已有合同，此后在合同台账、项目台账中办理；
 *   <li>LOST 未成交；
 *   <li>ARCHIVED 已被该门店更新的已确认版本取代。
 * </ul>
 */
public final class QuoteStage {

    public static final String DRAFT = "DRAFT",
        ISSUED = "ISSUED",
        ACCEPTED = "ACCEPTED",
        CONTRACTED = "CONTRACTED",
        LOST = "LOST",
        ARCHIVED = "ARCHIVED";

    private QuoteStage() {}

    /**
     * @param status 报价状态
     * @param contractState 门店合同状态（NONE 表示还没有合同）
     * @param version 本报价版本号
     * @param acceptedVersion 该门店已确认报价的版本号，没有则为空
     */
    public static String of(String status, String contractState, int version, Integer acceptedVersion) {
        boolean hasContract = !"NONE".equals(contractState);
        if ("LOST".equals(status)) return LOST;
        if ("ACCEPTED".equals(status)) return hasContract ? CONTRACTED : ACCEPTED;
        if (acceptedVersion != null && version < acceptedVersion) return ARCHIVED;
        if ("DRAFT".equals(status)) return DRAFT;
        // 已出具 / 历史报价：门店已有合同且没有另行确认的版本时，这份报价就是合同依据
        return hasContract && acceptedVersion == null ? CONTRACTED : ISSUED;
    }
}
