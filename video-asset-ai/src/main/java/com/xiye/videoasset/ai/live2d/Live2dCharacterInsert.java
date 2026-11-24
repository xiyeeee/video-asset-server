package com.xiye.videoasset.ai.live2d;

final class Live2dCharacterInsert {
    private final long projectId;
    private final String name;
    private long id;

    Live2dCharacterInsert(long projectId, String name) {
        this.projectId = projectId;
        this.name = name;
    }

    long id() {
        return id;
    }
}
