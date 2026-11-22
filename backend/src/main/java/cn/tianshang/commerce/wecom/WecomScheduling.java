package cn.tianshang.commerce.wecom;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 打开定时任务：目前只有企业微信机器人每天早上推送今日待办（{@link WecomMorningPush}）。 */
@Configuration
@EnableScheduling
public class WecomScheduling {}
