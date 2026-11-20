package com.xiye.videoasset.ai.characters.persistence;

public record CharacterAssetEntity(
        long id, long taskId, String bucket, byte[] objectKey, long timestampMs,
        String sourceFrame, int frameCharacterNumber, long sizeBytes
) {
}
