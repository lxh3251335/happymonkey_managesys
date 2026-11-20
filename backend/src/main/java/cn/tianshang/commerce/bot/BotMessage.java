package cn.tianshang.commerce.bot;

/**
 * 发给机器人的一条消息，与具体的聊天软件无关。
 *
 * @param channel 渠道，目前只有 {@link BotBindingService#WECOM}
 * @param externalId 发消息的人在该渠道里的 ID
 * @param chatId 会话 ID；单聊时与 externalId 相同
 * @param group 是否群聊（群里所有人都看得到回复，所以不回答金额）
 * @param text 文字内容，可为空
 * @param fileName 附带文件的文件名，没有文件时为空
 * @param file 文件内容
 */
public record BotMessage(
    String channel,
    String externalId,
    String chatId,
    boolean group,
    String text,
    String fileName,
    byte[] file
) {
    public static BotMessage text(String channel, String externalId, String text) {
        return new BotMessage(channel, externalId, externalId, false, text, null, null);
    }
}
