package com.xiye.videoasset.ai.characters.model;

import java.util.List;

public record CharacterAssetVO(
        String id, String taskId, long timestampMs, String sourceFrame, int frameCharacterNumber,
        long sizeBytes, String url, int expiresInSeconds
) {
    @Override
    public String toString() {
        return "CharacterAssetVO[id=" + id + "]";
    }

    public record Page(List<CharacterAssetVO> items, int page, int size, long total) {
    }
}
