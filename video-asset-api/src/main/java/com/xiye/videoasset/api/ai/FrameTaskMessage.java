package com.xiye.videoasset.api.ai;

/** 首帧任务只携带数据库 ID 和本次投递对应的已执行次数。 */
public record FrameTaskMessage(long taskId, long previousAttempt) {
}
