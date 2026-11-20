package com.xiye.videoasset.api.extraction;

import java.util.List;

public class ExtractionJobs {
    private final ExtractionJobWriter tasks;

    public ExtractionJobs(ExtractionJobWriter tasks) {
        this.tasks = tasks;
    }

    // 调用方在一个独立事务内执行，任务与步骤同时可见后才进入 RabbitMQ 投递扫描。
    public ExtractionJob create(
            long projectId, long episodeId, long sourceId, String key,
            List<ExtractionType> types, boolean regenerate
    ) {
        String signature = ExtractionPlan.signature(types);
        tasks.insert(projectId, episodeId, sourceId, key, signature, regenerate);
        ExtractionJob task = tasks.find(episodeId, sourceId, key);
        if (!signature.equals(task.typeSignature()) || task.regenerate() != regenerate) {
            return null;
        }
        tasks.addSteps(task.id(), types);
        return task;
    }
}
