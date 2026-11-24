package com.xiye.videoasset.ai.live2d;

import com.xiye.videoasset.ai.live2d.Live2dPython.Artifact;
import com.xiye.videoasset.ai.live2d.Live2dPython.Snapshot;
import com.xiye.videoasset.ai.live2d.Live2dTask.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class Live2dSync {
    private static final Logger LOG = LoggerFactory.getLogger(Live2dSync.class);
    private final Live2dMapper tasks;
    private final Live2dHub hub;

    Live2dSync(Live2dMapper tasks, Live2dHub hub) {
        this.tasks = tasks;
        this.hub = hub;
    }

    void apply(Live2dTask task, Snapshot snapshot) {
        if (task.status() == Status.SUCCEEDED || task.status() == Status.FAILED) {
            return;
        }
        if (snapshot.error() != null) {
            String stage = snapshot.error().stage() == null ? snapshot.stage() : snapshot.error().stage();
            if (tasks.failOpen(task.id(), snapshot.error().code(), stage) > 0) {
                LOG.warn("Live2D 任务失败 taskId={} projectId={} stage={} errorCode={}",
                        task.id(), task.projectId(), stage, snapshot.error().code());
                hub.publish(task.projectId(), task.id());
            }
            return;
        }
        Artifact artifact = snapshot.result();
        if (artifact != null) {
            if (tasks.attach(task.id(), snapshot.stage(), artifact.manifestPath(), artifact.artifactRef()) > 0) {
                LOG.info("Live2D 模型包已到达 taskId={} projectId={} stage={}",
                        task.id(), task.projectId(), snapshot.stage());
                hub.publish(task.projectId(), task.id());
            }
            return;
        }
        Status state = snapshot.status() == Status.QUEUED ? Status.QUEUED : Status.RUNNING;
        if (tasks.report(task.id(), state, snapshot.stage()) > 0) {
            hub.publish(task.projectId(), task.id());
        }
    }
}
